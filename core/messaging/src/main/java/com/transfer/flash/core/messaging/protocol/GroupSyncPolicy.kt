package com.transfer.flash.core.messaging.protocol

import kotlin.experimental.and

/**
 * Pure FLASH_GSYNC election/pacing rules (F3 / group Phase 1B). Deterministic so every holder
 * that observes the same claim set elects the SAME pusher — no coordinator, no negotiation.
 */
public object GroupSyncPolicy {

    /**
     * Deterministic rank of a claimant for one message: claims are elected by
     * `(tierRank, stableHash(deviceId + msgId))` — lowest wins (rank 0 pushes), rank 1 arms
     * the backup timer, the rest stand down. [stableHash] is a 32-bit FNV-1a so the order is
     * stable across processes and platforms (String.hashCode is not contractual).
     */
    public fun <T> electRank(
        claimants: Map<T, GroupSyncTier>,
        msgId: String,
    ): List<T> = claimants.entries
        .sortedWith(
            compareBy(
                { tierRank(it.value) },
                { stableHash(it.key.toString() + msgId) },
            ),
        )
        .map { it.key }

    public fun tierRank(tier: GroupSyncTier): Int = when (tier) {
        GroupSyncTier.LOW -> 0
        GroupSyncTier.MEDIUM -> 1
        GroupSyncTier.HIGH -> 2
    }

    /** 32-bit FNV-1a, returned as an unsigned-ordered Int (stable, platform-independent). */
    public fun stableHash(input: String): Int {
        var hash = -0x340d631b // FNV offset basis (Int-truncated)
        for (byte in input.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte and 0xff.toByte()).toInt()
            hash *= 0x01000193 // FNV prime
        }
        return hash
    }

    /**
     * Messages one holder owns for a catch-up round: strictly newer than the requester's
     * `(sentAt, msgId)` cursor, non-tombstoned, inside the TTL window, and capped at the
     * requester's per-round budget.
     */
    public fun <M> ownedMessages(
        messages: Collection<M>,
        cursor: GroupSyncCursor,
        maxTotal: Int,
        nowMs: Long,
        sentAt: (M) -> Long,
        messageId: (M) -> String,
        deletedAt: (M) -> Long?,
    ): List<M> = messages
        .filter { deletedAt(it) == null && sentAt(it) >= nowMs - GroupPolicy.SYNC_TTL_MS }
        .filter { GroupSyncCursor(sentAt(it), messageId(it)) > cursor }
        .sortedBy { GroupSyncCursor(sentAt(it), messageId(it)) }
        .take(maxTotal.coerceAtLeast(0))

    /**
     * Inter-push delay for a paced push: [maxPerSecond] messages per second, floored at 1 ms.
     */
    public fun pushIntervalMs(maxPerSecond: Int): Long {
        if (maxPerSecond <= 0) return 1_000L
        return (1_000L / maxPerSecond).coerceAtLeast(1L)
    }

    /** Whether a claim is still inside the window measured from [requestedAtMs]. */
    public fun claimWindowOpen(requestedAtMs: Long, requesterIsLow: Boolean, nowMs: Long): Boolean {
        val window = if (requesterIsLow) GroupPolicy.LOW_CLAIM_WINDOW_MS else GroupPolicy.CLAIM_WINDOW_MS
        return nowMs - requestedAtMs <= window
    }
}
