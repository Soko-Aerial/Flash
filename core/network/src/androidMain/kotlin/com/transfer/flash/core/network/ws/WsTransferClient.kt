@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.content.Context
import android.net.ConnectivityManager
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.network.util.Ipv4Routing
import com.transfer.flash.core.network.util.LanRouteChooser
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.transfer.flash.core.network.tls.SecureSocketUpgrader
import com.transfer.flash.core.network.tls.TlsOptions

/**
 * Opens an outbound WebSocket connection to a peer's [WsTransferServer].
 *
 * The socket is bound to the network that is actually on-link for the destination, or to nothing
 * when the destination is reachable only over an interface the platform does not model as a network
 * (this device's hotspot). See [LanRouteChooser] and [Ipv4Routing] — picking the wrong one costs a full
 * [CONNECT_TIMEOUT_MS] per attempt and is invisible from either end.
 *
 * Pass a non-null [tls] to upgrade the CONNECT socket to TOFU-pinned TLS via
 * `SecureSocketUpgrader.wrapClient` BEFORE the WebSocket handshake is sent, so the
 * HTTP upgrade itself travels encrypted ([TlsOptions.expectedDeviceId] is the peer's
 * stable id; null fails every handshake closed). The plain streams are never touched
 * before the wrap (clean-boundary rule, see `SecureSocketUpgrader` KDoc).
 *
 * @param context nullable so pure-JVM tests can drive loopback connections; when null the
 * socket is not pinned to any network (plain default routing).
 */
public class WsTransferClient(
    context: Context?,
    private val connectionListener: WsConnection.Listener,
    private val tls: TlsOptions? = null,
    /**
     * Keepalive cadence for every connection this client opens (ERROR-033), read per connection so
     * a tier change reaches the next dial. Defaults to [WsConnection]'s own constants, so a caller
     * that does not tier is byte-for-byte unchanged.
     */
    private val keepalive: () -> WsKeepaliveTiming = { WsKeepaliveTiming.DEFAULT },
    /** The owning network's shared keepalive clock (PC1); null runs one loop per connection as before. */
    private val ticker: WsKeepaliveTicker? = null,
) {
    private val connectivityManager = context?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // The route rules moved to LanRouteChooser unchanged so the DR3 sweep probe uses the same ones.
    private val routes = LanRouteChooser(connectivityManager)

    public suspend fun connect(
        host: String,
        port: Int,
        peerDeviceId: String? = null,
    ): WsConnection = withContext(Dispatchers.IO) {
        // Manual dial with no known peer id: the pin cannot be evaluated during the handshake, so
        // capture the leaf here and let WsFlashNetwork bind it after HELLO (ADR-040).
        var deferredLeafFingerprint: String? = null
        val route = routes.choose(host)
        WsLog.i(
            TAG,
            "WS connecting address=$host:$port tls=${tls != null} " +
                "network=${route.network?.networkHandle ?: "default"} via=${route.reason}",
        )
        var socket: Socket = route.network?.socketFactory?.createSocket() ?: Socket()
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
                ticker = ticker,
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
