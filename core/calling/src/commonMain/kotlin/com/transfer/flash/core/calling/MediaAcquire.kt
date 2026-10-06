package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.CameraPermissionException
import com.shepeliev.webrtckmp.RecordAudioPermissionException
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallNotice
import kotlinx.coroutines.CancellationException

/**
 * ERROR-105: what opening the microphone (and camera) for a call came to.
 *
 * Both sessions used to turn every media failure into a bare `false` and end with a generic
 * "Call failed", and a denied camera on an incoming *video* call could never be answered at all.
 * [T] is the opened stream (a `MediaStream`; generic so the rules can be tested without native media).
 */
internal sealed interface MediaAcquire<out T> {
    /** [stream] is open. [notice] is non-null when a video call was joined without the camera. */
    data class Ready<out T>(val stream: T, val notice: FlashCallNotice? = null) : MediaAcquire<T>

    /** The call cannot start: [reason] says why, in terms the call screen can word. */
    data class Failed(val reason: FlashCallEndReason) : MediaAcquire<Nothing>
}

/**
 * Opens local media through [open] (`video = true` asks for camera and microphone).
 *
 * A video call that cannot open the camera for *any* reason is retried once as audio only, because
 * the platform exceptions cannot tell "camera busy" from "microphone busy" (the JVM backend throws
 * plain exceptions). If the audio-only retry works the camera was the problem and the call goes
 * ahead without it ([MediaAcquire.Ready.notice]); if it fails too, the microphone is the problem.
 *
 * Cancellation always propagates. An [Error] (a missing native library) is not a device problem and
 * stays the generic [FlashCallEndReason.ERROR].
 */
internal suspend fun <T> acquireWithCameraFallback(
    wantVideo: Boolean,
    open: suspend (video: Boolean) -> T,
): MediaAcquire<T> {
    try {
        return MediaAcquire.Ready(open(wantVideo))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (!wantVideo || t is RecordAudioPermissionException) return MediaAcquire.Failed(micFailureReason(t))
        if (t !is Exception) return MediaAcquire.Failed(FlashCallEndReason.ERROR)
        val notice = if (t is CameraPermissionException) {
            FlashCallNotice.CAMERA_DENIED_AUDIO_ONLY
        } else {
            FlashCallNotice.CAMERA_UNAVAILABLE_AUDIO_ONLY
        }
        return try {
            MediaAcquire.Ready(open(false), notice)
        } catch (e: CancellationException) {
            throw e
        } catch (second: Throwable) {
            MediaAcquire.Failed(micFailureReason(second))
        }
    }
}

/** The end reason for a failure that happened with no camera involved. */
internal fun micFailureReason(t: Throwable): FlashCallEndReason = when {
    t is RecordAudioPermissionException -> FlashCallEndReason.MIC_DENIED
    t is Exception -> FlashCallEndReason.MIC_UNAVAILABLE
    else -> FlashCallEndReason.ERROR
}
