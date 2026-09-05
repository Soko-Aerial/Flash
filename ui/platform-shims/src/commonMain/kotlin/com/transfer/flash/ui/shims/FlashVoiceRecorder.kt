package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Microphone capture for the composer's press-and-hold voice recorder.
 *
 * Not thread-safe — call from the main thread alongside the composer's gesture callbacks.
 */
public interface FlashVoiceRecorder {
    /** True while a capture session is live (between [start] and [stop]/[cancel]). */
    public val isRecording: Boolean

    /**
     * Begins capturing to a fresh temporary file. Returns false (and leaves nothing running) if
     * capture cannot start — the caller must treat that as "recording did not start". Assumes the
     * microphone permission is already granted; the host checks it first via [FlashPermissionRequester].
     */
    public fun start(): Boolean

    /** Current peak amplitude scaled to the waveform's 0..100 range; 0 when not recording. */
    public fun maxAmplitude(): Int

    /**
     * Stops capture and returns a `file:` URI to the recording, or null if nothing usable was written
     * (start never succeeded, or the encoder produced an empty/failed file).
     */
    public fun stop(): String?

    /** Aborts capture and deletes the partial file (slide-to-cancel / too-short). */
    public fun cancel()
}

/**
 * The voice-capture seam.
 *
 * As with [rememberFlashAudioPlayer], PHASE-19 specifies `expect class FlashVoiceRecorder` and it
 * cannot be one — the Android implementation's constructor takes the `Context` whose `cacheDir` it
 * writes into, and `expect class` requires every actual to share that signature.
 *
 * The caller owns disposal: `DisposableEffect(recorder) { onDispose { recorder.cancel() } }`, so
 * leaving the screen mid-hold discards the partial file rather than leaking an open encoder.
 */
@Composable
public expect fun rememberFlashVoiceRecorder(): FlashVoiceRecorder
