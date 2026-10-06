package com.transfer.flash.ui.calling

import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallNotice
import com.transfer.flash.core.calling.model.FlashCameraProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ERROR-105: the words for a call that could not open the microphone or camera. */
class FlashCallMediaTextTest {

    @Test
    fun `every end reason has its own line and a microphone failure no longer reads Call failed`() {
        FlashCallEndReason.entries.forEach { assertTrue(endReasonText(it).isNotBlank(), "$it") }
        assertEquals("Call ended", endReasonText(null))
        assertEquals("Call failed", endReasonText(FlashCallEndReason.ERROR))
        assertEquals("Microphone permission needed", endReasonText(FlashCallEndReason.MIC_DENIED))
        assertEquals("Microphone is busy or unavailable", endReasonText(FlashCallEndReason.MIC_UNAVAILABLE))
        assertNotEquals(endReasonText(FlashCallEndReason.ERROR), endReasonText(FlashCallEndReason.MIC_UNAVAILABLE))
    }

    @Test
    fun `a stopped camera offers to try again and a failed switch only says so`() {
        assertEquals("Try again", cameraProblemAction(FlashCameraProblem.FAILED))
        assertEquals(null, cameraProblemAction(FlashCameraProblem.SWITCH_FAILED))
        FlashCameraProblem.entries.forEach { assertTrue(cameraProblemText(it).isNotBlank(), "$it") }
        assertNotEquals(cameraProblemText(FlashCameraProblem.FAILED), cameraProblemText(FlashCameraProblem.SWITCH_FAILED))
    }

    @Test
    fun `a call joined without camera says which of the two reasons applied`() {
        val denied = callNoticeText(FlashCallNotice.CAMERA_DENIED_AUDIO_ONLY)
        val unavailable = callNoticeText(FlashCallNotice.CAMERA_UNAVAILABLE_AUDIO_ONLY)
        assertTrue(denied.startsWith("Joined without camera"), denied)
        assertTrue(unavailable.startsWith("Joined without camera"), unavailable)
        assertNotEquals(denied, unavailable)
        assertTrue(denied.contains("permission"), denied)
    }
}
