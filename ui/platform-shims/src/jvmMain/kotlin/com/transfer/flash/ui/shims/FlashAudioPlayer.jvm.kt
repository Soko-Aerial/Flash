package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.net.URI
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

@Composable
public actual fun rememberFlashAudioPlayer(uri: String?): FlashAudioPlayer? =
    remember(uri) { if (uri != null) JvmAudioPlayer(uri) else null }

/**
 * `javax.sound.sampled.Clip`.
 *
 * **What this can and cannot play, stated plainly.** The JDK's sampled-audio SPI decodes WAV, AU and
 * AIFF. It does **not** decode AAC, and Flash voice notes are AAC in an MP4 container (`.m4a`) — see
 * `FlashVoiceRecorder`'s Android actual. So on desktop today a Flash voice note does not play: the
 * source fails to open and every method degrades to "nothing plays", which is the same contract the
 * Android implementation offers for a malformed recording, and the same outcome the bubble already
 * renders for a note whose transfer has not finished.
 *
 * This is a platform capability gap, not a stub to force a compile (R2): the implementation is real
 * and plays every format the JDK supports, which is what makes it useful the moment desktop capture or
 * an AAC decoder library exists. Adding that library is a dependency decision (R10) and belongs to a
 * phase with a human answer behind it — it is on the backlog with desktop sound playback from Phase 18.
 *
 * Not thread-safe — drive from the composition's main thread, as on Android.
 */
/**
 * `internal` for the same reason [JvmImageDecoder] is: the public factory is `@Composable`, the repo has
 * no Compose UI-test harness, and R3.1 does not accept "it compiled" as verification. `jvmTest`
 * constructs this directly to prove the degrade-to-silence path never throws.
 */
internal class JvmAudioPlayer(private val uri: String) : FlashAudioPlayer {
    private var clip: Clip? = null
    private var failed = false

    private fun ensurePrepared(): Clip? {
        clip?.let { return it }
        // One attempt only. An unsupported container fails deterministically, and retrying on every
        // frame of a progress animation would re-open the file dozens of times a second.
        if (failed) return null
        val file = resolveFile(uri)
        if (file == null) {
            failed = true
            return null
        }
        return try {
            AudioSystem.getAudioInputStream(file).use { stream ->
                AudioSystem.getClip().apply {
                    open(stream)
                    clip = this
                }
            }
        } catch (t: Throwable) {
            // `println`, not a logging framework: PHASE-19 forbids `android.util.Log` here, and
            // `:ui:platform-shims` has no logger dependency. `:core:common`'s FlashLog is not on this
            // module's classpath and adding it to reach one warning is not worth the edge.
            println("[FlashAudioPlayer] Voice playback failed to prepare: $t")
            runCatching { clip?.close() }
            clip = null
            failed = true
            null
        }
    }

    override fun play() {
        val c = ensurePrepared() ?: return
        runCatching {
            // A finished clip parks at its end and `start()` would be a no-op; Android's MediaPlayer
            // restarts from the beginning instead, so match that.
            if (c.microsecondPosition >= c.microsecondLength) c.microsecondPosition = 0L
            if (!c.isRunning) c.start()
        }
    }

    override fun pause() {
        runCatching { clip?.let { if (it.isRunning) it.stop() } }
    }

    override fun seekTo(ms: Long) {
        runCatching { clip?.microsecondPosition = ms.coerceAtLeast(0L) * 1000L }
    }

    /**
     * No-op. `Clip` has no playback-rate control — changing it means resampling the stream, which the
     * sampled SPI does not do — so the 1x/1.5x/2x selector has no effect on desktop. Silently
     * accepting the call keeps the shared UI's speed control from having to know which platform it is
     * on; nothing about playback correctness depends on it.
     */
    override fun setSpeed(speed: Float) {
        // Intentionally empty.
    }

    override fun positionMs(): Long =
        runCatching { (clip?.microsecondPosition ?: 0L) / 1000L }.getOrDefault(0L)

    override fun isPlaying(): Boolean = runCatching { clip?.isRunning == true }.getOrDefault(false)

    override fun release() {
        runCatching {
            clip?.let {
                it.stop()
                it.close()
            }
        }
        clip = null
    }
}

/** `content://` has no desktop meaning; `file:` URIs and bare paths both resolve. */
internal fun resolveFile(uri: String): File? = when {
    uri.startsWith("content://") -> null
    uri.startsWith("file:") -> runCatching { File(URI(uri)) }.getOrNull()
    else -> File(uri)
}?.takeIf { it.isFile && it.length() > 0L }
