@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.transfer.flash.core.network.tls.SecureSocketUpgrader
import com.transfer.flash.core.network.tls.TlsOptions

/**
 * Opens an outbound WebSocket connection to a peer's [WsTransferServer].
 * Routes through the active Wi-Fi/Ethernet network like `LanConnectionProbe`,
 * so the socket lands on the same LAN interface as the peer.
 *
 * Pass a non-null [tls] to upgrade the CONNECT socket to TOFU-pinned TLS via
 * `SecureSocketUpgrader.wrapClient` BEFORE the WebSocket handshake is sent, so the
 * HTTP upgrade itself travels encrypted ([TlsOptions.expectedDeviceId] is the peer's
 * stable id; null fails every handshake closed). The plain streams are never touched
 * before the wrap (clean-boundary rule, see `SecureSocketUpgrader` KDoc).
 *
 * @param context nullable so pure-JVM tests can drive loopback connections; when null the
 * socket is not pinned to a Wi-Fi/Ethernet network (plain default routing).
 */
public class WsTransferClient(
    context: Context?,
    private val connectionListener: WsConnection.Listener,
    private val tls: TlsOptions? = null,
) {
    private val connectivityManager = context?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    public suspend fun connect(host: String, port: Int): WsConnection = withContext(Dispatchers.IO) {
        val network = findLanNetwork()
        WsLog.i(
            TAG,
            "WS connecting address=$host:$port tls=${tls != null} network=${network?.networkHandle ?: "default"}",
        )
        var socket: Socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tls?.let { options ->
                // Track stream access from this point on; any accidental pre-wrap touch
                // makes wrapClient fail closed with IllegalStateException.
                val tracked = SecureSocketUpgrader.withPlainStreamTracking(socket)
                socket = tracked
                socket = SecureSocketUpgrader.wrapClient(
                    tracked,
                    options.expectedDeviceId,
                    options.pinVerifier,
                    options.keyManagers,
                    options.handshakeTimeoutMs,
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
            WsConnection(socket, maskOutboundFrames = true, remoteLabel = "$host:$port", listener = connectionListener)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun findLanNetwork(): Network? {
        return connectivityManager?.allNetworks
            ?.filter { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
            // Deterministic sort by network handle so both devices pick the same network when
            // multiple eligible networks exist (e.g. dual-band SSID, cellular + Wi-Fi). Without
            // this, non-deterministic platform ordering can cause each device to bind to a different
            // network, making the socket unreachable from the peer's perspective.
            ?.sortedBy { it.networkHandle }
            ?.firstOrNull()
    }

    public companion object {
        public const val TAG: String = "WS"
        public const val CONNECT_TIMEOUT_MS: Int = 4_000
        public const val HANDSHAKE_TIMEOUT_MS: Int = 8_000
    }
}
