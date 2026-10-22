package com.transfer.flash.core.ptt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RVW22 F7: the JVM clock must stay above zero even when System.nanoTime() is negative. */
class PttClockTest {
    @Test
    fun aNegativeNanoTimeOriginStillGivesAPositiveClock() {
        val origin = -9_000_000_000_000L
        assertTrue(anchoredElapsedMs(origin, origin) > 0L)
        assertEquals(anchoredElapsedMs(origin, origin) + 2_500L, anchoredElapsedMs(origin + 2_500_000_000L, origin))
    }

    @Test
    fun theLiveClockIsPositiveAndNeverGoesBackwards() {
        val a = pttElapsedRealtimeMs()
        val b = pttElapsedRealtimeMs()
        assertTrue(a > 0L)
        assertTrue(b >= a)
    }
}
