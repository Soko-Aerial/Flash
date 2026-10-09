package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rules of group history sync (ADR-100): ceiling clamping, holder windows, the returning-member window (O2),
 * where a request starts (S1) and when the watermark may move.
 */
class GroupHistoryPolicyTest {
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val now = 100L * day

    private data class Row(val id: String, val at: Long, val file: Boolean = false)

    // ---- ceiling and choice

    @Test
    fun ceilingWindowsAreTheOwnersNumbers() {
        assertEquals(0L, GroupHistoryCeiling.NONE.windowMs)
        assertEquals(24 * hour, GroupHistoryCeiling.H24.windowMs)
        assertEquals(7 * day, GroupHistoryCeiling.D7.windowMs)
        assertEquals(30 * day, GroupHistoryCeiling.D30.windowMs)
        assertEquals(Long.MAX_VALUE, GroupHistoryCeiling.ALL.windowMs)
        assertEquals(GroupHistoryCeiling.D30, GroupHistoryCeiling.DEFAULT)
    }

    @Test
    fun ceilingNamesRoundTripAndUnknownNamesAreRefused() {
        GroupHistoryCeiling.entries.forEach { assertEquals(it, GroupHistoryCeiling.fromName(it.name)) }
        assertEquals(GroupHistoryCeiling.D7, GroupHistoryCeiling.fromName(" d7 "))
        assertNull(GroupHistoryCeiling.fromName("D90"))
        assertNull(GroupHistoryCeiling.fromName(null))
        assertNull(GroupHistoryCeiling.fromName(""))
    }

    @Test
    fun aChoiceAboveTheCeilingIsClampedToIt() {
        val everything = GroupHistoryChoice(messageWindowMs = Long.MAX_VALUE, includeFiles = true)
        assertEquals(7 * day, GroupHistoryPolicy.clamp(everything, GroupHistoryCeiling.D7).messageWindowMs)
        assertEquals(24 * hour, GroupHistoryPolicy.clamp(everything, GroupHistoryCeiling.H24).messageWindowMs)
        assertEquals(Long.MAX_VALUE, GroupHistoryPolicy.clamp(everything, GroupHistoryCeiling.ALL).messageWindowMs)
        // A choice already inside the ceiling is kept as it is.
        val week = GroupHistoryChoice(7 * day, includeFiles = false)
        assertEquals(week, GroupHistoryPolicy.clamp(week, GroupHistoryCeiling.D30))
        // A negative window cannot ask for the future.
        assertEquals(0L, GroupHistoryPolicy.clamp(GroupHistoryChoice(-5L, true), GroupHistoryCeiling.D30).messageWindowMs)
    }

    @Test
    fun aNoneCeilingGivesNoMessagesAndNoFiles() {
        val clamped = GroupHistoryPolicy.clamp(GroupHistoryChoice(30 * day, includeFiles = true), GroupHistoryCeiling.NONE)
        assertEquals(GroupHistoryChoice(0L, includeFiles = false), clamped)
        assertEquals(listOf(0L), GroupHistoryPolicy.messageOptions(GroupHistoryCeiling.NONE))
    }

    @Test
    fun theDefaultChoiceIsThirtyDaysOfMessagesAndFilesInsideTheCeiling() {
        assertEquals(GroupHistoryChoice(30 * day, true), GroupHistoryPolicy.defaultChoice(GroupHistoryCeiling.D30))
        assertEquals(GroupHistoryChoice(30 * day, true), GroupHistoryPolicy.defaultChoice(GroupHistoryCeiling.ALL))
        assertEquals(GroupHistoryChoice(7 * day, true), GroupHistoryPolicy.defaultChoice(GroupHistoryCeiling.D7))
        assertEquals(GroupHistoryChoice(0L, false), GroupHistoryPolicy.defaultChoice(GroupHistoryCeiling.NONE))
    }

    @Test
    fun theCardOffersOnlyWindowsInsideTheCeiling() {
        assertEquals(listOf(0L, 24 * hour, 7 * day, 30 * day, Long.MAX_VALUE), GroupHistoryPolicy.messageOptions(GroupHistoryCeiling.ALL))
        assertEquals(listOf(0L, 24 * hour, 7 * day, 30 * day), GroupHistoryPolicy.messageOptions(GroupHistoryCeiling.D30))
        assertEquals(listOf(0L, 24 * hour, 7 * day), GroupHistoryPolicy.messageOptions(GroupHistoryCeiling.D7))
        assertEquals(listOf(0L, 24 * hour), GroupHistoryPolicy.messageOptions(GroupHistoryCeiling.H24))
    }

    // ---- holder windows

    @Test
    fun aHolderServesTheSmallerOfTheRequestAndTheCeiling() {
        val windows = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.D7, requestedWindowMs = 30 * day, includeFiles = true)
        assertEquals(7 * day, windows.textMs)
        assertEquals(7 * day, windows.fileMs)
        val small = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.D30, requestedWindowMs = 2 * day, includeFiles = true)
        assertEquals(2 * day, small.textMs)
        assertEquals(2 * day, small.fileMs, "files stay inside the window the requester asked for")
    }

    @Test
    fun filesNeverReachBackFurtherThanSevenDaysWhateverTheCeiling() {
        val windows = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.ALL, requestedWindowMs = Long.MAX_VALUE, includeFiles = true)
        assertEquals(Long.MAX_VALUE, windows.textMs)
        assertEquals(GroupHistoryPolicy.FILE_WINDOW_MS, windows.fileMs)
        assertEquals(7 * day, windows.fileMs)
    }

    @Test
    fun noFilesWhenTheRequesterDoesNotWantThemOrTheCeilingIsNone() {
        val declined = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.D30, requestedWindowMs = 30 * day, includeFiles = false)
        assertEquals(0L, declined.fileMs)
        assertEquals(30 * day, declined.textMs)
        val none = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.NONE, requestedWindowMs = 30 * day, includeFiles = true)
        assertTrue(none.servesNothing)
    }

    @Test
    fun anOlderRequesterWithNoWindowGetsTodaysWindowsButStillTheCeiling() {
        val legacy = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.D30, requestedWindowMs = null, includeFiles = null)
        assertEquals(24 * hour, legacy.textMs)
        assertEquals(7 * day, legacy.fileMs)
        // The setting binds a requester that has never heard of it.
        val capped = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.H24, requestedWindowMs = null, includeFiles = null)
        assertEquals(24 * hour, capped.textMs)
        assertEquals(24 * hour, capped.fileMs)
        assertTrue(GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.NONE, null, null).servesNothing)
    }

    @Test
    fun rowsAreServedByTheWindowOfTheirOwnKind() {
        val windows = GroupHistoryWindows(textMs = 30 * day, fileMs = 7 * day)
        assertTrue(GroupHistoryPolicy.inWindow(now - 29 * day, isFile = false, nowMs = now, windows = windows))
        assertFalse(GroupHistoryPolicy.inWindow(now - 31 * day, isFile = false, nowMs = now, windows = windows))
        assertTrue(GroupHistoryPolicy.inWindow(now - 6 * day, isFile = true, nowMs = now, windows = windows))
        assertFalse(GroupHistoryPolicy.inWindow(now - 8 * day, isFile = true, nowMs = now, windows = windows))
        assertFalse(GroupHistoryPolicy.inWindow(now, isFile = false, nowMs = now, windows = GroupHistoryWindows(0L, 0L)))
        // "Everything" does not overflow.
        assertTrue(GroupHistoryPolicy.inWindow(1L, isFile = false, nowMs = now, windows = GroupHistoryWindows(Long.MAX_VALUE, 0L)))
    }

    // ---- returning member (O2)

    @Test
    fun aReturningMemberAlwaysGetsTheSevenDayFloor() {
        // Away for an hour, or never seen before: 7 days (decision 3), not the old fixed 24 hours.
        assertEquals(7 * day, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D30, now - hour, now))
        assertEquals(7 * day, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D30, null, now))
        assertEquals(7 * day, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D30, 0L, now))
    }

    @Test
    fun aMemberAwayLongerThanAWeekGetsTheWholeGapUpToTheCeiling() {
        assertEquals(10 * day, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D30, now - 10 * day, now))
        assertEquals(30 * day, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D30, now - 60 * day, now))
        assertEquals(7 * day, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D7, now - 60 * day, now))
    }

    @Test
    fun theReturningFloorNeverExceedsTheCeiling() {
        assertEquals(24 * hour, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.H24, now - hour, now))
        assertEquals(0L, GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.NONE, now - 40 * day, now))
    }

    // ---- request start (S1)

    @Test
    fun aRequestStartsAtTheWatermarkNotAtTheNewestLocalRow() {
        val watermark = GroupSyncCursor(now - 3 * day, "w")
        val newest = GroupSyncCursor(now - hour, "live")
        val start = GroupHistoryPolicy.requestStart(watermark, newest, now, 7 * day)
        assertEquals(watermark, start)
    }

    @Test
    fun withoutAWatermarkTheNewestLocalRowIsTodaysCursor() {
        val newest = GroupSyncCursor(now - hour, "live")
        assertEquals(newest, GroupHistoryPolicy.requestStart(null, newest, now, 7 * day))
        assertEquals(GroupSyncCursor(now - 7 * day, ""), GroupHistoryPolicy.requestStart(null, null, now, 7 * day))
    }

    @Test
    fun aRequestNeverStartsBeforeTheWindow() {
        val old = GroupSyncCursor(now - 20 * day, "w")
        assertEquals(GroupSyncCursor(now - 7 * day, ""), GroupHistoryPolicy.requestStart(old, null, now, 7 * day))
        // A window of "none" starts right now: nothing earlier is asked for.
        assertEquals(GroupSyncCursor(now, ""), GroupHistoryPolicy.requestStart(null, null, now, 0L))
    }

    // ---- watermark

    @Test
    fun theWatermarkMovesOnlyWhenTheWholePageWasReceived() {
        val start = GroupSyncCursor(10L, "a")
        val end = GroupSyncCursor(50L, "z")
        assertEquals(end, GroupHistoryPolicy.advanceWatermark(start, end, pushedCount = 100, seenCount = 100))
        assertEquals(start, GroupHistoryPolicy.advanceWatermark(start, end, pushedCount = 100, seenCount = 99))
        assertEquals(end, GroupHistoryPolicy.advanceWatermark(null, end, pushedCount = 3, seenCount = 4))
        assertNull(GroupHistoryPolicy.advanceWatermark(null, end, pushedCount = 3, seenCount = 2))
    }

    @Test
    fun theWatermarkNeverMovesBackwardsAndAnEmptyPageLeavesItAlone() {
        val current = GroupSyncCursor(80L, "m")
        assertEquals(current, GroupHistoryPolicy.advanceWatermark(current, GroupSyncCursor(50L, "z"), 2, 2))
        assertEquals(current, GroupHistoryPolicy.advanceWatermark(current, null, 0, 0))
        assertNull(GroupHistoryPolicy.advanceWatermark(null, null, 0, 0))
    }

    @Test
    fun theBannerExpectsWhatLandedPlusWhatRemains() {
        assertEquals(340, GroupHistoryPolicy.expectedTotal(received = 100, remaining = 240))
        assertEquals(100, GroupHistoryPolicy.expectedTotal(received = 100, remaining = -3))
    }

    // ---- the proofs of S1 and S2, against the holder's real selection rule

    @Test
    fun s1TheNewestRowCursorSkipsARowMissedBelowIt_andTheWatermarkStartRecoversIt() {
        // The requester holds a live row R from an hour ago but missed M three hours ago (it was disconnected then).
        val holder = listOf(Row("m", now - 3 * hour), Row("r", now - hour))
        val newestLocal = GroupSyncCursor(now - hour, "r")
        val old = GroupSyncPolicy.ownedMessages(
            messages = holder, cursor = newestLocal, maxTotal = 10, nowMs = now,
            sentAt = { it.at }, messageId = { it.id }, deletedAt = { null },
        )
        assertTrue(old.none { it.id == "m" }, "today's cursor never asks for M")

        // The watermark of this holder is from before the gap, so the request starts below M.
        val watermark = GroupSyncCursor(now - 5 * hour, "old")
        val start = GroupHistoryPolicy.requestStart(watermark, newestLocal, now, 7 * day)
        val fixed = GroupSyncPolicy.ownedMessages(
            messages = holder, cursor = start, maxTotal = 10, nowMs = now,
            sentAt = { it.at }, messageId = { it.id }, deletedAt = { null },
        )
        assertEquals(listOf("m", "r"), fixed.map { it.id })
    }

    @Test
    fun s2TheFixedDayWindowDropsTheMiddleDayOfAMemberOfflineTwoDays_andTheReturningWindowKeepsIt() {
        // Offline for two days: the requester's newest row is 2 days old; the holder has text from 40 h ago and 30 h ago.
        val holder = listOf(Row("t-40h", now - 40 * hour), Row("t-30h", now - 30 * hour), Row("t-1h", now - hour))
        val cursor = GroupSyncCursor(now - 2 * day, "last")
        val old = GroupSyncPolicy.ownedMessages(
            messages = holder, cursor = cursor, maxTotal = 10, nowMs = now,
            sentAt = { it.at }, messageId = { it.id }, deletedAt = { null },
        )
        assertEquals(listOf("t-1h"), old.map { it.id }, "the old 24 h window loses the middle day")

        val window = GroupHistoryPolicy.returningWindowMs(GroupHistoryCeiling.D30, now - 2 * day, now)
        val windows = GroupHistoryPolicy.holderWindows(GroupHistoryCeiling.D30, window, includeFiles = true)
        val fixed = GroupSyncPolicy.ownedMessages(
            messages = holder, cursor = cursor, maxTotal = 10, nowMs = now,
            sentAt = { it.at }, messageId = { it.id }, deletedAt = { null },
            ttlMs = { windows.textMs },
        )
        assertEquals(listOf("t-40h", "t-30h", "t-1h"), fixed.map { it.id })
    }

    // ---- who may change the ceiling

    private val roster = listOf(
        GroupAdminPolicy.Member("owner", MemberCert.ROLE_OWNER, isActive = true),
        GroupAdminPolicy.Member("admin", MemberCert.ROLE_ADMIN, isActive = true),
        GroupAdminPolicy.Member("plain", MemberCert.ROLE_MEMBER, isActive = true),
        GroupAdminPolicy.Member("gone-admin", MemberCert.ROLE_ADMIN, isActive = false),
    )

    @Test
    fun theOwnerAndAnActiveAdminMayChangeTheCeiling() {
        assertTrue(GroupAdminPolicy.canChangeHistoryCeiling("owner", "owner", roster))
        assertTrue(GroupAdminPolicy.canChangeHistoryCeiling("admin", "owner", roster))
    }

    @Test
    fun aPlainMemberARemovedAdminAndAStrangerMayNot() {
        assertFalse(GroupAdminPolicy.canChangeHistoryCeiling("plain", "owner", roster))
        assertFalse(GroupAdminPolicy.canChangeHistoryCeiling("gone-admin", "owner", roster))
        assertFalse(GroupAdminPolicy.canChangeHistoryCeiling("stranger", "owner", roster))
        assertFalse(GroupAdminPolicy.isAdmin("owner", "owner", emptyList()))
    }

    @Test
    fun anOwnerWhoseRowIsInactiveIsNotAnAdmin() {
        val left = listOf(GroupAdminPolicy.Member("owner", MemberCert.ROLE_OWNER, isActive = false))
        assertFalse(GroupAdminPolicy.isAdmin("owner", "owner", left))
    }

    // ---- what a request carries (requestFor)

    private fun state(pending: Boolean = false, window: Long = 30 * day, files: Boolean = true, decided: Long = now, contact: Long = 0L) =
        GroupHistoryPolicy.RequestState(pending, window, files, decided, contact)

    @Test
    fun aMemberThatHasNotChosenYetAsksForNothing() {
        assertNull(GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D30, state(pending = true), now))
    }

    @Test
    fun theFirstRequestAsksForWhatWasChosenPlusTheTimeSince() {
        val choice = GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D30, state(window = 7 * day, decided = now - hour), now)!!
        assertEquals(7 * day + hour, choice.messageWindowMs)
        assertTrue(choice.includeFiles)
    }

    @Test
    fun theFirstRequestIsClampedToTheCeiling() {
        val choice = GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D7, state(window = 30 * day), now)!!
        assertEquals(7 * day, choice.messageWindowMs)
    }

    @Test
    fun aReturningMemberGetsTheFullSevenDaysEvenAfterAShortAbsence() {
        val choice = GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D30, state(decided = now - 20 * day, contact = now - hour), now)!!
        assertEquals(7 * day, choice.messageWindowMs)
    }

    @Test
    fun aMemberAwayForTenDaysGetsTenDaysUpToTheCeiling() {
        val s = state(decided = now - 40 * day, contact = now - 10 * day)
        assertEquals(10 * day, GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D30, s, now)!!.messageWindowMs)
        assertEquals(7 * day, GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D7, s, now)!!.messageWindowMs)
    }

    @Test
    fun aMemberWhoChoseNoHistoryNeverReachesBeforeItsJoin() {
        // Chose "none" 2 days ago, was last served 1 hour ago: the returning floor is 7 days, but it asked to see nothing older.
        val s = state(window = 0L, files = false, decided = now - 2 * day, contact = now - hour)
        val choice = GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D30, s, now)!!
        assertEquals(2 * day, choice.messageWindowMs)
        assertFalse(choice.includeFiles)
    }

    @Test
    fun aGroupFromBeforeTheFeatureIsAReturningMemberAndAsksForFiles() {
        val choice = GroupHistoryPolicy.requestFor(GroupHistoryCeiling.D30, null, now)!!
        assertEquals(7 * day, choice.messageWindowMs)
        assertTrue(choice.includeFiles)
    }

    @Test
    fun aNoneCeilingSendsNoRequestAtAll() {
        assertNull(GroupHistoryPolicy.requestFor(GroupHistoryCeiling.NONE, state(), now))
        assertNull(GroupHistoryPolicy.requestFor(GroupHistoryCeiling.NONE, null, now))
    }

    @Test
    fun theAllCeilingAsksForEverythingWithoutOverflow() {
        val s = state(window = Long.MAX_VALUE, decided = now - day)
        assertEquals(Long.MAX_VALUE, GroupHistoryPolicy.requestFor(GroupHistoryCeiling.ALL, s, now)!!.messageWindowMs)
    }
}
