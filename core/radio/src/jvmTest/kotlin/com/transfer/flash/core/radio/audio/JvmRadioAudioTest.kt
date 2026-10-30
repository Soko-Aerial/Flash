package com.transfer.flash.core.radio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JvmRadioAudioTest {

    @Test
    fun radvU80_pcmBytesAreLittleEndianAndRoundTrip() {
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768, 0x1234)

        val bytes = pcmToBytes(pcm, pcm.size)

        assertEquals(12, bytes.size)
        assertEquals(0x34.toByte(), bytes[10])
        assertEquals(0x12.toByte(), bytes[11])
        val back = ShortArray(pcm.size)
        assertEquals(pcm.size, bytesToPcm(bytes, bytes.size, back))
        assertArrayEquals(pcm, back)
    }

    @Test
    fun radvU81_anOddTrailingByteAndAShortBufferAreHandled() {
        val into = ShortArray(2)
        assertEquals(1, bytesToPcm(byteArrayOf(1, 0, 9), 3, into))
        assertEquals(1, into[0].toInt())
        assertEquals(2, bytesToPcm(ByteArray(8), 8, into))
    }
}
