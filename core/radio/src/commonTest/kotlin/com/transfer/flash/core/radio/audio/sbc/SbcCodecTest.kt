package com.transfer.flash.core.radio.audio.sbc

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SbcCodecTest {

    private fun radioFrame(bitpool: Int) = SbcFrame(
        frequency = SbcFrequency.F32K,
        mode = SbcMode.MONO,
        allocation = SbcAllocation.LOUDNESS,
        blocks = 16,
        subbands = 8,
        bitpool = bitpool,
    )

    /** A speech-like test signal: a few tones with a slow amplitude envelope. */
    private fun signal(samples: Int, rate: Int = 32000): ShortArray = ShortArray(samples) { i ->
        val t = i.toDouble() / rate
        val envelope = 0.6 + 0.4 * sin(2 * PI * 3 * t)
        val v = 9000 * sin(2 * PI * 220 * t) + 5000 * sin(2 * PI * 1250 * t) + 2500 * sin(2 * PI * 3100 * t)
        (v * envelope).toInt().toShort()
    }

    private fun encodeAll(pcm: ShortArray, frame: SbcFrame): List<ByteArray> {
        val encoder = SbcEncoder()
        val per = frame.samplesPerChannel
        return (0 until pcm.size / per).map { n ->
            assertNotNull(encoder.encode(pcm, null, frame, leftOffset = n * per), "frame $n")
        }
    }

    private fun decodeAll(frames: List<ByteArray>): ShortArray {
        val decoder = SbcDecoder()
        val out = ArrayList<Short>()
        for (f in frames) {
            val r = decoder.decode(f)
            assertTrue(r.success, "decode failed")
            r.pcmLeft.forEach { out.add(it) }
        }
        return out.toShortArray()
    }

    /** Best signal-to-noise ratio over every alignment up to [maxLag], because the codec delays the signal. */
    private fun snrDb(reference: ShortArray, decoded: ShortArray, maxLag: Int = 200): Double {
        var best = Double.NEGATIVE_INFINITY
        val skip = 400 // let both filters settle
        for (lag in 0..maxLag) {
            var signalEnergy = 0.0
            var noiseEnergy = 0.0
            for (i in skip until decoded.size - lag) {
                val ref = reference[i].toDouble()
                val dec = decoded[i + lag].toDouble()
                signalEnergy += ref * ref
                noiseEnergy += (ref - dec) * (ref - dec)
            }
            if (noiseEnergy > 0) best = maxOf(best, 10 * log10(signalEnergy / noiseEnergy))
        }
        return best
    }

    @Test
    fun theRadiosFrameShapeHasTheKnownSizeAndHeader() {
        val frame = radioFrame(bitpool = 18)
        // 4 header + (8 scale factors * 4 bits + 16 blocks * 18 bits) / 8 = 4 + 40
        assertEquals(44, frame.frameSize())
        val encoded = assertNotNull(SbcEncoder().encode(ShortArray(128), null, frame))
        assertEquals(44, encoded.size)
        // sync 0x9C, 32 kHz | 16 blocks | mono | loudness | 8 subbands = 0b01_11_00_0_1 = 0x71, bitpool 0x12
        assertEquals(0x9C, encoded[0].toInt() and 0xFF)
        assertEquals(0x71, encoded[1].toInt() and 0xFF)
        assertEquals(0x12, encoded[2].toInt() and 0xFF)
    }

    @Test
    fun probeReadsTheHeaderWithoutDecoding() {
        val encoded = assertNotNull(SbcEncoder().encode(signal(128), null, radioFrame(40)))
        val probed = assertNotNull(SbcDecoder().probe(encoded))
        assertEquals(SbcFrequency.F32K, probed.frequency)
        assertEquals(SbcMode.MONO, probed.mode)
        assertEquals(16, probed.blocks)
        assertEquals(8, probed.subbands)
        assertEquals(40, probed.bitpool)
        assertEquals(encoded.size, probed.frameSize())
    }

    @Test
    fun aRoundTripKeepsTheSignalAtTheRadiosDefaultBitpool() {
        val pcm = signal(32000)
        val decoded = decodeAll(encodeAll(pcm, radioFrame(18)))
        val snr = snrDb(pcm, decoded)
        println("SBC 32k mono bitpool 18 round-trip SNR = $snr dB")
        // A swapped or missing window coefficient collapses this to about 5-9 dB.
        assertTrue(snr > 20.0, "SNR was $snr dB")
    }

    @Test
    fun aHigherBitpoolIsCleaner() {
        val pcm = signal(32000)
        val low = snrDb(pcm, decodeAll(encodeAll(pcm, radioFrame(18))))
        val high = snrDb(pcm, decodeAll(encodeAll(pcm, radioFrame(40))))
        println("SBC bitpool 40 round-trip SNR = $high dB (bitpool 18: $low dB)")
        assertTrue(high > low + 5.0, "bitpool 40 gave $high dB against $low dB")
        assertTrue(high > 35.0, "SNR was $high dB")
    }

    @Test
    fun fourSubbandsRoundTripToo() {
        val frame = SbcFrame(SbcFrequency.F32K, SbcMode.MONO, SbcAllocation.LOUDNESS, blocks = 16, subbands = 4, bitpool = 20)
        val pcm = signal(32000)
        val snr = snrDb(pcm, decodeAll(encodeAll(pcm, frame)))
        println("SBC 4 subbands round-trip SNR = $snr dB")
        assertTrue(snr > 20.0, "SNR was $snr dB")
    }

    @Test
    fun snrAllocationRoundTrips() {
        val frame = radioFrame(40).also { it.allocation = SbcAllocation.SNR }
        val pcm = signal(32000)
        val snr = snrDb(pcm, decodeAll(encodeAll(pcm, frame)))
        println("SBC snr-allocation round-trip SNR = $snr dB")
        assertTrue(snr > 25.0, "SNR was $snr dB")
    }

    @Test
    fun jointStereoRoundTrips() {
        val frame = SbcFrame(SbcFrequency.F44K1, SbcMode.JOINT_STEREO, SbcAllocation.LOUDNESS, blocks = 16, subbands = 8, bitpool = 53)
        val left = signal(44100, 44100)
        val right = ShortArray(left.size) { (left[it] / 2).toShort() }
        val enc = SbcEncoder()
        val dec = SbcDecoder()
        val per = frame.samplesPerChannel
        val outL = ArrayList<Short>()
        for (n in 0 until left.size / per) {
            val f = assertNotNull(enc.encode(left.copyOfRange(n * per, (n + 1) * per), right.copyOfRange(n * per, (n + 1) * per), frame))
            val r = dec.decode(f)
            assertTrue(r.success)
            assertNotNull(r.pcmRight)
            r.pcmLeft.forEach { outL.add(it) }
        }
        val snr = snrDb(left, outL.toShortArray())
        println("SBC joint stereo round-trip SNR = $snr dB")
        assertTrue(snr > 25.0, "SNR was $snr dB")
    }

    @Test
    fun aDamagedFrameIsRejectedByTheCrc() {
        val encoded = assertNotNull(SbcEncoder().encode(signal(128), null, radioFrame(40)))
        val bad = encoded.copyOf().also { it[2] = (it[2] + 1).toByte() } // bitpool changes, CRC no longer matches
        assertFalse(SbcDecoder().decode(bad).success)
        val badPayload = encoded.copyOf().also { it[1] = (it[1].toInt() xor 0x40).toByte() }
        assertFalse(SbcDecoder().decode(badPayload).success)
    }

    @Test
    fun garbageIsNotAFrame() {
        assertNull(SbcDecoder().probe(byteArrayOf(0x12, 0x34, 0x56, 0x78)))
        assertNull(SbcDecoder().probe(byteArrayOf(0x9C.toByte())))
        assertFalse(SbcDecoder().decode(ByteArray(3)).success)
    }

    @Test
    fun theSameInputGivesTheSameBytes() {
        val pcm = signal(2048)
        val a = encodeAll(pcm, radioFrame(18))
        val b = encodeAll(pcm, radioFrame(18))
        a.zip(b).forEach { (x, y) -> assertContentEquals(x, y) }
    }

    @Test
    fun silenceDecodesToSilence() {
        val frames = encodeAll(ShortArray(128 * 8), radioFrame(18))
        val decoded = decodeAll(frames)
        assertTrue(decoded.all { kotlin.math.abs(it.toInt()) <= 1 })
    }
}
