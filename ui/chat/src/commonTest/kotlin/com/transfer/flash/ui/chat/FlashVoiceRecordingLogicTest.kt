package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashVoiceRecordingLogicTest {

    @Test
    fun `leftward slide past threshold claims cancel`() {
        assertEquals(
            FlashHoldSlideTarget.Cancel,
            FlashVoiceRecordingMath.resolveHoldSlide(
                dx = -100f,
                dy = 0f,
                cancelThresholdPx = FlashVoiceRecordingMath.CANCEL_SLIDE_DP,
                lockThresholdPx = FlashVoiceRecordingMath.LOCK_SLIDE_DP,
            ),
        )
    }

    @Test
    fun `upward slide past threshold claims lock`() {
        assertEquals(
            FlashHoldSlideTarget.Lock,
            FlashVoiceRecordingMath.resolveHoldSlide(
                dx = 0f,
                dy = -80f,
                cancelThresholdPx = FlashVoiceRecordingMath.CANCEL_SLIDE_DP,
                lockThresholdPx = FlashVoiceRecordingMath.LOCK_SLIDE_DP,
            ),
        )
    }

    @Test
    fun `dominant axis wins when both thresholds are exceeded`() {
        // Leftward dominant → cancel even though upward also passed its threshold
        assertEquals(
            FlashHoldSlideTarget.Cancel,
            FlashVoiceRecordingMath.resolveHoldSlide(-120f, -80f, 96f, 72f),
        )
        // Upward dominant → lock
        assertEquals(
            FlashHoldSlideTarget.Lock,
            FlashVoiceRecordingMath.resolveHoldSlide(-60f, -150f, 96f, 72f),
        )
    }

    @Test
    fun `slides below thresholds stay in hold`() {
        assertEquals(
            FlashHoldSlideTarget.Stay,
            FlashVoiceRecordingMath.resolveHoldSlide(-50f, -30f, 96f, 72f),
        )
        assertEquals(
            FlashHoldSlideTarget.Stay,
            FlashVoiceRecordingMath.resolveHoldSlide(0f, 0f, 96f, 72f),
        )
        // Rightward/downward drags never claim a target
        assertEquals(
            FlashHoldSlideTarget.Stay,
            FlashVoiceRecordingMath.resolveHoldSlide(200f, 200f, 96f, 72f),
        )
    }

    @Test
    fun `amplitude smoothing is an exponential moving average clamped to bounds`() {
        // 50 + 0.35 * (78 - 50) = 59.8 -> truncated to 59
        assertEquals(59, FlashVoiceRecordingMath.smoothAmplitude(previous = 50, next = 78))
        // Clamped to demo bounds
        assertEquals(FlashVoiceRecordingMath.DEMO_AMPLITUDE_MAX, FlashVoiceRecordingMath.smoothAmplitude(previous = 95, next = 200))
        assertEquals(FlashVoiceRecordingMath.DEMO_AMPLITUDE_MIN, FlashVoiceRecordingMath.smoothAmplitude(previous = 20, next = -50))
    }

    @Test
    fun `demo amplitude random walk stays within bounds`() {
        var value = 60
        repeat(500) {
            value = FlashVoiceRecordingMath.nextDemoAmplitude(value)
            assertTrue(value in FlashVoiceRecordingMath.DEMO_AMPLITUDE_MIN..FlashVoiceRecordingMath.DEMO_AMPLITUDE_MAX)
        }
    }

    @Test
    fun `short presses discard silently instead of sending`() {
        assertTrue(FlashVoiceRecordingMath.shouldDiscardShortRecording(elapsedMs = 0L))
        assertTrue(FlashVoiceRecordingMath.shouldDiscardShortRecording(elapsedMs = 499L))
        assertFalse(FlashVoiceRecordingMath.shouldDiscardShortRecording(elapsedMs = 500L))
        assertFalse(FlashVoiceRecordingMath.shouldDiscardShortRecording(elapsedMs = 5_000L))
    }

    @Test
    fun `strip samples keep only the most recent window`() {
        val samples = (1..40).toList()
        val strip = FlashVoiceRecordingMath.stripSamples(samples)

        assertEquals(FlashVoiceRecordingMath.STRIP_BAR_COUNT, strip.size)
        assertEquals(13, strip.first())
        assertEquals(40, strip.last())

        // Shorter recordings render everything available
        assertEquals(listOf(1, 2, 3), FlashVoiceRecordingMath.stripSamples(listOf(1, 2, 3)))
    }
}
