package com.transfer.flash.ui.calling

import com.transfer.flash.core.ptt.PttPressOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PttElapsedFormatTest {

    @Test
    fun `zero formats as 0 colon 00`() {
        assertEquals("0:00", formatPttElapsed(0L))
    }

    @Test
    fun `seconds pad to two digits`() {
        assertEquals("0:05", formatPttElapsed(5_000L))
        assertEquals("0:59", formatPttElapsed(59_999L))
    }

    @Test
    fun `minutes roll over`() {
        assertEquals("1:05", formatPttElapsed(65_000L))
        assertEquals("59:59", formatPttElapsed(3_599_999L))
    }

    @Test
    fun `negative clamps to zero`() {
        assertEquals("0:00", formatPttElapsed(-1_000L))
    }

    @Test
    fun `an accepted press has no message and every refusal has one`() {
        assertNull(pttPressOutcomeMessage(PttPressOutcome.ACCEPTED))
        assertNotNull(pttPressOutcomeMessage(PttPressOutcome.NO_PEERS))
        assertNotNull(pttPressOutcomeMessage(PttPressOutcome.NO_MIC))
        assertNotNull(pttPressOutcomeMessage(PttPressOutcome.CALL_ACTIVE))
        assertNotNull(pttPressOutcomeMessage(PttPressOutcome.VOICE_NOTE_ACTIVE))
    }
}
