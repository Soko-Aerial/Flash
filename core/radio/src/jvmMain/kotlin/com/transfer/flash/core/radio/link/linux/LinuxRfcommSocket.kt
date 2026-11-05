package com.transfer.flash.core.radio.link.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal interface LinuxLibc : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun connect(sockfd: Int, addr: Pointer, addrlen: Int): Int
    fun read(fd: Int, buf: ByteArray, count: Int): Int
    fun write(fd: Int, buf: ByteArray, count: Int): Int
    fun close(fd: Int): Int
    fun poll(fds: Pointer, nfds: Int, timeout: Int): Int

    companion object {
        val INSTANCE: LinuxLibc by lazy {
            Native.load("c", LinuxLibc::class.java)
        }
    }
}

/**
 * Direct user-space RFCOMM Bluetooth client socket on Linux (`AF_BLUETOOTH` / `BTPROTO_RFCOMM`).
 *
 * Connects directly to Bluetooth SPP devices (such as TNC radios, serial devices, and microcontrollers)
 * without requiring root permissions, `sudo`, or creating `/dev/rfcomm0` kernel TTY device nodes.
 */
public object LinuxRfcommSocket {

    public const val AF_BLUETOOTH: Int = 31
    public const val SOCK_STREAM: Int = 1
    public const val BTPROTO_RFCOMM: Int = 3

    public const val POLLIN: Short = 0x0001
    public const val POLLERR: Short = 0x0008
    public const val POLLHUP: Short = 0x0010
    public const val POLLNVAL: Short = 0x0020

    /** Packs a 10-byte `sockaddr_rc` structure for BlueZ. */
    public fun sockaddr(mac: String, channel: Int = 1): ByteArray {
        val clean = mac.trim().uppercase()
        val parts = clean.split(":").map { it.toInt(16).toByte() }.reversed()
        require(parts.size == 6) { "Invalid Bluetooth MAC address: $mac" }

        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(AF_BLUETOOTH.toShort())
        for (b in parts) buf.put(b)
        buf.put(channel.toByte())
        buf.put(0.toByte())
        return buf.array()
    }

    public fun open(mac: String, channel: Int = 1, description: String = "rfcomm $mac"): ByteLink {
        val libc = try {
            LinuxLibc.INSTANCE
        } catch (t: Throwable) {
            throw LinkException("Linux libc is not available: ${t.message}", t)
        }

        val fd = libc.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
        if (fd < 0) {
            throw LinkException("Could not allocate Linux Bluetooth RFCOMM socket (fd=$fd).")
        }

        val addr = sockaddr(mac, channel)
        val mem = Memory(addr.size.toLong())
        mem.write(0, addr, 0, addr.size)

        val rc = libc.connect(fd, mem, addr.size)
        if (rc != 0) {
            libc.close(fd)
            throw LinkException("Could not connect to Bluetooth device '$mac' on channel $channel (error $rc). Ensure the device is powered on, in range, and paired.")
        }

        return LinuxRfcommByteLink(fd, description, libc)
    }
}

internal class LinuxRfcommByteLink(
    private val fd: Int,
    override val description: String,
    private val libc: LinuxLibc,
) : ByteLink {

    private val closed = AtomicBoolean(false)
    private val pollMem = Memory(8).apply {
        setInt(0, fd)
        setShort(4, LinuxRfcommSocket.POLLIN)
        setShort(6, 0)
    }

    override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext -1

        pollMem.setShort(6, 0)
        val pollTimeout = timeoutMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val rc = libc.poll(pollMem, 1, pollTimeout)

        if (closed.get()) return@withContext -1
        if (rc == 0) return@withContext 0 // timeout elapsed with nothing to read
        if (rc < 0) throw LinkException("poll failed on RFCOMM socket fd=$fd")

        val revents = pollMem.getShort(6).toInt()
        if (revents and (LinuxRfcommSocket.POLLERR.toInt() or LinuxRfcommSocket.POLLNVAL.toInt()) != 0) {
            throw LinkException("Error state on RFCOMM socket (revents=$revents)")
        }

        if (revents and LinuxRfcommSocket.POLLIN.toInt() != 0) {
            val bytesRead = libc.read(fd, buffer, buffer.size)
            if (bytesRead < 0) throw LinkException("read failed on RFCOMM socket fd=$fd")
            if (bytesRead == 0) return@withContext -1 // Peer closed connection
            return@withContext bytesRead
        }

        if (revents and LinuxRfcommSocket.POLLHUP.toInt() != 0) {
            return@withContext -1
        }

        0
    }

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (closed.get()) throw LinkException("RFCOMM link is closed.")
        var offset = 0
        while (offset < data.size) {
            val chunk = if (offset == 0 && data.size <= 4096) data else data.copyOfRange(offset, (offset + 4096).coerceAtMost(data.size))
            val written = libc.write(fd, chunk, chunk.size)
            if (written < 0) {
                throw LinkException("write failed on RFCOMM socket fd=$fd")
            }
            offset += written
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            libc.close(fd)
        }
    }
}
