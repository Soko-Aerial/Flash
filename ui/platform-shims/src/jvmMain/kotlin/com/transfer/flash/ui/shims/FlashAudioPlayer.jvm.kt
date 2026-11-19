package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.jcodec.codecs.aac.AACDecoder
import org.jcodec.common.Codec
import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.MP4Demuxer

@Composable
public actual fun rememberFlashAudioPlayer(uri: String?): FlashAudioPlayer? {
    val player = remember(uri) { uri?.let(::JvmAudioPlayer) }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    return player
}

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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val playbackLock = Any()
    private var released = false

    @Volatile
    private var clip: Clip? = null

    @Volatile
    private var basePcm: DecodedPcm? = null

    @Volatile
    private var failed = false

    @Volatile
    private var pendingPlay = false

    @Volatile
    private var pendingSeekMs: Long? = null

    @Volatile
    private var playbackSpeed = 1.0f

    private var speedGeneration = 0L

    /**
     * True while a preparation is in flight. Set synchronously by whoever starts one, so `play()`
     * never starts a second concurrent decode (two threads preparing the same clip used to assign
     * `clip` twice and leak the first clip).
     */
    @Volatile
    private var preparing = true

    init {
        // O05-02: Pre-prepare audio asynchronously on Dispatchers.IO to avoid blocking the UI thread on software AAC decode
        scope.launch { finishPrepare(ensurePrepared()) }
    }

    private fun finishPrepare(prepared: Clip?): Unit = synchronized(playbackLock) {
        preparing = false
        if (released) return@synchronized
        val activeSpeed = playbackSpeed
        val seekTarget = pendingSeekMs
        if (seekTarget != null && prepared != null) {
            pendingSeekMs = null
            applySeekLocked(prepared, seekTarget, activeSpeed)
        }
        // A tap that landed during preparation starts playback here instead of on the UI thread.
        if (pendingPlay) {
            pendingPlay = false
            prepared?.let(::startClip)
        }
    }

    private fun openClip(stream: AudioInputStream): Clip? {
        val prepared = AudioSystem.getClip()
        try {
            prepared.open(stream)
            synchronized(playbackLock) {
                if (!released) {
                    clip = prepared
                    return prepared
                }
            }
        } catch (e: Exception) {
            runCatching { prepared.close() }
            throw e
        }
        prepared.close()
        return null
    }

    private fun openPcmClip(pcm: DecodedPcm): Clip? {
        val format = AudioFormat(
            pcm.sampleRateHz.toFloat(),
            16,
            pcm.channels,
            true,
            pcm.bigEndian,
        )
        return AudioInputStream(
            ByteArrayInputStream(pcm.bytes),
            format,
            pcm.bytes.size.toLong() / format.frameSize,
        ).use { stream ->
            openClip(stream)
        }
    }

    private fun startClip(c: Clip) {
        runCatching {
            // A finished clip parks at its end and `start()` would be a no-op; Android's MediaPlayer
            // restarts from the beginning instead, so match that.
            if (c.microsecondPosition >= c.microsecondLength) c.microsecondPosition = 0L
            if (!c.isRunning) c.start()
        }
    }

    private fun applySeekLocked(c: Clip, logicalMs: Long, speed: Float) {
        val scaledUs = ((logicalMs.coerceAtLeast(0L) / speed.toDouble()) * 1000.0)
            .roundToLong()
            .coerceIn(0L, c.microsecondLength.coerceAtLeast(0L))
        runCatching { c.microsecondPosition = scaledUs }
    }

    private fun currentLogicalPositionMsLocked(): Long {
        val c = clip ?: return pendingSeekMs ?: 0L
        val rawUs = runCatching { c.microsecondPosition }.getOrDefault(0L)
        return ((rawUs / 1000.0) * playbackSpeed.toDouble()).roundToLong().coerceAtLeast(0L)
    }

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
        val activeSpeed = synchronized(playbackLock) { playbackSpeed }
        // Tier 1: JDK-native containers (decoded to PCM so 1x/1.5x/2x time-scaling works uniformly).
        val tier1Result = runCatching {
            val pcm = decodeJdkStreamToPcm(file)
            if (pcm != null) {
                synchronized(playbackLock) { basePcm = pcm }
                return openPcmClip(timeScalePcm(pcm, activeSpeed))
            }
            return AudioSystem.getAudioInputStream(file).use(::openClip)
        }
        tier1Result.exceptionOrNull()?.let { tier1 ->
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
            synchronized(playbackLock) { basePcm = pcm }
            val scaled = timeScalePcm(pcm, activeSpeed)
            openPcmClip(scaled)
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

    override fun play(): Unit = synchronized(playbackLock) {
        if (released || failed) return@synchronized
        clip?.let { c ->
            val seekTarget = pendingSeekMs
            if (seekTarget != null) {
                pendingSeekMs = null
                applySeekLocked(c, seekTarget, playbackSpeed)
            }
            startClip(c)
            return@synchronized
        }
        // Not ready yet (still decoding, or rebuilding for speed change): let preparation start playback
        // when it finishes instead of blocking the UI thread here.
        pendingPlay = true
        if (preparing) return@synchronized
        preparing = true
        scope.launch { finishPrepare(ensurePrepared()) }
    }

    override fun pause(): Unit = synchronized(playbackLock) {
        pendingPlay = false
        runCatching { clip?.let { if (it.isRunning) it.stop() } }
    }

    override fun seekTo(ms: Long): Unit = synchronized(playbackLock) {
        val clamped = ms.coerceAtLeast(0L)
        val c = clip
        if (c == null) {
            pendingSeekMs = clamped
            return@synchronized
        }
        pendingSeekMs = null
        applySeekLocked(c, clamped, playbackSpeed)
    }

    /**
     * Applies pitch-preserving time-scaled playback speed (1x / 1.5x / 2x) over the decoded PCM stream.
     */
    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        val gen = synchronized(playbackLock) {
            if (released || abs(playbackSpeed - clamped) < 0.01f) return
            playbackSpeed = clamped
            val pcm = basePcm ?: return
            if (failed) return
            val logicalPos = currentLogicalPositionMsLocked()
            val wasPlaying = runCatching { clip?.isRunning == true }.getOrDefault(false) || pendingPlay
            pendingSeekMs = logicalPos
            pendingPlay = wasPlaying
            val oldClip = clip.also { clip = null }
            runCatching {
                oldClip?.stop()
                oldClip?.close()
            }
            preparing = true
            ++speedGeneration
        }
        scope.launch {
            val pcm = synchronized(playbackLock) { basePcm } ?: return@launch
            val activeSpeed = synchronized(playbackLock) { playbackSpeed }
            val scaled = timeScalePcm(pcm, activeSpeed)
            val newClip = runCatching { openPcmClip(scaled) }.getOrNull()
            synchronized(playbackLock) {
                if (released || speedGeneration != gen) {
                    runCatching { newClip?.close() }
                    return@synchronized
                }
                if (newClip == null) {
                    failed = true
                }
                clip = newClip
                finishPrepare(newClip)
            }
        }
    }

    override fun positionMs(): Long = synchronized(playbackLock) { currentLogicalPositionMsLocked() }

    override fun isPlaying(): Boolean = runCatching { clip?.isRunning == true }.getOrDefault(false)

    override fun release() {
        val oldClip = synchronized(playbackLock) {
            released = true
            pendingPlay = false
            pendingSeekMs = null
            basePcm = null
            clip.also { clip = null }
        }
        scope.cancel()
        runCatching { oldClip?.close() }
    }
}

/** `content://` has no desktop meaning; `file:` URIs and bare paths both resolve. */
internal fun resolveFile(uri: String): File? = when {
    uri.startsWith("content://") -> null
    uri.startsWith("file:", ignoreCase = true) -> runCatching {
        try {
            File(URI(uri))
        } catch (_: Exception) {
            try {
                File(URI(uri.replace(" ", "%20")))
            } catch (_: Exception) {
                var clean = uri.replaceFirst(Regex("^file:", RegexOption.IGNORE_CASE), "")
                if (clean.startsWith("///")) {
                    clean = clean.substring(2)
                } else if (clean.startsWith("//")) {
                    clean = clean.substring(1)
                }
                if (clean.matches(Regex("^/[A-Za-z]:.*"))) {
                    clean = clean.substring(1)
                }
                val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
                File(decoded)
            }
        }
    }.getOrNull()
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

/**
 * Decodes a JDK-supported sampled audio file (WAV/AU/AIFF) into 16-bit signed PCM up to [MAX_PCM_BYTES].
 * Propagates [UnsupportedAudioFileException] so Tier 2 can fall back to JCodec AAC decode.
 */
internal fun decodeJdkStreamToPcm(file: File): DecodedPcm? {
    AudioSystem.getAudioInputStream(file).use { rawStream ->
        val fmt = rawStream.format
        val sampleRate = fmt.sampleRate.takeIf { it > 0f } ?: return null
        val channels = fmt.channels.takeIf { it > 0 } ?: return null
        val pcmFormat = if (
            fmt.encoding == AudioFormat.Encoding.PCM_SIGNED &&
            fmt.sampleSizeInBits == 16
        ) {
            fmt
        } else {
            AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false,
            )
        }
        val pcmStream = if (pcmFormat == fmt) rawStream else AudioSystem.getAudioInputStream(pcmFormat, rawStream)
        pcmStream.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                if (out.size() + read > MAX_PCM_BYTES) return null
                out.write(buf, 0, read)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return null
            return DecodedPcm(
                bytes = bytes,
                sampleRateHz = sampleRate.roundToInt(),
                channels = channels,
                bigEndian = pcmFormat.isBigEndian,
            )
        }
    }
}

/**
 * Pitch-preserving WSOLA overlap-add time-scaling for 16-bit signed interleaved PCM.
 * Speed > 1.0 compresses duration by `1 / speed` (e.g., 1.5x, 2.0x) while preserving pitch.
 */
internal fun timeScalePcm(pcm: DecodedPcm, speed: Float): DecodedPcm {
    val clampedSpeed = speed.coerceIn(0.5f, 3.0f)
    if (clampedSpeed in 0.99f..1.01f || pcm.bytes.isEmpty() || pcm.channels <= 0 || pcm.sampleRateHz <= 0) {
        return pcm
    }
    val channels = pcm.channels
    val frameBytes = channels * 2
    val totalFrames = pcm.bytes.size / frameBytes
    if (totalFrames < 128) return pcm

    val input = ShortArray(totalFrames * channels)
    val bytes = pcm.bytes
    if (pcm.bigEndian) {
        for (i in input.indices) {
            val b = i * 2
            input[i] = ((bytes[b].toInt() shl 8) or (bytes[b + 1].toInt() and 0xFF)).toShort()
        }
    } else {
        for (i in input.indices) {
            val b = i * 2
            input[i] = ((bytes[b + 1].toInt() shl 8) or (bytes[b].toInt() and 0xFF)).toShort()
        }
    }

    val winFrames = ((pcm.sampleRateHz * 0.024).roundToInt() and 1.inv()).coerceIn(64, (totalFrames / 2).coerceAtLeast(64))
    val synHop = (winFrames / 2).coerceAtLeast(16)
    val searchRadius = (pcm.sampleRateHz * 0.006).roundToInt().coerceIn(0, synHop / 2)
    val targetOutFrames = ((totalFrames / clampedSpeed.toDouble()).roundToInt()).coerceAtLeast(winFrames)
    val maxOutFrames = targetOutFrames + winFrames
    if (maxOutFrames.toLong() * frameBytes > MAX_PCM_BYTES) return pcm

    val output = ShortArray(maxOutFrames * channels)
    val firstCopy = minOf(winFrames, totalFrames)
    System.arraycopy(input, 0, output, 0, firstCopy * channels)

    var outPos = synHop
    var anaPos = synHop * clampedSpeed.toDouble()
    val corrStep = (synHop / 24).coerceAtLeast(1)

    while (anaPos.roundToInt() + winFrames <= totalFrames && outPos + winFrames <= maxOutFrames) {
        val nominal = anaPos.roundToInt()
        val bestOffset = if (searchRadius > 0) {
            var bestDelta = 0
            var bestScore = Long.MIN_VALUE
            val minDelta = maxOf(-searchRadius, -nominal)
            val maxDelta = minOf(searchRadius, totalFrames - winFrames - nominal)
            for (delta in minDelta..maxDelta) {
                val candStart = nominal + delta
                var score = 0L
                var i = 0
                while (i < synHop) {
                    val outIdx = (outPos + i) * channels
                    val inIdx = (candStart + i) * channels
                    score += output[outIdx].toLong() * input[inIdx].toLong()
                    i += corrStep
                }
                if (score > bestScore) {
                    bestScore = score
                    bestDelta = delta
                }
            }
            bestDelta
        } else {
            0
        }

        val srcStart = (nominal + bestOffset).coerceIn(0, totalFrames - winFrames)
        for (i in 0 until synHop) {
            val wIn = i.toFloat() / synHop.toFloat()
            val wOut = 1f - wIn
            val outBase = (outPos + i) * channels
            val inBase = (srcStart + i) * channels
            for (ch in 0 until channels) {
                val blended = (output[outBase + ch] * wOut + input[inBase + ch] * wIn).roundToInt()
                output[outBase + ch] = blended.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        val tailFrames = winFrames - synHop
        System.arraycopy(input, (srcStart + synHop) * channels, output, (outPos + synHop) * channels, tailFrames * channels)

        outPos += synHop
        anaPos += synHop * clampedSpeed.toDouble()
    }

    val finalFrames = (outPos + synHop).coerceAtMost(maxOutFrames)
    val outBytes = ByteArray(finalFrames * frameBytes)
    if (pcm.bigEndian) {
        for (i in 0 until finalFrames * channels) {
            val v = output[i].toInt()
            val b = i * 2
            outBytes[b] = (v shr 8).toByte()
            outBytes[b + 1] = (v and 0xFF).toByte()
        }
    } else {
        for (i in 0 until finalFrames * channels) {
            val v = output[i].toInt()
            val b = i * 2
            outBytes[b] = (v and 0xFF).toByte()
            outBytes[b + 1] = (v shr 8).toByte()
        }
    }
    return pcm.copy(bytes = outBytes)
}
