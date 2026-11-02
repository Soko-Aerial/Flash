@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.ptt

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog

/**
 * Milliseconds since an arbitrary start, always positive (RVW22 F7). `System.nanoTime()` has an arbitrary origin and is
 * often negative; the session codec drops any frame whose `sentAt` is not above zero, which killed a PTT session after
 * its first heartbeat timeout. Measured from the first call plus [POSITIVE_BASE_MS], it stays monotonic and positive.
 */
public actual fun pttElapsedRealtimeMs(): Long = anchoredElapsedMs(System.nanoTime(), ORIGIN_NANOS)

private val ORIGIN_NANOS: Long = System.nanoTime()

internal fun anchoredElapsedMs(nowNanos: Long, originNanos: Long): Long = (nowNanos - originNanos) / NANOS_PER_MILLI + POSITIVE_BASE_MS

private const val POSITIVE_BASE_MS = 1_000L

private const val NANOS_PER_MILLI = 1_000_000L

internal actual class PttLock {
    private val monitor = Any()

    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}

public actual fun platformPttAudio(): PttAudioPlatform = JvmPttAudio(JavaSoundPttLines).also { audio ->
    // OPT-O12-03 / PTTD-04: the first `TargetDataLine` open costs about a second on this host (EXP-018 measured ~1.0 s
    // cold, ~0.23 s warm), and that second used to be spent inside the first PTT press — clipping the first words and
    // holding the floor's command lock. The engine is constructed at host start-up, so this warms the mixer and the
    // javax.sound classes then, off the calling thread.
    audio.warmUpInBackground()
}

/**
 * Desktop capture and playout over a [PttPcmLines] (`javax.sound.sampled` in production, a fake in the
 * tests). Wire format identical to Android: PCM16 little-endian mono (ADR-032).
 */
internal class JvmPttAudio(private val lines: PttPcmLines) : PttAudioPlatform {

    /**
     * OPT-O12-03: pays the one-off `javax.sound.sampled` open cost before the first press, not during it. Opens both
     * lines once at the usual 16 kHz and closes them immediately; nothing is read from the microphone and the line is
     * held for a few milliseconds. Only the production factory calls it — a test's fake lines are never opened behind
     * the test's back.
     *
     * Idempotent and never throws: a host with no audio device simply logs that the warm-up found nothing (the press
     * would have failed the same way, and the session's own notice covers it).
     */
    fun warmUpInBackground() {
        if (!warmedUp.compareAndSet(false, true)) return
        val thread = Thread({
            val startedNs = System.nanoTime()
            var opened = 0
            runCatching {
                lines.openInput(WARM_RATE_HZ, WARM_BUFFER_BYTES)?.let {
                    opened++
                    it.stop()
                    it.close()
                }
            }
            runCatching {
                lines.openOutput(WARM_RATE_HZ, WARM_BUFFER_BYTES)?.let {
                    opened++
                    it.stop()
                    it.close()
                }
            }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            FlashLog.i(TAG, "Audio warm-up opened=$opened lines in ${elapsedMs}ms (the first press no longer pays this)")
        }, "ptt-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        thread.start()
    }

    override fun createCapture(
        requestedRateHz: Int,
        packetMs: Int,
        onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
        onCaptureLost: () -> Unit,
    ): PttCaptureDevice = JvmPttCapture(lines, requestedRateHz, packetMs, onPacket, onCaptureLost)

    override fun createPlayout(
        sampleRateHz: Int,
        packetMs: Int,
        onAmplitude: (Float) -> Unit,
        onPlayoutLost: () -> Unit,
    ): PttPlayoutDevice = JvmPttPlayout(lines, sampleRateHz, packetMs, onAmplitude, onPlayoutLost)

    private val warmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    private companion object {
        const val TAG = "PTT_JSND"

        /** The rate the engine asks for on a normal device, so the warm-up hits the same mixer path. */
        const val WARM_RATE_HZ = 16_000

        /** Four 20 ms packets, the same shape [JvmPttCapture] asks for. */
        const val WARM_BUFFER_BYTES = WARM_RATE_HZ * 20 / 1000 * 2 * 4
    }
}
