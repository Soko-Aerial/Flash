@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.ptt

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The one place the desktop PTT touches `javax.sound.sampled`. Everything above it ([JvmPttCapture],
 * [JvmPttPlayout]) works on these three small interfaces, so the loops, the loss reporting and the
 * rate fallback are unit-tested with a fake [PttPcmLines] and no audio hardware.
 */
internal interface PttPcmInput {
    /** Blocking read of up to [length] bytes. Fewer (or 0) once stopped; negative on error. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun stop()

    fun close()
}

internal interface PttPcmOutput {
    /** Blocking write of up to [length] bytes; returns how many were accepted. */
    fun write(buffer: ByteArray, offset: Int, length: Int): Int

    fun flush()

    fun stop()

    fun close()
}

/** Opens a started 16-bit signed little-endian mono line at [rateHz], or null when it cannot. */
internal interface PttPcmLines {
    fun openInput(rateHz: Int, bufferBytes: Int): PttPcmInput?

    fun openOutput(rateHz: Int, bufferBytes: Int): PttPcmOutput?
}

internal object JavaSoundPttLines : PttPcmLines {
    private const val TAG = "PTT_JSND"

    private fun formatFor(rateHz: Int) = AudioFormat(rateHz.toFloat(), BITS, CHANNELS, true, false)

    override fun openInput(rateHz: Int, bufferBytes: Int): PttPcmInput? {
        val format = formatFor(rateHz)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            FlashLog.w(TAG, "No capture line for $rateHz Hz PCM16 mono")
            return null
        }
        var line: TargetDataLine? = null
        return try {
            line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format, bufferBytes)
            line.start()
            FlashLog.i(TAG, "Capture line open rate=$rateHz buffer=${line.bufferSize}")
            TargetInput(line)
        } catch (error: Exception) {
            // LineUnavailableException (device busy / privacy block), IllegalArgumentException, SecurityException.
            FlashLog.w(TAG, "Capture line for $rateHz Hz failed: ${error.message}")
            runCatching { line?.close() }
            null
        }
    }

    override fun openOutput(rateHz: Int, bufferBytes: Int): PttPcmOutput? {
        val format = formatFor(rateHz)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            FlashLog.w(TAG, "No playback line for $rateHz Hz PCM16 mono")
            return null
        }
        var line: SourceDataLine? = null
        return try {
            line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format, bufferBytes)
            line.start()
            FlashLog.i(TAG, "Playback line open rate=$rateHz buffer=${line.bufferSize}")
            SourceOutput(line)
        } catch (error: Exception) {
            FlashLog.w(TAG, "Playback line for $rateHz Hz failed: ${error.message}")
            runCatching { line?.close() }
            null
        }
    }

    private class TargetInput(private val line: TargetDataLine) : PttPcmInput {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = line.read(buffer, offset, length)

        override fun stop() = line.stop()

        override fun close() = line.close()
    }

    private class SourceOutput(private val line: SourceDataLine) : PttPcmOutput {
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int = line.write(buffer, offset, length)

        override fun flush() = line.flush()

        override fun stop() = line.stop()

        override fun close() = line.close()
    }

    private const val BITS = 16
    private const val CHANNELS = 1
}
