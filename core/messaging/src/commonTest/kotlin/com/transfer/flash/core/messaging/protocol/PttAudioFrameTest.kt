package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PttAudioFrameTest {
    @Test
    fun roundTrip() {
        val frame = PttAudioFrame(
            sessionId = "session-uuid-1234",
            seq = 42L,
            captureTsMs = 1757336400123L,
            pcm = byteArrayOf(0x01, 0x02, 0x7F, 0x7F.toByte()),
        )

        assertEquals(frame, PttAudioFrame.decode(PttAudioFrame.encode(frame)))
    }

    @Test
    fun goldenBytes() {
        // sessionId "s", seq 1, captureTs 2, pcm [0x01, 0x02].
        val expected = byteArrayOf(
            0x50, 0x54, 0x54, 0x31, // "PTT1"
            0x01, // version
            0x01, 0x00, // sessionLen = 1 LE
            0x73, // 's'
            0x01, 0x00, 0x00, 0x00, // seq = 1 LE
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // captureTs = 2 LE
            0x01, 0x02, // pcm
        )
        val frame = PttAudioFrame("s", 1L, 2L, byteArrayOf(0x01, 0x02))

        assertContentEquals(expected, PttAudioFrame.encode(frame))
        assertEquals(frame, PttAudioFrame.decode(expected))
    }

    @Test
    fun isPttAudioPreCheck() {
        assertTrue(PttAudioFrame.isPttAudio(byteArrayOf(0x50, 0x54, 0x54, 0x31, 0x01)))
        assertFalse(PttAudioFrame.isPttAudio(byteArrayOf(0x46, 0x4C, 0x53, 0x48, 0x02))) // "FLSH2"
        assertFalse(PttAudioFrame.isPttAudio(byteArrayOf(0x50, 0x54)))
        assertFalse(PttAudioFrame.isPttAudio(byteArrayOf()))
    }

    @Test
    fun negotiatedPcmSizeIsEnforced() {
        val medium = PttAudioFrame("s", 1L, 2L, ByteArray(640))
        val low = PttAudioFrame("s", 1L, 2L, ByteArray(960))

        assertEquals(640, PttAudioFrame.expectedPcmBytes(16000, 20))
        assertEquals(960, PttAudioFrame.expectedPcmBytes(8000, 60))
        assertTrue(PttAudioFrame.hasExpectedPcmSize(medium, 16000, 20))
        assertTrue(PttAudioFrame.hasExpectedPcmSize(low, 8000, 60))
        assertFalse(PttAudioFrame.hasExpectedPcmSize(medium, 8000, 60))
        val encodedMedium = PttAudioFrame.encode(medium)
        assertEquals(medium, PttAudioFrame.decodeExpectedSize(encodedMedium, 640))
        assertNull(PttAudioFrame.decodeExpectedSize(encodedMedium, 960))
        assertNull(PttAudioFrame.expectedPcmBytes(44100, 20))
        assertNull(PttAudioFrame.expectedPcmBytes(16000, 10))
    }

    @Test
    fun encodeRejectsInvalidLocalFrames() {
        assertFailsWith<IllegalArgumentException> {
            PttAudioFrame.encode(PttAudioFrame(" ", 1L, 2L, byteArrayOf(0x01, 0x02)))
        }
        assertFailsWith<IllegalArgumentException> {
            PttAudioFrame.encode(PttAudioFrame("s", 1L, 2L, byteArrayOf()))
        }
        assertFailsWith<IllegalArgumentException> {
            PttAudioFrame.encode(PttAudioFrame("s", 1L, 2L, byteArrayOf(0x01)))
        }
    }

    @Test
    fun corruptFramesAreRejected() {
        val good = PttAudioFrame.encode(PttAudioFrame("s", 1L, 2L, byteArrayOf(0x01, 0x02)))
        // Truncated.
        assertNull(PttAudioFrame.decode(good.copyOf(10)))
        // Wrong version.
        assertNull(PttAudioFrame.decode(good.copyOf().also { it[4] = 0x02 }))
        // Odd pcm length.
        assertNull(PttAudioFrame.decode(good + 0x03))
        // Empty pcm.
        assertNull(PttAudioFrame.decode(good.copyOf(good.size - 2)))
    }
}
