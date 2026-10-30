package com.transfer.flash.core.radio.audio.sbc

// A Kotlin port of the SBC (Bluetooth A2DP "Low Complexity Subband Codec") encoder and decoder used by HTCommander
// (Apache-2.0, Copyright 2026 Ylian Saint-Hilaire, lib/sbc/*.dart), which in turn follows Google's libsbc (Apache-2.0).
// The handhelds Flash supports carry their voice as SBC, 32 kHz mono, 16 blocks, 8 subbands, inside the vendor RFCOMM
// channel (docs/network/RADIO-AUDIO.md). mSBC (Hands-Free) is deliberately not ported: the radio never sends it.
//
// Fixed-point arithmetic is kept exactly as in the reference, including the 16-bit truncation on store, so the output
// matches the reference sample for sample.

/** SBC sampling frequencies, in the header's 2-bit order. */
internal enum class SbcFrequency(val hz: Int) { F16K(16000), F32K(32000), F44K1(44100), F48K(48000) }

/** SBC channel modes, in the header's 2-bit order. */
internal enum class SbcMode { MONO, DUAL_CHANNEL, STEREO, JOINT_STEREO }

/** Bit allocation methods, in the header's 1-bit order. */
internal enum class SbcAllocation { LOUDNESS, SNR }

/** One frame's parameters. */
internal class SbcFrame(
    var frequency: SbcFrequency = SbcFrequency.F16K,
    var mode: SbcMode = SbcMode.MONO,
    var allocation: SbcAllocation = SbcAllocation.LOUDNESS,
    var blocks: Int = 0,
    var subbands: Int = 0,
    var bitpool: Int = 0,
) {
    val frequencyHz: Int get() = frequency.hz

    fun isValid(): Boolean {
        if (blocks < 4 || blocks > 16 || blocks % 4 != 0) return false
        if (subbands != 4 && subbands != 8) return false
        val twoChannels = mode != SbcMode.MONO
        val dualMode = mode == SbcMode.DUAL_CHANNEL
        val jointMode = mode == SbcMode.JOINT_STEREO
        val stereoMode = jointMode || mode == SbcMode.STEREO
        val maxBits = ((16 * subbands * blocks) shl (if (twoChannels) 1 else 0)) -
            (HEADER_SIZE * 8) -
            ((4 * subbands) shl (if (twoChannels) 1 else 0)) -
            (if (jointMode) subbands else 0)
        val maxBitpool = minOf(
            maxBits / (blocks shl (if (dualMode) 1 else 0)),
            (16 shl (if (stereoMode) 1 else 0)) * subbands,
        )
        return bitpool <= maxBitpool
    }

    /** Encoded size in bytes, 0 when the parameters are invalid. */
    fun frameSize(): Int {
        if (!isValid()) return 0
        val twoChannels = mode != SbcMode.MONO
        val dualMode = mode == SbcMode.DUAL_CHANNEL
        val jointMode = mode == SbcMode.JOINT_STEREO
        val nbits = ((4 * subbands) shl (if (twoChannels) 1 else 0)) +
            ((blocks * bitpool) shl (if (dualMode) 1 else 0)) +
            (if (jointMode) subbands else 0)
        return HEADER_SIZE + ((nbits + 7) shr 3)
    }

    /** PCM samples per channel one frame carries. */
    val samplesPerChannel: Int get() = blocks * subbands

    companion object {
        const val MAX_SUBBANDS = 8
        const val MAX_BLOCKS = 16
        const val MAX_SAMPLES = MAX_BLOCKS * MAX_SUBBANDS
        const val HEADER_SIZE = 4
        const val SYNCWORD = 0x9C
    }
}

/** MSB-first bit reader / writer over a fixed buffer. */
internal class SbcBitStream(private val data: ByteArray, private val maxBytes: Int, private val isReader: Boolean) {
    private var bytePosition = 0
    private var accumulator = 0L
    private var bitsInAccumulator = 0

    var hasError: Boolean = false
        private set

    val bitPosition: Int
        get() = if (isReader) bytePosition * 8 - bitsInAccumulator else bytePosition * 8 + bitsInAccumulator

    fun getBits(numBits: Int): Int {
        if (numBits == 0) return 0
        if (numBits < 0 || numBits > 32) {
            hasError = true
            return 0
        }
        while (bitsInAccumulator < numBits && bytePosition < maxBytes) {
            accumulator = ((accumulator shl 8) or (data[bytePosition++].toLong() and 0xFF)) and 0xFFFFFFFFL
            bitsInAccumulator += 8
        }
        if (bitsInAccumulator < numBits) {
            val result = accumulator shl (numBits - bitsInAccumulator)
            bitsInAccumulator = 0
            accumulator = 0
            hasError = true
            return (result and ((1L shl numBits) - 1)).toInt()
        }
        bitsInAccumulator -= numBits
        val value = (accumulator shr bitsInAccumulator) and ((1L shl numBits) - 1)
        accumulator = accumulator and ((1L shl bitsInAccumulator) - 1)
        return value.toInt()
    }

    fun putBits(value: Int, numBits: Int) {
        if (numBits == 0) return
        if (numBits < 0 || numBits > 32) {
            hasError = true
            return
        }
        val masked = value.toLong() and ((1L shl numBits) - 1)
        accumulator = (accumulator shl numBits) or masked
        bitsInAccumulator += numBits
        while (bitsInAccumulator >= 8) {
            if (bytePosition >= maxBytes) {
                hasError = true
                return
            }
            bitsInAccumulator -= 8
            data[bytePosition++] = ((accumulator shr bitsInAccumulator) and 0xFF).toByte()
            accumulator = accumulator and ((1L shl bitsInAccumulator) - 1)
        }
    }

    fun flush() {
        if (bitsInAccumulator > 0) {
            if (bytePosition >= maxBytes) {
                hasError = true
                return
            }
            data[bytePosition++] = ((accumulator shl (8 - bitsInAccumulator)) and 0xFF).toByte()
            bitsInAccumulator = 0
            accumulator = 0
        }
    }
}

private fun saturate16(value: Int): Int = if (value > 32767) 32767 else if (value < -32768) -32768 else value

/** Truncates to a signed 16-bit value, like a store into an `int16_t`. */
private fun wrap16(value: Int): Int = value.toShort().toInt()

private fun computeCrc(frame: SbcFrame, data: ByteArray, size: Int): Int {
    val nch = if (frame.mode != SbcMode.MONO) 2 else 1
    val nsb = frame.subbands
    val nbit = nch * nsb * 4 + (if (frame.mode == SbcMode.JOINT_STEREO) nsb else 0)
    if (size < ((SbcFrame.HEADER_SIZE * 8 + nbit + 7) shr 3)) return -1
    val table = SbcTables.crc8
    fun b(i: Int) = data[i].toInt() and 0xFF
    var crc = 0x0F
    crc = table[crc xor b(1)]
    crc = table[crc xor b(2)]
    for (i in 4 until 4 + nbit / 8) crc = table[crc xor b(i)]
    if (nbit % 8 != 0) crc = ((crc shl 4) xor table[(crc shr 4) xor (b(4 + nbit / 8) shr 4)]) and 0xFF
    return crc
}

/** Bit allocation, shared by both directions. [scaleFactors] / [nbits] are `[channel][subband]`. */
private fun computeBitAllocation(frame: SbcFrame, scaleFactors: Array<IntArray>, nbits: Array<IntArray>) {
    val loudnessOffset =
        if (frame.subbands == 4) SbcTables.loudnessOffset4[frame.frequency.ordinal]
        else SbcTables.loudnessOffset8[frame.frequency.ordinal]
    val stereoMode = frame.mode == SbcMode.STEREO || frame.mode == SbcMode.JOINT_STEREO
    val nsubbands = frame.subbands
    val nchannels = if (stereoMode) 2 else 1

    val bitneeds = Array(2) { IntArray(SbcFrame.MAX_SUBBANDS) }
    var maxBitneed = 0
    for (ch in 0 until nchannels) {
        for (sb in 0 until nsubbands) {
            val scf = scaleFactors[ch][sb]
            var bitneed: Int
            if (frame.allocation == SbcAllocation.LOUDNESS) {
                bitneed = if (scf != 0) scf - loudnessOffset[sb] else -5
                bitneed = bitneed shr (if (bitneed > 0) 1 else 0)
            } else {
                bitneed = scf
            }
            if (bitneed > maxBitneed) maxBitneed = bitneed
            bitneeds[ch][sb] = bitneed
        }
    }

    val bitpool = frame.bitpool
    var bitcount = 0
    var bitslice = maxBitneed + 1
    var bc = 0
    while (bc < bitpool) {
        val bs = bitslice--
        bitcount = bc
        if (bitcount == bitpool) break
        for (ch in 0 until nchannels) {
            for (sb in 0 until nsubbands) {
                val bn = bitneeds[ch][sb]
                bc += (if (bn >= bs && bn < bs + 15) 1 else 0) + (if (bn == bs) 1 else 0)
            }
        }
    }

    for (ch in 0 until nchannels) {
        for (sb in 0 until nsubbands) {
            val nbit = bitneeds[ch][sb] - bitslice
            nbits[ch][sb] = if (nbit < 2) 0 else if (nbit > 16) 16 else nbit
        }
    }

    var sb = 0
    while (sb < nsubbands && bitcount < bitpool) {
        var ch = 0
        while (ch < nchannels && bitcount < bitpool) {
            val n = if (nbits[ch][sb] > 0 && nbits[ch][sb] < 16) 1
            else if (bitneeds[ch][sb] == bitslice + 1 && bitpool > bitcount + 1) 2
            else 0
            nbits[ch][sb] += n
            bitcount += n
            ch++
        }
        sb++
    }
    sb = 0
    while (sb < nsubbands && bitcount < bitpool) {
        var ch = 0
        while (ch < nchannels && bitcount < bitpool) {
            val n = if (nbits[ch][sb] < 16) 1 else 0
            nbits[ch][sb] += n
            bitcount += n
            ch++
        }
        sb++
    }
}

/** Result of [SbcDecoder.decode]. [pcmRight] is null for mono. */
internal class SbcDecodeResult(
    val success: Boolean,
    val pcmLeft: ShortArray,
    val pcmRight: ShortArray?,
    val frame: SbcFrame,
)

/** SBC frames to PCM. One instance per audio stream: it carries the synthesis filter's history. */
internal class SbcDecoder {
    private class State {
        var index = 0
        val v = Array(2) { Array(SbcFrame.MAX_SUBBANDS) { IntArray(10) } }
        fun reset() {
            index = 0
            for (odd in 0..1) for (sb in 0 until SbcFrame.MAX_SUBBANDS) v[odd][sb].fill(0)
        }
    }

    private val states = arrayOf(State(), State())

    fun reset() {
        states[0].reset()
        states[1].reset()
    }

    /** Reads the 4-byte header at the start of [data] without decoding, or null when it is not a valid SBC header. */
    fun probe(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): SbcFrame? {
        if (length < SbcFrame.HEADER_SIZE) return null
        val bits = SbcBitStream(data.copyOfRange(offset, offset + SbcFrame.HEADER_SIZE), SbcFrame.HEADER_SIZE, true)
        val frame = SbcFrame()
        if (decodeHeader(bits, frame) == null) return null
        return if (bits.hasError) null else frame
    }

    /** Decodes exactly one frame that starts at [offset]. */
    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): SbcDecodeResult {
        val frame = SbcFrame()
        fun failure() = SbcDecodeResult(false, ShortArray(0), null, frame)
        if (length < SbcFrame.HEADER_SIZE) return failure()
        val sbc = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)

        val headerBits = SbcBitStream(sbc, SbcFrame.HEADER_SIZE, true)
        val crc = decodeHeader(headerBits, frame)
        if (crc == null || headerBits.hasError) return failure()
        val frameSize = frame.frameSize()
        if (sbc.size < frameSize) return failure()
        if (computeCrc(frame, sbc, sbc.size) != crc) return failure()

        val dataBits = SbcBitStream(sbc, frameSize, true)
        dataBits.getBits(SbcFrame.HEADER_SIZE * 8)
        val sbSamples = arrayOf(IntArray(SbcFrame.MAX_SAMPLES), IntArray(SbcFrame.MAX_SAMPLES))
        val sbScale = IntArray(2)
        decodeFrameData(dataBits, frame, sbSamples, sbScale)
        if (dataBits.hasError) return failure()

        val numBlocks = frame.blocks
        val numSubbands = frame.subbands
        val perChannel = numBlocks * numSubbands
        val left = IntArray(perChannel)
        synthesize(states[0], numBlocks, numSubbands, sbSamples[0], sbScale[0], left)
        var right: ShortArray? = null
        if (frame.mode != SbcMode.MONO) {
            val r = IntArray(perChannel)
            synthesize(states[1], numBlocks, numSubbands, sbSamples[1], sbScale[1], r)
            right = ShortArray(perChannel) { r[it].toShort() }
        }
        return SbcDecodeResult(true, ShortArray(perChannel) { left[it].toShort() }, right, frame)
    }

    private fun decodeHeader(bits: SbcBitStream, frame: SbcFrame): Int? {
        val syncword = bits.getBits(8)
        if (syncword != SbcFrame.SYNCWORD) return null
        frame.frequency = SbcFrequency.entries[bits.getBits(2)]
        frame.blocks = (1 + bits.getBits(2)) shl 2
        frame.mode = SbcMode.entries[bits.getBits(2)]
        frame.allocation = SbcAllocation.entries[bits.getBits(1)]
        frame.subbands = (1 + bits.getBits(1)) shl 2
        frame.bitpool = bits.getBits(8)
        val crc = bits.getBits(8)
        return if (frame.isValid()) crc else null
    }

    private fun decodeFrameData(bits: SbcBitStream, frame: SbcFrame, sbSamples: Array<IntArray>, sbScale: IntArray) {
        val nchannels = if (frame.mode != SbcMode.MONO) 2 else 1
        val nsubbands = frame.subbands

        var mjoint = 0
        if (frame.mode == SbcMode.JOINT_STEREO) {
            val v = bits.getBits(nsubbands)
            mjoint = if (nsubbands == 4) {
                ((v and 0x02) shl 1) or ((v and 0x04) shr 1) or ((v and 0x08) shr 3)
            } else {
                ((v and 0x02) shl 5) or ((v and 0x04) shl 3) or ((v and 0x08) shl 1) or
                    ((v and 0x10) shr 1) or ((v and 0x20) shr 3) or ((v and 0x40) shr 5) or ((v and 0x80) shr 7)
            }
        }

        val scaleFactors = Array(2) { IntArray(SbcFrame.MAX_SUBBANDS) }
        for (ch in 0 until nchannels) for (sb in 0 until nsubbands) scaleFactors[ch][sb] = bits.getBits(4)

        val nbits = Array(2) { IntArray(SbcFrame.MAX_SUBBANDS) }
        computeBitAllocation(frame, scaleFactors, nbits)
        if (frame.mode == SbcMode.DUAL_CHANNEL) {
            val sf1 = arrayOf(scaleFactors[1])
            val nb1 = arrayOf(nbits[1])
            computeBitAllocation(frame, sf1, nb1)
        }

        for (ch in 0 until nchannels) {
            var maxScf = 0
            for (sb in 0 until nsubbands) {
                val scf = scaleFactors[ch][sb] + ((mjoint shr sb) and 1)
                if (scf > maxScf) maxScf = scf
            }
            sbScale[ch] = (15 - maxScf) - (17 - 16)
        }
        if (frame.mode == SbcMode.JOINT_STEREO) {
            val minScale = minOf(sbScale[0], sbScale[1])
            sbScale[0] = minScale
            sbScale[1] = minScale
        }

        for (blk in 0 until frame.blocks) {
            for (ch in 0 until nchannels) {
                for (sb in 0 until nsubbands) {
                    val nbit = nbits[ch][sb]
                    val scf = scaleFactors[ch][sb]
                    val idx = blk * nsubbands + sb
                    if (nbit == 0) {
                        sbSamples[ch][idx] = 0
                        continue
                    }
                    val raw = bits.getBits(nbit).toLong()
                    val scaled = ((raw shl 1) or 1L) * SbcTables.rangeScale[nbit - 1].toLong()
                    sbSamples[ch][idx] = wrap16(((scaled - (1L shl 28)) shr (28 - ((scf + 1) + sbScale[ch]))).toInt())
                }
            }
        }

        for (sb in 0 until nsubbands) {
            if (((mjoint shr sb) and 1) == 0) continue
            for (blk in 0 until frame.blocks) {
                val idx = blk * nsubbands + sb
                val s0 = sbSamples[0][idx]
                val s1 = sbSamples[1][idx]
                sbSamples[0][idx] = wrap16(s0 + s1)
                sbSamples[1][idx] = wrap16(s0 - s1)
            }
        }

        val paddingBits = 8 - (bits.bitPosition % 8)
        if (paddingBits < 8) bits.getBits(paddingBits)
    }

    private fun synthesize(state: State, nblocks: Int, nsubbands: Int, input: IntArray, scale: Int, output: IntArray) {
        for (blk in 0 until nblocks) {
            val inOffset = blk * nsubbands
            val outOffset = blk * nsubbands
            val dctIdx = if (state.index != 0) 10 - state.index else 0
            val odd = dctIdx and 1
            if (nsubbands == 4) {
                dct4(input, inOffset, scale, state.v[odd], state.v[1 - odd], dctIdx)
                applyWindow(state.v[odd], state.index, output, outOffset, 4, SbcDecoderTables.window4)
            } else {
                dct8(input, inOffset, scale, state.v[odd], state.v[1 - odd], dctIdx)
                applyWindow(state.v[odd], state.index, output, outOffset, 8, SbcDecoderTables.window8)
            }
            state.index = if (state.index < 9) state.index + 1 else 0
        }
    }

    private fun dct4(input: IntArray, o: Int, scale: Int, out0: Array<IntArray>, out1: Array<IntArray>, idx: Int) {
        val cos8 = SbcTables.cos8
        val s03 = (input[o] + input[o + 3]) shr 1
        val d03 = (input[o] - input[o + 3]) shr 1
        val s12 = (input[o + 1] + input[o + 2]) shr 1
        val d12 = (input[o + 1] - input[o + 2]) shr 1

        var a0 = (s03 - s12) * cos8[2]
        var b1 = (-(s03 + s12)) shl 13
        var a1 = d03 * cos8[3] - d12 * cos8[1]
        var b0 = -d03 * cos8[1] - d12 * cos8[3]

        val shr = 12 + scale
        val round = 1 shl (shr - 1)
        a0 = (a0 + round) shr shr
        b0 = (b0 + round) shr shr
        a1 = (a1 + round) shr shr
        b1 = (b1 + round) shr shr

        out0[0][idx] = saturate16(a0)
        out0[3][idx] = saturate16(-a1)
        out0[1][idx] = saturate16(a1)
        out0[2][idx] = 0

        out1[0][idx] = saturate16(-a0)
        out1[3][idx] = saturate16(b0)
        out1[1][idx] = saturate16(b0)
        out1[2][idx] = saturate16(b1)
    }

    private fun dct8(input: IntArray, o: Int, scale: Int, out0: Array<IntArray>, out1: Array<IntArray>, idx: Int) {
        val cos16 = SbcTables.cos16
        val s07 = (input[o] + input[o + 7]) shr 1
        val d07 = (input[o] - input[o + 7]) shr 1
        val s16 = (input[o + 1] + input[o + 6]) shr 1
        val d16 = (input[o + 1] - input[o + 6]) shr 1
        val s25 = (input[o + 2] + input[o + 5]) shr 1
        val d25 = (input[o + 2] - input[o + 5]) shr 1
        val s34 = (input[o + 3] + input[o + 4]) shr 1
        val d34 = (input[o + 3] - input[o + 4]) shr 1

        var a0 = ((s07 + s34) - (s25 + s16)) * cos16[4]
        var b3 = (-(s07 + s34) - (s25 + s16)) shl 13
        var a2 = (s07 - s34) * cos16[6] + (s25 - s16) * cos16[2]
        var b1 = (s34 - s07) * cos16[2] + (s25 - s16) * cos16[6]
        var a1 = d07 * cos16[5] - d16 * cos16[1] + d25 * cos16[7] + d34 * cos16[3]
        var b2 = -d07 * cos16[1] - d16 * cos16[3] - d25 * cos16[5] - d34 * cos16[7]
        var a3 = d07 * cos16[7] - d16 * cos16[5] + d25 * cos16[3] - d34 * cos16[1]
        var b0 = -d07 * cos16[3] + d16 * cos16[7] + d25 * cos16[1] + d34 * cos16[5]

        val shr = 12 + scale
        val round = 1 shl (shr - 1)
        a0 = (a0 + round) shr shr
        b0 = (b0 + round) shr shr
        a1 = (a1 + round) shr shr
        b1 = (b1 + round) shr shr
        a2 = (a2 + round) shr shr
        b2 = (b2 + round) shr shr
        a3 = (a3 + round) shr shr
        b3 = (b3 + round) shr shr

        out0[0][idx] = saturate16(a0)
        out0[7][idx] = saturate16(-a1)
        out0[1][idx] = saturate16(a1)
        out0[6][idx] = saturate16(-a2)
        out0[2][idx] = saturate16(a2)
        out0[5][idx] = saturate16(-a3)
        out0[3][idx] = saturate16(a3)
        out0[4][idx] = 0

        out1[0][idx] = saturate16(-a0)
        out1[7][idx] = saturate16(b0)
        out1[1][idx] = saturate16(b0)
        out1[6][idx] = saturate16(b1)
        out1[2][idx] = saturate16(b1)
        out1[5][idx] = saturate16(b2)
        out1[3][idx] = saturate16(b2)
        out1[4][idx] = saturate16(b3)
    }

    private fun applyWindow(
        input: Array<IntArray>,
        index: Int,
        output: IntArray,
        offset: Int,
        subbands: Int,
        window: Array<IntArray>,
    ) {
        for (i in 0 until subbands) {
            var s = 0
            for (j in 0 until 10) s += input[i][j] * window[i][index + j]
            output[offset + i] = saturate16((s + (1 shl 12)) shr 13)
        }
    }
}

/** PCM to SBC frames. One instance per audio stream: it carries the analysis filter's history. */
internal class SbcEncoder {
    private class State {
        var index = 0
        val x = Array(2) { Array(SbcFrame.MAX_SUBBANDS) { IntArray(5) } }
        val y = IntArray(4)
        fun reset() {
            index = 0
            for (odd in 0..1) for (sb in 0 until SbcFrame.MAX_SUBBANDS) x[odd][sb].fill(0)
            y.fill(0)
        }
    }

    private val states = arrayOf(State(), State())

    fun reset() {
        states[0].reset()
        states[1].reset()
    }

    /**
     * Encodes `frame.blocks * frame.subbands` samples of [pcmLeft] (and [pcmRight] for a two-channel mode) into one
     * frame, or returns null when the parameters or the input are unusable.
     */
    fun encode(pcmLeft: ShortArray, pcmRight: ShortArray?, frame: SbcFrame, leftOffset: Int = 0): ByteArray? {
        if (!frame.isValid()) return null
        val frameSize = frame.frameSize()
        val perChannel = frame.samplesPerChannel
        if (pcmLeft.size - leftOffset < perChannel) return null
        if (frame.mode != SbcMode.MONO && (pcmRight == null || pcmRight.size < perChannel)) return null

        val sbSamples = arrayOf(IntArray(SbcFrame.MAX_SAMPLES), IntArray(SbcFrame.MAX_SAMPLES))
        analyze(states[0], frame, pcmLeft, leftOffset, sbSamples[0])
        if (frame.mode != SbcMode.MONO && pcmRight != null) analyze(states[1], frame, pcmRight, 0, sbSamples[1])

        val output = ByteArray(frameSize)
        val dataBits = SbcBitStream(output, frameSize, false)
        dataBits.putBits(0, SbcFrame.HEADER_SIZE * 8)
        encodeFrameData(dataBits, frame, sbSamples)
        dataBits.flush()
        if (dataBits.hasError) return null

        val headerBits = SbcBitStream(output, SbcFrame.HEADER_SIZE, false)
        headerBits.putBits(SbcFrame.SYNCWORD, 8)
        headerBits.putBits(frame.frequency.ordinal, 2)
        headerBits.putBits((frame.blocks shr 2) - 1, 2)
        headerBits.putBits(frame.mode.ordinal, 2)
        headerBits.putBits(frame.allocation.ordinal, 1)
        headerBits.putBits((frame.subbands shr 2) - 1, 1)
        headerBits.putBits(frame.bitpool, 8)
        headerBits.putBits(0, 8)
        headerBits.flush()
        if (headerBits.hasError) return null

        val crc = computeCrc(frame, output, frameSize)
        if (crc < 0) return null
        output[3] = crc.toByte()
        return output
    }

    private fun encodeFrameData(bits: SbcBitStream, frame: SbcFrame, sbSamples: Array<IntArray>) {
        val nchannels = if (frame.mode != SbcMode.MONO) 2 else 1
        val nsubbands = frame.subbands

        val scaleFactors = Array(2) { IntArray(SbcFrame.MAX_SUBBANDS) }
        var mjoint = 0
        if (frame.mode == SbcMode.JOINT_STEREO) {
            mjoint = computeScaleFactorsJointStereo(frame, sbSamples, scaleFactors)
        } else {
            computeScaleFactors(frame, sbSamples, scaleFactors)
        }
        if (frame.mode == SbcMode.DUAL_CHANNEL) {
            // The reference runs the single-channel pass on channel 1 again, writing it to slot 0 of a one-row view.
            val sb1 = arrayOf(sbSamples[1])
            val sf1 = arrayOf(scaleFactors[1])
            computeScaleFactors(frame, sb1, sf1)
        }

        if (frame.mode == SbcMode.JOINT_STEREO) {
            if (nsubbands == 4) {
                bits.putBits(((mjoint and 0x01) shl 3) or ((mjoint and 0x02) shl 1) or ((mjoint and 0x04) shr 1), 4)
            } else {
                bits.putBits(
                    ((mjoint and 0x01) shl 7) or ((mjoint and 0x02) shl 5) or ((mjoint and 0x04) shl 3) or
                        ((mjoint and 0x08) shl 1) or ((mjoint and 0x10) shr 1) or ((mjoint and 0x20) shr 3) or
                        ((mjoint and 0x40) shr 5),
                    8,
                )
            }
        }

        for (ch in 0 until nchannels) for (sb in 0 until nsubbands) bits.putBits(scaleFactors[ch][sb], 4)

        val nbits = Array(2) { IntArray(SbcFrame.MAX_SUBBANDS) }
        computeBitAllocation(frame, scaleFactors, nbits)
        if (frame.mode == SbcMode.DUAL_CHANNEL) {
            val sf1 = arrayOf(scaleFactors[1])
            val nb1 = arrayOf(nbits[1])
            computeBitAllocation(frame, sf1, nb1)
        }

        for (sb in 0 until nsubbands) {
            if (((mjoint shr sb) and 1) == 0) continue
            for (blk in 0 until frame.blocks) {
                val idx = blk * nsubbands + sb
                val s0 = sbSamples[0][idx]
                val s1 = sbSamples[1][idx]
                sbSamples[0][idx] = (s0 + s1) shr 1
                sbSamples[1][idx] = (s0 - s1) shr 1
            }
        }

        for (blk in 0 until frame.blocks) {
            for (ch in 0 until nchannels) {
                for (sb in 0 until nsubbands) {
                    val nbit = nbits[ch][sb]
                    if (nbit == 0) continue
                    val scf = scaleFactors[ch][sb]
                    val sample = sbSamples[ch][blk * nsubbands + sb]
                    val range = (1 shl nbit) - 1
                    val quantized = (((sample * range) shr (scf + 1)) + range) shr 1
                    bits.putBits(quantized, nbit)
                }
            }
        }

        val paddingBits = 8 - (bits.bitPosition % 8)
        if (paddingBits < 8) bits.putBits(0, paddingBits)
    }

    private fun computeScaleFactorsJointStereo(
        frame: SbcFrame,
        sbSamples: Array<IntArray>,
        scaleFactors: Array<IntArray>,
    ): Int {
        var mjoint = 0
        for (sb in 0 until frame.subbands) {
            var m0 = 0
            var m1 = 0
            var mj0 = 0
            var mj1 = 0
            for (blk in 0 until frame.blocks) {
                val idx = blk * frame.subbands + sb
                val s0 = sbSamples[0][idx]
                val s1 = sbSamples[1][idx]
                m0 = m0 or kotlin.math.abs(s0)
                m1 = m1 or kotlin.math.abs(s1)
                mj0 = mj0 or kotlin.math.abs(s0 + s1)
                mj1 = mj1 or kotlin.math.abs(s0 - s1)
            }
            var scf0 = if (m0 != 0) 31 - m0.countLeadingZeroBits() else 0
            var scf1 = if (m1 != 0) 31 - m1.countLeadingZeroBits() else 0
            val js0 = if (mj0 != 0) 31 - mj0.countLeadingZeroBits() else 0
            val js1 = if (mj1 != 0) 31 - mj1.countLeadingZeroBits() else 0
            if (sb < frame.subbands - 1 && js0 + js1 < scf0 + scf1) {
                mjoint = mjoint or (1 shl sb)
                scf0 = js0
                scf1 = js1
            }
            scaleFactors[0][sb] = scf0
            scaleFactors[1][sb] = scf1
        }
        return mjoint
    }

    private fun computeScaleFactors(frame: SbcFrame, sbSamples: Array<IntArray>, scaleFactors: Array<IntArray>) {
        val nchannels = if (frame.mode != SbcMode.MONO) 2 else 1
        for (ch in 0 until nchannels) {
            // A dual-channel second pass hands in a one-row view, so never read past what was handed in.
            if (ch >= sbSamples.size) break
            for (sb in 0 until frame.subbands) {
                var m = 0
                for (blk in 0 until frame.blocks) m = m or kotlin.math.abs(sbSamples[ch][blk * frame.subbands + sb])
                scaleFactors[ch][sb] = if (m != 0) 31 - m.countLeadingZeroBits() else 0
            }
        }
    }

    private fun analyze(state: State, frame: SbcFrame, input: ShortArray, inputOffset: Int, output: IntArray) {
        for (blk in 0 until frame.blocks) {
            val inOffset = inputOffset + blk * frame.subbands
            val outOffset = blk * frame.subbands
            if (frame.subbands == 4) analyze4(state, input, inOffset, output, outOffset)
            else analyze8(state, input, inOffset, output, outOffset)
        }
    }

    private fun sample(input: ShortArray, at: Int): Int = if (at < input.size) input[at].toInt() else 0

    private fun analyze4(state: State, input: ShortArray, inOffset: Int, output: IntArray, outOffset: Int) {
        val window = SbcEncoderTables.window4
        val window1 = SbcEncoderTables.window4b
        val cos8 = SbcTables.cos8

        val idx = state.index shr 1
        val odd = state.index and 1
        val inIdx = if (idx != 0) 5 - idx else 0
        val x = state.x[odd]

        x[0][inIdx] = sample(input, inOffset + 3)
        x[1][inIdx] = sample(input, inOffset + 1)
        x[2][inIdx] = sample(input, inOffset + 2)
        x[3][inIdx] = sample(input, inOffset + 0)

        var y0 = 0
        var y1 = 0
        var y3 = 0
        for (j in 0 until 5) {
            y0 += x[0][j] * window[0][idx + j]
            y1 += x[2][j] * window[2][idx + j] + x[3][j] * window[3][idx + j]
            y3 += x[1][j] * window[1][idx + j]
        }
        y0 += state.y[0]
        state.y[0] = 0
        for (j in 0 until 5) state.y[0] += x[0][j] * window1[0][idx + j]

        val y2 = state.y[1]
        state.y[1] = 0
        for (j in 0 until 5) state.y[1] += x[2][j] * window1[2][idx + j] - x[3][j] * window1[3][idx + j]

        val y = intArrayOf(
            saturate16((y0 + (1 shl 14)) shr 15),
            saturate16((y1 + (1 shl 14)) shr 15),
            saturate16((y2 + (1 shl 14)) shr 15),
            saturate16((y3 + (1 shl 14)) shr 15),
        )
        state.index = if (state.index < 9) state.index + 1 else 0

        val s0 = y[0] * cos8[2] + y[1] * cos8[1] + y[2] * cos8[3] + (y[3] shl 13)
        val s1 = -y[0] * cos8[2] + y[1] * cos8[3] - y[2] * cos8[1] + (y[3] shl 13)
        val s2 = -y[0] * cos8[2] - y[1] * cos8[3] + y[2] * cos8[1] + (y[3] shl 13)
        val s3 = y[0] * cos8[2] - y[1] * cos8[1] - y[2] * cos8[3] + (y[3] shl 13)

        output[outOffset + 0] = saturate16((s0 + (1 shl 12)) shr 13)
        output[outOffset + 1] = saturate16((s1 + (1 shl 12)) shr 13)
        output[outOffset + 2] = saturate16((s2 + (1 shl 12)) shr 13)
        output[outOffset + 3] = saturate16((s3 + (1 shl 12)) shr 13)
    }

    private fun analyze8(state: State, input: ShortArray, inOffset: Int, output: IntArray, outOffset: Int) {
        val window = SbcEncoderTables.window8
        val window1 = SbcEncoderTables.window8b
        val cosmat = SbcEncoderTables.cosMatrix8

        val idx = state.index shr 1
        val odd = state.index and 1
        val inIdx = if (idx != 0) 5 - idx else 0
        val x = state.x[odd]

        x[0][inIdx] = sample(input, inOffset + 7)
        x[1][inIdx] = sample(input, inOffset + 3)
        x[2][inIdx] = sample(input, inOffset + 6)
        x[3][inIdx] = sample(input, inOffset + 0)
        x[4][inIdx] = sample(input, inOffset + 5)
        x[5][inIdx] = sample(input, inOffset + 1)
        x[6][inIdx] = sample(input, inOffset + 4)
        x[7][inIdx] = sample(input, inOffset + 2)

        val yTemp = IntArray(8)
        for (i in 0 until 8) {
            var acc = 0
            for (j in 0 until 5) acc += x[i][j] * window[i][idx + j]
            yTemp[i] = acc
        }

        val y0 = yTemp[0] + state.y[0]
        val y1 = yTemp[2] + yTemp[3]
        val y2 = yTemp[4] + yTemp[5]
        val y3 = yTemp[6] + yTemp[7]
        val y4 = state.y[1]
        val y5 = state.y[2]
        val y6 = state.y[3]
        val y7 = yTemp[1]

        state.y.fill(0)
        for (j in 0 until 5) {
            state.y[0] += x[0][j] * window1[0][idx + j]
            state.y[1] += x[2][j] * window1[2][idx + j] - x[3][j] * window1[3][idx + j]
            state.y[2] += x[4][j] * window1[4][idx + j] - x[5][j] * window1[5][idx + j]
            state.y[3] += x[6][j] * window1[6][idx + j] - x[7][j] * window1[7][idx + j]
        }

        val y = intArrayOf(y0, y1, y2, y3, y4, y5, y6, y7).map { saturate16((it + (1 shl 14)) shr 15) }
        state.index = if (state.index < 9) state.index + 1 else 0

        for (i in 0 until 8) {
            var s = 0
            for (j in 0 until 8) s += y[j] * cosmat[i][j]
            output[outOffset + i] = saturate16((s + (1 shl 12)) shr 13)
        }
    }
}
