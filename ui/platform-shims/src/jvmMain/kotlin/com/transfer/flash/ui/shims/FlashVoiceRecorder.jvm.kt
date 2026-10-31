package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
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
    private var wavFile: File? = null
    private val peak = AtomicInteger(0)

    override val isRecording: Boolean get() = line != null

    override fun start(): Boolean {
        stopQuietly()
        peak.set(0)
        pruneOldVoiceFiles(tempDir())
        val wav = File(tempDir(), "flash-voice-${System.currentTimeMillis()}.wav")
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, FORMAT)
            // Throws when no capture device exists — a headless CI box, or a desktop with no mic. The
            // composer treats `false` as "recording did not start", exactly as it does on Android when
            // the encoder cannot be prepared.
            val target = AudioSystem.getLine(info) as TargetDataLine
            target.open(FORMAT)
            target.start()
            line = target
            wavFile = wav
            drain = Thread { drainLine(target, wav) }.apply {
                isDaemon = true
                name = "flash-voice-capture"
                start()
            }
            true
        } catch (t: Throwable) {
            println("[FlashVoiceRecorder] Voice capture failed to start: $t")
            runCatching { line?.close() }
            wav.delete()
            line = null
            wavFile = null
            drain = null
            false
        }
    }

    /**
     * Streams the line directly into the destination WAV file, first writing a 44-byte placeholder
     * header, then raw PCM samples while tracking loudest sample seen. On stop(), the 44-byte header
     * is finalized in-place with RandomAccessFile, avoiding any intermediate temp files or second-pass copies (O05-03).
     */
    private fun drainLine(target: TargetDataLine, wav: File) {
        runCatching {
            BufferedOutputStream(FileOutputStream(wav)).use { out ->
                // Reserve 44 bytes for WAV header
                out.write(ByteArray(44))
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
        val wav = wavFile
        val worker = drain
        line = null
        wavFile = null
        drain = null
        if (target == null) return null
        return try {
            // `stop()` then `close()` makes the blocked `read()` return 0, which ends the drain loop;
            // joining then guarantees the file is complete and closed before finalizing the header.
            target.stop()
            target.close()
            worker?.join(DRAIN_JOIN_TIMEOUT_MS)
            if (wav == null || !wav.exists() || wav.length() <= 44L) {
                wav?.delete()
                return null
            }
            val pcmLength = wav.length() - 44L
            writeWavHeader(wav, pcmLength)
            if (wav.exists() && wav.length() > 44L) {
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
            wav?.delete()
            null
        }
    }

    override fun cancel() {
        val wav = wavFile
        stopQuietly()
        wav?.delete()
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

    internal companion object {
        /**
         * Matches the Android encoder's 44.1 kHz / 16-bit so the amplitude scale — and therefore the
         * waveform the shared composer draws — is identical on both platforms. Mono, because a voice
         * note has no use for a second channel and it halves the bytes on the wire.
         */
        val FORMAT = AudioFormat(44_100f, 16, 1, true, false)

        /** The drain loop exits as soon as `close()` unblocks `read()`; this only bounds a pathology. */
        const val DRAIN_JOIN_TIMEOUT_MS = 2_000L

        /**
         * Writes a standard 44-byte RIFF/WAVE header directly into [file] at offset 0 (O05-03).
         */
        internal fun writeWavHeader(file: File, pcmDataLength: Long) {
            val totalDataLen = pcmDataLength + 36
            val sampleRate = FORMAT.sampleRate.toLong()
            val channels = FORMAT.channels
            val byteRate = sampleRate * channels * FORMAT.sampleSizeInBits / 8
            val blockAlign = channels * FORMAT.sampleSizeInBits / 8

            val header = ByteArray(44)
            // "RIFF"
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            // total file length - 8
            header[4] = (totalDataLen and 0xffL).toByte()
            header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
            header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
            header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
            // "WAVE"
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            // "fmt "
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            // Subchunk1Size = 16 for PCM
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            // AudioFormat = 1 (PCM)
            header[20] = 1
            header[21] = 0
            // NumChannels
            header[22] = channels.toByte()
            header[23] = 0
            // SampleRate
            header[24] = (sampleRate and 0xffL).toByte()
            header[25] = ((sampleRate shr 8) and 0xffL).toByte()
            header[26] = ((sampleRate shr 16) and 0xffL).toByte()
            header[27] = ((sampleRate shr 24) and 0xffL).toByte()
            // ByteRate
            header[28] = (byteRate and 0xffL).toByte()
            header[29] = ((byteRate shr 8) and 0xffL).toByte()
            header[30] = ((byteRate shr 16) and 0xffL).toByte()
            header[31] = ((byteRate shr 24) and 0xffL).toByte()
            // BlockAlign
            header[32] = blockAlign.toByte()
            header[33] = 0
            // BitsPerSample
            header[34] = FORMAT.sampleSizeInBits.toByte()
            header[35] = 0
            // "data"
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            // Subchunk2Size
            header[40] = (pcmDataLength and 0xffL).toByte()
            header[41] = ((pcmDataLength shr 8) and 0xffL).toByte()
            header[42] = ((pcmDataLength shr 16) and 0xffL).toByte()
            header[43] = ((pcmDataLength shr 24) and 0xffL).toByte()

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header)
            }
        }
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

/**
 * O05-08 / O23-04: Prunes abandoned temporary voice recording files older than [maxAgeMs].
 */
internal fun pruneOldVoiceFiles(dir: File, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
    runCatching {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles { _, name -> name.startsWith("flash-voice-") }?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
