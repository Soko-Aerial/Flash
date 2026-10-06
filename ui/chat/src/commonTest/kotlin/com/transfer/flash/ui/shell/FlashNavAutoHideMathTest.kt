package com.transfer.flash.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashNavAutoHideMathTest {

    @Test
    fun `a fast downward flick hides the bar`() {
        // 20 px in one 16 ms frame is 1250 px/s.
        assertFalse(FlashNavAutoHideMath.visibleAfter(current = true, deltaPx = 20f, elapsedMs = 16, atTop = false))
    }

    @Test
    fun `a slow downward drift leaves the bar as it is`() {
        // 4 px in 16 ms is 250 px/s, below the hide speed. The old rule hid at any 20 px of travel.
        assertTrue(FlashNavAutoHideMath.visibleAfter(current = true, deltaPx = 4f, elapsedMs = 16, atTop = false))
        assertFalse(FlashNavAutoHideMath.visibleAfter(current = false, deltaPx = 4f, elapsedMs = 16, atTop = false))
    }

    @Test
    fun `a quick upward scroll brings the bar back and a slow one does not`() {
        assertTrue(FlashNavAutoHideMath.visibleAfter(current = false, deltaPx = -8f, elapsedMs = 16, atTop = false))
        assertFalse(FlashNavAutoHideMath.visibleAfter(current = false, deltaPx = -2f, elapsedMs = 16, atTop = false))
    }

    @Test
    fun `the top of the list always shows the bar`() {
        assertTrue(FlashNavAutoHideMath.visibleAfter(current = false, deltaPx = 50f, elapsedMs = 16, atTop = true))
    }

    @Test
    fun `no elapsed time means no velocity and no change`() {
        assertEquals(0f, FlashNavAutoHideMath.velocityPxPerSec(100f, 0))
        assertTrue(FlashNavAutoHideMath.visibleAfter(current = true, deltaPx = 100f, elapsedMs = 0, atTop = false))
    }

    @Test
    fun `a change of first visible row counts as a distance`() {
        assertEquals(FlashNavAutoHideMath.ROW_ESTIMATE_PX + 10f, FlashNavAutoHideMath.scrolledPx(3, 0, 4, 10))
        assertEquals(-FlashNavAutoHideMath.ROW_ESTIMATE_PX + 30f, FlashNavAutoHideMath.scrolledPx(4, 0, 3, 30))
    }
}
