package com.transfer.flash.core.messaging.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
    /** ADR-100: the window this request carried; null for a request that carried none (the pre-ADR-100 shape). */
    val windowMs: Long? = null,
    val includeFiles: Boolean? = null,
    /** Which page of a continuation chain this request is (0 = the first). */
    val pageNo: Int = 0,
    /**
     * G5/G12: the pushes seen for THIS request. It lives and dies with the request, so an entry cannot outlive it (a holder
     * that never sends a page marker used to leave one behind per request) and a late push cannot re-create a removed one.
     */
    val tally: PushTally = PushTally(),
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

/**
 * What a windowed request has received so far (ADR-100): how many pushes, and the newest `(sentAt, id)` among them. The page
 * marker is believed only as far as the pushes it announces: its claimed end is cut back to [newest] (G12), so a holder cannot
 * move this device's watermark past rows it never sent.
 */
internal class PushTally {
    /** Pushes seen, whatever became of them (stored, duplicate, refused as unsigned): the marker counts rows SENT. */
    val seen: MutableStateFlow<Int> = MutableStateFlow(0)

    @kotlin.concurrent.Volatile
    var newest: GroupSyncCursor? = null
        private set

    fun record(cursor: GroupSyncCursor) {
        val current = newest
        if (current == null || cursor > current) newest = cursor
        seen.update { it + 1 }
    }
}
