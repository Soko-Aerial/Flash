package com.transfer.flash.core.radio.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * OPT-O26-03 / ADR-331: the outbound ACK table is bounded by size and age, and forgetting an entry costs a
 * retransmission (the outbox re-sends the identical frame and the peer re-ACKs it, ERROR-869/ERROR-870), not a message.
 */
class PendingAckTableTest {

    @Test
    fun keepsAtMostItsMaximumAndEvictsTheOldest() {
        val table = PendingAckTable()
        repeat(1_000) { table.put(it.toLong(), "m$it", "peer-b", sentAtMs = it.toLong(), nowMs = it.toLong()) }
        assertEquals(PendingAckTable.MAX_ENTRIES, table.size, "1 000 unacknowledged sends must not grow the table")
        assertNull(table.remove(0, nowMs = 1_000), "the oldest entry was evicted")
        assertNotNull(table.remove(999, nowMs = 1_000), "the newest entry is still tracked")
    }

    @Test
    fun forgetsAnEntryOlderThanTheTtl() {
        val table = PendingAckTable(ttlMs = 1_000)
        table.put(1, "m1", "peer-b", sentAtMs = 0, nowMs = 0)
        table.put(2, "m2", "peer-b", sentAtMs = 0, nowMs = 500)
        assertNull(table.remove(1, nowMs = 1_000), "1 000 ms old with a 1 000 ms TTL is gone")
        assertNotNull(table.remove(2, nowMs = 1_000), "500 ms old is still waiting")
    }

    @Test
    fun anExpiredEntryCannotProduceADeliveryReceipt() {
        val table = PendingAckTable(ttlMs = 100)
        table.put(7, "m7", "peer-b", sentAtMs = 0, nowMs = 0)
        assertNull(table.remove(7, nowMs = 5_000), "the outbox gave up long ago; a late ACK must not resurrect it")
        assertEquals(0, table.size)
    }

    @Test
    fun reSendingTheSameCounterRestartsItsTtl() {
        val table = PendingAckTable(ttlMs = 1_000)
        table.put(5, "m5", "peer-b", sentAtMs = 0, nowMs = 0)
        table.put(5, "m5", "peer-b", sentAtMs = 0, nowMs = 900) // the retry re-sends the identical frame
        assertEquals(1, table.size, "a re-send refreshes one entry, it does not add a second")
        assertNotNull(table.remove(5, nowMs = 1_500), "the refreshed entry outlives the original TTL")
    }

    @Test
    fun anUnknownCounterIsHarmless() {
        val table = PendingAckTable()
        assertNull(table.remove(42, nowMs = 0))
        assertEquals(0, table.size)
    }
}
