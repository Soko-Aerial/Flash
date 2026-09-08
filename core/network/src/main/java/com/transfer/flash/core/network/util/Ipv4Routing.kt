package com.transfer.flash.core.network.util

/**
 * On-link IPv4 arithmetic for choosing which local interface a dial should leave from
 * (ERROR-035).
 *
 * ## The bug this exists for
 *
 * `WsTransferClient` used to bind every outbound socket to the first Wi-Fi/Ethernet [android.net.Network]
 * ConnectivityManager offered, without ever looking at where it was dialling. That is correct
 * whenever there is one LAN, and wrong in exactly the topology this project is built for: a device
 * joined to a router *and* running its hotspot has two LANs, and only one of them can reach a given
 * peer.
 *
 * The failure is asymmetric and looks like a platform limitation, which is how it survived so long.
 * A SoftAP interface (`ap0`) is not a `Network` — the platform hands out no `Network` object for it —
 * so it is never a candidate here, and the STA network always is. Every dial from the hotspot HOST to
 * one of its own clients on `192.168.43.x` was therefore bound to the router network, where that
 * address is off-link and unroutable, and spent the full `CONNECT_TIMEOUT_MS` failing. The client
 * dialling the host worked fine, because a client has only one network. Three separate KDocs in this
 * codebase concluded from that pattern that Android forbids a SoftAP gateway from opening a TCP
 * connection to its stations. No such rule exists; the host is the gateway and has a directly
 * connected route.
 *
 * ## Why the answer is "sometimes bind nothing"
 *
 * Binding a socket to a `Network` sets its routing decision explicitly and permanently. When the
 * destination is reachable only over an interface with no `Network` object, the sole way to reach it
 * is to bind nothing and let the kernel consult its own routing table, which does know about `ap0`.
 * So the choice is three-way — bind the network that is on-link, bind nothing when only a
 * non-`Network` interface is on-link, or fall back to today's deterministic pick when nothing is
 * on-link (a routed peer reached through a gateway, where any LAN network may be correct and a
 * stable choice matters more than a clever one).
 *
 * Pure integer arithmetic with no platform types so the policy is unit-testable without a device;
 * the Android-shaped inputs are collected by the caller.
 */
internal object Ipv4Routing {

    /**
     * Dotted-quad to big-endian bits, or null for anything that is not a bare IPv4 literal.
     *
     * Deliberately strict and allocation-light: no hostname resolution (a DNS lookup on the dial
     * path would be a blocking network call), no octal or short forms, and no IPv6. A null result
     * means "cannot reason about this destination", which routes the caller to its fallback rather
     * than to a wrong answer.
     */
    fun parse(address: String): Int? {
        var octets = 0
        var value = 0
        var digits = 0
        var current = 0
        for (character in address) {
            when {
                character in '0'..'9' -> {
                    if (digits == 3) return null
                    current = current * 10 + (character - '0')
                    if (current > 255) return null
                    digits += 1
                }
                character == '.' -> {
                    if (digits == 0 || octets == 3) return null
                    value = (value shl 8) or current
                    octets += 1
                    current = 0
                    digits = 0
                }
                else -> return null
            }
        }
        if (octets != 3 || digits == 0) return null
        return (value shl 8) or current
    }

    /**
     * True when [destination] falls inside the subnet [localAddress]/[prefixLength].
     *
     * A prefix outside 1..32 returns false rather than matching everything: /0 is a default route,
     * not a local subnet, and treating one as on-link would make every destination look directly
     * connected to whichever interface reported it.
     */
    fun onLink(localAddress: Int, prefixLength: Int, destination: Int): Boolean {
        if (prefixLength !in 1..32) return false
        // 32 - 32 = 0 shifts are well defined; `ushr 32` on an Int would be a no-op, hence the
        // explicit full-mask case.
        val mask = if (prefixLength == 32) -1 else (-1 shl (32 - prefixLength))
        return (localAddress and mask) == (destination and mask)
    }

    /**
     * Whether an address is worth considering as a local LAN identity at all.
     *
     * Loopback would match a /8 containing every 127.x destination; a link-local /16 address means
     * DHCP failed and the interface can only reach other equally-unconfigured hosts, so treating it
     * as on-link would bind dials to an interface that cannot carry them.
     */
    fun isUsableLocalAddress(address: String): Boolean =
        address.isNotBlank() &&
            !address.startsWith("127.") &&
            !address.startsWith("169.254.")

    /**
     * RFC 1918 private space: 10/8, 172.16/12, 192.168/16.
     *
     * Used as a second guard on the "bind nothing" branch. The interfaces that branch exists for —
     * SoftAP and USB/Bluetooth tethering — are always given private addresses by the platform, while
     * the interface it must never fire for is cellular, which is carrier-assigned CGNAT (100.64/10)
     * or public. The primary guard is ConnectivityManager's own view of which interface belongs to
     * which transport; this one covers interfaces CM does not model at all.
     */
    fun isPrivate(address: Int): Boolean =
        onLink(localAddress = 0x0A000000, prefixLength = 8, destination = address) ||
            onLink(localAddress = 0xAC100000.toInt(), prefixLength = 12, destination = address) ||
            onLink(localAddress = 0xC0A80000.toInt(), prefixLength = 16, destination = address)
}
