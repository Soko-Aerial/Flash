package com.transfer.flash.core.messaging.protocol

/**
 * A catch-up request this device sent (ADR-044 V1a, finding F-4).
 *
 * A `SyncPush` is only ingested against one of these: same `syncId`, same group, from the peer
 * that was asked, inside [GroupPolicy.SYNC_REQUEST_TTL_MS]. Before this, any active member could
 * push fabricated history at any time, because the receiver never recorded what it had asked for.
 */
internal data class OutgoingSyncRequest(
    val groupId: String,
    val askedPeerId: String,
    val requestedAtMs: Long,
) {
    fun acceptsPush(groupId: String, from: String, nowMs: Long): Boolean =
        this.groupId == groupId && askedPeerId == from &&
            nowMs - requestedAtMs <= GroupPolicy.SYNC_REQUEST_TTL_MS

    internal companion object {
        /**
         * Which recorded requests to forget: everything past the TTL, then the oldest beyond
         * [GroupPolicy.MAX_OUTGOING_SYNC_REQUESTS], so a device that reconnects to many peers
         * cannot grow the ledger without bound.
         */
        fun keysToDrop(all: Map<String, OutgoingSyncRequest>, nowMs: Long): List<String> {
            val expired = all.filterValues { nowMs - it.requestedAtMs > GroupPolicy.SYNC_REQUEST_TTL_MS }.keys
            val live = all.filterKeys { it !in expired }
            val overflow = (live.size - GroupPolicy.MAX_OUTGOING_SYNC_REQUESTS).coerceAtLeast(0)
            val oldest = live.entries.sortedBy { it.value.requestedAtMs }.take(overflow).map { it.key }
            return expired.toList() + oldest
        }
    }
}
