package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.CameraPermissionException
import com.shepeliev.webrtckmp.RecordAudioPermissionException
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ERROR-105: opening local media for a call, and what each failure ends as. */
class MediaAcquireTest {

    private val asked = mutableListOf<Boolean>()

    /** Opens fine unless [failWhen] says this ask (video yes/no) throws. */
    private fun opener(failWhen: (video: Boolean) -> Throwable?): suspend (Boolean) -> String = { video ->
        asked += video
        failWhen(video)?.let { throw it }
        if (video) "audio+video" else "audio"
    }

    @Test
    fun `everything opens, nothing is hidden`() = runTest {
        val r = acquireWithCameraFallback(true, opener { null })
        assertEquals(MediaAcquire.Ready("audio+video"), r)
        assertEquals(listOf(true), asked)
    }

    @Test
    fun `a denied camera joins audio only and says the permission is why`() = runTest {
        val r = acquireWithCameraFallback(true, opener { if (it) CameraPermissionException() else null })
        assertEquals(MediaAcquire.Ready("audio", FlashCallNotice.CAMERA_DENIED_AUDIO_ONLY), r)
        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun `a camera that will not open joins audio only and says it could not be opened`() = runTest {
        val r = acquireWithCameraFallback(true, opener { if (it) IllegalStateException("camera in use") else null })
        assertEquals(MediaAcquire.Ready("audio", FlashCallNotice.CAMERA_UNAVAILABLE_AUDIO_ONLY), r)
    }

    @Test
    fun `when the audio only retry fails too the microphone is the problem`() = runTest {
        val busy = acquireWithCameraFallback(true, opener { IllegalStateException("mic in use") })
        assertEquals(MediaAcquire.Failed(FlashCallEndReason.MIC_UNAVAILABLE), busy)
        assertEquals(listOf(true, false), asked)

        val denied = acquireWithCameraFallback(true, opener { if (it) CameraPermissionException() else RecordAudioPermissionException() })
        assertEquals(MediaAcquire.Failed(FlashCallEndReason.MIC_DENIED), denied)
    }

    @Test
    fun `a refused microphone ends the call without a pointless camera retry`() = runTest {
        val r = acquireWithCameraFallback(true, opener { RecordAudioPermissionException() })
        assertEquals(MediaAcquire.Failed(FlashCallEndReason.MIC_DENIED), r)
        assertEquals("only one attempt", listOf(true), asked)
    }

    @Test
    fun `a voice call never retries and maps the microphone failure`() = runTest {
        val busy = acquireWithCameraFallback(false, opener { IllegalStateException("mic in use") })
        assertEquals(MediaAcquire.Failed(FlashCallEndReason.MIC_UNAVAILABLE), busy)
        assertEquals(listOf(false), asked)
        val denied = acquireWithCameraFallback(false, opener { RecordAudioPermissionException() })
        assertEquals(MediaAcquire.Failed(FlashCallEndReason.MIC_DENIED), denied)
    }

    @Test
    fun `a missing native library is not blamed on a device`() = runTest {
        val r = acquireWithCameraFallback(true, opener { UnsatisfiedLinkError("libjingle") })
        assertEquals("an Error is a broken install, not a busy camera", MediaAcquire.Failed(FlashCallEndReason.ERROR), r)
        assertEquals("no retry for an Error", listOf(true), asked)
        assertEquals(FlashCallEndReason.ERROR, micFailureReason(NoClassDefFoundError()))
    }

    @Test
    fun `cancellation is never turned into a failure`() = runTest {
        try {
            acquireWithCameraFallback(true, opener { CancellationException("scope closed") })
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertTrue(asked.size == 1)
        }
        asked.clear()
        try {
            acquireWithCameraFallback(true, opener { if (it) CameraPermissionException() else CancellationException("closed during retry") })
            fail("cancellation during the retry must propagate")
        } catch (e: CancellationException) {
            assertEquals(listOf(true, false), asked)
        }
    }
}
