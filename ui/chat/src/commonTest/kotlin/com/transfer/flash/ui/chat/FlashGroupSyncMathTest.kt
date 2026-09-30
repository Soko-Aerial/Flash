package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UI-052 catch-up banner copy and sweep geometry (`docs/ui/group-ui.md`). */
class FlashGroupSyncMathTest {

    @Test
    fun theLabelCarriesTheCountOnlyOnceSomethingWasCounted() {
        assertEquals("Catching up on earlier messages", FlashGroupSyncMath.label(0))
        assertEquals("Catching up on earlier messages", FlashGroupSyncMath.label(-3))
        assertEquals("Catching up on earlier messages · 1", FlashGroupSyncMath.label(1))
        assertEquals("Catching up on earlier messages · 12", FlashGroupSyncMath.label(12))
    }

    @Test
    fun theCountLabelIsNullForZeroOrLess() {
        assertNull(FlashGroupSyncMath.countLabel(0))
        assertNull(FlashGroupSyncMath.countLabel(-1))
        assertEquals("7", FlashGroupSyncMath.countLabel(7))
    }

    @Test
    fun theSpokenFormSaysHowManyWereReceived() {
        assertEquals("Catching up on earlier messages", FlashGroupSyncMath.description(0))
        assertEquals("Catching up on earlier messages, 12 received", FlashGroupSyncMath.description(12))
    }

    @Test
    fun theSegmentEntersFromTheLeftAndLeavesToTheRight() {
        val width = 200f
        val segment = width * FlashGroupSyncMath.SEGMENT_FRACTION
        val tolerance = 0.001f
        assertEquals(-segment, FlashGroupSyncMath.segmentStart(width, 0f), tolerance, "fully off the left edge at the start of a loop")
        assertEquals(width, FlashGroupSyncMath.segmentStart(width, 1f), tolerance, "fully off the right edge at the end")
        assertEquals(-segment, FlashGroupSyncMath.segmentStart(width, -5f), tolerance, "a progress below 0 is held at the start")
        assertEquals(width, FlashGroupSyncMath.segmentStart(width, 5f), tolerance, "a progress above 1 is held at the end")
    }
}
