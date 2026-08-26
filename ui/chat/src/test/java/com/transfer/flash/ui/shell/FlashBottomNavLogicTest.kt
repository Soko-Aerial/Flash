package com.transfer.flash.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for UI-046 bottom-nav pure math helpers. */
class FlashBottomNavLogicTest {

    @Test
    fun `indicator centers under each of four equal tabs`() {
        val barWidth = 400f
        val indicatorWidth = 56f
        assertEquals(22f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 0, indicatorWidth), 0.001f)
        assertEquals(122f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 1, indicatorWidth), 0.001f)
        assertEquals(222f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 2, indicatorWidth), 0.001f)
        assertEquals(322f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 3, indicatorWidth), 0.001f)
    }

    @Test
    fun `indicator wider than a tab clamps to bar bounds`() {
        // 2 tabs on 100px bar = 50px tabs; an 80px pill cannot center without bleeding
        // past an edge, so both positions pin to the nearest legal bar bound.
        assertEquals(0f, FlashBottomNavMath.indicatorStartPx(100f, 2, 0, 80f), 0.001f)
        assertEquals(20f, FlashBottomNavMath.indicatorStartPx(100f, 2, 1, 80f), 0.001f)
    }

    @Test
    fun `indicator math rejects invalid geometry`() {
        assertThrows(IllegalArgumentException::class.java) {
            FlashBottomNavMath.indicatorStartPx(400f, 0, 0, 56f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlashBottomNavMath.indicatorStartPx(400f, 4, 4, 56f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlashBottomNavMath.indicatorStartPx(400f, 4, -1, 56f)
        }
    }

    @Test
    fun `badge counts collapse past nine`() {
        assertEquals("1", FlashBottomNavMath.formatBadgeCount(1))
        assertEquals("9", FlashBottomNavMath.formatBadgeCount(9))
        assertEquals("9+", FlashBottomNavMath.formatBadgeCount(10))
        assertEquals("9+", FlashBottomNavMath.formatBadgeCount(999))
    }

    @Test
    fun `fractional position interpolates between tab centers`() {
        // Halfway between tab 0 (22f) and tab 1 (122f) while the chip is in flight.
        assertEquals(72f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 0.5f, 56f), 0.001f)
        assertEquals(22f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 0f, 56f), 0.001f)
        assertEquals(322f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 3f, 56f), 0.001f)
    }

    @Test
    fun `fractional position clamps overshoot from a bouncy spring`() {
        // A snappy spring overshoots past the target index; the chip must stay on the bar.
        assertEquals(22f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, -0.4f, 56f), 0.001f)
        assertEquals(322f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 3.4f, 56f), 0.001f)
    }

    @Test
    fun `stretch rests at one and peaks at the boost ceiling`() {
        assertEquals(1f, FlashBottomNavMath.indicatorStretch(0f), 0.001f)
        assertEquals(1f + FlashBottomNavMath.MAX_STRETCH_BOOST, FlashBottomNavMath.indicatorStretch(1f), 0.001f)
        // Out-of-range travel cannot inflate the chip further.
        assertEquals(1f + FlashBottomNavMath.MAX_STRETCH_BOOST, FlashBottomNavMath.indicatorStretch(4f), 0.001f)
        assertEquals(1f, FlashBottomNavMath.indicatorStretch(-2f), 0.001f)
    }

    @Test
    fun `squash thins the chip only while it stretches`() {
        assertEquals(1f, FlashBottomNavMath.indicatorSquash(1f), 0.001f)
        val squash = FlashBottomNavMath.indicatorSquash(1.3f)
        assertTrue("expected partial area conservation, got $squash", squash in 0.85f..0.9f)
    }

    @Test
    fun `travel amplitude grows with jump distance and ignores direction`() {
        assertEquals(0.45f, FlashBottomNavMath.travelAmplitude(1), 0.001f)
        assertEquals(0.667f, FlashBottomNavMath.travelAmplitude(2), 0.01f)
        assertEquals(1f, FlashBottomNavMath.travelAmplitude(3), 0.001f)
        assertEquals(
            FlashBottomNavMath.travelAmplitude(2),
            FlashBottomNavMath.travelAmplitude(-2),
            0.001f,
        )
    }
}
