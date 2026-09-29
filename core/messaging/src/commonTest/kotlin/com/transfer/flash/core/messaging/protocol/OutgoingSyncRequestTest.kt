package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutgoingSyncRequestTest {
    private val request = OutgoingSyncRequest(groupId = "g1", askedPeerId = "peer-a", requestedAtMs = 1_000L)

    @Test
    fun `accepts a push from the asked peer for the asked group inside the ttl`() {
        assertTrue(request.acceptsPush("g1", "peer-a", 1_000L))
        assertTrue(request.acceptsPush("g1", "peer-a", 1_000L + GroupPolicy.SYNC_REQUEST_TTL_MS))
    }

    @Test
    fun `rejects another peer, another group and an expired round`() {
        assertFalse(request.acceptsPush("g1", "peer-b", 2_000L))
        assertFalse(request.acceptsPush("g2", "peer-a", 2_000L))
        assertFalse(request.acceptsPush("g1", "peer-a", 1_001L + GroupPolicy.SYNC_REQUEST_TTL_MS))
    }

    @Test
    fun `a clock that steps backwards does not cut off an answer in flight`() {
        assertTrue(request.acceptsPush("g1", "peer-a", 500L))
    }

    @Test
    fun `keysToDrop forgets expired requests first`() {
        val now = 10L * GroupPolicy.SYNC_REQUEST_TTL_MS
        val all = mapOf(
            "old" to OutgoingSyncRequest("g", "p", now - GroupPolicy.SYNC_REQUEST_TTL_MS - 1),
            "fresh" to OutgoingSyncRequest("g", "p", now - 1),
        )
        assertEquals(listOf("old"), OutgoingSyncRequest.keysToDrop(all, now))
    }

    @Test
    fun `keysToDrop keeps the ledger bounded by dropping the oldest live requests`() {
        val now = 1_000_000L
        val all = (0 until GroupPolicy.MAX_OUTGOING_SYNC_REQUESTS + 3).associate { i ->
            "sync-$i" to OutgoingSyncRequest("g", "p", now - 1_000L + i)
        }
        assertEquals(listOf("sync-0", "sync-1", "sync-2"), OutgoingSyncRequest.keysToDrop(all, now))
    }

    @Test
    fun `keysToDrop leaves a small live ledger alone`() {
        val all = mapOf("a" to OutgoingSyncRequest("g", "p", 5L))
        assertTrue(OutgoingSyncRequest.keysToDrop(all, 6L).isEmpty())
    }
}
