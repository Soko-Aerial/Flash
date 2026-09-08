package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashMediaViewerLogicTest {

    @Test
    fun `zoom clamps to min and max on settle`() {
        assertEquals(1.0f, FlashMediaViewerMath.clampedScale(0.4f))
        assertEquals(1.0f, FlashMediaViewerMath.clampedScale(1.0f))
        assertEquals(2.75f, FlashMediaViewerMath.clampedScale(2.75f))
        assertEquals(4.0f, FlashMediaViewerMath.clampedScale(9.5f))
    }

    @Test
    fun `pinch ceiling allows bounded overshoot beyond max zoom`() {
        val ceiling = FlashMediaViewerMath.pinchCeiling()
        assertTrue(ceiling > FlashMediaViewerMath.ZOOM_MAX)
        assertEquals(
            FlashMediaViewerMath.ZOOM_MAX * (1f + FlashMediaViewerMath.ZOOM_OVERSHOOT),
            ceiling,
        )
        assertTrue(FlashMediaViewerMath.clampedScale(ceiling) <= FlashMediaViewerMath.ZOOM_MAX)
    }

    @Test
    fun `anchored offset keeps the image point under the gesture fixed`() {
        // Zooming 1x -> 2x about a centroid left of center shifts offset to keep that point.
        val oldOffset = 0f
        val centroidToCenter = -200f
        val ratio = 2f

        val newOffset = FlashMediaViewerMath.anchoredOffset(oldOffset, centroidToCenter, ratio)

        assertEquals(200f, newOffset)
    }

    @Test
    fun `anchored offset at viewport center scales existing pan by ratio`() {
        // Zooming about the viewport center keeps the centered image point fixed,
        // which proportionally scales the current pan offset.
        assertEquals(
            120f,
            FlashMediaViewerMath.anchoredOffset(oldOffset = 40f, centroidToCenter = 0f, ratio = 3f),
        )
        // Ratio of 1 (no scale change) always preserves the offset regardless of anchor.
        assertEquals(
            40f,
            FlashMediaViewerMath.anchoredOffset(oldOffset = 40f, centroidToCenter = -150f, ratio = 1f),
        )
    }

    @Test
    fun `pan limit grows with zoom and is zero unzoomed`() {
        assertEquals(0f, FlashMediaViewerMath.panLimit(1000f, 1.0f))
        assertEquals(500f, FlashMediaViewerMath.panLimit(1000f, 2.0f))
        assertEquals(1500f, FlashMediaViewerMath.panLimit(1000f, 4.0f))
    }

    @Test
    fun `dismiss claim requires vertical dominance over horizontal`() {
        val slop = 8f

        // Dominant vertical drag claims dismiss
        assertTrue(FlashMediaViewerMath.claimsDismiss(totalDx = 5f, totalDy = 60f, touchSlopPx = slop))
        // Horizontal drag never claims dismiss (pager owns it)
        assertFalse(FlashMediaViewerMath.claimsDismiss(totalDx = 60f, totalDy = 5f, touchSlopPx = slop))
        // Diagonal 45-degree drag does not claim (needs 2x dominance)
        assertFalse(FlashMediaViewerMath.claimsDismiss(totalDx = 50f, totalDy = 50f, touchSlopPx = slop))
        // Sub-slop movement does not claim
        assertFalse(FlashMediaViewerMath.claimsDismiss(totalDx = 0f, totalDy = 10f, touchSlopPx = 20f))
    }

    @Test
    fun `dismiss resolves by distance threshold`() {
        val thresholdPx = 480f

        assertFalse(FlashMediaViewerMath.shouldDismissByDistance(dragY = 100f, thresholdPx = thresholdPx))
        assertTrue(FlashMediaViewerMath.shouldDismissByDistance(dragY = 479.9f, thresholdPx = thresholdPx).not())
        assertTrue(FlashMediaViewerMath.shouldDismissByDistance(dragY = 480f, thresholdPx = thresholdPx))
    }

    @Test
    fun `dismiss resolves by fling velocity`() {
        assertFalse(FlashMediaViewerMath.shouldDismissByVelocity(velocityYPxPerSec = 899f))
        assertTrue(FlashMediaViewerMath.shouldDismissByVelocity(velocityYPxPerSec = 900f))
        assertTrue(FlashMediaViewerMath.shouldDismissByVelocity(velocityYPxPerSec = 4000f))
    }

    @Test
    fun `counter label is one-based with spaces`() {
        assertEquals("1 / 7", FlashMediaViewerMath.counterLabel(index = 0, total = 7))
        assertEquals("3 / 7", FlashMediaViewerMath.counterLabel(index = 2, total = 7))
        assertEquals("7 / 7", FlashMediaViewerMath.counterLabel(index = 6, total = 7))
        assertEquals("1 / 1", FlashMediaViewerMath.counterLabel(index = 0, total = 1))
    }

    @Test
    fun `page description supports TalkBack announcement`() {
        assertEquals("Photo 2 of 5", FlashMediaViewerMath.pageDescription(index = 1, total = 5))
        assertEquals("Photo 1 of 1", FlashMediaViewerMath.pageDescription(index = 0, total = 1))
    }

    @Test
    fun `initial page clamps into valid pager range`() {
        assertEquals(0, FlashMediaViewerMath.initialPage(requested = -3, total = 5))
        assertEquals(2, FlashMediaViewerMath.initialPage(requested = 2, total = 5))
        assertEquals(4, FlashMediaViewerMath.initialPage(requested = 9, total = 5))
        assertEquals(0, FlashMediaViewerMath.initialPage(requested = 0, total = 0))
    }

    @Test
    fun `inSampleSize guard keeps decoded long edge within bound`() {
        val maxEdge = FlashMediaViewerMath.MAX_DECODE_LONG_EDGE

        // Small images are untouched
        assertEquals(1, FlashMediaViewerMath.computeInSampleSize(width = 1920, height = 1080))

        // Oversized albums decode down to at most the long-edge bound
        assertEquals(2, FlashMediaViewerMath.computeInSampleSize(width = 8000, height = 6000))
        assertEquals(2, FlashMediaViewerMath.computeInSampleSize(width = 4097, height = 3000))

        val sample = FlashMediaViewerMath.computeInSampleSize(width = 16000, height = 12000)
        assertTrue(maxOf(16000, 12000) / sample <= maxEdge)

        // Degenerate dimensions fall back safely
        assertEquals(1, FlashMediaViewerMath.computeInSampleSize(width = 0, height = 100))
        assertEquals(1, FlashMediaViewerMath.computeInSampleSize(width = 100, height = -1))
    }

    @Test
    fun `backdrop alpha fades out across dismiss progress`() {
        assertEquals(1f, FlashMediaViewerMath.backdropAlpha(dismissProgress = 0f))
        assertEquals(0.5f, FlashMediaViewerMath.backdropAlpha(dismissProgress = 0.5f))
        assertEquals(0f, FlashMediaViewerMath.backdropAlpha(dismissProgress = 1f))
        assertEquals(0f, FlashMediaViewerMath.backdropAlpha(dismissProgress = 2f))
    }

    @Test
    fun `page scales down during dismiss drag only`() {
        assertEquals(1f, FlashMediaViewerMath.dismissPageScale(dismissProgress = 0f))
        assertTrue(FlashMediaViewerMath.dismissPageScale(dismissProgress = 0.5f) < 1f)
        assertEquals(
            1f - FlashMediaViewerMath.DISMISS_SCALE_DELTA,
            FlashMediaViewerMath.dismissPageScale(dismissProgress = 1f),
        )
        assertEquals(
            1f - FlashMediaViewerMath.DISMISS_SCALE_DELTA,
            FlashMediaViewerMath.dismissPageScale(dismissProgress = 3f),
        )
    }

    @Test
    fun `viewer item model carries attribution metadata`() {
        val item = FlashMediaViewerItem(
            image = FlashImageAttachmentUi(id = "img-9", width = 800, height = 600),
            senderName = "Jordan Lee",
            timeLabel = "2:15 PM",
        )

        assertEquals("img-9", item.image.id)
        assertEquals("Jordan Lee", item.senderName)
        assertEquals("2:15 PM", item.timeLabel)
    }
}
