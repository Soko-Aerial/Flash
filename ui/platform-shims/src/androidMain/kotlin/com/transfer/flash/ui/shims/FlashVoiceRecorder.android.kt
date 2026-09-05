package com.transfer.flash.ui.shims

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
public actual fun rememberFlashVoiceRecorder(): FlashVoiceRecorder {
    val context = LocalContext.current
    return remember(context) { AndroidVoiceRecorder(context) }
}

/**
 * B9: real microphone capture backing the composer's press-and-hold voice recorder.
 *
 * Moved here from `:ui:chat` by Phase 19 with no behaviour change — same encoder settings (AAC/MPEG-4,
 * 64 kbps, 44.1 kHz), same cache-file naming, same guards, same log tag.
 *
 * Wraps a single [MediaRecorder] writing AAC/MPEG-4 audio into the app cache. The composer drives
 * the gesture + UI; this owns the encoder lifecycle and exposes [maxAmplitude] so the on-screen
 * waveform reflects real loudness instead of the previous demo-mode fake.
 *
 * Not thread-safe — call from the main thread alongside the composer gesture callbacks.
 */
private class AndroidVoiceRecorder(private val context: Context) : FlashVoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override val isRecording: Boolean get() = recorder != null

    override fun start(): Boolean {
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

    override fun maxAmplitude(): Int {
        val rec = recorder ?: return 0
        return try {
            // MediaRecorder.getMaxAmplitude() peaks near 32767 (16-bit PCM).
            ((rec.maxAmplitude / 32767f) * 100f).toInt().coerceIn(0, 100)
        } catch (t: Throwable) {
            0
        }
    }

    override fun stop(): String? {
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

    override fun cancel() {
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
