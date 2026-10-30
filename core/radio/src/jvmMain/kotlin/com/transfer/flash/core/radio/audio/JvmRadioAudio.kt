package com.transfer.flash.core.radio.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Little-endian PCM16 bytes of the first [length] samples. */
internal fun pcmToBytes(pcm: ShortArray, length: Int): ByteArray {
    val out = ByteArray(length * 2)
    for (i in 0 until length) {
        val v = pcm[i].toInt()
        out[i * 2] = v.toByte()
        out[i * 2 + 1] = (v shr 8).toByte()
    }
    return out
}

/** Samples from little-endian PCM16 [bytes] (the first [length] bytes; an odd trailing byte is ignored). */
internal fun bytesToPcm(bytes: ByteArray, length: Int, into: ShortArray): Int {
    val n = minOf(length / 2, into.size)
    for (i in 0 until n) {
        into[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
    }
    return n
}

/**
 * Plays the radio's audio through Java Sound (32 kHz mono PCM16, or 48 kHz upsampled when the sound device refuses 32 kHz).
 * Same contract as the Android output: never blocks, a write that does not fit in the roughly 800 ms buffer is dropped,
 * and about 60 ms is buffered before playback starts (and again after a pause) so the first words are not chopped.
 */
public class JvmRadioPcmOutput : RadioPcmOutput {
    private var line: SourceDataLine? = null
    private var sourceRate = 0
    private var lineRate = 0
    private var playing = false
    private var primedBytes = 0
    private var preRollBytes = 0
    private var lastWriteAt = 0L
    private var lastSample: Short = 0
    private var upPos = 0.0

    override fun start(sampleRate: Int) {
        stop()
        sourceRate = sampleRate
        val chosen = RATES_TO_TRY.firstOrNull { rate ->
            val info = DataLine.Info(SourceDataLine::class.java, formatOf(rate))
            rate >= sampleRate && AudioSystem.isLineSupported(info)
        } ?: throw IllegalStateException("The speaker does not support the radio's audio format.")
        lineRate = chosen
        val l = AudioSystem.getSourceDataLine(formatOf(lineRate))
        l.open(formatOf(lineRate), lineRate * 2 * BUFFER_MS / 1000)
        line = l
        playing = false
        primedBytes = 0
        preRollBytes = lineRate * 2 * PREROLL_MS / 1000
        lastSample = 0
        upPos = 0.0
    }

    override fun write(pcm: ShortArray, length: Int) {
        val l = line ?: return
        val now = System.nanoTime() / 1_000_000
        if (playing && now - lastWriteAt > GAP_RESET_MS) {
            runCatching { l.stop(); l.flush() }
            playing = false
            primedBytes = 0
        }
        lastWriteAt = now
        val data = if (lineRate == sourceRate) pcmToBytes(pcm, length) else upsample(pcm, length)
        if (l.available() < data.size) return // a stalled speaker must not back the radio link up
        val written = l.write(data, 0, data.size)
        if (written > 0 && !playing) {
            primedBytes += written
            if (primedBytes >= preRollBytes) {
                runCatching { l.start() }
                playing = true
            }
        }
    }

    override fun stop() {
        val l = line ?: return
        line = null
        playing = false
        runCatching { l.stop() }
        runCatching { l.flush() }
        runCatching { l.close() }
    }

    /** Linear interpolation from [sourceRate] up to [lineRate]; keeps the last sample between calls. */
    private fun upsample(pcm: ShortArray, length: Int): ByteArray {
        val step = sourceRate.toDouble() / lineRate
        val out = ArrayList<Short>((length * lineRate / sourceRate) + 2)
        // virtual signal: index -1 is lastSample, 0..length-1 is pcm
        while (true) {
            val i = kotlin.math.floor(upPos).toInt()
            if (i + 1 >= length) break
            val a = if (i < 0) lastSample.toInt() else pcm[i].toInt()
            val b = pcm[i + 1].toInt()
            val frac = upPos - i
            out.add((a + (b - a) * frac).toInt().toShort())
            upPos += step
        }
        if (length > 0) lastSample = pcm[length - 1]
        upPos -= length
        return pcmToBytes(out.toShortArray(), out.size)
    }

    private companion object {
        const val BUFFER_MS = 800
        const val PREROLL_MS = 60
        const val GAP_RESET_MS = 300L
        val RATES_TO_TRY = intArrayOf(32_000, 48_000)

        fun formatOf(rate: Int) = AudioFormat(rate.toFloat(), 16, 1, true, false)
    }
}

/**
 * Captures the microphone through Java Sound. Tries 32, 16, 44.1 then 48 kHz mono; the session resamples to 32 kHz.
 * On Windows the line can open yet deliver silence when Settings > Privacy > Microphone blocks desktop apps; the host
 * checks that the line opens before it offers hold-to-talk.
 */
public class JvmRadioPcmInput : RadioPcmInput {
    private var line: TargetDataLine? = null
    private var scratch = ByteArray(0)

    override fun start(): Int {
        stop()
        for (rate in RATES) {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) continue
            val l = try {
                (AudioSystem.getLine(info) as TargetDataLine).also { it.open(format, rate / 5 * 2) }
            } catch (e: Exception) {
                continue
            }
            l.start()
            line = l
            return rate
        }
        throw IllegalStateException("The microphone could not be opened.")
    }

    override suspend fun read(buffer: ShortArray): Int {
        val l = line ?: return -1
        return withContext(Dispatchers.IO) {
            if (scratch.size < buffer.size * 2) scratch = ByteArray(buffer.size * 2)
            val n = l.read(scratch, 0, buffer.size * 2)
            when {
                n > 0 -> bytesToPcm(scratch, n, buffer)
                l.isOpen && l.isActive -> 0
                else -> -1
            }
        }
    }

    override fun stop() {
        val l = line ?: return
        line = null
        runCatching { l.stop() }
        runCatching { l.close() }
    }

    /** True when a microphone line can be opened right now; the line is closed again at once. */
    public fun canOpen(): Boolean = try {
        start()
        stop()
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        val RATES = intArrayOf(32_000, 16_000, 44_100, 48_000)
    }
}
