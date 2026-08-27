package com.transfer.flash.core.network.datachannel

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Outbound side of the dedicated transfer data channels (real N-socket multistream).
 *
 * [connect] opens one TCP socket, performs the `FLASH_JOIN` handshake against the peer's
 * [DataChannelServer], and returns a handle whose inbound frames are pushed to [onFrame] and
 * whose [DataSocketChannel.send] is safe for one concurrent sender (dispatcher workers each own
 * their channel). Connection failure/handshake rejection returns **null** so callers can fall
 * back to multiplexing over the main WebSocket.
 */
public object DataChannelClient {

    private const val TAG = "DATA"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val HANDSHAKE_TIMEOUT_MS = 6_000

    public interface DataSendChannel {
        /** Writes one frame; false on failure (channel dead). */
        public suspend fun send(payload: ByteArray): Boolean

        public fun close()
    }

    /**
     * Opens and joins one data channel.
     *
     * @param targetDeviceId the PEER's device id as the remote server expects it (its local id).
     * @param localDeviceId OUR device id, sent in the join so the peer can route control frames back.
     * @param onFrame invoked on a reader coroutine for every inbound frame (ACKs/COMPLETE).
     */
    public fun connect(
        host: String,
        port: Int,
        targetDeviceId: String,
        channelId: Int,
        onFrame: (ByteArray) -> Unit,
        onClosed: () -> Unit,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        localDeviceId: String = "",
    ): DataSendChannel? {
        val socket = Socket()
        return runCatching {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val joinLine = buildString {
                append(DataChannelFraming.JOIN_PREFIX)
                append(' ').append(targetDeviceId)
                append(' ').append(channelId)
                if (localDeviceId.isNotBlank()) append(' ').append(localDeviceId)
                append('\n')
            }
            output.write(joinLine.toByteArray(Charsets.US_ASCII))
            output.flush()

            val response = DataChannelFraming.readLine(input)
            if (!response.startsWith(DataChannelFraming.JOIN_OK)) {
                Log.d(TAG, "join rejected channel=$channelId host=$host: $response")
                runCatching { socket.close() }
                return null
            }
            socket.soTimeout = 0

            val channel = object : DataSendChannel {
                private val writeLock = Any()
                private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

                override suspend fun send(payload: ByteArray): Boolean =
                    withContext(Dispatchers.IO) {
                        if (closed.get()) {
                            false
                        } else {
                            runCatching {
                                synchronized(writeLock) { DataChannelFraming.writeFrame(output, payload) }
                                true
                            }.onFailure { error ->
                                if (!closed.get()) {
                                    Log.w(TAG, "data write failed channel=$channelId", error)
                                    close()
                                }
                            }.getOrDefault(false)
                        }
                    }

                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    scope.launch {
                        runCatching { socket.close() }
                    }
                    onClosed()
                }
            }

            scope.launch {
                try {
                    while (true) {
                        val payload = DataChannelFraming.readFrame(input) ?: break
                        try {
                            onFrame(payload)
                        } catch (e: Exception) {
                            Log.w(TAG, "data client frame handler error channel=$channelId", e)
                        }
                    }
                } catch (_: Exception) {
                    // EOF / reset — normal teardown path
                }
                channel.close()
            }

            Log.d(TAG, "joined data channel=$channelId → $host:$port")
            channel
        }.getOrElse { error ->
            Log.d(TAG, "data connect failed host=$host:$port (${error.message})")
            runCatching { socket.close() }
            null
        }
    }
}
