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
    fun `only an idle gesture that has not asked the host yet can start capture`() {
        assertTrue(FlashVoiceRecordingMath.canStartCapture(FlashRecordingPhase.Idle, captureStarted = false))
        // One gesture asks once: a repeated callback cannot start a second capture.
        assertFalse(FlashVoiceRecordingMath.canStartCapture(FlashRecordingPhase.Idle, captureStarted = true))
        // A lock has no start action behind it; only the idle mic may start.
        assertFalse(FlashVoiceRecordingMath.canStartCapture(FlashRecordingPhase.Holding, captureStarted = true))
        assertFalse(FlashVoiceRecordingMath.canStartCapture(FlashRecordingPhase.Locked, captureStarted = true))
        assertFalse(FlashVoiceRecordingMath.canStartCapture(FlashRecordingPhase.Locked, captureStarted = false))
    }

    @Test
    fun `a refused start cannot arm cancel or lock a phantom recording`() {
        // The host said no (permission prompt, call/PTT lease, busy encoder). The same hold's drags
        // must not move the phase out of Idle.
        assertFalse(FlashVoiceRecordingMath.acceptsSlide(FlashRecordingPhase.Idle, captureStarted = false))
        // Locked is ended by its own trash/send buttons, not by a drag across the trailing key.
        assertFalse(FlashVoiceRecordingMath.acceptsSlide(FlashRecordingPhase.Locked, captureStarted = true))
        assertTrue(FlashVoiceRecordingMath.acceptsSlide(FlashRecordingPhase.Holding, captureStarted = true))
        assertTrue(FlashVoiceRecordingMath.acceptsSlide(FlashRecordingPhase.CancelArmed, captureStarted = true))
        assertFalse(FlashVoiceRecordingMath.acceptsSlide(FlashRecordingPhase.Holding, captureStarted = false))
        assertFalse(FlashVoiceRecordingMath.acceptsSlide(FlashRecordingPhase.CancelArmed, captureStarted = false))
    }

    @Test
    fun `the trailing slot sends only after a locked hold has ended`() {
        // While the finger is still down the slot must stay the mic: swapping it would dispose the
        // pointer stream that owns the hold (ERROR-010).
        assertFalse(FlashVoiceRecordingMath.showsVoiceSend(FlashRecordingPhase.Locked, lockedHandsFree = false))
        assertTrue(FlashVoiceRecordingMath.showsVoiceSend(FlashRecordingPhase.Locked, lockedHandsFree = true))
        assertFalse(FlashVoiceRecordingMath.showsVoiceSend(FlashRecordingPhase.Holding, lockedHandsFree = true))
        assertFalse(FlashVoiceRecordingMath.showsVoiceSend(FlashRecordingPhase.CancelArmed, lockedHandsFree = true))
        assertFalse(FlashVoiceRecordingMath.showsVoiceSend(FlashRecordingPhase.Idle, lockedHandsFree = true))
    }

    @Test
    fun `short release discards on touch with prompt and locks hands-free on desktop`() {
        assertEquals(
            FlashVoiceRecordingMath.ReleaseAction.DiscardShortTap,
            FlashVoiceRecordingMath.resolveReleaseAction(
                phase = FlashRecordingPhase.Holding,
                captureStarted = true,
                elapsedMs = 200L,
                isDesktop = false,
            ),
        )
        assertEquals(
            FlashVoiceRecordingMath.ReleaseAction.LockHandsFree,
            FlashVoiceRecordingMath.resolveReleaseAction(
                phase = FlashRecordingPhase.Holding,
                captureStarted = true,
                elapsedMs = 200L,
                isDesktop = true,
            ),
        )
        assertEquals(
            FlashVoiceRecordingMath.ReleaseAction.Send,
            FlashVoiceRecordingMath.resolveReleaseAction(
                phase = FlashRecordingPhase.Holding,
                captureStarted = true,
                elapsedMs = 1_200L,
                isDesktop = false,
            ),
        )
        assertEquals(
            FlashVoiceRecordingMath.ReleaseAction.FinishLockGesture,
            FlashVoiceRecordingMath.resolveReleaseAction(
                phase = FlashRecordingPhase.Locked,
                captureStarted = true,
                elapsedMs = 200L,
                isDesktop = false,
            ),
        )
        assertEquals(
            FlashVoiceRecordingMath.ReleaseAction.Cancel,
            FlashVoiceRecordingMath.resolveReleaseAction(
                phase = FlashRecordingPhase.CancelArmed,
                captureStarted = true,
                elapsedMs = 800L,
                isDesktop = true,
            ),
        )
        assertEquals(
            FlashVoiceRecordingMath.ReleaseAction.None,
            FlashVoiceRecordingMath.resolveReleaseAction(
                phase = FlashRecordingPhase.Holding,
                captureStarted = false,
                elapsedMs = 800L,
                isDesktop = true,
            ),
        )
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
