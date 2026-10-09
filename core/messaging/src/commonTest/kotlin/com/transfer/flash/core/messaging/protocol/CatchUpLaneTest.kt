package com.transfer.flash.core.messaging.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-106 (review G3): a group's catch-up asks one holder at a time. Pure bookkeeping, fake clock.
 */
class CatchUpLaneTest {
    private val stall = 1_000L
    private val episode = 10_000L

    private fun lane() = CatchUpLane(stallMs = stall, episodeMs = episode)

    @Test
    fun onlyTheFirstOfManyHoldersIsStartedAndTheRestWait() = runBlocking {
        val lane = lane()
        assertEquals("h1", lane.offer(listOf("h1", "h2", "h3"), nowMs = 0))
        assertNull(lane.offer(listOf("h2", "h3"), nowMs = 100), "a second offer while the head is healthy starts nobody")
        assertEquals("h1", lane.leader())
    }

    @Test
    fun aCompleteChainThatDeliveredRowsEndsTheEpisodeAndTheOthersAreNeverAsked() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2", "h3"), 0)
        lane.progress("h1", pushedRows = 30, nowMs = 200)
        assertNull(lane.finish("h1", complete = true, nowMs = 300))
        assertNull(lane.offer(listOf("h2"), 400), "inside the episode nobody is started")
        assertNull(lane.offer(listOf("h3"), episode - 1))
        // The next episode starts a holder again.
        assertEquals("h2", lane.offer(listOf("h2", "h3"), 300 + episode))
    }

    @Test
    fun aHolderThatDeliveredNothingHandsOverToTheNextOne() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2", "h3"), 0)
        assertEquals("h2", lane.finish("h1", complete = true, nowMs = 100))
        assertEquals("h2", lane.leader())
        lane.progress("h2", 5, 200)
        assertNull(lane.finish("h2", complete = true, nowMs = 300))
        assertNull(lane.offer(listOf("h3"), 400), "h3 was never needed")
    }

    @Test
    fun aFailedChainHandsOverAndAnIncompleteOneToo() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2"), 0)
        lane.progress("h1", 3, 50)
        assertEquals("h2", lane.finish("h1", complete = false, nowMs = 100), "a chain with a lost page is not a delivery")
    }

    @Test
    fun aHolderThatGoesQuietIsReplacedAfterTheStallTime() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2"), 0)
        val early = lane.checkStall("h1", stall - 1)
        assertTrue(early.stillLeads)
        assertNull(early.next)
        val late = lane.checkStall("h1", stall)
        assertFalse(late.stillLeads)
        assertEquals("h2", late.next)
        assertEquals("h2", lane.leader())
        assertFalse(lane.checkStall("h1", stall + 10).stillLeads, "the replaced holder no longer leads")
    }

    @Test
    fun signsOfLifeKeepTheHeadInChargeAndAMarkerlessHolderThatPushedCountsAsDone() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2"), 0)
        lane.progress("h1", 1, 900)
        assertTrue(lane.checkStall("h1", 1_500).stillLeads, "a push at 900 keeps it alive at 1 500")
        // An older build never sends a marker: after it goes quiet, rows were delivered, so the episode ends.
        val step = lane.checkStall("h1", 900 + stall)
        assertFalse(step.stillLeads)
        assertNull(step.next)
        assertNull(lane.offer(listOf("h2"), 2_000))
    }

    @Test
    fun theHolderInChargeOfferedAgainRestartsItsOwnChain() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2"), 0)
        lane.progress("h1", 4, 100)
        assertEquals("h1", lane.offer(listOf("h1"), 200), "its session came back")
        // The restarted chain delivered nothing yet, so finishing it empty moves on.
        assertEquals("h2", lane.finish("h1", complete = true, nowMs = 250))
    }

    @Test
    fun aHolderThatCameBackOnlineIsAskedAgainAfterItFailed() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1"), 0)
        assertNull(lane.finish("h1", complete = false, nowMs = 10))
        assertEquals("h1", lane.offer(listOf("h1"), 20))
    }

    @Test
    fun aNewChoiceStartsANewEpisode() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1"), 0)
        lane.progress("h1", 2, 10)
        lane.finish("h1", complete = true, nowMs = 20)
        assertNull(lane.offer(listOf("h2"), 30))
        lane.reset()
        assertEquals("h2", lane.offer(listOf("h2"), 40))
    }

    @Test
    fun aFinishFromAHolderThatIsNotInChargeChangesNothing() = runBlocking {
        val lane = lane()
        lane.offer(listOf("h1", "h2"), 0)
        assertNull(lane.finish("h2", complete = true, nowMs = 5))
        assertEquals("h1", lane.leader())
    }

    @Test
    fun theTallyKeepsTheNewestPushAndItsCount() {
        val tally = PushTally()
        listOf(GroupSyncCursor(30L, "c"), GroupSyncCursor(10L, "a"), GroupSyncCursor(20L, "b")).forEach { tally.record(it) }
        assertEquals(3, tally.seen.value)
        assertEquals(GroupSyncCursor(30L, "c"), tally.newest)
    }

    @Test
    fun theTallyIsPartOfItsRequestSoNothingOutlivesTheRequest() {
        val a = OutgoingSyncRequest("g", "h", requestedAtMs = 0L, windowMs = 1L)
        val b = OutgoingSyncRequest("g", "h", requestedAtMs = 0L, windowMs = 1L)
        a.tally.record(GroupSyncCursor(1L, "x"))
        assertEquals(0, b.tally.seen.value)
        val drop = OutgoingSyncRequest.keysToDrop(mapOf("old" to a), nowMs = GroupPolicy.SYNC_REQUEST_TTL_MS + 1)
        assertEquals(listOf("old"), drop)
    }

    @Test
    fun theOneAdminRuleMatchesTheCheckTheRepositoryUsedToRepeat() {
        // G10: updateGroupSettings used to AND "has an active row or is the owner" in front of the policy. The policy already
        // needs an active roster row, so the extra condition was redundant; this pins that for every roster shape.
        val owner = "o"
        val shapes = listOf(
            listOf(GroupAdminPolicy.Member("me", "owner", true)),
            listOf(GroupAdminPolicy.Member("me", "admin", true)),
            listOf(GroupAdminPolicy.Member("me", "member", true)),
            listOf(GroupAdminPolicy.Member("me", "admin", false)),
            listOf(GroupAdminPolicy.Member("me", "owner", false)),
            emptyList(),
        )
        for (ownerId in listOf("me", owner, null)) for (roster in shapes) {
            val self = roster.firstOrNull { it.deviceId == "me" }?.takeIf { it.isActive }
            val old = (self != null || ownerId == "me") && GroupAdminPolicy.canChangeHistoryCeiling("me", ownerId, roster)
            assertEquals(old, GroupAdminPolicy.canChangeHistoryCeiling("me", ownerId, roster), "owner=$ownerId roster=$roster")
        }
    }
}
