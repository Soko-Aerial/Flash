package com.transfer.flash.core.ptt

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

public actual fun platformPttAudio(): PttAudioPlatform = JvmPttAudio(JavaSoundPttLines)

/**
 * Desktop capture and playout over a [PttPcmLines] (`javax.sound.sampled` in production, a fake in the
 * tests). Wire format identical to Android: PCM16 little-endian mono (ADR-032).
 */
internal class JvmPttAudio(private val lines: PttPcmLines) : PttAudioPlatform {
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
}
