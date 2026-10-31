package com.transfer.flash.core.network.util

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.transfer.flash.core.network.ws.monotonicNowMs

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

    private class SubnetLink(val network: Network, val localBits: Int, val prefixLength: Int)
    private class UnmanagedSubnet(val localBits: Int, val prefixLength: Int)

    private class RouteSnapshot(
        val timestampMs: Long,
        val candidates: List<Network>,
        val links: List<SubnetLink>,
        val unmanaged: List<UnmanagedSubnet>,
    )

    @Volatile
    private var cachedSnapshot: RouteSnapshot? = null

    private fun getSnapshot(manager: ConnectivityManager): RouteSnapshot {
        val now = monotonicNowMs()
        val existing = cachedSnapshot
        if (existing != null && now - existing.timestampMs < CACHE_TTL_MS) {
            return existing
        }
        val candidates = lanNetworks(manager)
        val links = candidates.flatMap { network ->
            manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { linkAddress ->
                val local = (linkAddress.address as? java.net.Inet4Address)?.hostAddress
                if (local != null && Ipv4Routing.isUsableLocalAddress(local)) {
                    Ipv4Routing.parse(local)?.let { localBits ->
                        SubnetLink(network, localBits, linkAddress.prefixLength)
                    }
                } else null
            }
        }
        val unmanaged = unmanagedSubnets(manager)
        val snapshot = RouteSnapshot(now, candidates, links, unmanaged)
        cachedSnapshot = snapshot
        return snapshot
    }

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
        val destination = Ipv4Routing.parse(host)
        val snapshot = getSnapshot(manager)
        if (destination == null) {
            return Route(snapshot.candidates.firstOrNull(), "non-literal-host")
        }

        // 1. On-link Wi-Fi / Ethernet
        for (link in snapshot.links) {
            if (Ipv4Routing.onLink(link.localBits, link.prefixLength, destination)) {
                return Route(link.network, "on-link")
            }
        }

        // 2. Unmanaged interface (SoftAP / USB tether)
        for (sub in snapshot.unmanaged) {
            if (Ipv4Routing.onLink(sub.localBits, sub.prefixLength, destination)) {
                return Route(null, "on-link-unmanaged-interface")
            }
        }

        // 3. Fallback
        return Route(snapshot.candidates.firstOrNull(), "routed-fallback")
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

    private fun unmanagedSubnets(manager: ConnectivityManager): List<UnmanagedSubnet> {
        val excluded = nonLanInterfaceNames(manager)
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
                .filter { nic ->
                    runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false) &&
                        nic.name !in excluded
                }
                .flatMap { nic -> nic.interfaceAddresses.asSequence() }
                .mapNotNull { interfaceAddress ->
                    val local = (interfaceAddress.address as? java.net.Inet4Address)?.hostAddress
                    if (local != null && Ipv4Routing.isUsableLocalAddress(local)) {
                        Ipv4Routing.parse(local)?.let { localBits ->
                            if (Ipv4Routing.isPrivate(localBits)) {
                                UnmanagedSubnet(localBits, interfaceAddress.networkPrefixLength.toInt())
                            } else null
                        }
                    } else null
                }
                .toList()
        }.getOrDefault(emptyList())
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

    companion object {
        /** 5-second TTL prevents repeated Binder IPC / ioctl storms during /24 sweeps (OPT-O10-03). */
        private const val CACHE_TTL_MS: Long = 5_000L
    }
}
