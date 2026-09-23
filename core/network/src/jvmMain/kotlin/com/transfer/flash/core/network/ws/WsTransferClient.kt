@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.transfer.flash.core.network.tls.SecureSocketUpgrader
import com.transfer.flash.core.network.tls.TlsOptions

/**
 * DESKTOP DUPLICATE (Phase 15-3) of `androidMain`'s [WsTransferClient], adapted in exactly one
 * way: the route-picking half is gone. D1 = Option B forbids a shared JVM tier, so this class is
 * duplicated per target (CONVENTIONS.md R5 amendment); do NOT edit the handshake half in one copy
 * without the other.
 *
 * On Android, `chooseRoute` binds the dial to the `ConnectivityManager` network that is on-link
 * for the destination — a platform service with no desktop equivalent, and one a desktop does
 * not need: a desktop JVM has one kernel routing table and no per-network socket factories, so
 * plain default routing (`Socket()`) is the whole answer. That is also exactly the path the
 * Android original takes when `context` is null, which is what makes this an adaptation rather
 * than a behaviour change: the handshake bytes are identical either way.
 *
 * Opens an outbound WebSocket connection to a peer's [WsTransferServer].
 *
 * Pass a non-null [tls] to upgrade the CONNECT socket to TOFU-pinned TLS via
 * `SecureSocketUpgrader.wrapClient` BEFORE the WebSocket handshake is sent, so the
 * HTTP upgrade itself travels encrypted ([TlsOptions.expectedDeviceId] is the peer's
 * stable id; null fails every handshake closed). The plain streams are never touched
 * before the wrap (clean-boundary rule, see `SecureSocketUpgrader` KDoc).
 */
public class WsTransferClient(
    private val connectionListener: WsConnection.Listener,
    private val tls: TlsOptions? = null,
    /**
     * Keepalive cadence for every connection this client opens (ERROR-033), read per connection so
     * a tier change reaches the next dial. Defaults to [WsConnection]'s own constants, so a caller
     * that does not tier is byte-for-byte unchanged.
     */
    private val keepalive: () -> WsKeepaliveTiming = { WsKeepaliveTiming.DEFAULT },
) {
    public suspend fun connect(
        host: String,
        port: Int,
        peerDeviceId: String? = null,
    ): WsConnection = withContext(Dispatchers.IO) {
        // Manual dial with no known peer id: the pin cannot be evaluated during the handshake, so
        // capture the leaf here and let JvmWsFlashNetwork bind it after HELLO (ADR-040).
        var deferredLeafFingerprint: String? = null
        var socket: Socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tls?.let { options ->
                // Track stream access from this point on; any accidental pre-wrap touch
                // makes wrapClient fail closed with IllegalStateException.
                val tracked = SecureSocketUpgrader.withPlainStreamTracking(socket)
                socket = tracked
                val targetDeviceId = peerDeviceId ?: options.expectedDeviceId
                socket = SecureSocketUpgrader.wrapClient(
                    tracked,
                    targetDeviceId,
                    options.pinVerifier,
                    options.keyManagers,
                    options.handshakeTimeoutMs,
                    deferPinWhenDeviceIdUnknown = targetDeviceId == null,
                    onLeafObserved = { fingerprint -> deferredLeafFingerprint = fingerprint },
                ).getOrElse { error -> throw error }
                WsLog.i(TAG, "TLS established cipher=${(socket as javax.net.ssl.SSLSocket).session.cipherSuite}")
            }

            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val key = WebSocketCodec.newClientKey()
            val request = buildString {
                append("GET /flash-ws HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val (statusLine, headers) =
                WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(socket.getInputStream()))
            if (!statusLine.contains(" 101")) {
                throw IOException("WebSocket upgrade refused: $statusLine")
            }
            val accept = headers["sec-websocket-accept"]
                ?: throw IOException("Missing Sec-WebSocket-Accept header")
            if (accept != WebSocketCodec.acceptKey(key)) {
                throw IOException("Bad Sec-WebSocket-Accept header")
            }
            socket.soTimeout = 0
            val timing = keepalive()
            WsConnection(
                socket,
                maskOutboundFrames = true,
                remoteLabel = "$host:$port",
                listener = connectionListener,
                pingIntervalMs = timing.pingIntervalMs,
                livenessTimeoutMs = timing.livenessTimeoutMs,
                deferredPeerLeafFingerprintHex = deferredLeafFingerprint,
            )
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    public companion object {
        public const val TAG: String = "WS"
        public const val CONNECT_TIMEOUT_MS: Int = 4_000
        public const val HANDSHAKE_TIMEOUT_MS: Int = 8_000
    }
}
