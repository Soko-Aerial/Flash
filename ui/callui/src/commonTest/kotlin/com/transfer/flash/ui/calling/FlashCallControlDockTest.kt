package com.transfer.flash.ui.calling

import androidx.compose.ui.unit.dp
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

    /**
     * OPT-O03-18: the dock no longer measures itself with a `BoxWithConstraints` before it can compose its buttons. The
     * per-slot arithmetic the old code did is now a constant, so this pins that the buttons a 360 dp phone draws are the
     * same size they were before (52 dp cap, 16/count + 4 dp given back per slot, 40 dp floor).
     */
    @Test
    fun `dock buttons are the same size the measuring dock produced`() {
        // 360 dp phone, 6 controls, 16 dp of horizontal padding on each side: 328 dp of row, 54.67 dp per slot.
        val slot360 = 328.dp / 6
        assertEquals(48.dp.value, dockButtonSize(slot360, 6).value, 0.01f)

        // 3 controls on the same phone: a slot is wider than the cap, so the button is the cap.
        assertEquals(52.dp.value, dockButtonSize(328.dp / 3, 3).value, 0.01f)

        // Below ~320 dp the arithmetic asks for less than the 40 dp floor; the floor is what the rule says, and the layout
        // cannot exceed its slot, so on such a window the circle renders at the slot width (no overlap).
        assertEquals(40.dp.value, dockButtonSize(208.dp / 6, 6).value, 0.01f)

        assertEquals(0.dp, dockButtonInset(0), "no controls, no inset")
        assertTrue(dockButtonInset(6) < dockButtonInset(3), "more controls give back less per slot")
    }
}
