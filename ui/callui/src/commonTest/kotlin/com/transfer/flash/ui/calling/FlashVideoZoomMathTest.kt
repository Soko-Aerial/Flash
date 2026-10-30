package com.transfer.flash.ui.calling

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-050h: the arithmetic behind pinch-to-zoom on a shared screen, tested without Compose. The surface only multiplies the
 * picture it already fitted by the scale and offsets it by the pan (see `FlashCallVideoSurface`), so everything that can be
 * got wrong lives in these functions.
 */
class FlashVideoZoomMathTest {

    @Test
    fun aPinchIsKeptBetweenTheWholePictureAndFiveTimesIt() {
        assertEquals(2f, FlashVideoZoomMath.scaleAfter(1f, 2f))
        assertEquals(3f, FlashVideoZoomMath.scaleAfter(1.5f, 2f))
        assertEquals(FlashVideoZoomMath.MAX_SCALE, FlashVideoZoomMath.scaleAfter(4f, 3f))
        assertEquals(FlashVideoZoomMath.MIN_SCALE, FlashVideoZoomMath.scaleAfter(1f, 0.5f))
        assertEquals(FlashVideoZoomMath.MAX_SCALE, FlashVideoZoomMath.scaleAfter(5f, 2f))
    }

    @Test
    fun aBrokenPinchLeavesTheScaleAloneAndACompletePinchInGoesToTheWholePicture() {
        // A gesture that reports nothing usable (NaN, infinity) must not throw the picture to an edge.
        assertEquals(2f, FlashVideoZoomMath.scaleAfter(2f, Float.NaN))
        assertEquals(2f, FlashVideoZoomMath.scaleAfter(2f, Float.POSITIVE_INFINITY))
        // A factor of zero is a pinch all the way in: the whole picture, never below it.
        assertEquals(FlashVideoZoomMath.MIN_SCALE, FlashVideoZoomMath.scaleAfter(2f, 0f))
    }

    @Test
    fun atTheWholePictureNothingCanMove() {
        // 1x is the whole picture sitting in the box: there is nothing outside the frame to look at.
        assertEquals(
            Offset.Zero,
            FlashVideoZoomMath.panAfter(Offset.Zero, Offset(200f, 200f), IntSize(1000, 600), 1f),
        )
    }

    @Test
    fun aDragIsAFractionOfHalfTheOverflow() {
        // 3x in a 1000 x 600 box: the picture is 3000 x 1800, so it overflows by 2000 x 1200 and can travel 1000 x 600 from
        // the middle to an edge. A drag of half that travel moves the fraction by half.
        val box = IntSize(1000, 600)
        assertEquals(Offset(0.5f, 0f), FlashVideoZoomMath.panAfter(Offset.Zero, Offset(500f, 0f), box, 3f))
        assertEquals(Offset(0f, 0.5f), FlashVideoZoomMath.panAfter(Offset.Zero, Offset(0f, 300f), box, 3f))
        assertEquals(Offset(-0.25f, 0f), FlashVideoZoomMath.panAfter(Offset.Zero, Offset(-250f, 0f), box, 3f))
    }

    @Test
    fun theDragStopsAtTheEdgeOfThePicture() {
        val box = IntSize(1000, 600)
        assertEquals(
            Offset(1f, 1f),
            FlashVideoZoomMath.panAfter(Offset(0.9f, 0.9f), Offset(900f, 900f), box, 2f),
        )
        assertEquals(
            Offset(-1f, -1f),
            FlashVideoZoomMath.panAfter(Offset(-0.9f, -0.9f), Offset(-900f, -900f), box, 2f),
        )
    }

    @Test
    fun draggingBackFromAnEdgeReturnsTowardsTheMiddle() {
        val box = IntSize(1000, 600)
        // From the right edge (+1) a drag of a quarter of the travel each way is 500 px at 3x.
        assertEquals(Offset(0.75f, 0f), FlashVideoZoomMath.panAfter(Offset(1f, 0f), Offset(-250f, 0f), box, 3f))
    }

    @Test
    fun theStateStartsOnTheWholePicture() {
        val zoom = FlashVideoZoomState()
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.pan)
        assertFalse(zoom.active)
    }

    @Test
    fun aPinchMagnifiesAndTheStateSaysSo() {
        val zoom = FlashVideoZoomState()
        zoom.onTransform(zoomChange = 2f, panChange = Offset.Zero, box = IntSize(1000, 600))
        assertEquals(2f, zoom.scale)
        assertTrue(zoom.active)
    }

    @Test
    fun aPinchBackBelowTheWholePictureResetsTheScaleAndThePan() {
        val zoom = FlashVideoZoomState()
        zoom.onTransform(zoomChange = 3f, panChange = Offset(200f, 0f), box = IntSize(1000, 600))
        assertTrue(zoom.pan.x > 0f)
        zoom.onTransform(zoomChange = 0.2f, panChange = Offset.Zero, box = IntSize(1000, 600))
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.pan)
        assertFalse(zoom.active)
    }

    @Test
    fun aPanAndAZoomInOneFrameBothCount() {
        val zoom = FlashVideoZoomState()
        zoom.onTransform(zoomChange = 2f, panChange = Offset(500f, 0f), box = IntSize(1000, 600))
        assertEquals(2f, zoom.scale)
        // At 2x the picture overflows by 1000 px, so it travels 500 px each way: the whole drag is one edge.
        assertEquals(Offset(1f, 0f), zoom.pan)
    }

    @Test
    fun resetForgetsBothTheScaleAndThePan() {
        val zoom = FlashVideoZoomState()
        zoom.onTransform(zoomChange = 4f, panChange = Offset(120f, 80f), box = IntSize(1000, 600))
        assertTrue(zoom.active)
        zoom.reset()
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.pan)
        assertFalse(zoom.active)
    }

    @Test
    fun theWordingIsTheVisibleWayBack() {
        assertEquals("Fit to screen", CallZoomText.FIT_TO_SCREEN)
        assertTrue(CallZoomText.FIT_TO_SCREEN_DESCRIPTION.isNotBlank())
    }
}
