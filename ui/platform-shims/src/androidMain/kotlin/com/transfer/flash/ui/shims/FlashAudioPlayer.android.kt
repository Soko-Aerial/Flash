package com.transfer.flash.ui.shims

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
public actual fun rememberFlashAudioPlayer(uri: String?): FlashAudioPlayer? {
    val context = LocalContext.current
    return remember(context, uri) {
        if (uri != null) AndroidAudioPlayer(context, uri) else null
    }
}

/**
 * B9: thin [MediaPlayer] wrapper backing real voice-note playback in `FlashVoiceMessageCard`.
 *
 * Moved here from `:ui:chat` by Phase 19 with no behaviour change — same lazy prepare, same guards,
 * same log tag. Only the class name (it now implements the common [FlashAudioPlayer]) and its
 * visibility differ; the constructor stays `(Context, String)`, which is why the seam is a factory and
 * not an `expect class`.
 *
 * Lazily prepares from a local `file://`/`content://` URI — or a bare filesystem path, which is what
 * a *received* note is (the transfer layer reports where it wrote the file, not a URI) — on first
 * [play]. All calls are guarded so a malformed/incomplete recording degrades to "nothing plays"
 * rather than crashing the bubble. Not thread-safe — drive from the composition's main thread.
 */
private class AndroidAudioPlayer(
    private val context: Context,
    private val uri: String,
) : FlashAudioPlayer {
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSpeed = 1.0f

    private fun ensurePrepared(): MediaPlayer? {
        player?.let { return it }
        return try {
            MediaPlayer().apply {
                // Uri.parse on "/storage/…/Voice message.m4a" yields a scheme-less URI, which only
                // reaches the media server through setDataSource(Uri)'s undocumented last-ditch
                // fallback. Pick the overload by shape instead of relying on that.
                if (uri.startsWith("content://") || uri.startsWith("file://")) {
                    setDataSource(context, Uri.parse(uri))
                } else {
                    setDataSource(uri)
                }
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

    override fun play() {
        val p = ensurePrepared() ?: return
        runCatching {
            setSpeed(pendingSpeed)
            if (!p.isPlaying) p.start()
        }
    }

    override fun pause() {
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    override fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms.toInt()) }
    }

    /** Playback speed (1x/1.5x/2x). Requires API 23+ (minSdk 24) — always available here. */
    override fun setSpeed(speed: Float) {
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

    override fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }

    private companion object {
        const val TAG = "FlashAudioPlayer"
    }
}
