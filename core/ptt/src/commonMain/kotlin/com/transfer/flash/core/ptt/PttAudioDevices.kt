package com.transfer.flash.core.ptt

/**
 * The audio seam of [PttSessionEngine] (ADR-058).
 *
 * The session engine decides WHEN to capture and play; these interfaces are HOW, per platform:
 * Android uses `AudioRecord` / `AudioTrack`, the desktop JVM uses `javax.sound.sampled`. The wire
 * contract is identical on both: raw PCM16 little-endian mono, one fixed-size packet per callback
 * (`rate * packetMs / 1000 * 2` bytes), the rate and packet length announced in the session Start
 * frame (ADR-032).
 */

/** Result of [PttCaptureDevice.start]. The rate may differ from the requested one (HAL fallback). */
public sealed interface PttCaptureStart {
    public data class Started(
        val actualRateHz: Int,
        val actualPacketMs: Int,
    ) : PttCaptureStart

    public data object Failed : PttCaptureStart
}

/**
 * Microphone capture for one talk session. One instance per session; never reused after [stop].
 *
 * Packets are delivered on the device's own capture thread through the `onPacket` callback given to
 * [PttAudioPlatform.createCapture], and only after [enablePackets] (the engine opens the gate once
 * the session Start frame has been sent, so no audio can overtake the receiver's format claim).
 * An unexpected end while armed (read error, system silence, unplugged microphone) reports
 * `onCaptureLost` exactly once; [stop] never reports.
 */
public interface PttCaptureDevice {
    /** Starts capture. Idempotent while running. Never blocks the caller on audio I/O. */
    public fun start(): PttCaptureStart

    /** Opens packet delivery. No-op when capture is not running. */
    public fun enablePackets()

    /** Stops capture and frees the microphone. Safe from any thread, never reports loss. */
    public fun stop()
}

/** Playout counters read by the session stats; safe to build from any thread. */
public data class PttPlayoutSnapshot(
    val readyTotal: Long,
    val concealedTotal: Long,
    val depthPackets: Int,
    val amplitude01: Float,
)

/**
 * Speaker playout for one listen session. One instance per session; never reused after [stop].
 *
 * Holds the jitter buffer: [offer] is called from network threads, the device's own playout thread
 * drains it. An unexpected death of the output line reports `onPlayoutLost`; [stop] never reports.
 */
public interface PttPlayoutDevice {
    /** Non-blocking enqueue from any thread. False when stopped or the packet has the wrong size. */
    public fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean

    public fun snapshot(): PttPlayoutSnapshot

    /** Starts playout. False when the output line cannot be opened (the engine ends the listen). */
    public fun start(): Boolean

    /** Stops playout and frees the output line. Safe from any thread, never reports loss. */
    public fun stop()
}

/** Factory for the two devices; one per platform, see [platformPttAudio]. */
public interface PttAudioPlatform {
    public fun createCapture(
        requestedRateHz: Int,
        packetMs: Int,
        onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit,
        onCaptureLost: () -> Unit,
    ): PttCaptureDevice

    public fun createPlayout(
        sampleRateHz: Int,
        packetMs: Int,
        onAmplitude: (Float) -> Unit,
        onPlayoutLost: () -> Unit,
    ): PttPlayoutDevice
}
