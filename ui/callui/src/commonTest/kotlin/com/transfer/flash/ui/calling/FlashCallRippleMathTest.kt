package com.transfer.flash.ui.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashCallRippleMathTest {

    @Test
    fun testRestingGlowScales() {
        val outerResting = FlashCallRippleMath.computeOuterGlowScale(isSpeaking = false, pulseValue = 1.0f)
        assertEquals(1.06f, outerResting, 0.001f)

        val middleResting = FlashCallRippleMath.computeMiddleGlowScale(isSpeaking = false, pulseValue = 1.0f)
        assertEquals(1.0f, middleResting, 0.001f)
    }

    @Test
    fun testSpeakingGlowReachesCeiling() {
        val outerMaxSpeaking = FlashCallRippleMath.computeOuterGlowScale(isSpeaking = true, pulseValue = 1.08f)
        assertEquals(1.22f, outerMaxSpeaking, 0.001f)

        val middleMaxSpeaking = FlashCallRippleMath.computeMiddleGlowScale(isSpeaking = true, pulseValue = 1.08f)
        assertEquals(1.14f, middleMaxSpeaking, 0.001f)
    }

    @Test
    fun testSpeakerBorderAlpha() {
        assertEquals(0f, FlashCallRippleMath.speakerBorderAlpha(isSpeaking = false, animationFraction = 1f))
        val minSpeakingAlpha = FlashCallRippleMath.speakerBorderAlpha(isSpeaking = true, animationFraction = 0f)
        assertEquals(0.45f, minSpeakingAlpha, 0.001f)
        val maxSpeakingAlpha = FlashCallRippleMath.speakerBorderAlpha(isSpeaking = true, animationFraction = 1f)
        assertEquals(1.0f, maxSpeakingAlpha, 0.001f)
    }
}
