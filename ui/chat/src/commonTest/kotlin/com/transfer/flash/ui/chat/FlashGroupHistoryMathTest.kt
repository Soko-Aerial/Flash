package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI-057: join card copy, options and the clamp to the signed ceiling (`docs/ui/group-history-join-card.md`). */
class FlashGroupHistoryMathTest {
    private val day = 24L * 60L * 60L * 1000L
    private val hour = 60L * 60L * 1000L

    @Test
    fun windowsAreNamedInPlainTime() {
        assertEquals("None", FlashGroupHistoryMath.windowLabel(0L))
        assertEquals("24 hours", FlashGroupHistoryMath.windowLabel(24 * hour))
        assertEquals("7 days", FlashGroupHistoryMath.windowLabel(7 * day))
        assertEquals("30 days", FlashGroupHistoryMath.windowLabel(30 * day))
        assertEquals("Everything available", FlashGroupHistoryMath.windowLabel(Long.MAX_VALUE))
        assertEquals("2 days", FlashGroupHistoryMath.windowLabel(2 * day))
    }

    @Test
    fun theCardNeverOffersMoreThanTheCeiling() {
        assertEquals(listOf(0L), FlashGroupHistoryMath.windowOptions(GroupHistoryCeiling.NONE), "a no-history group offers only none")
        assertEquals(listOf(0L, 24 * hour), FlashGroupHistoryMath.windowOptions(GroupHistoryCeiling.H24))
        assertEquals(listOf(0L, 24 * hour, 7 * day), FlashGroupHistoryMath.windowOptions(GroupHistoryCeiling.D7))
        assertEquals(listOf(0L, 24 * hour, 7 * day, 30 * day), FlashGroupHistoryMath.windowOptions(GroupHistoryCeiling.D30))
        assertEquals(
            listOf(0L, 24 * hour, 7 * day, 30 * day, Long.MAX_VALUE),
            FlashGroupHistoryMath.windowOptions(GroupHistoryCeiling.ALL),
        )
        for (ceiling in GroupHistoryCeiling.entries) {
            assertTrue(
                FlashGroupHistoryMath.windowOptions(ceiling).all { it <= ceiling.windowMs },
                "every option of $ceiling is inside it",
            )
        }
    }

    @Test
    fun loadOlderOffersEveryOptionExceptNone() {
        assertEquals(emptyList(), FlashGroupHistoryMath.loadOlderOptions(GroupHistoryCeiling.NONE))
        assertEquals(listOf(24 * hour, 7 * day), FlashGroupHistoryMath.loadOlderOptions(GroupHistoryCeiling.D7))
    }

    @Test
    fun theDefaultSelectionIsThirtyDaysCutToTheCeiling() {
        assertEquals(30 * day, FlashGroupHistoryMath.defaultWindow(GroupHistoryCeiling.D30))
        assertEquals(30 * day, FlashGroupHistoryMath.defaultWindow(GroupHistoryCeiling.ALL))
        assertEquals(7 * day, FlashGroupHistoryMath.defaultWindow(GroupHistoryCeiling.D7))
        assertEquals(24 * hour, FlashGroupHistoryMath.defaultWindow(GroupHistoryCeiling.H24))
        assertEquals(0L, FlashGroupHistoryMath.defaultWindow(GroupHistoryCeiling.NONE))
    }

    @Test
    fun aSelectionSnapsDownToAnOptionInsideTheCeiling() {
        assertEquals(7 * day, FlashGroupHistoryMath.snapToOptions(30 * day, GroupHistoryCeiling.D7))
        assertEquals(24 * hour, FlashGroupHistoryMath.snapToOptions(3 * day, GroupHistoryCeiling.D30))
        assertEquals(0L, FlashGroupHistoryMath.snapToOptions(30 * day, GroupHistoryCeiling.NONE))
        assertEquals(0L, FlashGroupHistoryMath.snapToOptions(hour, GroupHistoryCeiling.D30), "below the smallest option is none")
    }

    @Test
    fun noHistoryMeansNoFiles() {
        assertFalse(FlashGroupHistoryMath.filesAvailable(0L, GroupHistoryCeiling.D30))
        assertFalse(FlashGroupHistoryMath.filesAvailable(7 * day, GroupHistoryCeiling.NONE))
        assertTrue(FlashGroupHistoryMath.filesAvailable(7 * day, GroupHistoryCeiling.D30))
        assertFalse(FlashGroupHistoryMath.effectiveFiles(true, 0L, GroupHistoryCeiling.D30), "the switch cannot bring files with no history")
        assertTrue(FlashGroupHistoryMath.effectiveFiles(true, 7 * day, GroupHistoryCeiling.D30))
        assertFalse(FlashGroupHistoryMath.effectiveFiles(false, 7 * day, GroupHistoryCeiling.D30))
        assertEquals("No history means no files.", FlashGroupHistoryMath.filesDescription(0L, true, GroupHistoryCeiling.D30))
        assertEquals(FlashGroupHistoryMath.FILES_ON, FlashGroupHistoryMath.filesDescription(7 * day, true, GroupHistoryCeiling.D30))
        assertEquals(FlashGroupHistoryMath.FILES_OFF, FlashGroupHistoryMath.filesDescription(7 * day, false, GroupHistoryCeiling.D30))
    }

    @Test
    fun aNoHistoryGroupSaysSoInTheOwnersWords() {
        assertEquals("This group does not share earlier history", FlashGroupHistoryMath.NO_HISTORY_SENTENCE)
        assertEquals(FlashGroupHistoryMath.NO_HISTORY_SENTENCE, FlashGroupHistoryMath.ceilingReadOnly(GroupHistoryCeiling.NONE))
    }

    @Test
    fun theFooterAppearsOnlyBelowTheUsualThirtyDays() {
        assertNull(FlashGroupHistoryMath.ceilingFooter(GroupHistoryCeiling.D30))
        assertNull(FlashGroupHistoryMath.ceilingFooter(GroupHistoryCeiling.ALL))
        assertNull(FlashGroupHistoryMath.ceilingFooter(GroupHistoryCeiling.NONE))
        assertEquals("This group shares up to 7 days.", FlashGroupHistoryMath.ceilingFooter(GroupHistoryCeiling.D7))
        assertEquals("This group shares up to 24 hours.", FlashGroupHistoryMath.ceilingFooter(GroupHistoryCeiling.H24))
    }

    @Test
    fun theAdminChoicesCoverEveryCeilingLowestFirst() {
        assertEquals(GroupHistoryCeiling.entries.toSet(), FlashGroupHistoryMath.ceilingChoices.toSet())
        val windows = FlashGroupHistoryMath.ceilingChoices.map { it.windowMs }
        assertEquals(windows.sorted(), windows, "ordered from least to most")
        assertEquals("Nothing", FlashGroupHistoryMath.ceilingLabel(GroupHistoryCeiling.NONE))
        assertEquals("Everything available", FlashGroupHistoryMath.ceilingLabel(GroupHistoryCeiling.ALL))
        assertEquals("30 days, selected", FlashGroupHistoryMath.optionDescription(30 * day, true))
        assertEquals("7 days", FlashGroupHistoryMath.optionDescription(7 * day, false))
        assertEquals("Nothing, selected", FlashGroupHistoryMath.ceilingOptionDescription(GroupHistoryCeiling.NONE, true))
    }

    @Test
    fun theBannerShowsOfAboutOnlyWhenTheEstimateIsAboveWhatArrived() {
        assertEquals("12 of about 40", FlashGroupSyncMath.countLabel(12, 40))
        assertEquals("0 of about 40", FlashGroupSyncMath.countLabel(0, 40))
        assertEquals("12", FlashGroupSyncMath.countLabel(12, 12), "an estimate that is not above the count is not shown")
        assertEquals("12", FlashGroupSyncMath.countLabel(12, 3))
        assertNull(FlashGroupSyncMath.countLabel(0, 0))
        assertEquals("Catching up on earlier messages · 12 of about 40", FlashGroupSyncMath.label(12, 40))
        assertEquals("Catching up on earlier messages, 12 of about 40 received", FlashGroupSyncMath.description(12, 40))
    }

    @Test
    fun theCardNamesTheWayBackAfterNotNow() {
        // G9: "Not now" is stored as a final choice; the card must say where the choice can be revisited, and that place exists.
        assertTrue(FlashGroupHistoryMath.SKIP_NOTE.contains(FlashGroupHistoryMath.SKIP_ACTION))
        assertTrue(FlashGroupHistoryMath.SKIP_NOTE.contains(FlashGroupHistoryMath.LOAD_OLDER_TITLE))
    }
}
