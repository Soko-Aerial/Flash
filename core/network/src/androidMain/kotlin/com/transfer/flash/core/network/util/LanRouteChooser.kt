package com.transfer.flash.core.network.util

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Picks which network (if any) an outbound socket to a LAN host is bound to (ERROR-035).
 *
 * Moved out of `WsTransferClient` unchanged so the subnet sweep's probe (DR3) leaves by exactly the route
 * the dial that follows it will use. A probe that took a different route could find a host the dial then
 * cannot reach, or the reverse, and the hotspot host has two LANs where that is the normal case.
 */
internal class LanRouteChooser(private val connectivityManager: ConnectivityManager?) {

    /** The chosen egress, plus why — the `reason` is logged because this is hard to diagnose blind. */
    class Route(val network: Network?, val reason: String)

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
    fun choose(host: String): Route {
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
}
