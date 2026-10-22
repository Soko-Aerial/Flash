package com.transfer.flash.ui.calling

import kotlin.test.Test
import kotlin.test.assertEquals

/** ERROR-748: a desktop screen on a portrait phone is shown whole, not cropped to the phone's aspect ratio. */
class FlashCallVideoSizeTest {

    @Test
    fun aWideScreenShareOnAPortraitPhoneIsLetterboxedToTheFullWidth() {
        // 1920x1080 desktop in a 1080x2000 portrait box: the whole width, 608 px tall, bars above and below.
        assertEquals(1080 to 607, callVideoSize(CallVideoFit.Fit, 1920, 1080, 1080, 2000))
    }

    @Test
    fun aWideScreenShareInALandscapeBoxOfAnotherShapeKeepsItsAspectRatio() {
        // 16:9 in a 2:1 box: limited by the height, bars left and right.
        assertEquals(1777 to 1000, callVideoSize(CallVideoFit.Fit, 1920, 1080, 2000, 1000))
    }

    @Test
    fun fitNeverFillsEvenWhenTheOrientationsMatch() {
        assertEquals(750 to 1000, callVideoSize(CallVideoFit.Fit, 1080, 1440, 1200, 1000))
    }

    @Test
    fun balancedFillsTheBoxWhenThePictureAndTheBoxShareAnOrientation() {
        assertEquals(1200 to 1000, callVideoSize(CallVideoFit.Balanced, 1920, 1080, 1200, 1000))
        assertEquals(1000 to 2000, callVideoSize(CallVideoFit.Balanced, 720, 1280, 1000, 2000))
    }

    @Test
    fun balancedShowsTheWholePictureWhenTheOrientationsDiffer() {
        assertEquals(1080 to 607, callVideoSize(CallVideoFit.Balanced, 1920, 1080, 1080, 2000))
        assertEquals(562 to 1000, callVideoSize(CallVideoFit.Balanced, 1080, 1920, 2000, 1000))
    }

    @Test
    fun anUnknownFrameFillsTheBoxAndNeverExceedsIt() {
        assertEquals(500 to 800, callVideoSize(CallVideoFit.Fit, 0, 0, 500, 800))
        assertEquals(500 to 800, callVideoSize(CallVideoFit.Balanced, 1920, 0, 500, 800))
        assertEquals(0 to 0, callVideoSize(CallVideoFit.Fit, 1920, 1080, 0, 0))
    }
}
