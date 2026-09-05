package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

@Composable
public actual fun rememberFlashVoiceRecorder(): FlashVoiceRecorder = remember { JvmVoiceRecorder() }

/**
 * `javax.sound.sampled.TargetDataLine` capture, written out as a WAV.
 *
 * **Why WAV and not the `.m4a` Android produces.** The JDK ships no AAC encoder, and adding one is a
 * dependency decision (R10) with no human answer behind it yet. WAV is the format the platform can
 * actually write, and it is not a dead end: Android's `MediaPlayer` plays PCM WAV, so a note recorded
 * on desktop plays on a phone, and this module's own `jvm` [FlashAudioPlayer] plays it back locally.
 * The asymmetry is the other direction — a note recorded on Android is AAC, which desktop cannot decode.
 * That gap is recorded in [rememberFlashAudioPlayer]'s `jvm` actual and on the backlog.
 *
 * 44.1 kHz / 16-bit / mono matches the Android encoder's sampling rate and bit depth, so the waveform
 * amplitudes the composer draws are on the same scale on both platforms.
 *
 * Not thread-safe from the caller's side — drive it from the main thread, as on Android. Internally one
 * daemon thread drains the line; [maxAmplitude] reads across that boundary through an [AtomicInteger].
 */
/**
 * `internal` rather than `private` so `jvmTest` can drive the paths that need no microphone — a `stop()`
 * with no capture running, a `cancel()` on a fresh instance — since the public factory is `@Composable`
 * and cannot be called from a plain JVM test.
 */
internal class JvmVoiceRecorder : FlashVoiceRecorder {

    private var line: TargetDataLine? = null
    private var drain: Thread? = null
    private var rawFile: File? = null
    private val peak = AtomicInteger(0)

    override val isRecording: Boolean get() = line != null

    override fun start(): Boolean {
        stopQuietly()
        peak.set(0)
        val raw = File(tempDir(), "flash-voice-${System.currentTimeMillis()}.pcm")
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            // Throws when no capture device exists — a headless CI box, or a desktop with no mic. The
            // composer treats `false` as "recording did not start", exactly as it does on Android when
            // the encoder cannot be prepared.
            val target = AudioSystem.getLine(info) as TargetDataLine
            target.open(FORMAT)
            target.start()
            line = target
            rawFile = raw
            drain = Thread { drainLine(target, raw) }.apply {
                isDaemon = true
                name = "flash-voice-capture"
                start()
            }
            true
        } catch (t: Throwable) {
            println("[FlashVoiceRecorder] Voice capture failed to start: $t")
            runCatching { line?.close() }
            raw.delete()
            line = null
            rawFile = null
            drain = null
            false
        }
    }

    /**
     * Copies the line into a raw PCM file, tracking the loudest sample seen. Raw first, WAV at the end:
     * a WAV header carries its own length, which is not known until capture stops.
     */
    private fun drainLine(target: TargetDataLine, raw: File) {
        runCatching {
            BufferedOutputStream(FileOutputStream(raw)).use { out ->
                val buffer = ByteArray(FORMAT.frameSize * 1024)
                while (true) {
                    val read = target.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    recordPeak(buffer, read)
                }
            }
        }
    }

    /** 16-bit little-endian signed samples; keeps the running maximum for [maxAmplitude] to consume. */
    private fun recordPeak(buffer: ByteArray, length: Int) {
        val loudest = peakOf(buffer, length)
        peak.updateAndGet { previous -> if (loudest > previous) loudest else previous }
    }

    override fun maxAmplitude(): Int {
        if (line == null) return 0
        // `getAndSet(0)` mirrors `MediaRecorder.getMaxAmplitude()`, which reports the peak *since the
        // last call* and resets. A running maximum that never reset would pin the waveform at whatever
        // the loudest moment of the recording was.
        val loudest = peak.getAndSet(0)
        return ((loudest / 32767f) * 100f).toInt().coerceIn(0, 100)
    }

    override fun stop(): String? {
        val target = line
        val raw = rawFile
        val worker = drain
        line = null
        rawFile = null
        drain = null
        if (target == null) return null
        return try {
            // `stop()` then `close()` makes the blocked `read()` return 0, which ends the drain loop;
            // joining then guarantees the raw file is complete and closed before it is re-read.
            target.stop()
            target.close()
            worker?.join(DRAIN_JOIN_TIMEOUT_MS)
            if (raw == null || !raw.exists() || raw.length() <= 0L) {
                raw?.delete()
                return null
            }
            val wav = File(raw.parentFile, raw.nameWithoutExtension + ".wav")
            FileInputStream(raw).use { input ->
                AudioSystem.write(
                    AudioInputStream(input, FORMAT, raw.length() / FORMAT.frameSize),
                    AudioFileFormat.Type.WAVE,
                    wav,
                )
            }
            raw.delete()
            if (wav.exists() && wav.length() > 0L) {
                // `File.toURI()` gives `file:/…`, the same shape `Uri.fromFile` produces on Android, so
                // the transfer layer sees one URI convention.
                wav.toURI().toString()
            } else {
                wav.delete()
                null
            }
        } catch (t: Throwable) {
            println("[FlashVoiceRecorder] Voice capture stop failed: $t")
            runCatching { target.close() }
            raw?.delete()
            null
        }
    }

    override fun cancel() {
        val raw = rawFile
        stopQuietly()
        raw?.delete()
    }

    private fun stopQuietly() {
        val target = line ?: return
        val worker = drain
        line = null
        drain = null
        runCatching { target.stop() }
        runCatching { target.close() }
        runCatching { worker?.join(DRAIN_JOIN_TIMEOUT_MS) }
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir") ?: ".").also { runCatching { it.mkdirs() } }

    private companion object {
        /**
         * Matches the Android encoder's 44.1 kHz / 16-bit so the amplitude scale — and therefore the
         * waveform the shared composer draws — is identical on both platforms. Mono, because a voice
         * note has no use for a second channel and it halves the bytes on the wire.
         */
        val FORMAT = AudioFormat(44_100f, 16, 1, true, false)

        /** The drain loop exits as soon as `close()` unblocks `read()`; this only bounds a pathology. */
        const val DRAIN_JOIN_TIMEOUT_MS = 2_000L
    }
}

/**
 * Loudest absolute sample in the first [length] bytes of [buffer], read as signed 16-bit
 * little-endian PCM — the layout [JvmVoiceRecorder.FORMAT] captures in.
 *
 * A top-level `internal` function rather than a method because it is the one piece of this file with
 * arithmetic worth testing, and testing it must not require a microphone: `jvmTest` feeds it bytes
 * directly. A trailing odd byte is ignored — half a sample carries no amplitude.
 */
internal fun peakOf(buffer: ByteArray, length: Int): Int {
    var loudest = 0
    var i = 0
    val end = length.coerceAtMost(buffer.size)
    while (i + 1 < end) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
        val magnitude = abs(sample)
        if (magnitude > loudest) loudest = magnitude
        i += 2
    }
    return loudest
}
