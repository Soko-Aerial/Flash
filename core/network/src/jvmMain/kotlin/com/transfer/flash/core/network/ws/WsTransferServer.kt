@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.transfer.flash.core.network.tls.SecureSocketUpgrader
import com.transfer.flash.core.network.tls.TlsOptions

/**
 * DESKTOP DUPLICATE (Phase 15-3) of the `androidMain` original — byte-identical apart from
 * this note. D1 = Option B forbids a shared JVM tier, so JDK-bound plumbing is duplicated
 * per target (CONVENTIONS.md R5 amendment). Do NOT edit one copy without the other.

 * Thin alias around [FlashLog] so WS code keeps a local logging surface. The sink
 * already swallows platform logging failures (never logs secrets, AGENTS.md §24).
 */
internal object WsLog {
    fun i(tag: String, message: String) = FlashLog.i(tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = FlashLog.w(tag, message, error)
    fun d(tag: String, message: String) = FlashLog.i(tag, message)
}

/**
 * Accepts inbound WebSocket upgrade requests for the experimental transfer track.
 * Prefers the stable port [PREFERRED_PORT] and falls back to a dynamic port when
 * it is busy — same approach as the LAN probe server.
 *
 * Pass a non-null [tls] to require TOFU-pinned TLS on every accepted connection:
 * each socket is wrapped server-side via `SecureSocketUpgrader.wrapAccepted` BEFORE the
 * WebSocket handshake is parsed, so the HTTP upgrade itself travels encrypted. The plain
 * streams of accepted sockets are never touched before the wrap (clean-boundary rule,
 * see `SecureSocketUpgrader` KDoc); pre-wrap access fails closed.
 */
public class WsTransferServer(
    private val connectionListener: WsConnection.Listener,
    private val onConnection: (WsConnection) -> Unit,
    private val tls: TlsOptions? = null,
    /**
     * Keepalive cadence for every connection this server accepts (ERROR-033), read per connection
     * so a tier change reaches the next inbound session. Defaults to [WsConnection]'s own
     * constants, so a caller that does not tier is byte-for-byte unchanged.
     *
     * Note the cadence is per *endpoint*, not negotiated: a LOW-tier device pings every 15 s and
     * forgives 40 s of silence while its HIGH-tier peer pings every 10 s and forgives 25 s. That
     * asymmetry is fine and in fact desirable — each end is describing its own tolerance for its
     * own radio, and each end's pings are what keep the *other* end's watchdog fed.
     */
    private val keepalive: () -> WsKeepaliveTiming = { WsKeepaliveTiming.DEFAULT },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val inFlightSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    public var listenPort: Int = 0
        private set

    public val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    @Synchronized
    public fun start(): Int {
        if (isRunning) return listenPort
        val socket = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        serverSocket = socket
        listenPort = socket.localPort
        acceptJob = scope.launch { acceptLoop(socket) }
        WsLog.i(TAG, "WS transfer server listening on port $listenPort tls=${tls != null}")
        return listenPort
    }

    @Synchronized
    public fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        listenPort = 0
        inFlightSockets.forEach { runCatching { it.close() } }
        inFlightSockets.clear()
    }

    private suspend fun acceptLoop(socket: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrElse { break }
            inFlightSockets.add(client)
            launch {
                var activeSocket: Socket = client
                // Audit S1: the client's TLS leaf, captured when the (lazy) server handshake runs inside
                // handshake() below, then carried on the WsConnection for the post-HELLO binding.
                var clientLeaf: String? = null
                try {
                    runCatching {
                        // TLS mode: track stream access so any pre-wrap touch fails closed, then
                        // wrap BEFORE the WS handshake reads a single byte. Lazy server handshake:
                        // the first read inside handshake() drives it (SecureSocketUpgrader KDoc).
                        val tracked = if (tls != null) SecureSocketUpgrader.withPlainStreamTracking(client) else client
                        activeSocket = tracked
                        val secure = tls?.let { options ->
                            SecureSocketUpgrader.wrapAccepted(
                                tracked,
                                options.pinVerifier,
                                options.keyManagers,
                                options.expectedDeviceId,
                                requireClientCertificate = true,
                                onClientLeafObserved = { fingerprint -> clientLeaf = fingerprint },
                            )
                        }
                        if (secure != null) {
                            activeSocket = secure
                            inFlightSockets.add(secure)
                        }
                        handshake(activeSocket) { clientLeaf }
                    }.onFailure { error ->
                        WsLog.d(TAG, "WS handshake rejected (${error.message ?: error::class.java.simpleName})")
                        runCatching { activeSocket.close() }
                    }
                } finally {
                    inFlightSockets.remove(client)
                    inFlightSockets.remove(activeSocket)
                }
            }
        }
    }

    private fun handshake(socket: Socket, peerLeafFingerprint: () -> String? = { null }) {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = socket.getInputStream()
        val (requestLine, headers) = WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(input))
        val upgrade = headers["upgrade"]?.contains("websocket", ignoreCase = true) == true
        val key = headers["sec-websocket-key"]
        if (!requestLine.startsWith("GET ") || !upgrade || key.isNullOrBlank()) {
            throw IOException("Not a WebSocket upgrade request")
        }
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(WebSocketCodec.acceptKey(key)).append("\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        socket.soTimeout = 0
        val label = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"
        val timing = keepalive()
        onConnection(
            WsConnection(
                socket,
                maskOutboundFrames = false,
                remoteLabel = label,
                listener = connectionListener,
                pingIntervalMs = timing.pingIntervalMs,
                livenessTimeoutMs = timing.livenessTimeoutMs,
                deferredPeerLeafFingerprintHex = peerLeafFingerprint(),
            ),
        )
    }

    public companion object {
        public const val PREFERRED_PORT: Int = 45822
        private const val TAG = "WS"
        private const val HANDSHAKE_TIMEOUT_MS = 8_000
    }
}
