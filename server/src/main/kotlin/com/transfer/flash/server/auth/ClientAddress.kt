package com.transfer.flash.server.auth

import io.ktor.server.application.ApplicationCall
import java.net.InetAddress

/**
 * The reverse proxies whose `X-Forwarded-*` headers the hub believes (SEC-06). A request from anywhere else is taken
 * at face value: its forwarded headers are attacker-controlled input and are ignored.
 *
 * [cidrs] are CIDR blocks or single addresses (`10.0.0.0/8`, `127.0.0.1`, `::1`, `fd00::/8`). Invalid entries are a
 * configuration error and throw at construction (the hub must not start with a typo that silently trusts nothing, or
 * everything).
 */
class TrustedProxies(cidrs: List<String> = emptyList()) {

    private class Block(val network: ByteArray, val prefixBits: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            var bits = prefixBits
            var i = 0
            while (bits > 0) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((address[i].toInt() and mask) != (network[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }
    }

    private val blocks: List<Block> = cidrs.map { parse(it.trim()) }

    val isEmpty: Boolean get() = blocks.isEmpty()

    fun isTrusted(address: String): Boolean {
        val bytes = literalBytes(address) ?: return false
        return blocks.any { it.contains(bytes) }
    }

    /**
     * The client address of a request: [remoteAddress] unless it is a trusted proxy, in which case the right-most
     * `X-Forwarded-For` entry that is NOT itself a trusted proxy (a client can prepend entries but can not forge the
     * ones its proxy appended).
     */
    fun clientAddress(remoteAddress: String, forwardedFor: String?): String {
        if (!isTrusted(remoteAddress) || forwardedFor.isNullOrBlank()) return remoteAddress
        val chain = forwardedFor.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (candidate in chain.asReversed()) {
            if (literalBytes(candidate) == null) return remoteAddress // garbage in the chain: do not guess
            if (!isTrusted(candidate)) return candidate
        }
        return chain.firstOrNull() ?: remoteAddress
    }

    /** True when the request reached a trusted proxy over TLS (`X-Forwarded-Proto: https`). */
    fun forwardedAsHttps(remoteAddress: String, forwardedProto: String?): Boolean =
        isTrusted(remoteAddress) && forwardedProto?.split(',')?.lastOrNull()?.trim().equals("https", ignoreCase = true)

    private fun parse(entry: String): Block {
        require(entry.isNotEmpty()) { "empty entry in the trusted proxy list" }
        val slash = entry.indexOf('/')
        val host = if (slash < 0) entry else entry.substring(0, slash)
        val bytes = literalBytes(host) ?: throw IllegalArgumentException("not an IP address in the trusted proxy list: '$entry'")
        val max = bytes.size * 8
        val prefix = if (slash < 0) max else entry.substring(slash + 1).toIntOrNull()
            ?: throw IllegalArgumentException("bad prefix length in the trusted proxy list: '$entry'")
        require(prefix in 0..max) { "prefix length out of range in the trusted proxy list: '$entry'" }
        return Block(bytes, prefix)
    }

    /** Bytes of an IP LITERAL only (never triggers a DNS lookup). */
    private fun literalBytes(text: String): ByteArray? {
        val t = text.trim().removePrefix("[").removeSuffix("]")
        if (t.isEmpty() || t.length > 45) return null
        val ipv4 = t.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
        val ipv6 = t.contains(':') && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!ipv4 && !ipv6) return null
        return try {
            InetAddress.getByName(t).address
        } catch (_: Exception) {
            null
        }
    }
}

/** Per-request network facts, resolved with the configured [TrustedProxies]. */
class ClientAddressResolver(
    private val proxies: TrustedProxies = TrustedProxies(),
    /** The TCP peer of the call. A seam for tests whose in-memory host has no real peer address. */
    private val peerOf: (ApplicationCall) -> String = { it.request.local.remoteAddress },
) {

    fun clientIp(call: ApplicationCall): String =
        proxies.clientAddress(peerOf(call), call.request.headers["X-Forwarded-For"])

    /** [clientIp] folded for rate limiting and lockouts: IPv6 by /64 (REV-10). */
    fun clientKey(call: ApplicationCall): String = RateKeys.normalise(clientIp(call))

    /** True when the caller's connection is TLS as far as the hub can tell. */
    fun isHttps(call: ApplicationCall, directTls: Boolean): Boolean =
        directTls || proxies.forwardedAsHttps(peerOf(call), call.request.headers["X-Forwarded-Proto"])

    /** True when the TCP peer is this machine and no proxy headers claim otherwise. */
    fun isDirectLoopback(call: ApplicationCall): Boolean {
        val headers = call.request.headers
        if (headers["X-Forwarded-For"] != null || headers["Forwarded"] != null || headers["X-Real-IP"] != null) return false
        val a = call.request.local.remoteAddress
        return a == "127.0.0.1" || a == "::1" || a == "0:0:0:0:0:0:0:1" || a == "localhost"
    }
}

/**
 * The key a rate limiter or a lockout counts a client under (REV-10): an IPv4 address as is, an IPv6 address by its /64
 * (one subscriber owns a /64 and can vary the rest at will, so counting full addresses gives an attacker 2^64 fresh buckets).
 * IPv4-mapped IPv6 (`::ffff:a.b.c.d`) is the IPv4 address.
 */
object RateKeys {
    fun normalise(address: String): String {
        val text = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (!text.contains(':')) return text.take(64)
        if (!text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return text.take(64)
        val bytes = try {
            InetAddress.getByName(text).address
        } catch (_: Exception) {
            return text.take(64)
        }
        if (bytes.size == 4) return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        if (bytes.size != 16) return text.take(64)
        return bytes.take(8).chunked(2).joinToString(":") { (a, b) -> "%02x%02x".format(a.toInt() and 0xFF, b.toInt() and 0xFF) } + "::/64"
    }
}
