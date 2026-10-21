package com.transfer.flash.ui.hub

import com.transfer.flash.core.enterprise.OrgLease

/** How the offline-trust lease reads at a glance: the colour and word of the ring on the Hub header. */
public enum class FlashHubLeaseTone { HEALTHY, SOON, EXPIRED, UNKNOWN }

/**
 * Pure numbers behind the Hub's visual layer (2026-10-20 redesign): the lease ring, the department share bars and the
 * count-up. Kept out of the composables so they are unit-tested without composing anything.
 */
public object FlashHubVisualMath {
    private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

    /** The ring is full when this many days of offline trust remain (the default `offlineGraceDays`, docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md). */
    public const val LEASE_WINDOW_DAYS: Int = 30

    /** At or below this many days the ring turns to the warning tone. */
    public const val LEASE_SOON_DAYS: Int = 7

    /** Whole days of offline trust left, rounded up (23.2 days reads as 24); 0 once the lease ended; null when there never was one. */
    public fun leaseDaysLeft(lease: OrgLease, nowMs: Long): Int? = when (lease) {
        is OrgLease.Valid -> {
            val remaining = lease.untilMs - nowMs
            if (remaining <= 0L) 0 else ((remaining + DAY_MS - 1L) / DAY_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        is OrgLease.Expired -> 0
        OrgLease.None -> null
    }

    /** Ring fill, 0..1. Null when there is no lease at all (the header then draws no ring). */
    public fun leaseFraction(lease: OrgLease, nowMs: Long): Float? =
        leaseDaysLeft(lease, nowMs)?.let { (it.toFloat() / LEASE_WINDOW_DAYS).coerceIn(0f, 1f) }

    public fun leaseTone(lease: OrgLease, nowMs: Long): FlashHubLeaseTone {
        val days = leaseDaysLeft(lease, nowMs) ?: return FlashHubLeaseTone.UNKNOWN
        return when {
            days <= 0 -> FlashHubLeaseTone.EXPIRED
            days <= LEASE_SOON_DAYS -> FlashHubLeaseTone.SOON
            else -> FlashHubLeaseTone.HEALTHY
        }
    }

    /** One department's bar: its members as a share of the largest visible department (so the biggest bar is full). */
    public fun share(count: Int, max: Int): Float =
        if (max <= 0 || count <= 0) 0f else (count.toFloat() / max).coerceIn(0f, 1f)

    /** The value the count-up shows at [progress] (0..1) on its way to [target]. Never overshoots, never negative. */
    public fun countAt(target: Int, progress: Float): Int =
        if (target <= 0) 0 else (target * progress.coerceIn(0f, 1f)).toInt().coerceIn(0, target)

    /** Sweep angle of a ring, in degrees, for a fill fraction. */
    public fun sweepDegrees(fraction: Float): Float = 360f * fraction.coerceIn(0f, 1f)
}
