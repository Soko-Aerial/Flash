package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.RtpSender
import com.transfer.flash.core.common.logging.FlashLog
import org.webrtc.Priority
import org.webrtc.RtpParameters.DegradationPreference

/**
 * Android actuals: the org.webrtc reach-through the sessions used before S2e, moved here
 * verbatim so the session classes can live in commonMain (Phase 25 S2e). The knob set is the
 * full one — pacer priority and degradation preference included.
 */
internal actual fun RtpSender.applyAudioTuning(tuning: AudioSendTuning): Boolean {
    val native = android
    val params = native.parameters
    if (params.encodings.isEmpty()) return false
    params.encodings.forEach { encoding ->
        encoding.active = true
        encoding.networkPriority = Priority.HIGH
        encoding.bitratePriority = AUDIO_BITRATE_PRIORITY
        encoding.maxBitrateBps = tuning.maxBitrateBps
    }
    return native.setParameters(params)
}

internal actual fun RtpSender.applyVideoTuning(tuning: VideoSendTuning): Boolean {
    val native = android
    val params = native.parameters
    if (params.encodings.isEmpty()) return false
    if (tuning.maintainFramerate) {
        params.degradationPreference = DegradationPreference.MAINTAIN_FRAMERATE
    }
    params.encodings.forEach { encoding ->
        encoding.active = tuning.active
        encoding.maxBitrateBps = tuning.maxBitrateBps
        // minBitrateBps is assigned DIRECTLY — null here means "clear the floor", which is
        // what the concession ladder's non-floor rungs do (they must lift a previously set
        // floor or recovery stalls at the rung's bitrate).
        encoding.minBitrateBps = tuning.minBitrateBps
        // The rest skip when absent: the concession ladder re-reads native parameters that
        // still carry the earlier tune's framerate cap and must not wipe it with null.
        tuning.maxFramerate?.let { encoding.maxFramerate = it.toInt() } // org.webrtc takes Int
        tuning.scaleResolutionDownBy?.let { encoding.scaleResolutionDownBy = it }
        if (tuning.demoteForVoice) {
            encoding.networkPriority = Priority.LOW
            encoding.bitratePriority = VIDEO_BITRATE_PRIORITY
        }
    }
    return native.setParameters(params)
}
