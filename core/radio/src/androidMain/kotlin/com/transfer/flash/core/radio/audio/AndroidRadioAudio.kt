package com.transfer.flash.core.radio.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays the radio's audio through an [AudioTrack] (32 kHz mono PCM16). Never blocks the caller: a write that does not
 * fit in the roughly 800 ms buffer is dropped, so a stalled speaker cannot back the radio link up. About 60 ms is
 * buffered before playback starts (and again after a pause in the audio) so the first words are not chopped.
 */
public class AndroidRadioPcmOutput : RadioPcmOutput {
    private var track: AudioTrack? = null
    private var playing = false
    private var primed = 0
    private var lastWriteAt = 0L

    override fun start(sampleRate: Int) {
        stop()
        val bytes = sampleRate * 2 * BUFFER_MS / 1000
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(bytes, min))
            .build()
        playing = false
        primed = 0
        preRollSamples = sampleRate * PREROLL_MS / 1000
    }

    private var preRollSamples = 1920

    override fun write(pcm: ShortArray, length: Int) {
        val t = track ?: return
        val now = SystemClock.elapsedRealtime()
        if (playing && now - lastWriteAt > GAP_RESET_MS) {
            runCatching { t.pause(); t.flush() }
            playing = false
            primed = 0
        }
        lastWriteAt = now
        val written = t.write(pcm, 0, length, AudioTrack.WRITE_NON_BLOCKING)
        if (written > 0 && !playing) {
            primed += written
            if (primed >= preRollSamples) {
                runCatching { t.play() }
                playing = true
            }
        }
    }

    override fun stop() {
        val t = track ?: return
        track = null
        playing = false
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    private companion object {
        const val BUFFER_MS = 800
        const val PREROLL_MS = 60
        const val GAP_RESET_MS = 300L
    }
}

/**
 * Captures the microphone for transmitting. Uses the plain MIC source, not VOICE_COMMUNICATION: that source opened and
 * then delivered silence on a Transsion handset (see the WebRTC capture-source notes). Tries 32, 16, 44.1 then 48 kHz;
 * the session resamples to 32 kHz. The caller must hold RECORD_AUDIO.
 */
public class AndroidRadioPcmInput : RadioPcmInput {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // the host checks RECORD_AUDIO before it offers push-to-talk
    override fun start(): Int {
        stop()
        for (rate in RATES) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, rate / 5 * 2),
                )
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                continue
            }
            r.startRecording()
            record = r
            return rate
        }
        throw IllegalStateException("The microphone could not be opened.")
    }

    override suspend fun read(buffer: ShortArray): Int {
        val r = record ?: return -1
        val n = withContext(Dispatchers.IO) { r.read(buffer, 0, buffer.size) }
        return if (n < 0) -1 else n
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    private companion object {
        val RATES = intArrayOf(32_000, 16_000, 44_100, 48_000)
    }
}
