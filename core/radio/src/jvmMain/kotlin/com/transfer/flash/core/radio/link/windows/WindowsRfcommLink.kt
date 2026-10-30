package com.transfer.flash.core.radio.link.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The few Winsock calls an RFCOMM client needs (`ws2_32.dll`). A SOCKET is pointer sized, so this needs a 64-bit JVM
 * ([WindowsRfcomm.open] refuses a 32-bit one).
 */
internal interface Ws2Library : Library {
    fun WSAStartup(versionRequested: Short, data: Pointer): Int
    fun WSAGetLastError(): Int
    fun socket(af: Int, type: Int, protocol: Int): Long
    fun connect(s: Long, name: Pointer, nameLength: Int): Int
    fun send(s: Long, buf: ByteArray, len: Int, flags: Int): Int
    fun recv(s: Long, buf: ByteArray, len: Int, flags: Int): Int
    fun closesocket(s: Long): Int

    companion object {
        val INSTANCE: Ws2Library by lazy {
            Native.load("ws2_32", Ws2Library::class.java).also { lib ->
                val data = Memory(WSADATA_BYTES)
                val rc = lib.WSAStartup(WINSOCK_2_2, data)
                if (rc != 0) throw LinkException("Windows sockets could not start (error $rc).")
            }
        }

        private const val WSADATA_BYTES = 512L
        private const val WINSOCK_2_2: Short = 0x0202
    }
}

/**
 * A Bluetooth RFCOMM client socket on Windows (Winsock `AF_BTH`), used for the radio's vendor audio service. That service
 * has no virtual COM port (only Serial Port Profile does), so the channel is found by its service class UUID: with
 * `port = 0` and a service class id, Windows looks the RFCOMM channel up through SDP itself.
 *
 * Sources (read 2026-10-23): learn.microsoft.com/en-us/windows/win32/bluetooth/bluetooth-and-socket and
 * learn.microsoft.com/en-us/windows/win32/api/ws2bth/ns-ws2bth-sockaddr_bth (SOCKADDR_BTH is packed: `addressFamily` USHORT,
 * `btAddr` ULONGLONG, `serviceClassId` GUID, `port` ULONG = 30 bytes). **Never connected to a radio yet** (RADVOICE-05).
 */
internal object WindowsRfcomm {
    const val AF_BTH: Int = 32
    const val BTHPROTO_RFCOMM: Int = 3
    private const val SOCK_STREAM = 1
    private const val INVALID_SOCKET = -1L
    const val SOCKADDR_BTH_BYTES: Int = 30
    private const val CONNECT_TIMEOUT_MS = 12_000L

    /** The 30-byte packed `SOCKADDR_BTH` for [addressLong] and the service [serviceUuid] (channel 0 = look it up by SDP). */
    fun sockaddr(addressLong: Long, serviceUuid: String, channel: Int = 0): ByteArray {
        val uuid = UUID.fromString(serviceUuid)
        val b = ByteBuffer.allocate(SOCKADDR_BTH_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(AF_BTH.toShort())
        b.putLong(addressLong)
        // GUID: Data1 (4, LE), Data2 (2, LE), Data3 (2, LE), Data4 (8, as written).
        val hi = uuid.mostSignificantBits
        b.putInt((hi ushr 32).toInt())
        b.putShort(((hi ushr 16) and 0xFFFF).toShort())
        b.putShort((hi and 0xFFFF).toShort())
        val lo = uuid.leastSignificantBits
        for (shift in 56 downTo 0 step 8) b.put(((lo ushr shift) and 0xFF).toByte())
        b.putInt(channel)
        return b.array()
    }

    /** Blocking. @throws LinkException when the radio does not accept the connection within about 12 s. */
    fun open(addressLong: Long, serviceUuid: String, description: String): ByteLink {
        if (Native.POINTER_SIZE != 8) throw LinkException("Radio audio needs a 64-bit Java runtime.")
        val ws = try {
            Ws2Library.INSTANCE
        } catch (e: LinkException) {
            throw e
        } catch (e: Throwable) {
            throw LinkException("Windows sockets are not available: ${e.message}", e)
        }
        val sock = ws.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (sock == INVALID_SOCKET) {
            throw LinkException("Windows could not create a Bluetooth socket (error ${ws.WSAGetLastError()}).")
        }
        val addr = sockaddr(addressLong, serviceUuid)
        val mem = Memory(addr.size.toLong())
        mem.write(0, addr, 0, addr.size)

        val result = AtomicInteger(Int.MIN_VALUE)
        val error = AtomicInteger(0)
        val worker = Thread({
            val rc = ws.connect(sock, mem, addr.size)
            if (rc != 0) error.set(ws.WSAGetLastError())
            result.set(rc)
        }, "rfcomm-connect").apply { isDaemon = true }
        worker.start()
        worker.join(CONNECT_TIMEOUT_MS)
        if (worker.isAlive) {
            ws.closesocket(sock) // wakes the blocked connect
            worker.join(2_000L)
            throw LinkException("The radio did not answer on its audio channel in time.")
        }
        if (result.get() != 0) {
            ws.closesocket(sock)
            throw LinkException(connectFailure(error.get()))
        }
        return WinsockRfcommLink(ws, sock, description)
    }

    fun connectFailure(winsockError: Int): String = when (winsockError) {
        10060 -> "The radio did not answer on its audio channel in time."
        10061, 10064, 10065 -> "The radio refused the audio channel (it may be busy, or this entry is not the audio one)."
        10013 -> "Windows refused access to the radio's audio channel (is Bluetooth blocked for apps?)."
        10050, 10051, 10049 -> "The radio is not reachable over Bluetooth."
        0 -> "The radio did not accept the audio channel."
        else -> "The radio's audio channel could not be opened (Windows error $winsockError)."
    }
}

/** [ByteLink] over a connected Winsock RFCOMM socket; a daemon thread blocks in `recv` and hands chunks over. */
internal class WinsockRfcommLink(
    private val ws: Ws2Library,
    private val sock: Long,
    override val description: String,
) : ByteLink {
    private val closed = AtomicBoolean(false)
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    private var leftover: ByteArray? = null
    private var leftoverOffset = 0

    init {
        Thread({
            val scratch = ByteArray(1024)
            try {
                while (true) {
                    val n = ws.recv(sock, scratch, scratch.size, 0)
                    if (n == 0) break // the radio closed the channel
                    if (n < 0) {
                        if (!closed.get()) chunks.close(IOException("Bluetooth read failed (error ${ws.WSAGetLastError()})"))
                        else chunks.close()
                        return@Thread
                    }
                    chunks.trySend(scratch.copyOf(n))
                }
                chunks.close()
            } catch (e: Throwable) {
                chunks.close(IOException(e.message, e))
            }
        }, "rfcomm-audio-rx").apply { isDaemon = true }.start()
    }

    override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
        if (closed.get()) return -1
        leftover?.let { return drain(it, buffer) }
        val received = withTimeoutOrNull(timeoutMs) { chunks.receiveCatching() } ?: return 0
        val chunk = received.getOrNull()
        if (chunk == null) {
            val cause = received.exceptionOrNull()
            if (cause != null && !closed.get()) throw LinkException(cause.message ?: "Bluetooth read failed", cause)
            return -1
        }
        return drain(chunk, buffer)
    }

    private fun drain(source: ByteArray, buffer: ByteArray): Int {
        val n = minOf(source.size - leftoverOffset, buffer.size)
        System.arraycopy(source, leftoverOffset, buffer, 0, n)
        if (leftoverOffset + n >= source.size) {
            leftover = null
            leftoverOffset = 0
        } else {
            leftover = source
            leftoverOffset += n
        }
        return n
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (closed.get()) throw LinkException("link closed")
            var offset = 0
            while (offset < data.size) {
                val chunk = if (offset == 0) data else data.copyOfRange(offset, data.size)
                val sent = ws.send(sock, chunk, chunk.size, 0)
                if (sent <= 0) throw LinkException("Bluetooth write failed (error ${ws.WSAGetLastError()})")
                offset += sent
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { ws.closesocket(sock) }
        }
    }
}
