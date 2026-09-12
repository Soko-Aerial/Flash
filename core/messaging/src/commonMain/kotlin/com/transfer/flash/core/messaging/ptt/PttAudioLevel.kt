package com.transfer.flash.core.messaging.ptt

import kotlin.math.sqrt

/**
 * PCM level math for PTT session UI (ADR-032, Phase 3 animation + badges).
 *
 * Lives in common now so the capture/playout hosts and the UI share one definition of
 * "how loud is this packet" instead of each inventing its own scale.
 */
public object PttAudioLevel {
    /**
     * RMS of LE int16 mono [pcm], mapped 0 (silence) to ~1 (full-scale square wave).
     * A trailing odd byte is ignored; empty input returns 0.
     */
    public fun rms01(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sum += sample * sample.toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
