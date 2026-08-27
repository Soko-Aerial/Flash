@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One live WebSocket connection after the HTTP upgrade handshake completed.
 *
 * Reads run on a Dispatchers.IO coroutine; writes are serialized through a lock
 * so file chunks and control frames from different threads can never interleave.
 * Listener callbacks fire on the read thread — implementations must be thread-safe.
 *
 * ## Liveness (ADR-016 keepalive)
 *
 * A periodic WebSocket PING is sent every [pingIntervalMs]. Combined with the socket
 * read timeout ([readTimeoutMs], ~3 ping intervals), any connection that receives NO
 * inbound traffic for that window — including half-open TCP after NAT/idle drops on
 * mobile hotspots — throws SocketTimeoutException in the read loop and is closed, so
 * sessions surface disconnects instead of blocking forever. Live peers answer PINGs
 * with PONGs, which keeps healthy idle connections fresh.
 */
public class WsConnection(
    private val socket: Socket,
    private val maskOutboundFrames: Boolean,
    public val remoteLabel: String,
    private val listener: Listener,
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val livenessTimeoutMs: Long = DEFAULT_LIVENESS_TIMEOUT_MS,
) {
    public interface Listener {
        public fun onTextMessage(connection: WsConnection, text: String)
        public fun onBinaryMessage(connection: WsConnection, data: ByteArray)
        public fun onConnectionClosed(connection: WsConnection, reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    /**
     * Wall-clock of the last inbound frame of ANY kind (text/binary/ping/pong). The keepalive
     * watchdog compares against this to prune half-open connections deterministically, instead of
     * waiting for a blocked read to trip its socket timeout — which never fires reliably when the
     * peer's OS silently drops the TCP state (force-stop / uninstall on a Wi-Fi hotspot).
     */
    @Volatile
    private var lastInboundAtMs: Long = System.currentTimeMillis()

    public val isOpen: Boolean
        get() = !closed.get()

    public fun start() {
        runCatching { socket.soTimeout = readTimeoutMs }
        // TCP keepalive gives the kernel a second, independent path to notice a dead peer.
        runCatching { socket.keepAlive = true }
        lastInboundAtMs = System.currentTimeMillis()
        scope.launch { readLoop() }
        scope.launch {
            while (scope.isActive) {
                kotlinx.coroutines.delay(pingIntervalMs)
                if (closed.get()) break
                // Prune before pinging: if nothing has come back within the liveness window, the
                // peer is gone. A live peer answers our PINGs with PONGs (which refresh
                // lastInboundAtMs), so a stale timestamp means no traffic at all — close now.
                val silentForMs = System.currentTimeMillis() - lastInboundAtMs
                if (silentForMs > livenessTimeoutMs) {
                    close("No inbound traffic for ${silentForMs}ms")
                    break
                }
                send(WebSocketCodec.OPCODE_PING, ByteArray(0))
            }
        }
    }

    public fun sendText(text: String): Boolean {
        return send(WebSocketCodec.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** Fire-and-forget text send that is safe to call from any thread (including main). */
    public fun sendTextAsync(text: String) {
        scope.launch { sendText(text) }
    }

    public fun sendBinary(data: ByteArray): Boolean {
        return send(WebSocketCodec.OPCODE_BINARY, data)
    }

    /**
     * Closes the connection. The close frame and socket close run on the connection scope
     * so this is safe to call from any thread (StrictMode forbids network writes on main).
     * The listener callback fires immediately; the peer observes the close frame or EOF.
     */
    public fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                synchronized(writeLock) {
                    WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_CLOSE, ByteArray(0), maskOutboundFrames)
                }
            }
            runCatching { socket.close() }
            scope.cancel()
        }
        listener.onConnectionClosed(this, reason)
    }

    private fun send(opcode: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        return runCatching {
            synchronized(writeLock) {
                WebSocketCodec.writeFrame(output, opcode, payload, maskOutboundFrames)
            }
        }.onFailure { error ->
            if (!closed.get()) {
                Log.w(TAG, "WS write failed remote=$remoteLabel", error)
                close("Write failed")
            }
        }.isSuccess
    }

    private suspend fun readLoop() {
        try {
            while (!closed.get() && scope.isActive) {
                val message = WebSocketCodec.readMessage(input)
                // Any inbound frame proves the peer is alive — refresh the watchdog timestamp.
                lastInboundAtMs = System.currentTimeMillis()
                when (message) {
                    is WebSocketCodec.Message.Text -> listener.onTextMessage(this, message.text)
                    is WebSocketCodec.Message.Binary -> listener.onBinaryMessage(this, message.data)
                    is WebSocketCodec.Message.Ping -> send(WebSocketCodec.OPCODE_PONG, message.payload)
                    is WebSocketCodec.Message.Pong -> Unit
                    is WebSocketCodec.Message.Close -> {
                        close(if (message.reason.isNotBlank()) message.reason else "Peer closed connection (${message.code})")
                        return
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.d(TAG, "WS read loop ended remote=$remoteLabel (${error.message ?: error::class.java.simpleName})")
                close("Connection error: ${error.message ?: error::class.java.simpleName}")
            }
        } finally {
            if (!closed.get()) {
                close("EOF")
            }
        }
    }

    public companion object {
        private const val TAG = "WS"

        /** Idle connections are refreshed 3x per read-timeout window (ping -> pong traffic). */
        public const val DEFAULT_PING_INTERVAL_MS: Long = 10_000L
        public const val DEFAULT_READ_TIMEOUT_MS: Int = 30_000

        /**
         * Watchdog window: if no inbound frame arrives for this long the peer is pruned. Sized to
         * ~2.5 ping intervals so a live peer that misses one PONG is forgiven, but a dead one is
         * dropped in ~25s regardless of where the read loop is parked.
         */
        public const val DEFAULT_LIVENESS_TIMEOUT_MS: Long = 25_000L
    }
}
