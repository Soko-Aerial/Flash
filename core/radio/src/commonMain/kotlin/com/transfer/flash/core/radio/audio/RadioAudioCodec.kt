package com.transfer.flash.core.radio.audio

import com.transfer.flash.core.radio.audio.sbc.SbcAllocation
import com.transfer.flash.core.radio.audio.sbc.SbcDecoder
import com.transfer.flash.core.radio.audio.sbc.SbcEncoder
import com.transfer.flash.core.radio.audio.sbc.SbcFrame
import com.transfer.flash.core.radio.audio.sbc.SbcFrequency
import com.transfer.flash.core.radio.audio.sbc.SbcMode

/** The audio format the handhelds use on the vendor channel: SBC, 32 kHz, mono, 16 blocks, 8 subbands, loudness. */
internal object RadioAudioFormat {
    const val SAMPLE_RATE: Int = 32_000

    /** The radio's own default; HTCommander transmits at 40 (more quality, more bytes), 124 was rejected. */
    const val DEFAULT_BITPOOL: Int = 18

    /** PCM samples carried by one SBC frame (16 blocks x 8 subbands). */
    const val SAMPLES_PER_FRAME: Int = 128

    /** A packet is filled with SBC frames until its payload reaches this size. */
    const val PACKET_TARGET_BYTES: Int = 300

    fun frame(bitpool: Int): SbcFrame = SbcFrame(
        frequency = SbcFrequency.F32K,
        mode = SbcMode.MONO,
        allocation = SbcAllocation.LOUDNESS,
        blocks = 16,
        subbands = 8,
        bitpool = bitpool,
    )
}

/**
 * Turns the radio's audio frames into PCM. Sans-IO: the caller feeds [AudioFrame]s and supplies the sinks.
 * A "run" is one received transmission: it starts with the first audio frame and ends with an END frame
 * (or [endRun], called by the idle watchdog). The decoder is reset at each run start so no filter history leaks.
 */
internal class RadioAudioReceiver(
    private val onRunStart: () -> Unit,
    private val onPcm: (ShortArray) -> Unit,
    private val onRunEnd: () -> Unit,
) {
    private val decoder = SbcDecoder()

    var inRun: Boolean = false
        private set

    /** SBC frames that failed to decode (CRC, truncated). */
    var badFrames: Int = 0
        private set

    fun onFrame(f: AudioFrame) {
        when (f.command) {
            RadioAudioFraming.CMD_AUDIO, RadioAudioFraming.CMD_AUDIO_ALT -> {
                if (!inRun) {
                    inRun = true
                    decoder.reset()
                    onRunStart()
                }
                decodeAll(f.payload)
            }
            RadioAudioFraming.CMD_END -> endRun()
            else -> Unit // ACK, TX echo and unknown commands are not audio we play
        }
    }

    fun endRun() {
        if (!inRun) return
        inRun = false
        onRunEnd()
    }

    private fun decodeAll(data: ByteArray) {
        var i = 0
        while (i + SbcFrame.HEADER_SIZE <= data.size) {
            if ((data[i].toInt() and 0xFF) != SbcFrame.SYNCWORD) { i++; continue }
            val header = decoder.probe(data, i, data.size - i)
            val size = header?.frameSize() ?: 0
            if (header == null || size <= 0 || i + size > data.size) { badFrames++; i++; continue }
            val r = decoder.decode(data, i, size)
            if (r.success) onPcm(r.pcmLeft) else badFrames++
            i += size
        }
    }
}

/**
 * Turns PCM (already 32 kHz mono) into framed radio packets. Sans-IO. Partial SBC frames are buffered; packets are
 * filled to [RadioAudioFormat.PACKET_TARGET_BYTES]; [finish] flushes what is left and appends the END frame.
 */
internal class RadioAudioTransmitter(bitpool: Int = RadioAudioFormat.DEFAULT_BITPOOL) {
    private val encoder = SbcEncoder()
    private val frame = RadioAudioFormat.frame(bitpool)
    private val pcm = ShortArray(RadioAudioFormat.SAMPLES_PER_FRAME)
    private var pcmFill = 0
    private var pending = ByteArray(RadioAudioFormat.PACKET_TARGET_BYTES + 64)
    private var pendingLen = 0

    init {
        require(frame.isValid()) { "bitpool $bitpool is not valid for the radio's SBC shape" }
    }

    /** Returns zero or more wire-ready packets for [length] samples of [samples]. */
    fun push(samples: ShortArray, length: Int = samples.size): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        var i = 0
        while (i < length) {
            val n = minOf(pcm.size - pcmFill, length - i)
            samples.copyInto(pcm, pcmFill, i, i + n)
            pcmFill += n; i += n
            if (pcmFill == pcm.size) {
                pcmFill = 0
                val sbc = encoder.encode(pcm, null, frame) ?: continue
                append(sbc)
                if (pendingLen >= RadioAudioFormat.PACKET_TARGET_BYTES) out += takePacket()
            }
        }
        return out
    }

    /** The last packet (padding the partial frame with silence) followed by the END frame. */
    fun finish(): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        if (pcmFill > 0) {
            pcm.fill(0, pcmFill, pcm.size)
            pcmFill = 0
            encoder.encode(pcm, null, frame)?.let { append(it) }
        }
        if (pendingLen > 0) out += takePacket()
        out += RadioAudioFraming.END_FRAME
        encoder.reset()
        return out
    }

    private fun append(sbc: ByteArray) {
        if (pendingLen + sbc.size > pending.size) pending = pending.copyOf(pending.size * 2)
        sbc.copyInto(pending, pendingLen)
        pendingLen += sbc.size
    }

    private fun takePacket(): ByteArray {
        val p = RadioAudioFraming.encode(RadioAudioFraming.CMD_AUDIO, pending, 0, pendingLen)
        pendingLen = 0
        return p
    }
}

/** Streaming linear resampler to [RadioAudioFormat.SAMPLE_RATE]; keeps phase between calls. Voice-grade, not hi-fi. */
internal class LinearResampler(private val sourceRate: Int, private val targetRate: Int = RadioAudioFormat.SAMPLE_RATE) {
    private var last: Short = 0
    private var pos = 0.0 // position of the next output sample, in source samples relative to the start of the next input

    fun process(input: ShortArray, length: Int = input.size): ShortArray {
        if (sourceRate == targetRate) return input.copyOf(length)
        val step = sourceRate.toDouble() / targetRate
        val out = ArrayList<Short>((length * targetRate / sourceRate) + 2)
        // virtual signal: index -1 is `last`, 0..length-1 is input
        while (true) {
            val i = kotlin.math.floor(pos).toInt()
            val bIdx = i + 1
            if (bIdx >= length) break
            val frac = pos - i
            val a = if (i < 0) last.toInt() else input[i].toInt()
            val b = input[bIdx].toInt()
            out += (a + (b - a) * frac).toInt().toShort()
            pos += step
        }
        pos -= length
        if (length > 0) last = input[length - 1]
        return out.toShortArray()
    }
}
