package com.transfer.flash.ui.chat

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * B9: thin [MediaPlayer] wrapper backing real voice-note playback in [FlashVoiceMessageCard].
 *
 * Lazily prepares from a local `file://`/`content://` URI on first [play]. All calls are guarded so
 * a malformed/incomplete recording degrades to "nothing plays" rather than crashing the bubble.
 * Not thread-safe — drive from the composition's main thread.
 */
class FlashAudioPlayer(
    private val context: Context,
    private val uri: String,
) {
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSpeed = 1.0f

    private fun ensurePrepared(): MediaPlayer? {
        player?.let { return it }
        return try {
            MediaPlayer().apply {
                setDataSource(context, Uri.parse(uri))
                prepare() // local file → cheap synchronous prepare
                prepared = true
                player = this
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Voice playback failed to prepare", t)
            runCatching { player?.release() }
            player = null
            prepared = false
            null
        }
    }

    fun play() {
        val p = ensurePrepared() ?: return
        runCatching {
            setSpeed(pendingSpeed)
            if (!p.isPlaying) p.start()
        }
    }

    fun pause() {
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms.toInt()) }
    }

    /** Playback speed (1x/1.5x/2x). Requires API 23+ (minSdk 24) — always available here. */
    fun setSpeed(speed: Float) {
        pendingSpeed = speed
        val p = player ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val wasPlaying = p.isPlaying
                p.playbackParams = p.playbackParams.setSpeed(speed)
                // Setting params auto-starts on some devices; honor the prior play/pause state.
                if (!wasPlaying && p.isPlaying) p.pause()
            }
        }
    }

    fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    private companion object {
        const val TAG = "FlashAudioPlayer"
    }
}
