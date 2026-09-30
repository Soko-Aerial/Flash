package com.transfer.flash.core.ptt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaker playout loop for PTT reception (ADR-032, Phase 1).
 *
 * Owns the [AudioTrack] (STREAM mode, media path = loudspeaker by default), the
 * [PttJitterBuffer], and the playout thread. Network pushes arrive via [offer] from any
 * thread into a drop-oldest inbox — stale live audio is shed, never queued — and the
 * playout thread alone touches the buffer (see its threading contract). Each tick writes
 * exactly one packet duration: ready audio, last-frame repeat on gaps, zeros while
 * starving. `AudioTrack.write` blocks and paces the loop; no sleep math.
 *
 * One instance per session: the engine discards it on teardown, so no cross-thread
 * buffer reset is ever needed. [snapshot] reads volatiles/counters only and is safe
 * from any thread. Unexpected track death reports [onPlayoutLost] (engine ends the
 * listen); [stop] never reports.
 *
 * Android implementation of [PttPlayoutDevice] (ADR-058). The inbox, jitter buffer, concealment and
 * counters moved to the shared [PttPlayoutCore]; this class keeps only the `AudioTrack` and its thread.
 */
internal class PttPlayout(
    private val sampleRateHz: Int,
    private val packetMs: Int,
    targetDepthMs: Long = PttPlayoutCore.DEFAULT_TARGET_DEPTH_MS,
    private val onAmplitude: (Float) -> Unit = {},
    private val onPlayoutLost: () -> Unit = {},
) : PttPlayoutDevice {
    private val core = PttPlayoutCore(sampleRateHz, packetMs, targetDepthMs)
    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val bytesPerPacket: Int = core.bytesPerPacket

    /** Non-blocking enqueue from any thread. Rejects packets outside the negotiated format. */
    override fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean {
        if (!running.get()) return false
        return core.offer(seq, captureTsMs, pcm)
    }

    override fun snapshot(): PttPlayoutSnapshot = core.snapshot()

    /** Starts playout. False when the track cannot be built (caller ends the listen). */
    override fun start(): Boolean {
        if (running.get()) return true
        val track = openTrack() ?: return false
        audioTrack = track
        running.set(true)
        val thread = Thread({ loop(track) }, "ptt-playout").apply { isDaemon = true }
        worker = thread
        thread.start()
        return true
    }

    /** Stops playout and frees the track. Safe from any thread, never reports loss. */
    override fun stop() {
        running.set(false)
        val track = audioTrack
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        if (audioTrack === track) audioTrack = null
        runCatching { worker?.join(1000L) }
        worker = null
    }

    private fun openTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "Rate $sampleRateHz unsupported for playout (minBuffer=$minBuffer)")
            return null
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, bytesPerPacket * TRACK_PACKETS))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            Log.w(TAG, "AudioTrack init failed rate=$sampleRateHz")
            return null
        }
        runCatching { track.play() }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.release() }
            Log.w(TAG, "AudioTrack would not play rate=$sampleRateHz")
            return null
        }
        Log.i(TAG, "Playout started rate=$sampleRateHz packetMs=$packetMs")
        return track
    }

    private fun loop(track: AudioTrack) {
        try {
            while (running.get()) {
                writeFully(track, core.nextPacket())
                onAmplitude(core.lastAmplitude)
            }
        } catch (error: Exception) {
            // Track death mid-session (route torn down underneath us). running is still true
            // only if stop() did not initiate this exit.
            Log.w(TAG, "Playout loop died", error)
            if (running.getAndSet(false)) onPlayoutLost()
            return
        } finally {
            if (audioTrack === track) audioTrack = null
            runCatching {
                track.stop()
                track.flush()
                track.release()
            }
        }
    }

    private fun writeFully(track: AudioTrack, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size && running.get()) {
            val written = track.write(pcm, offset, pcm.size - offset)
            if (written <= 0) throw IllegalStateException("AudioTrack write=$written")
            offset += written
        }
    }

    private companion object {
        const val TAG = "PTT_OUT"
        const val TRACK_PACKETS = 8
    }
}
