@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.RtpSender
import com.transfer.flash.core.common.logging.FlashLog

/**
 * Desktop actuals over `dev.onvoid.webrtc` (webrtc-java 0.17.0) — Phase 25 S2e.
 *
 * webrtc-java's `RTCRtpEncodingParameters` carries the bitrate window, frame-rate cap,
 * resolution scale and the active flag. It does NOT carry `networkPriority`/`bitratePriority`
 * (the pacer ordering/allocator weight — the native binding never exposed them) and its send
 * parameters carry no `degradationPreference`. Those two knobs are the audio path's whole
 * point on Android; on desktop they are logged once per process (not per call — the answer
 * will not change) and the tuning degrades to the bitrate/ceiling half, which is most of the
 * benefit on a LAN where the bandwidth estimate is generous but the pacer is not the
 * bottleneck.
 */
internal actual fun RtpSender.applyAudioTuning(tuning: AudioSendTuning): Boolean {
    logDesktopGapOnce()
    val native = native
    val params = native.parameters
    if (params.encodings.isEmpty()) return false
    params.encodings.forEach { encoding ->
        encoding.active = true
        encoding.maxBitrate = tuning.maxBitrateBps
    }
    native.setParameters(params)
    return true
}

internal actual fun RtpSender.applyVideoTuning(tuning: VideoSendTuning): Boolean {
    if (tuning.maintainFramerate || tuning.demoteForVoice) logDesktopGapOnce()
    val native = native
    val params = native.parameters
    if (params.encodings.isEmpty()) return false
    params.encodings.forEach { encoding ->
        encoding.active = tuning.active
        encoding.maxBitrate = tuning.maxBitrateBps
        // Direct assignment, matching the Android actual: null clears the floor.
        encoding.minBitrate = tuning.minBitrateBps
        tuning.maxFramerate?.let { encoding.maxFramerate = it }
        tuning.scaleResolutionDownBy?.let { encoding.scaleResolutionDownBy = it }
    }
    native.setParameters(params)
    return true
}

@Volatile
private var desktopGapLogged = false

private fun logDesktopGapOnce() {
    if (desktopGapLogged) return
    desktopGapLogged = true
    FlashLog.i(
        "CALL",
        "desktop webrtc backend: pacer priority and degradation preference are not exposed " +
            "by webrtc-java — audio/video tuning applies bitrate windows only",
    )
}
