package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Voice-note playback for a single source. Not thread-safe — drive from the composition's main thread.
 *
 * Every method is failure-tolerant by contract: a malformed or incomplete recording must degrade to
 * "nothing plays" rather than crash the bubble that owns it.
 */
public interface FlashAudioPlayer {
    /** Prepares on first call, then starts (or resumes) at the currently selected speed. */
    public fun play()
    public fun pause()
    public fun seekTo(ms: Long)
    /** Playback speed — the UI offers 1x / 1.5x / 2x. Remembered and re-applied on the next [play]. */
    public fun setSpeed(speed: Float)
    public fun positionMs(): Long
    public fun isPlaying(): Boolean
    /** Releases the platform decoder. The instance is dead afterwards; obtain a new one. */
    public fun release()
}

/**
 * The voice-playback seam.
 *
 * PHASE-19 specifies `expect class FlashAudioPlayer`. It cannot be one: an `expect class` forces every
 * actual to share the *same* constructor signature, and the Android implementation needs a `Context`
 * that `commonMain` cannot name and desktop has no analogue for. So the type is a plain common
 * interface and the platform detail lives in this factory.
 *
 * @param uri the source to play — `content://`, `file://` or a bare filesystem path (which is what a
 *   *received* note is: the transfer layer reports where it wrote the file, not a URI). Null returns
 *   null, so a caller can express "no audio yet" without a conditional `@Composable` call, which
 *   Compose forbids. That is exactly how `FlashVoiceMessageCard` uses it: an attachment whose transfer
 *   has not finished has no player.
 *
 * Disposal is **not** handled here. The caller owns it via `DisposableEffect(player) { onDispose { … } }`
 * — which is what already releases the previous instance when [uri] changes, and would double-release
 * if this factory also cleaned up.
 */
@Composable
public expect fun rememberFlashAudioPlayer(uri: String?): FlashAudioPlayer?
