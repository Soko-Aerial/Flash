package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.RtpSender

/**
 * Platform-neutral sender-tuning payloads — the Phase 25 S2e seam.
 *
 * The session classes' quality logic (voice-first ordering, tier ceilings, the concession
 * ladder) is pure policy and lives in commonMain. What it drives, however, is knobs only the
 * native layer exposes, and the two backends expose DIFFERENT knobs:
 *
 * - Android reaches through `RtpSender.android` to `org.webrtc.RTCRtpEncodingParameters`,
 *   which has `networkPriority`, `bitratePriority` and `degradationPreference`.
 * - Desktop reaches through `RtpSender.native` to `dev.onvoid.webrtc.RTCRtpEncodingParameters`
 *   (webrtc-java 0.17.0), which has the bitrate/framerate/scale/active fields but NO pacer
 *   priority and NO degradation preference.
 *
 * So the sessions build these payloads and the `expect` extensions below apply them; each
 * actual maps what its backend supports and logs — never silently drops — what it cannot.
 * A missing knob costs quality tuning, not correctness: every call site is best-effort by
 * design (a tuning failure costs bitrate or priority, not the call).
 */

/** Pacer/allocator weights the Android backend honours. Desktop has no equivalents. */
internal const val AUDIO_BITRATE_PRIORITY = 4.0
internal const val VIDEO_BITRATE_PRIORITY = 0.5

/** Audio-sender tuning: activate the encoding, raise its pacer priority, cap its ceiling. */
internal class AudioSendTuning(
    val maxBitrateBps: Int,
)

/**
 * Video-sender tuning. `maintainFramerate` asks the encoder to shed resolution instead of
 * frame rate under congestion (Android-only knob — desktop logs its absence once). `demote`
 * drops the stream below audio in the pacer ordering (Android-only knob).
 */
internal class VideoSendTuning(
    val maxBitrateBps: Int,
    val minBitrateBps: Int? = null,
    val maxFramerate: Double? = null,
    val scaleResolutionDownBy: Double? = null,
    val active: Boolean = true,
    val demoteForVoice: Boolean = false,
    val maintainFramerate: Boolean = false,
)

/** Applies [tuning] to the sender's encodings. Returns whether the backend accepted it. */
internal expect fun RtpSender.applyAudioTuning(tuning: AudioSendTuning): Boolean

/** Applies [tuning] to the sender's encodings. Returns whether the backend accepted it. */
internal expect fun RtpSender.applyVideoTuning(tuning: VideoSendTuning): Boolean
