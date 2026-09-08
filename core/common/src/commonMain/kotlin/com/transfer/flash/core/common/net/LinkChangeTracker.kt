package com.transfer.flash.core.common.net

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Decides when a stream of platform link reports should be treated as "the link moved"
 * (ERROR-033, ERROR-035).
 *
 * ## Why this is needed at all
 *
 * The reported failure was that a walking user's device loses its session on a multi-AP mesh and
 * does not get it back, while two other phones do. The mechanism has two halves, and this class is
 * the first: **a mesh roam is invisible to the code that watches for network changes.**
 *
 * `ConnectivityManager` hands out one `Network` object per *network*, not per association. Roaming
 * from one AP of an SSID to another keeps the same SSID, the same `Network`, and usually the same
 * DHCP lease — so `onAvailable` never fires again and `onLost` never fires at all. Every recovery
 * path keyed off those two callbacks therefore sleeps through the one event it exists for. The
 * session dies of its own liveness watchdog some seconds later and is then redialled by a backoff
 * loop that has already climbed toward its ceiling.
 *
 * ## Why the signal is a hint and not a fact
 *
 * There is no permission-free, all-API-level way to *know* a roam happened. The BSSID would say so
 * outright, but reading it needs `ACCESS_FINE_LOCATION` (below API 29 via `WifiManager`, and from
 * API 31 the copy inside `NetworkCapabilities` is redacted without that permission too). Adding a
 * location permission to detect a Wi-Fi roam is not a trade worth making, so the signal used
 * instead is the *shape* of the link: interface, addresses, routes, DNS, and the platform's own
 * link-bandwidth estimate, any of which commonly — but not always — changes across a reassociation.
 *
 * That makes false positives certain and false negatives possible, which dictates the response:
 * a change must trigger something **cheap and non-destructive**. The transport answers a verdict
 * from this class by *probing* its sessions (one keepalive frame each) and only reaping the ones
 * that fail to answer; discovery answers it by re-arming its browse and advertisement. A false
 * positive therefore costs one frame per peer and one mDNS re-register; a false negative costs
 * nothing that was not already broken, because the liveness watchdog is still there behind it.
 *
 * ## Rate limiting is the load-bearing part
 *
 * `onCapabilitiesChanged` fires whenever the platform's bandwidth estimate moves, which on a
 * marginal link is continuous. Without [minIntervalMs] a weak signal would generate a permanent
 * probe storm — more airtime spent asking "are you there" than the roam ever cost. One verdict per
 * interval, and the interval is deliberately longer than a probe window.
 *
 * ## Why this lives in `core:common`
 *
 * ERROR-035: two subsystems have to answer the same question. `core:network` probes and redials its
 * sessions; `core:discovery` re-registers its NSD advertisement and restarts its browse. Module
 * direction rules out sharing it from either one — `core:network` depends on `core:discovery`, so a
 * class in `core:network` is invisible to the discovery side, and the reverse would invert the
 * dependency. A second copy would be worse than either: the rate limit is the load-bearing part, and
 * two copies drift. Pure Kotlin with no platform types, so it also satisfies the `commonMain` rule.
 *
 * Single-threaded-by-contract: `ConnectivityManager` delivers every callback for a given callback
 * object on one thread, and each instance is only ever fed from there. One instance per callback
 * registration; never share one across two registrations.
 *
 * @param nowMs wall clock, injectable so tests need not sleep.
 * @param minIntervalMs floor on the gap between two link-change verdicts.
 */
@FlashInternalApi
public class LinkChangeTracker(
    private val nowMs: () -> Long,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private var lastFingerprint: String? = null
    private var lastVerdictAtMs: Long = 0L

    /**
     * Feeds one platform report.
     *
     * @return true when the caller should treat this as a possible roam. False for the first
     *   report ever seen (that is the initial association, not a change), for an unchanged
     *   fingerprint, and for a change that arrives inside [minIntervalMs] of the previous verdict.
     */
    public fun onFingerprint(fingerprint: String): Boolean {
        val previous = lastFingerprint
        lastFingerprint = fingerprint
        if (previous == null || previous == fingerprint) return false
        val now = nowMs()
        // Note the `lastVerdictAtMs == 0L` case: the very first *change* is always reported, even
        // if the process started less than an interval ago. A roam during app start is exactly the
        // case this exists for.
        if (lastVerdictAtMs != 0L && now - lastVerdictAtMs < minIntervalMs) return false
        lastVerdictAtMs = now
        return true
    }

    /** Forgets the recorded link so the next report re-seeds instead of reading as a change. */
    public fun reset() {
        lastFingerprint = null
        lastVerdictAtMs = 0L
    }

    public companion object {
        /**
         * 5 s. Longer than the longest tier's `linkChangeProbeMs` (4 s) so a probe round always
         * finishes before another can be triggered, and short enough that two genuine roams a few
         * seconds apart — a user walking past an AP — are both seen.
         */
        public const val DEFAULT_MIN_INTERVAL_MS: Long = 5_000L
    }
}
