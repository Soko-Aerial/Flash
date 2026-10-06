package com.transfer.flash.ui.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The dock's wording and motion math (UI-050e). Rendering is covered by `FlashCallControlDockRenderTest`. */
class FlashCallControlDockTest {

    @Test
    fun `a toggle's label names the state and its description names the action`() {
        assertEquals("Mic on", CallDockText.micLabel(muted = false))
        assertEquals("Muted", CallDockText.micLabel(muted = true))
        assertEquals("Mute microphone", CallDockText.micDescription(muted = false))
        assertEquals("Unmute microphone", CallDockText.micDescription(muted = true))

        assertEquals("Video on", CallDockText.videoLabel(cameraOff = false))
        assertEquals("Video off", CallDockText.videoLabel(cameraOff = true))
        assertEquals("Turn camera off", CallDockText.videoDescription(cameraOff = false))
        assertEquals("Turn camera on", CallDockText.videoDescription(cameraOff = true))

        assertEquals("Speaker", CallDockText.routeLabel(speakerOn = true))
        assertEquals("Earpiece", CallDockText.routeLabel(speakerOn = false))
        assertEquals("Switch to earpiece", CallDockText.routeDescription(speakerOn = true))
        assertEquals("Switch to speaker", CallDockText.routeDescription(speakerOn = false))
    }

    @Test
    fun `every state of every toggle reads differently so state never depends on colour alone`() {
        assertNotEquals(CallDockText.micLabel(true), CallDockText.micLabel(false))
        assertNotEquals(CallDockText.videoLabel(true), CallDockText.videoLabel(false))
        assertNotEquals(CallDockText.routeLabel(true), CallDockText.routeLabel(false))
        assertNotEquals(CallDockText.micDescription(true), CallDockText.micDescription(false))
        assertNotEquals(CallDockText.videoDescription(true), CallDockText.videoDescription(false))
        assertNotEquals(CallDockText.routeDescription(true), CallDockText.routeDescription(false))
    }

    @Test
    fun `labels stay short enough for five slots on a 320 dp phone`() {
        val all = listOf(
            CallDockText.micLabel(true), CallDockText.micLabel(false),
            CallDockText.videoLabel(true), CallDockText.videoLabel(false),
            CallDockText.FLIP_LABEL,
            CallDockText.routeLabel(true), CallDockText.routeLabel(false),
            CallDockText.END_LABEL,
        )
        all.forEach { assertTrue(it.length <= 9, "'$it' is too long for a ~52 dp slot") }
    }

    @Test
    fun `pop starts small, ends at full size and may overshoot`() {
        assertEquals(0.5f, popScale(0f))
        assertEquals(1f, popScale(1f))
        assertTrue(popScale(1.1f) > 1f)
    }

    @Test
    fun `eyelid starts nearly shut and ends fully open`() {
        assertTrue(eyelidScaleY(0f) in 0.01f..0.2f)
        assertEquals(1f, eyelidScaleY(1f))
    }

    @Test
    fun `an arriving glyph is never invisible and never brighter than opaque`() {
        assertTrue(arrivalAlpha(0f) > 0f)
        assertEquals(1f, arrivalAlpha(1f))
        assertEquals(1f, arrivalAlpha(1.4f), "a spring overshoot must not push alpha above 1")
    }

    @Test
    fun `outward ripple grows and inward ripple shrinks, both fade out`() {
        assertEquals(1f, rippleRadiusScale(0f, outward = true))
        assertEquals(1f + RIPPLE_GROWTH, rippleRadiusScale(1f, outward = true), 1e-6f)
        assertEquals(1f + RIPPLE_GROWTH, rippleRadiusScale(0f, outward = false), 1e-6f)
        assertEquals(1f, rippleRadiusScale(1f, outward = false), 1e-6f)
        assertEquals(RIPPLE_ALPHA, rippleAlpha(0f))
        assertEquals(0f, rippleAlpha(1f))
        assertEquals(0f, rippleAlpha(1.3f), "overshoot must not make the ring reappear")
    }

    @Test
    fun `ring wiggle is still at both ends of the cycle and bounded inside it`() {
        assertEquals(0f, ringWiggleDegrees(0f))
        assertEquals(0f, ringWiggleDegrees(RING_ACTIVE_FRACTION))
        assertEquals(0f, ringWiggleDegrees(0.9f), "the second half of the cycle is rest")
        var sawPositive = false
        var sawNegative = false
        var phase = 0.01f
        while (phase < RING_ACTIVE_FRACTION) {
            val d = ringWiggleDegrees(phase)
            assertTrue(kotlin.math.abs(d) <= RING_WIGGLE_MAX_DEGREES, "angle $d out of range at $phase")
            if (d > 0.5f) sawPositive = true
            if (d < -0.5f) sawNegative = true
            phase += 0.01f
        }
        assertTrue(sawPositive && sawNegative, "a ringing handset swings both ways")
    }
}
