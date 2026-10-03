package com.transfer.flash.core.discovery.nsd

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Picks the address Flash should dial from the addresses a resolved service reports.
 *
 * From API 34 `NsdServiceInfo` carries a LIST of addresses (the deprecated single `host` is just its
 * first element), and a dual-stack peer can list an IPv6 link-local address first. Flash dials
 * `host:port` over IPv4 on a LAN (the multicast beacon and the JmDNS bridge only ever produce IPv4),
 * and a link-local IPv6 address is not dialable without its scope id, so taking whichever the
 * platform happened to list first can turn a resolvable peer into one that never connects.
 *
 * Order: routable IPv4, then link-local IPv4 (169.254/16, better than nothing on an isolated
 * hotspot), then non-link-local IPv6, then whatever is left. Null only for an empty list.
 */
internal fun preferredDialableHost(candidates: List<InetAddress>): String? {
    fun rank(address: InetAddress): Int = when {
        address is Inet4Address && !address.isLinkLocalAddress -> 0
        address is Inet4Address -> 1
        !address.isLinkLocalAddress -> 2
        else -> 3
    }
    return candidates.minByOrNull(::rank)?.hostAddress
}
