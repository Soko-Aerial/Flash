package com.transfer.flash.core.common.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the precedence in [FlashMotionPolicy] — the part users notice if it inverts. */
class FlashMotionPolicyTest {

    @Test
    fun high_tier_follows_the_platform_when_the_user_has_no_preference() {
        assertFalse(resolve(FlashPerformanceMode.HIGH, null, systemReduceMotion = false))
        assertTrue(resolve(FlashPerformanceMode.HIGH, null, systemReduceMotion = true))
    }

    @Test
    fun user_override_beats_the_platform_at_high_tier() {
        assertTrue(resolve(FlashPerformanceMode.HIGH, true, systemReduceMotion = false))
        assertFalse(resolve(FlashPerformanceMode.HIGH, false, systemReduceMotion = true))
    }

    @Test
    fun constrained_tiers_never_animate_whatever_anyone_asks() {
        listOf(FlashPerformanceMode.LOW, FlashPerformanceMode.MEDIUM).forEach { mode ->
            assertTrue("$mode + allow-motion", resolve(mode, false, systemReduceMotion = false))
            assertTrue("$mode + platform off", resolve(mode, null, systemReduceMotion = false))
        }
    }

    private fun resolve(
        mode: FlashPerformanceMode,
        overrideForcesReduce: Boolean?,
        systemReduceMotion: Boolean,
    ): Boolean = FlashMotionPolicy.resolveReduceMotion(mode, overrideForcesReduce, systemReduceMotion)
}
