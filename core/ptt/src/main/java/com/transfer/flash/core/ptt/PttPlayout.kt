package com.transfer.flash.core.ptt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.transfer.flash.core.messaging.ptt.PttAudioLevel
import com.transfer.flash.core.messaging.ptt.PttJitterBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

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
 */
public class PttPlayout(
    private val sampleRateHz: Int,
    private val packetMs: Int,
    targetDepthMs: Long = 120L,
    private val onAmplitude: (Float) -> Unit = {},
    private val onPlayoutLost: () -> Unit = {},
) {
    public data class Snapshot(
        val readyTotal: Long,
        val concealedTotal: Long,
        val depthPackets: Int,
        val amplitude01: Float,
    )

    private data class Incoming(val seq: Long, val captureTsMs: Long, val pcm: ByteArray)

    private val inbox = Channel<Incoming>(INBOX_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val buffer = PttJitterBuffer(targetDepthMs)
    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val concealedTotal = AtomicLong(0L)
    private val readyTotal = AtomicLong(0L)

    @Volatile
    private var lastDepthPackets: Int = 0

    @Volatile
    private var lastAmplitude: Float = 0f

    private val bytesPerPacket: Int = sampleRateHz * packetMs / 1000 * 2

    /** Non-blocking enqueue from any thread. Rejects packets outside the negotiated format. */
    public fun offer(seq: Long, captureTsMs: Long, pcm: ByteArray): Boolean {
        if (!running.get() || pcm.size != bytesPerPacket) return false
        return inbox.trySend(Incoming(seq, captureTsMs, pcm)).isSuccess
    }

    public fun snapshot(): Snapshot =
        Snapshot(readyTotal.get(), concealedTotal.get(), lastDepthPackets, lastAmplitude)

    /** Starts playout. False when the track cannot be built (caller ends the listen). */
    public fun start(): Boolean {
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
    public fun stop() {
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
        val zeros = ByteArray(bytesPerPacket)
        var last: ByteArray? = null
        try {
            while (running.get()) {
                var incoming = inbox.tryReceive().getOrNull()
                while (incoming != null) {
                    buffer.push(incoming.seq, incoming.captureTsMs, incoming.pcm)
                    incoming = inbox.tryReceive().getOrNull()
                }
                val level: Float
                when (val tick = buffer.poll(packetMs.toLong())) {
                    is PttJitterBuffer.Poll.Ready -> {
                        writeFully(track, tick.pcm)
                        last = tick.pcm
                        readyTotal.incrementAndGet()
                        level = PttAudioLevel.rms01(tick.pcm)
                    }
                    is PttJitterBuffer.Poll.Concealed -> {
                        val fill = last ?: zeros
                        writeFully(track, fill)
                        concealedTotal.incrementAndGet()
                        level = PttAudioLevel.rms01(fill) * 0.5f
                    }
                    is PttJitterBuffer.Poll.Starving -> {
                        writeFully(track, zeros)
                        level = 0f
                    }
                }
                lastAmplitude = level
                lastDepthPackets = buffer.stats().depthPackets
                onAmplitude(level)
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
        const val INBOX_CAPACITY = 64
        const val TRACK_PACKETS = 8
    }
}
