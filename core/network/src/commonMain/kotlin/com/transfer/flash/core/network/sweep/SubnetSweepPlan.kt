package com.transfer.flash.core.network.sweep

import com.transfer.flash.core.network.util.Ipv4Routing

/** Why a subnet is not swept. Shown to the user as a short note, and logged. */
public enum class SweepRefusal {
    /** No LAN interface, or none with a usable address. */
    NO_LAN,

    /** Not RFC 1918 space (CGNAT, public or link-local), so probably not a network we may scan. */
    NOT_PRIVATE,

    /** Wider than a /24 and the sweep was automatic. Only the user may scan the local /24 of a larger network. */
    TOO_LARGE,

    /** /31 or /32: a point-to-point link, there is no one else on it. */
    TOO_SMALL,

    /** The previous sweep ended too recently. */
    RATE_LIMITED,
}

/**
 * Which addresses a sweep of one local subnet probes (DR3, `docs/network/DISCOVERY-RESILIENCE-PLAN.md` §3.3 C).
 *
 * The limits are the whole safety story of the feature, so they live in one pure function:
 * - **RFC 1918 only.** A CGNAT (100.64/10), public or link-local subnet is refused.
 * - **A /24 or smaller.** A /25 to /30 is swept whole. A wider subnet (a /16 office network) is refused when
 *   the sweep is automatic. When the user asks for it the sweep covers the /24 block holding this
 *   device's address and reports that it was narrowed, so it never walks 65 534 hosts.
 * - **Never this device, the network address or the broadcast address** of the real subnet.
 * - **Nearest first.** Addresses are ordered by distance from this device's own. DHCP pools hand out
 *   neighbouring addresses, so a peer usually turns up in the first probes.
 */
internal object SubnetSweepPlan {

    /** The hosts to probe, or [refusal] when the subnet must not be swept. */
    sealed interface Outcome {
        /**
         * @property narrowed the subnet was wider than a /24 and only its /24 block is covered.
         */
        data class Targets(val hosts: List<String>, val narrowed: Boolean) : Outcome

        data class Refused(val refusal: SweepRefusal) : Outcome
    }

    /** The widest swept block is a /24, that is 256 addresses. */
    private const val BLOCK_PREFIX = 24
    private const val BLOCK_SIZE = 256

    fun plan(subnet: LocalSubnet, manual: Boolean): Outcome {
        val own = Ipv4Routing.parse(subnet.address) ?: return Outcome.Refused(SweepRefusal.NO_LAN)
        if (!Ipv4Routing.isUsableLocalAddress(subnet.address)) return Outcome.Refused(SweepRefusal.NO_LAN)
        if (!Ipv4Routing.isPrivate(own)) return Outcome.Refused(SweepRefusal.NOT_PRIVATE)

        val prefix = subnet.prefixLength
        if (prefix !in 1..32) return Outcome.Refused(SweepRefusal.NO_LAN)
        if (prefix >= 31) return Outcome.Refused(SweepRefusal.TOO_SMALL)

        val narrowed = prefix < BLOCK_PREFIX
        if (narrowed && !manual) return Outcome.Refused(SweepRefusal.TOO_LARGE)

        val subnetMask = maskOf(prefix)
        val subnetNetwork = own and subnetMask
        val subnetBroadcast = subnetNetwork or subnetMask.inv()

        // The block actually walked: the whole subnet when it is a /24 or smaller, else the /24 around us.
        val blockMask = maskOf(maxOf(prefix, BLOCK_PREFIX))
        val blockFirst = own and blockMask
        val blockLast = blockFirst or blockMask.inv()

        // Walked as Long: addresses above 127.255.255.255 are negative as Int, so Int comparison would
        // end the loop at once for 192.168/16 and 172.16/12.
        val hosts = ArrayList<Long>(BLOCK_SIZE)
        var candidate = blockFirst.toUnsigned()
        val last = blockLast.toUnsigned()
        while (candidate <= last) {
            val bits = candidate.toInt()
            if (bits != own && bits != subnetNetwork && bits != subnetBroadcast) hosts += candidate
            candidate += 1
        }

        val ownUnsigned = own.toUnsigned()
        // Stable sort: equal distances (one above, one below) keep the lower address first.
        val ordered = hosts.sortedBy { kotlin.math.abs(it - ownUnsigned) }
        return Outcome.Targets(ordered.map { format(it.toInt()) }, narrowed)
    }

    /** A stable label for "this network", used to rate-limit automatic sweeps per network. */
    fun networkKey(subnet: LocalSubnet): String? {
        val own = Ipv4Routing.parse(subnet.address) ?: return null
        val prefix = subnet.prefixLength
        if (prefix !in 1..32) return null
        return format(own and maskOf(prefix)) + "/" + prefix
    }

    private fun maskOf(prefix: Int): Int = if (prefix == 32) -1 else (-1 shl (32 - prefix))

    private fun Int.toUnsigned(): Long = toLong() and 0xFFFF_FFFFL

    private fun format(bits: Int): String =
        "${(bits ushr 24) and 0xFF}.${(bits ushr 16) and 0xFF}.${(bits ushr 8) and 0xFF}.${bits and 0xFF}"
}
