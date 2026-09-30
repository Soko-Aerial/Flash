package com.transfer.flash.core.ptt

public actual fun pttElapsedRealtimeMs(): Long = System.nanoTime() / NANOS_PER_MILLI

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
