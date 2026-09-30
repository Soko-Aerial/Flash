package com.transfer.flash.core.ptt

import android.os.SystemClock

public actual fun pttElapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

internal actual class PttLock {
    private val monitor = Any()

    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}

public actual fun platformPttAudio(): PttAudioPlatform = AndroidPttAudio

/** `AudioRecord` capture and `AudioTrack` playout (ADR-032). */
private object AndroidPttAudio : PttAudioPlatform {
    override fun createCapture(
        requestedRateHz: Int,
        packetMs: Int,
        onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
        onCaptureLost: () -> Unit,
    ): PttCaptureDevice = PttCapture(requestedRateHz, packetMs, onPacket, onCaptureLost)

    override fun createPlayout(
        sampleRateHz: Int,
        packetMs: Int,
        onAmplitude: (Float) -> Unit,
        onPlayoutLost: () -> Unit,
    ): PttPlayoutDevice = PttPlayout(
        sampleRateHz = sampleRateHz,
        packetMs = packetMs,
        onAmplitude = onAmplitude,
        onPlayoutLost = onPlayoutLost,
    )
}
