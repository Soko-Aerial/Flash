package com.transfer.flash.core.network.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.transfer.flash.core.network.sweep.LocalSubnet
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Every IPv4 address this device can be reached on over a LAN.
 *
 * Two sources, **merged** rather than tried in order (ERROR-035). ConnectivityManager answers for
 * networks the platform models; interface enumeration answers for the ones it does not, which is the
 * whole SoftAP/tethering family — the platform hands out no `Network` object for `ap0`.
 *
 * The merge is the fix. This used to return the CM list whenever it was non-empty and only fall back
 * to enumeration when it was empty, which meant a device that was *both* joined to Wi-Fi and hosting
 * a hotspot — the exact topology this project targets — reported only its router address and never
 * the `192.168.43.1` its own tethered clients had to use. The interface branch was unreachable in
 * precisely the case it was written for.
 */
public class LocalNetworkAddresses(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    public fun ipv4Addresses(): List<String> =
        (fromNetworks() + fromInterfaces())
            .filter(Ipv4Routing::isUsableLocalAddress)
            .distinct()
            .sorted()

    /**
     * IPv4 gateways this device currently routes through, nearest-use first by sort order.
     *
     * Read from `LinkProperties.routes` rather than assumed. The Dev Console's "probe gateway" action
     * used to dial a hardcoded `192.168.43.1` — Android's most common tethering subnet, but only one
     * of several in use across OEMs (`.42.1`, `.49.1`, `.61.1`, `172.20.10.1`), and wrong outright for
     * a client on an ordinary router. A device that is *hosting* a hotspot has no gateway on that
     * interface at all and correctly contributes nothing here.
     *
     * Available from API 21, so no version gate: `LinkProperties.getRoutes()` and
     * `RouteInfo.getGateway()` both predate this project's `minSdk`.
     */
    public fun ipv4Gateways(): List<String> = runCatching {
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks
            .filter { network -> isLanCapable(connectivityManager.getNetworkCapabilities(network)) }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)?.routes.orEmpty()
                    .mapNotNull { route -> route.gateway as? Inet4Address }
                    // 0.0.0.0 appears as the gateway of an on-link route: it means "no next hop",
                    // not a host that can be probed.
                    .filterNot { gateway -> gateway.isAnyLocalAddress }
                    .mapNotNull { gateway -> gateway.hostAddress }
            }
            .filter { address -> Ipv4Routing.parse(address) != null }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    /**
     * The subnets behind [ipv4Addresses], with prefix lengths, for the subnet sweep (DR3).
     *
     * Same two sources merged for the same reason: ConnectivityManager for Wi-Fi/Ethernet, interface
     * enumeration for this device's own hotspot or tether, which the platform models as no network. The
     * non-LAN interface exclusion applies to both, so a cellular address never appears; the sweep's own
     * RFC 1918 check is a second guard.
     */
    public fun ipv4Subnets(): List<LocalSubnet> =
        (subnetsFromNetworks() + subnetsFromInterfaces())
            .filter { Ipv4Routing.isUsableLocalAddress(it.address) }
            .distinctBy { it.address } // ConnectivityManager is listed first, so its prefix length wins over enumeration's

    private fun subnetsFromNetworks(): List<LocalSubnet> = runCatching {
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks
            .filter { network -> isLanCapable(connectivityManager.getNetworkCapabilities(network)) }
            .flatMap { network ->
                val properties = connectivityManager.getLinkProperties(network)
                properties?.linkAddresses.orEmpty().mapNotNull { linkAddress ->
                    val host = (linkAddress.address as? Inet4Address)?.hostAddress ?: return@mapNotNull null
                    LocalSubnet(properties?.interfaceName.orEmpty(), host, linkAddress.prefixLength)
                }
            }
    }.getOrDefault(emptyList())

    private fun subnetsFromInterfaces(): List<LocalSubnet> {
        val excluded = nonLanInterfaceNames()
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
                .filter { nic ->
                    runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false) &&
                        nic.name !in excluded
                }
                .flatMap { nic ->
                    nic.interfaceAddresses.asSequence().mapNotNull { entry ->
                        val host = (entry.address as? Inet4Address)?.hostAddress ?: return@mapNotNull null
                        LocalSubnet(nic.name, host, entry.networkPrefixLength.toInt())
                    }
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun isLanCapable(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )

    private fun fromNetworks(): List<String> = runCatching {
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks
            .filter { network -> isLanCapable(connectivityManager.getNetworkCapabilities(network)) }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)
                    ?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.mapNotNull { it.hostAddress }
                    .orEmpty()
            }
    }.getOrDefault(emptyList())

    /**
     * Interfaces that are up and not loopback, minus those belonging to a network that cannot carry
     * LAN traffic.
     *
     * The exclusion comes from ConnectivityManager's own interface-name mapping rather than from name
     * prefixes, which vary by OEM. Without it a cellular address (`rmnet0`) would be published as a
     * LAN address, and a peer that tried it would dial into the carrier network.
     */
    private fun fromInterfaces(): List<String> {
        val excluded = nonLanInterfaceNames()
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
                .filter { nic ->
                    runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false) &&
                        nic.name !in excluded
                }
                .flatMap { nic -> nic.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun nonLanInterfaceNames(): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks.mapNotNullTo(mutableSetOf()) { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                ?: return@mapNotNullTo null
            if (isLanCapable(capabilities)) return@mapNotNullTo null
            connectivityManager.getLinkProperties(network)?.interfaceName
        }
    }.getOrDefault(emptySet())
}
