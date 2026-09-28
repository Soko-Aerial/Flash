package com.transfer.flash.core.network.resilience

/**
 * Spreads a reconnect wave over [spreadMs] after a mass drop (PC2, `PRESENCE-CONNECTIONS-PLAN.md`
 * §3.5, "staggered storms").
 *
 * When the Wi-Fi goes, every session on every phone drops together. Without this, each phone's
 * reconnect loops fire at the same backoff floor, and when Wi-Fi returns the network watcher redials
 * everything at once. With 20 phones that is up to 190 TLS handshakes in the same second. This adds
 * a fixed offset per pair, `hash(local, peer) mod spreadMs`, to the **first** attempt of each
 * reconnect loop, but only during a storm. Later attempts follow the normal backoff, and a single
 * drop is never delayed.
 *
 * A storm is at least [threshold] unexpected drops within [windowMs]. It lasts until [holdMs] after
 * its last drop, so the Wi-Fi-rejoin redial that follows a mass drop is spread too.
 *
 * The offset is per pair, not per device (the plan says `hash(deviceId)`): the pair spreads one
 * phone's own loops as well as the phones across the mesh, and it is still deterministic, so a
 * test or a log can predict it. `String.hashCode` is specified identically on every Kotlin target.
 *
 * Not thread-safe. The networks call it under their registry lock.
 */
internal class ReconnectStagger(
    private val localDeviceId: String,
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val spreadMs: Long = DEFAULT_SPREAD_MS,
) {
    private val recentDrops = ArrayDeque<Long>()
    private var stormLastDropAtMs: Long? = null

    /** Records an unexpected session drop (not a local disconnect, not a stop). */
    fun onUnexpectedDrop(nowMs: Long) {
        recentDrops.addLast(nowMs)
        while (recentDrops.isNotEmpty() && nowMs - recentDrops.first() > windowMs) recentDrops.removeFirst()
        if (recentDrops.size >= threshold) stormLastDropAtMs = nowMs
    }

    fun inStorm(nowMs: Long): Boolean {
        val last = stormLastDropAtMs ?: return false
        return nowMs - last <= holdMs
    }

    /** Extra delay for the first attempt of a reconnect loop to [peerDeviceId]; 0 outside a storm. */
    fun firstAttemptDelayMs(peerDeviceId: String, nowMs: Long): Long =
        if (inStorm(nowMs)) offsetMs(peerDeviceId) else 0L

    fun offsetMs(peerDeviceId: String): Long {
        if (spreadMs <= 0) return 0L
        val h = "$localDeviceId|$peerDeviceId".hashCode().toLong()
        return ((h % spreadMs) + spreadMs) % spreadMs
    }

    companion object {
        const val DEFAULT_THRESHOLD: Int = 4
        const val DEFAULT_WINDOW_MS: Long = 3_000L
        const val DEFAULT_HOLD_MS: Long = 30_000L
        const val DEFAULT_SPREAD_MS: Long = 2_000L
    }
}
