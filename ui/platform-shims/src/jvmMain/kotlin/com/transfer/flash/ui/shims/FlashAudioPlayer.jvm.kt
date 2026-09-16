package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.UnsupportedAudioFileException
import org.jcodec.codecs.aac.AACDecoder
import org.jcodec.common.Codec
import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.MP4Demuxer

@Composable
public actual fun rememberFlashAudioPlayer(uri: String?): FlashAudioPlayer? =
    remember(uri) { if (uri != null) JvmAudioPlayer(uri) else null }

/**
 * `javax.sound.sampled.Clip`, with a pure-Java AAC fallback for voice notes.
 *
 * Two tiers, in order:
 * 1. The JDK's sampled-audio SPI (WAV/AU/AIFF) — zero dependencies, hardware-mixed.
 * 2. JCodec (`org.jcodec:jcodec`, BSD-2-Clause): MP4 demux + AAC decode to PCM for `.m4a`
 *    voice notes (AAC/MPEG-4, see `FlashVoiceRecorder`'s Android actual), which the JDK
 *    rejects with `UnsupportedAudioFileException`. The PCM is fed to the same `Clip`, so
 *    play/pause/seek/position semantics are identical on both tiers.
 *
 * Anything else (missing file, non-audio container, non-AAC track, corrupt frames) degrades
 * to "nothing plays" without throwing — the same contract as the Android implementation for
 * a malformed recording.
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
        // Tier 1: JDK-native containers.
        runCatching {
            AudioSystem.getAudioInputStream(file).use { stream ->
                AudioSystem.getClip().apply {
                    open(stream)
                    clip = this
                }
            }
            return clip
        }.exceptionOrNull()?.let { tier1 ->
            // Tier 2: AAC-in-MP4 voice notes via JCodec — but ONLY for the JDK's "I don't know
            // this container" signal. Any other failure (missing mixer/headless CI, IO error) is
            // not a codec problem and decoding would fail the same way downstream at Clip.open.
            if (tier1 !is UnsupportedAudioFileException) {
                println("[FlashAudioPlayer] Voice playback failed to prepare: $tier1")
                runCatching { clip?.close() }
                clip = null
                failed = true
                return null
            }
        }
        return runCatching {
            val pcm = decodeAacM4aToPcm(file) ?: run {
                failed = true
                return null
            }
            val format = AudioFormat(
                pcm.sampleRateHz.toFloat(),
                16,
                pcm.channels,
                true,
                pcm.bigEndian,
            )
            AudioInputStream(
                ByteArrayInputStream(pcm.bytes),
                format,
                pcm.bytes.size.toLong() / format.frameSize,
            ).use { stream ->
                AudioSystem.getClip().apply {
                    open(stream)
                    clip = this
                }
            }
            println(
                "[FlashAudioPlayer] AAC voice note decoded via JCodec: " +
                    "${pcm.bytes.size} bytes PCM ${pcm.sampleRateHz}Hz ${pcm.channels}ch",
            )
            clip
        }.getOrElse { t ->
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

/** Decoded voice-note PCM: 16-bit signed, interleaved, `bigEndian` as flagged. */
internal data class DecodedPcm(
    val bytes: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
    val bigEndian: Boolean,
)

/** Refuse absurd outputs before they become heap: a voice note never decodes to this. */
private const val MAX_PCM_BYTES = 32 * 1024 * 1024

/**
 * Demuxes the first audio track of an MP4/M4A file and AAC-decodes it to PCM.
 *
 * Fail-closed by construction: not-an-MP4, no audio track, non-AAC track, missing decoder
 * config, corrupt frames, non-16-bit output, or an over-large result all yield null (the
 * caller then renders "nothing plays"). Never throws.
 */
internal fun decodeAacM4aToPcm(file: File): DecodedPcm? = runCatching {
    NIOUtils.readableChannel(file).use { channel ->
        val demuxer = MP4Demuxer.createMP4Demuxer(channel)
        val track = demuxer.audioTracks.firstOrNull() ?: return null
        if (track.meta.codec != Codec.AAC) return null
        val esds = track.meta.codecPrivate ?: return null
        val decoder = AACDecoder(esds)
        // One AAC frame is 1024 samples; 64K holds the largest legal frame (8ch S16) 4x over.
        // Reused across frames: decodeFrame writes from the buffer's position, so clear first.
        val out = ByteBuffer.allocate(65536)
        val pcm = ByteArrayOutputStream()
        var sampleRateHz = 0
        var channels = 0
        var bigEndian = false
        var sawAudio = false
        while (true) {
            val packet = track.nextFrame() ?: break
            val data = packet.data ?: continue
            if (!data.hasRemaining()) continue
            out.clear()
            val decoded = decoder.decodeFrame(data, out) ?: continue
            val format = decoded.format ?: continue
            if (format.sampleSizeInBits != 16) return null
            if (sawAudio && (format.sampleRate != sampleRateHz || format.channels != channels)) {
                // Mid-stream format change: refuse rather than splice mismatched PCM.
                return null
            }
            sampleRateHz = format.sampleRate
            channels = format.channels
            bigEndian = format.isBigEndian
            sawAudio = true
            val bytes = ByteArray(decoded.data.remaining())
            decoded.data.get(bytes)
            if (pcm.size() + bytes.size > MAX_PCM_BYTES) return null
            pcm.write(bytes)
        }
        if (!sawAudio || sampleRateHz <= 0 || channels <= 0) return null
        DecodedPcm(pcm.toByteArray(), sampleRateHz, channels, bigEndian)
    }
}.getOrNull()
