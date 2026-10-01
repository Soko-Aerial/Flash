package com.transfer.flash.core.calling

import com.transfer.flash.core.common.perf.FlashVideoProfile

/**
 * The encoder settings for one leg's copy of this device's video in a group call.
 *
 * [height] is the copy's height from the G4 budget (null: the full camera profile, an old client).
 * [concession] is how much of this leg's video is being given up so the call's voice stays
 * intelligible (D8, [CallQualityGovernor]); each leg has its own, because each leg is its own
 * connection with its own bandwidth estimate and its own bad moments.
 *
 * **The floor.** The profile's floor (`minBitrateKbps`) is sized for a full-height 1:1 stream; held
 * on a 360p copy it equals or exceeds that copy's ceiling and pins the encoder at a constant
 * bitrate, so the sender's congestion control could not move it at all. It is capped at half the
 * copy's ceiling (provisional until measured, backlog GVID-MEAS) and, like in a 1:1 call, dropped
 * entirely on every concession rung: "a floor is a promise to keep spending, which is the
 * opposite of what a struggling link needs" ([VideoConcession.holdsBitrateFloor]). The VP8 section
 * carries no `x-google-min-bitrate` ([CallSdp.enforceVp8Only] strips fmtp lines), so this sender
 * parameter is the only floor there is.
 */
internal fun groupVideoTuning(
    profile: FlashVideoProfile,
    height: Int?,
    concession: VideoConcession,
    active: Boolean,
): VideoSendTuning {
    val sent = height?.coerceIn(1, profile.captureHeight) ?: profile.captureHeight
    val copyMaxKbps = if (height == null) {
        profile.maxBitrateKbps
    } else {
        minOf(profile.maxBitrateKbps, GroupVideoLimits.maxBitrateKbps(sent))
    }
    val maxKbps = (copyMaxKbps * concession.bitrateScale).toInt()
    val floorKbps = if (concession.holdsBitrateFloor) minOf(profile.minBitrateKbps, copyMaxKbps / 2) else null
    return VideoSendTuning(
        maxBitrateBps = maxKbps * BPS_PER_KBPS,
        minBitrateBps = floorKbps?.let { it * BPS_PER_KBPS },
        maxFramerate = profile.captureFps.toDouble(),
        scaleResolutionDownBy = profile.captureHeight.toDouble() / sent * concession.scaleResolutionDownBy,
        active = active && concession.videoActive,
        demoteForVoice = true,
        maintainFramerate = true,
    )
}

/**
 * Whether this device counts as talking, which is what lets it make room for a new watcher by dropping an
 * unpinned one ([GroupVideoRouter.setLocalSpeaking], owner decision Q5). A muted microphone is never a
 * talker: its capture level can still read above silence (a cough, the room), and that must not evict
 * anybody's video.
 */
internal fun isLocalTalker(micMuted: Boolean, audioLevel: Double?): Boolean =
    !micMuted && (audioLevel ?: 0.0) > TALKER_LEVEL

private const val TALKER_LEVEL = 0.01

private const val BPS_PER_KBPS = 1_000
