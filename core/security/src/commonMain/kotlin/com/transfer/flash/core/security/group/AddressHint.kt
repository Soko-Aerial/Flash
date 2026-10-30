package com.transfer.flash.core.security.group

/**
 * Which address hints of an invite may be dialed (SEC-D10-03).
 *
 * An invite is pasted from another person, and its hints make the joining device open a connection on its own. Accepting any
 * `host:port` would let a hostile invite use the device to probe other hosts, so only what a local network can legitimately
 * produce is dialed: an IPv4 address in a private range (10/8, 172.16/12, 192.168/16, or the carrier-grade 100.64/10 some
 * hotspots use) or a `.local` name, with a port from 1024 up. Loopback, the unspecified address, link-local (169.254/16, which
 * includes cloud metadata services) and public addresses are refused; so is anything that is not plain `host:port`.
 */
public object AddressHint {

    public fun isDialable(hint: String): Boolean {
        val trimmed = hint.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '/' || it == '@' || it == '?' || it == '#' || it == '[' }) return false
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0 || colon == trimmed.length - 1) return false
        val host = trimmed.substring(0, colon)
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return false
        if (port !in MIN_PORT..MAX_PORT) return false
        val octets = ipv4Octets(host)
        if (octets != null) return isPrivateV4(octets)
        return host.length in 3..63 + 6 && host.endsWith(".local", ignoreCase = true) &&
            host.all { it.isLetterOrDigit() || it == '-' || it == '.' } && !host.startsWith(".") && !host.contains("..")
    }

    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    private fun isPrivateV4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 100 && o[1] in 64..127 -> true
        else -> false
    }

    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535
}
