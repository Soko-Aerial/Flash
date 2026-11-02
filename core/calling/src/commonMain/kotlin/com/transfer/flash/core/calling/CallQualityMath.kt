package com.transfer.flash.core.calling

import kotlin.math.roundToInt

/**
 * The three call-quality numbers `getStats()` carries but nothing computed (OPT-O28-03), as pure arithmetic so they can be
 * tested without a native stack.
 *
 * WebRTC reports their ingredients as **cumulative counters**, so every function here takes a delta — the caller
 * ([CallDiagnostics]) already keeps the previous totals per leg for its rates.
 *
 * ```
 * jitterBufferDelay   s      total time packets spent waiting in the jitter buffer
 * jitterBufferEmittedCount   number of samples played out of it
 * concealedSamples           samples the decoder had to invent (packet loss concealment)
 * totalSamplesReceived       samples it received
 * estimatedPlayoutTimestamp  s      the RTP timestamp this endpoint expects to be playing out
 * ```
 *
 * A missing counter yields null rather than a number: a backend that does not report it must not read as "0 ms, perfect".
 */
internal object CallQualityMath {

    /**
     * Average time a played sample spent in the jitter buffer, in milliseconds:
     * `(Δ jitterBufferDelay / Δ jitterBufferEmittedCount) * 1000`.
     *
     * This is the delay the receiver's own buffer adds on top of the network, i.e. the part a depth change would move —
     * the single most useful number for "why does the voice lag".
     */
    fun jitterBufferDelayMs(delaySecondsDelta: Double?, emittedDelta: Double?): Int? {
        if (delaySecondsDelta == null || emittedDelta == null) return null
        if (emittedDelta <= 0.0 || delaySecondsDelta < 0.0) return null
        return (delaySecondsDelta / emittedDelta * 1000.0).roundToInt()
    }

    /**
     * Share of played samples the decoder had to conceal, in percent: `concealed / received * 100`.
     *
     * Reported instead of the raw `concealedSamples` total, which only ever grows and therefore says nothing about the
     * current link. Above a few percent the voice is audibly robotic.
     */
    fun concealmentPercent(concealedSamples: Double?, totalSamplesReceived: Double?): Double? {
        if (concealedSamples == null || totalSamplesReceived == null) return null
        if (totalSamplesReceived <= 0.0 || concealedSamples < 0.0) return null
        return (concealedSamples / totalSamplesReceived * 100.0 * 10.0).roundToInt() / 10.0
    }

    /**
     * Audio/video playout skew in milliseconds: `(audio playout timestamp - video playout timestamp) * 1000`.
     *
     * Both are the *estimated playout* timestamps of the two receive streams, so a non-zero difference means the sound and
     * the picture are being played at different media times — lip-sync error. Null when either stream is absent, which is
     * every audio-only call.
     */
    fun avDriftMs(audioPlayoutSeconds: Double?, videoPlayoutSeconds: Double?): Int? {
        if (audioPlayoutSeconds == null || videoPlayoutSeconds == null) return null
        return ((audioPlayoutSeconds - videoPlayoutSeconds) * 1000.0).roundToInt()
    }
}
