package com.transfer.flash.core.messaging.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSyncPolicyTest {
    private data class Msg(val id: String, val at: Long, val deleted: Long? = null)

    @Test
    fun electionIsDeterministicAcrossObservationOrder() {
        val claims = linkedMapOf(
            "device-x" to GroupSyncTier.MEDIUM,
            "device-y" to GroupSyncTier.LOW,
            "device-z" to GroupSyncTier.HIGH,
        )
        // The LOW claimant always wins on tierRank regardless of hash or observation order.
        val ranked1: List<String> = GroupSyncPolicy.electRank(claims, "msg-1")
        val ranked2: List<String> = GroupSyncPolicy.electRank(claims.entries.reversed().associate { it.toPair() }, "msg-1")
        assertEquals(ranked1, ranked2)
        assertEquals("device-y", ranked1.first())
    }

    @Test
    fun sameTierTieBreaksStablyByHash() {
        val claims = mapOf("a" to GroupSyncTier.HIGH, "b" to GroupSyncTier.HIGH)
        val ranked = GroupSyncPolicy.electRank(claims, "msg-1")
        assertEquals(ranked, GroupSyncPolicy.electRank(claims, "msg-1"))
        assertEquals(2, ranked.size)
    }

    @Test
    fun ownedMessagesFiltersCursorDeletedTtlAndBudget() {
        val now = 1_000_000_000L
        val messages = listOf(
            Msg("old", now - 2 * GroupPolicy.SYNC_TTL_MS), // outside TTL
            Msg("at-cursor", now - 500L), // equal cursor position → not newer
            Msg("before-cursor", now - 600L),
            Msg("deleted", now - 400L, deleted = now - 300L),
            Msg("new-1", now - 400L),
            Msg("new-2", now - 300L),
        )
        val owned = GroupSyncPolicy.ownedMessages(
            messages = messages,
            cursor = GroupSyncCursor(now - 500L, "at-cursor"),
            maxTotal = 10,
            nowMs = now,
            sentAt = { it.at },
            messageId = { it.id },
            deletedAt = { it.deleted },
        )
        assertEquals(listOf("new-1", "new-2"), owned.map { it.id })
    }

    @Test
    fun budgetCapsTheRound() {
        val now = 1_000_000_000L
        val messages = (1..50).map { Msg("m-%03d".format(it), now - 1000L + it) }
        val owned = GroupSyncPolicy.ownedMessages(
            messages = messages,
            cursor = GroupSyncCursor(Long.MIN_VALUE, ""),
            maxTotal = 7,
            nowMs = now,
            sentAt = { it.at },
            messageId = { it.id },
            deletedAt = { it.deleted },
        )
        assertEquals(7, owned.size)
    }

    @Test
    fun pushIntervalIsOneSecondDividedByRate() {
        assertEquals(50L, GroupSyncPolicy.pushIntervalMs(20))
        assertEquals(200L, GroupSyncPolicy.pushIntervalMs(5))
        assertEquals(1_000L, GroupSyncPolicy.pushIntervalMs(1))
        assertEquals(1_000L, GroupSyncPolicy.pushIntervalMs(0))
    }

    @Test
    fun claimWindowUsesLowTierWindow() {
        val requestedAt = 1_000L
        assertTrue(GroupSyncPolicy.claimWindowOpen(requestedAt, requesterIsLow = true, nowMs = requestedAt + GroupPolicy.LOW_CLAIM_WINDOW_MS))
        assertFalse(GroupSyncPolicy.claimWindowOpen(requestedAt, requesterIsLow = false, nowMs = requestedAt + GroupPolicy.LOW_CLAIM_WINDOW_MS))
        assertTrue(GroupSyncPolicy.claimWindowOpen(requestedAt, requesterIsLow = false, nowMs = requestedAt + GroupPolicy.CLAIM_WINDOW_MS))
    }

    @Test
    fun stableHashIsDeterministicAndCollidesOnIdenticalInputOnly() {
        // Pin the exact FNV-1a Int-truncated results (computed once, order-stable forever);
        // the point is cross-process stability, not a particular external constant.
        assertEquals(GroupSyncPolicy.stableHash("abc"), 617690539)
        assertTrue(GroupSyncPolicy.stableHash("abc") != GroupSyncPolicy.stableHash("abd"))
    }
}
