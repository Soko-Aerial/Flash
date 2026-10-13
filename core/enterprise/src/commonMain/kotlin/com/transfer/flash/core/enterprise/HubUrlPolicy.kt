package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Which hub URLs the app will talk to (CLI-01).
 *
 * - Only `https` / `wss` are accepted by default: an enrolment token, the device certificate and every directory call
 *   would otherwise cross the network in the clear.
 * - Cleartext `http` / `ws` is accepted for a loopback host (`localhost`, `127.x.x.x`, `::1`, used by development and
 *   by tests) and, only when [allowCleartext] is true, for any host. Hosts pass `true` only from a debug-build setting
 *   (never in release builds).
 * - A URL with a user-info part (`https://user:pass@host`) is invalid: credentials never belong in a hub URL and a
 *   crafted `https://trusted.example@evil.example` is a classic spoof.
 *
 * Pure string parsing, so it behaves the same on Android, desktop and in tests.
 */
@FlashInternalApi
public object HubUrlPolicy {

    public enum class Verdict { OK, CLEARTEXT_REFUSED, INVALID }

    /** A parsed hub URL: lower-case [scheme] and [host] (no brackets), optional [port]. */
    public data class Parsed(val scheme: String, val host: String, val port: Int?) {
        val isCleartext: Boolean get() = scheme == "http" || scheme == "ws"
        val isLoopback: Boolean get() = isLoopbackHost(host)
    }

    public fun parse(url: String): Parsed? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme !in SCHEMES) return null
        val rest = trimmed.substring(schemeEnd + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || '@' in authority || authority.any { it.isWhitespace() }) return null
        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close).lowercase()
            val after = authority.substring(close + 1)
            portText = if (after.startsWith(":")) after.substring(1) else if (after.isEmpty()) null else return null
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon).lowercase()
                portText = authority.substring(colon + 1)
            } else {
                host = authority.lowercase()
                portText = null
            }
        }
        if (host.isEmpty()) return null
        val port = if (portText == null) null else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Parsed(scheme, host, port)
    }

    public fun check(url: String, allowCleartext: Boolean = false): Verdict {
        val parsed = parse(url) ?: return Verdict.INVALID
        return if (!parsed.isCleartext || parsed.isLoopback || allowCleartext) Verdict.OK else Verdict.CLEARTEXT_REFUSED
    }

    /** True for `localhost`, any `127.x.y.z` and `::1`. */
    public fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1") return true
        val parts = h.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** `scheme://host[:port]` without a path, with a trailing slash removed; the form REST clients append paths to. */
    public fun baseUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return trimmed
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val http = when (scheme) {
            "ws" -> "http"
            "wss" -> "https"
            else -> scheme
        }
        return http + trimmed.substring(schemeEnd).substringBefore("/flash-relay").removeSuffix("/")
    }

    /**
     * True when [a] and [b] name the same hub: same host and port, any of http/https/ws/wss. The credentials pin the
     * hub's host (CLI-01), so every REST and relay call is checked against it with this.
     */
    public fun sameHub(a: String, b: String): Boolean {
        val pa = parse(a) ?: return false
        val pb = parse(b) ?: return false
        return pa.host == pb.host && effectivePort(pa) == effectivePort(pb)
    }

    private fun effectivePort(p: Parsed): Int = p.port ?: if (p.scheme == "https" || p.scheme == "wss") 443 else 80

    private val SCHEMES = setOf("http", "https", "ws", "wss")
}
