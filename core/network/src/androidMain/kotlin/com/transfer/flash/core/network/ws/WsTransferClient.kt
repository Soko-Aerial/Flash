@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.network.util.Ipv4Routing
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
 * (this device's hotspot). See [chooseRoute] and [Ipv4Routing] — picking the wrong one costs a full
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
) {
    private val connectivityManager = context?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    public suspend fun connect(
        host: String,
        port: Int,
        peerDeviceId: String? = null,
    ): WsConnection = withContext(Dispatchers.IO) {
        // Manual dial with no known peer id: the pin cannot be evaluated during the handshake, so
        // capture the leaf here and let WsFlashNetwork bind it after HELLO (ADR-040).
        var deferredLeafFingerprint: String? = null
        val route = chooseRoute(host)
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
            )
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    /** The chosen egress, plus why — the `reason` is logged because this is hard to diagnose blind. */
    private class Route(val network: Network?, val reason: String)

    /**
     * Picks which network (if any) to bind the dial to, based on where it is going.
     *
     * Three outcomes, in priority order — see [Ipv4Routing] for why the middle one has to exist:
     * 1. a Wi-Fi/Ethernet network whose own subnet contains [host] → bind it. Identical to the old
     *    behaviour for the ordinary case of one LAN and a peer on it.
     * 2. no such network, but a local interface with no `Network` object is on-link — this device's
     *    SoftAP or USB tether → bind nothing, so the kernel's routing table, which knows about that
     *    interface, makes the decision. Binding anything here is what made a hotspot host unable to
     *    dial its own clients.
     * 3. nothing on-link → the previous deterministic pick, which is right for a routed peer and
     *    stable across both ends.
     */
    private fun chooseRoute(host: String): Route {
        val manager = connectivityManager ?: return Route(null, "no-connectivity-service")
        val candidates = lanNetworks(manager)
        val destination = Ipv4Routing.parse(host)
            ?: return Route(candidates.firstOrNull(), "non-literal-host")

        candidates.firstOrNull { network -> isOnLink(manager, network, destination) }
            ?.let { return Route(it, "on-link") }

        if (unmanagedInterfaceIsOnLink(manager, destination)) {
            return Route(null, "on-link-unmanaged-interface")
        }

        return Route(candidates.firstOrNull(), "routed-fallback")
    }

    /**
     * Eligible networks, lowest handle first.
     *
     * The deterministic order is load-bearing for outcome 3: platform ordering is not stable, and
     * without a fixed rule two devices with the same pair of networks could each bind a different one
     * and become mutually unreachable.
     */
    private fun lanNetworks(manager: ConnectivityManager): List<Network> {
        @Suppress("DEPRECATION")
        return manager.allNetworks
            .filter { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }
            .sortedBy { it.networkHandle }
    }

    private fun isOnLink(
        manager: ConnectivityManager,
        network: Network,
        destination: Int,
    ): Boolean {
        val addresses = manager.getLinkProperties(network)?.linkAddresses ?: return false
        return addresses.any { linkAddress ->
            val local = (linkAddress.address as? java.net.Inet4Address)?.hostAddress
            local != null &&
                Ipv4Routing.isUsableLocalAddress(local) &&
                Ipv4Routing.parse(local)?.let { localBits ->
                    Ipv4Routing.onLink(localBits, linkAddress.prefixLength, destination)
                } == true
        }
    }

    /**
     * Whether [destination] is directly connected via an interface ConnectivityManager does not
     * model as a network — in practice this device's own SoftAP or tether.
     *
     * Interfaces belonging to a non-LAN network are excluded using CM's own mapping rather than name
     * prefixes, which vary by OEM, and the address must additionally be RFC 1918. Both guards exist
     * to keep a cellular interface from ever winning here: returning true for one would unbind a dial
     * and let it leave over mobile data.
     */
    private fun unmanagedInterfaceIsOnLink(manager: ConnectivityManager, destination: Int): Boolean {
        val excluded = nonLanInterfaceNames(manager)
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
                .filter { nic ->
                    runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false) &&
                        nic.name !in excluded
                }
                .flatMap { nic -> nic.interfaceAddresses.asSequence() }
                .any { interfaceAddress ->
                    val local = (interfaceAddress.address as? java.net.Inet4Address)?.hostAddress
                    local != null &&
                        Ipv4Routing.isUsableLocalAddress(local) &&
                        Ipv4Routing.parse(local)?.let { localBits ->
                            Ipv4Routing.isPrivate(localBits) &&
                                Ipv4Routing.onLink(
                                    localBits,
                                    interfaceAddress.networkPrefixLength.toInt(),
                                    destination,
                                )
                        } == true
                }
        }.getOrDefault(false)
    }

    private fun nonLanInterfaceNames(manager: ConnectivityManager): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        manager.allNetworks.mapNotNullTo(mutableSetOf()) { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNullTo null
            val lanCapable =
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (lanCapable) return@mapNotNullTo null
            manager.getLinkProperties(network)?.interfaceName
        }
    }.getOrDefault(emptySet())

    public companion object {
        public const val TAG: String = "WS"
        public const val CONNECT_TIMEOUT_MS: Int = 4_000
        public const val HANDSHAKE_TIMEOUT_MS: Int = 8_000
    }
}
