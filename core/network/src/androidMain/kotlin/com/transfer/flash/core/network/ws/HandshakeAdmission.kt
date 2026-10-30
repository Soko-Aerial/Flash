package com.transfer.flash.core.network.ws

/**
 * Bounds how many inbound sockets may sit in the TLS and WebSocket handshake at once (SEC-D24-01).
 *
 * Each accepted socket costs a coroutine and, for TLS, a lazily driven handshake, and it holds for up to the 8 s handshake
 * timeout. Without a bound one host opening thousands of sockets and sending nothing exhausts memory or file descriptors
 * before any identity check runs. Real peers handshake in milliseconds, so the limits are generous: [maxTotal] across all
 * addresses and [maxPerHost] for one address (a hotspot's NAT can put several devices behind one).
 *
 * A socket that is refused is closed at once; the peer's dial fails and its own retry ladder tries again.
 */
internal class HandshakeAdmission(
    private val maxTotal: Int = DEFAULT_MAX_TOTAL,
    private val maxPerHost: Int = DEFAULT_MAX_PER_HOST,
) {
    private val perHost = HashMap<String, Int>()
    private var total = 0

    /** Whether a new handshake from [host] may start. Every `true` must be paired with one [release]. */
    @Synchronized
    fun tryAdmit(host: String): Boolean {
        if (total >= maxTotal) return false
        val current = perHost[host] ?: 0
        if (current >= maxPerHost) return false
        perHost[host] = current + 1
        total++
        return true
    }

    @Synchronized
    fun release(host: String) {
        val current = perHost[host] ?: return
        if (current <= 1) perHost.remove(host) else perHost[host] = current - 1
        if (total > 0) total--
    }

    @Synchronized
    fun pending(): Int = total

    internal companion object {
        const val DEFAULT_MAX_TOTAL: Int = 64
        const val DEFAULT_MAX_PER_HOST: Int = 16
    }
}
