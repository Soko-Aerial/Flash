package com.transfer.flash.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

/**
 * B9: real microphone capture backing the composer's press-and-hold voice recorder.
 *
 * Wraps a single [MediaRecorder] writing AAC/MPEG-4 audio into the app cache. The composer drives
 * the gesture + UI; this owns the encoder lifecycle and exposes [maxAmplitude] so the on-screen
 * waveform reflects real loudness instead of the previous demo-mode fake.
 *
 * Not thread-safe — call from the main thread alongside the composer gesture callbacks.
 */
class FlashVoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** True while a capture session is live (between [start] and [stop]/[cancel]). */
    val isRecording: Boolean get() = recorder != null

    /**
     * Begins capturing to a fresh cache file. Returns false (and leaves nothing running) if the
     * encoder cannot be prepared — caller should treat that as "recording did not start".
     * Assumes RECORD_AUDIO is already granted; the host checks/requests permission first.
     */
    fun start(): Boolean {
        stopQuietly()
        val file = File(context.cacheDir, "flash-voice-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Voice capture failed to start", t)
            runCatching { rec.release() }
            file.delete()
            recorder = null
            outputFile = null
            false
        }
    }

    /** Current peak amplitude scaled to the waveform's 0..100 range; 0 when not recording. */
    fun maxAmplitude(): Int {
        val rec = recorder ?: return 0
        return try {
            // MediaRecorder.getMaxAmplitude() peaks near 32767 (16-bit PCM).
            ((rec.maxAmplitude / 32767f) * 100f).toInt().coerceIn(0, 100)
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Stops capture and returns a `file://` URI to the recording, or null if nothing usable was
     * written (start never succeeded, or the encoder produced an empty/failed file).
     */
    fun stop(): String? {
        val rec = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (rec == null) return null
        return try {
            rec.stop()
            rec.release()
            if (file != null && file.exists() && file.length() > 0L) {
                Uri.fromFile(file).toString()
            } else {
                file?.delete()
                null
            }
        } catch (t: Throwable) {
            // stop() throws if stopped too quickly (no frames) — discard the partial file.
            Log.w(TAG, "Voice capture stop failed", t)
            runCatching { rec.release() }
            file?.delete()
            null
        }
    }

    /** Aborts capture and deletes the partial file (slide-to-cancel / too-short). */
    fun cancel() {
        val file = outputFile
        stopQuietly()
        file?.delete()
    }

    private fun stopQuietly() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    private companion object {
        const val TAG = "FlashVoiceRecorder"
    }
}
