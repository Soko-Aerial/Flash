package com.transfer.flash.core.calling

/**
 * How much video is currently being given up so that voice stays intelligible (D8, ADR-025).
 *
 * A ladder, cheapest concession first. [CallQualityGovernor] walks it one rung at a time so a
 * transient burst of jitter costs a little bitrate rather than the whole camera, and a genuinely
 * unusable link ends up sending audio only.
 *
 * Why video is the thing that gives: on the networks Flash actually runs on — a phone hotspot,
 * half-duplex, shared with whatever else is associated to it — video is one to two orders of
 * magnitude more expensive than voice. A 32 kbit/s Opus stream fits in the gaps of almost any
 * link; 2.5 Mbit/s of 1080p does not, and when the two compete inside one bandwidth estimate the
 * loser is whichever the allocator was not told to protect.
 */
internal enum class VideoConcession(
    /** Multiplier applied to the video sender's normal ceiling. */
    val bitrateScale: Double,
    /** `scaleResolutionDownBy` — 2.0 means send half-width, half-height. */
    val scaleResolutionDownBy: Double,
    /** False stops the video encoding entirely (audio-only call, camera still previewing). */
    val videoActive: Boolean,
    /**
     * User-facing explanation, or null at [FULL]. Published on
     * [com.transfer.flash.core.calling.model.FlashCallUiState.videoLimitReason] so the call
     * screen can say *why* the picture got worse — video that silently degrades reads as a
     * broken app, video that says it is protecting the call reads as a working one.
     */
    val reason: String?,
) {
    FULL(bitrateScale = 1.0, scaleResolutionDownBy = 1.0, videoActive = true, reason = null),

    REDUCED_BITRATE(
        bitrateScale = 0.5,
        scaleResolutionDownBy = 1.0,
        videoActive = true,
        reason = "Video quality lowered to protect the call audio",
    ),

    REDUCED_RESOLUTION(
        bitrateScale = 0.25,
        scaleResolutionDownBy = 2.0,
        videoActive = true,
        reason = "Video resolution lowered to protect the call audio",
    ),

    PAUSED(
        bitrateScale = 0.25,
        scaleResolutionDownBy = 2.0,
        videoActive = false,
        reason = "Video paused to protect the call audio",
    ),
    ;

    /**
     * True only at [FULL]. The 600 kbit/s floor exists so a healthy link sheds *resolution*
     * instead of dribbling bitrate away — but a floor is a promise to keep spending, which is
     * the opposite of what a struggling link needs, so every concession rung drops it.
     */
    val holdsBitrateFloor: Boolean get() = this == FULL

    /** Next rung down, or null at the bottom. */
    val harsher: VideoConcession? get() = entries.getOrNull(ordinal + 1)

    /** Next rung up, or null at [FULL]. */
    val gentler: VideoConcession? get() = entries.getOrNull(ordinal - 1)
}

/** One second of link quality, reduced to the three numbers that predict audible damage. */
internal data class CallQualitySample(
    /** Round-trip time on the selected candidate pair, ms. */
    val rttMs: Int?,
    /** Inbound audio jitter, ms. */
    val audioJitterMs: Int?,
    /**
     * Inbound packet loss over the **last sampling interval only**, 0..1.
     *
     * Deliberately not
     * [com.transfer.flash.core.calling.model.FlashCallStats.packetLoss], which is cumulative
     * over the whole call: a cumulative fraction can only ever be paid down, so one bad burst
     * in the first minute would hold the governor at its lowest rung for the rest of the call.
     */
    val lossFraction: Double?,
)

/** Verdict on one [CallQualitySample]. [NEUTRAL] is the dead band between the two thresholds. */
internal enum class LinkHealth { GOOD, NEUTRAL, BAD }

/**
 * Decides when to trade video away for voice, and when to give it back (D8).
 *
 * Pure: no WebRTC, no clock, no coroutines. [FlashCallSession] feeds it one sample a second off
 * the existing `getStats()` loop and applies whatever rung it returns, which makes the entire
 * policy — thresholds, hysteresis, ladder ordering — testable on the JVM instead of on a phone
 * over a hotspot.
 *
 * **Hysteresis is asymmetric on purpose.** Degrading takes [degradeAfter] consecutive bad
 * samples (2 s); recovering takes [recoverAfter] consecutive *clean* ones (5 s). A link that
 * hovers in the dead band therefore holds its current rung indefinitely rather than oscillating,
 * and every accepted step resets both counters so the next decision needs a fresh window of
 * evidence — one burst cannot walk the ladder to the bottom in a single sequence.
 */
internal class CallQualityGovernor(
    private val degradeAfter: Int = DEGRADE_AFTER_SAMPLES,
    private val recoverAfter: Int = RECOVER_AFTER_SAMPLES,
) {

    /** Current rung. Changes only through [onSample] and [reset]. */
    var level: VideoConcession = VideoConcession.FULL
        private set

    private var badStreak: Int = 0
    private var goodStreak: Int = 0

    /**
     * Folds one sample in and returns the new rung, or **null when nothing should change** —
     * the caller only touches the encoder on a non-null result, so a steady call does no
     * `setParameters` work at all.
     */
    fun onSample(sample: CallQualitySample): VideoConcession? {
        when (classify(sample)) {
            LinkHealth.BAD -> {
                badStreak++
                goodStreak = 0
            }
            LinkHealth.GOOD -> {
                goodStreak++
                badStreak = 0
            }
            // Not clean enough to earn recovery, not bad enough to count as fresh evidence of
            // trouble: forget one bad sample rather than all of them, so an every-other-second
            // problem still adds up to a step down eventually.
            LinkHealth.NEUTRAL -> {
                badStreak = (badStreak - 1).coerceAtLeast(0)
                goodStreak = 0
            }
        }

        if (badStreak >= degradeAfter) {
            val next = level.harsher ?: return null
            return stepTo(next)
        }
        if (goodStreak >= recoverAfter) {
            val next = level.gentler ?: return null
            return stepTo(next)
        }
        return null
    }

    /**
     * Back to [VideoConcession.FULL] with no accumulated evidence.
     *
     * Called only when the video sender itself goes away (media release), never on an ICE
     * reconnect: the governor's rung and the encoder's parameters have to stay in lockstep, and
     * resetting one without re-applying the other would let the next bad sample "step down" from
     * FULL and quietly un-pause video that is still meant to be paused.
     */
    fun reset() {
        level = VideoConcession.FULL
        badStreak = 0
        goodStreak = 0
    }

    private fun stepTo(next: VideoConcession): VideoConcession {
        level = next
        badStreak = 0
        goodStreak = 0
        return next
    }

    internal companion object {
        /** Consecutive bad samples before one rung of video is given up. 2 s at 1 Hz. */
        const val DEGRADE_AFTER_SAMPLES: Int = 2

        /** Consecutive clean samples before one rung is handed back. 5 s at 1 Hz. */
        const val RECOVER_AFTER_SAMPLES: Int = 5

        /**
         * Audible-damage thresholds. Jitter is the sharpest of the three — the receiver's buffer
         * has to absorb it, so it converts directly into either latency or dropouts — and on a
         * quiet LAN it sits at 1-5 ms, so 30 ms already means the link is fighting.
         */
        const val BAD_JITTER_MS: Int = 30
        const val GOOD_JITTER_MS: Int = 15
        const val BAD_LOSS_FRACTION: Double = 0.03
        const val GOOD_LOSS_FRACTION: Double = 0.01
        const val BAD_RTT_MS: Int = 250
        const val GOOD_RTT_MS: Int = 120

        /**
         * Any one bad metric is enough to degrade; *every* measured metric has to be clean to
         * recover, and a sample that measured nothing at all is [LinkHealth.NEUTRAL] rather than
         * clean — silence is not evidence of health.
         */
        fun classify(sample: CallQualitySample): LinkHealth {
            val jitter = sample.audioJitterMs
            val loss = sample.lossFraction
            val rtt = sample.rttMs

            val bad = (jitter != null && jitter >= BAD_JITTER_MS) ||
                (loss != null && loss >= BAD_LOSS_FRACTION) ||
                (rtt != null && rtt >= BAD_RTT_MS)
            if (bad) return LinkHealth.BAD

            if (jitter == null && loss == null && rtt == null) return LinkHealth.NEUTRAL

            val good = (jitter == null || jitter <= GOOD_JITTER_MS) &&
                (loss == null || loss <= GOOD_LOSS_FRACTION) &&
                (rtt == null || rtt <= GOOD_RTT_MS)
            return if (good) LinkHealth.GOOD else LinkHealth.NEUTRAL
        }
    }
}
