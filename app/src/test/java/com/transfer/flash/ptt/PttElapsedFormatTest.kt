package com.transfer.flash.ptt

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
