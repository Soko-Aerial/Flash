package com.transfer.flash.core.discovery.multicast

/**
 * IPv4 directed-broadcast arithmetic for the DR2 beacon (`docs/network/DISCOVERY-RESILIENCE-PLAN.md` §3.3 B).
 *
 * The announcement that goes to the multicast group is ALSO sent to each interface's subnet broadcast address
 * (`192.168.1.255` on a `192.168.1.x/24` network). Some routers and access points filter or mishandle multicast
 * (IGMP snooping, "multicast enhancement", client isolation for group frames) but deliver broadcast normally.
 *
 * Pure `commonMain` so the address maths can be unit-tested with no network. Only the address ever leaves this file:
 * whether a datagram sent there arrives is a property of the network, measured on devices (TEST-BACKLOG DR-02).
 *
 * ## What is deliberately refused
 *
 * A wrong broadcast address costs nothing worse than a datagram nobody hears, but some interfaces have no broadcast
 * address at all, and a few would be actively wrong to send to:
 * - **Prefix lengths /31 and /32** (point-to-point and host routes) have no broadcast address.
 * - **Anything wider than /8** is not a LAN; it would address a huge domain for no benefit.
 * - **The unset address, loopback, multicast and class E** are not on a real subnet.
 * - An address equal to its own broadcast address is a misconfiguration.
 */
internal object DirectedBroadcast {

    private const val IPV4_BYTES = 4
    private const val BITS = 32
    private const val MIN_PREFIX_LENGTH = 8
    private const val MAX_PREFIX_LENGTH = 30
    private const val FIRST_MULTICAST_OCTET = 224
    private const val LOOPBACK_OCTET = 127

    /**
     * The directed broadcast address of [address]/[prefixLength] in dotted form, or null when that subnet has none
     * or is not one we should send to (see the class comment).
     *
     * @param address the interface's IPv4 address, four bytes in network order (`Inet4Address.address`); Java's bytes
     *   are signed, so each is masked before use.
     */
    fun forIpv4(address: ByteArray, prefixLength: Int): String? {
        if (address.size != IPV4_BYTES || prefixLength !in MIN_PREFIX_LENGTH..MAX_PREFIX_LENGTH) return null
        var ip = 0L
        for (byte in address) ip = (ip shl 8) or (byte.toLong() and 0xFF)
        val firstOctet = (ip shr 24).toInt() and 0xFF
        if (ip == 0L || firstOctet == LOOPBACK_OCTET || firstOctet >= FIRST_MULTICAST_OCTET) return null
        // Long, not Int: for a /8 the host mask needs 24 bits and the shift below must not wrap.
        val broadcast = ip or ((1L shl (BITS - prefixLength)) - 1)
        if (broadcast == ip) return null
        return "${(broadcast shr 24) and 0xFF}.${(broadcast shr 16) and 0xFF}.${(broadcast shr 8) and 0xFF}." +
            "${broadcast and 0xFF}"
    }
}
