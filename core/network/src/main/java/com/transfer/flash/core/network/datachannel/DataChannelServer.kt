package com.transfer.flash.core.network.datachannel

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Accepts dedicated transfer data sockets (real N-socket multistream, ADR-017 revisit).
 *
 * Protocol per accepted connection:
 *  1. Peer sends ASCII join line: `FLASH_JOIN <targetDeviceId> <channelId>\n`.
 *  2. Server validates [localDeviceId] match and replies `FLASH_OK\n` (else `FLASH_REJECT …`).
 *  3. Both sides exchange length-prefixed frames ([DataChannelFraming]).
 *
 * Frames arriving on a channel are delivered to [Listener.onFrame] with a `reply` lambda that
 * writes back down the SAME connection (ADR-015: ACKs travel the arriving channel).
 */
public class DataChannelServer(
    private val localDeviceId: String,
    private val listener: Listener,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    public interface Listener {
        /** One inbound frame from [senderDeviceId] (the joining peer) on stream [channelId]. */
        public fun onFrame(senderDeviceId: String, channelId: Int, payload: ByteArray, reply: (ByteArray) -> Boolean)

        public fun onConnectionClosed(peerDeviceId: String?, channelId: Int)
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    @Volatile
    public var listenPort: Int = 0
        private set

    @Synchronized
    public fun start(preferredPort: Int, portSpan: Int = 20): Int {
        if (running.get()) return listenPort
        var lastError: Exception? = null
        for (offset in 0..portSpan) {
            try {
                val socket = ServerSocket(preferredPort + offset)
                serverSocket = socket
                listenPort = socket.localPort
                running.set(true)
                scope.launch { acceptLoop(socket) }
                Log.i(TAG, "DataChannelServer listening on port $listenPort")
                return listenPort
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("DataChannelServer could not bind ports ${preferredPort}..${preferredPort + portSpan}: ${lastError?.message}")
    }

    @Synchronized
    public fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        listenPort = 0
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val client = runCatching { server.accept() }.getOrElse { break }
            scope.launch { handleConnection(client) }
        }
    }

    private fun handleConnection(socket: Socket) {
        var joinedPeer: String? = null
        var joinedChannel = -1
        runCatching {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val line = DataChannelFraming.readLine(input)
            val parts = line.trim().split(' ')
            // `FLASH_JOIN <targetDeviceId> <channelId> <senderDeviceId>` (sender id optional for
            // legacy peers) — lets the receiving side route control frames back to the sender.
            if (parts.size !in 3..4 || parts[0] != DataChannelFraming.JOIN_PREFIX) {
                reject(output, "malformed join"); return
            }
            val targetDeviceId = parts[1]
            val channelId = parts[2].toIntOrNull()
            val senderDeviceId = parts.getOrElse(3) { targetDeviceId }
            if (targetDeviceId != localDeviceId || channelId == null) {
                reject(output, "unknown target or channel")
                return
            }
            joinedPeer = senderDeviceId
            joinedChannel = channelId

            output.write((DataChannelFraming.JOIN_OK + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            socket.soTimeout = 0 // frames may idle between bursts; keepalives live at WS layer
            Log.d(TAG, "Data channel joined channel=$channelId from=${socket.inetAddress?.hostAddress}")

            while (true) {
                val payload = DataChannelFraming.readFrame(input) ?: break
                val reply: (ByteArray) -> Boolean = { bytes ->
                    runCatching {
                        synchronized(output) { DataChannelFraming.writeFrame(output, bytes) }
                        true
                    }.getOrDefault(false)
                }
                try {
                    listener.onFrame(senderDeviceId, channelId, payload, reply)
                } catch (e: Exception) {
                    Log.w(TAG, "data frame handler error channel=$channelId", e)
                }
            }
        }.onFailure { error ->
            Log.d(TAG, "data channel ended: ${error.message ?: error::class.java.simpleName}")
        }
        runCatching { socket.close() }
        listener.onConnectionClosed(joinedPeer, joinedChannel)
    }

    private fun reject(output: BufferedOutputStream, reason: String) {
        runCatching {
            output.write((DataChannelFraming.JOIN_REJECT + " $reason\n").toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    private companion object {
        private const val TAG = "DATA"
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
    }
}
