package com.transfer.flash.core.messaging.ptt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PttJitterBufferTest {
    private fun packet(seq: Long): ByteArray = byteArrayOf(seq.toByte(), 0x00)

    @Test
    fun starvesUntilDepthFillThenPlaysInOrder() {
        val buffer = PttJitterBuffer(targetDepthMs = 60L)
        buffer.push(0L, 0L, packet(0L))
        buffer.push(1L, 20L, packet(1L))

        assertEquals(PttJitterBuffer.Poll.Starving, buffer.poll(20L))

        buffer.push(2L, 40L, packet(2L))
        val first = buffer.poll(20L)
        assertTrue(first is PttJitterBuffer.Poll.Ready)
        assertEquals(0L, first.seq)
        val second = buffer.poll(20L)
        assertTrue(second is PttJitterBuffer.Poll.Ready)
        assertEquals(1L, second.seq)
    }

    @Test
    fun reordersOutOfOrderArrival() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(2L, 40L, packet(2L))
        buffer.push(0L, 0L, packet(0L))
        buffer.push(1L, 20L, packet(1L))

        val first = buffer.poll(20L)
        assertTrue(first is PttJitterBuffer.Poll.Ready)
        assertEquals(0L, first.seq)
    }

    @Test
    fun gapConcealsAndCountsLoss() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(0L, 0L, packet(0L))
        buffer.push(2L, 40L, packet(2L))

        assertTrue(buffer.poll(20L) is PttJitterBuffer.Poll.Ready)
        val concealed = buffer.poll(20L)
        assertTrue(concealed is PttJitterBuffer.Poll.Concealed)
        assertEquals(1L, concealed.seq)
        assertEquals(1L, buffer.stats().lost)

        val resumed = buffer.poll(20L)
        assertTrue(resumed is PttJitterBuffer.Poll.Ready)
        assertEquals(2L, resumed.seq)
    }

    @Test
    fun lateAndDuplicatePacketsAreDropped() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(0L, 0L, packet(0L))
        buffer.poll(20L) // consumes seq 0, nextSeq = 1.

        buffer.push(0L, 0L, packet(0L)) // already consumed: counts late, not duplicate.
        buffer.push(5L, 100L, packet(5L))
        buffer.push(5L, 100L, packet(5L)) // duplicate.

        assertEquals(1L, buffer.stats().duplicates)
    }

    @Test
    fun consumedSeqArrivingLateCountsLate() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(0L, 0L, packet(0L))
        buffer.push(1L, 20L, packet(1L))
        buffer.poll(20L) // consumes 0.
        buffer.poll(20L) // consumes 1, nextSeq = 2.

        buffer.push(0L, 0L, packet(0L)) // older than nextSeq.
        assertEquals(1L, buffer.stats().late)
    }

    @Test
    fun overflowDropsOldest() {
        val buffer = PttJitterBuffer(targetDepthMs = 1000L, maxPackets = 3)
        buffer.push(0L, 0L, packet(0L))
        buffer.push(1L, 20L, packet(1L))
        buffer.push(2L, 40L, packet(2L))
        buffer.push(3L, 60L, packet(3L))

        assertEquals(1L, buffer.stats().droppedOverflow)
        assertEquals(3, buffer.stats().depthPackets)
    }

    @Test
    fun resetClearsAll() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(0L, 0L, packet(0L))
        buffer.poll(20L)
        buffer.reset()

        assertEquals(PttJitterBuffer.Stats(0L, 0L, 0L, 0L, 0L, 0), buffer.stats())
        assertEquals(PttJitterBuffer.Poll.Starving, buffer.poll(20L))
    }
}

class PttAudioLevelTest {
    @Test
    fun silenceIsZero() {
        assertEquals(0f, PttAudioLevel.rms01(ByteArray(64)))
        assertEquals(0f, PttAudioLevel.rms01(ByteArray(0)))
        assertEquals(0f, PttAudioLevel.rms01(ByteArray(1)))
    }

    @Test
    fun fullScaleSquareIsOne() {
        // 0x7FFF LE repeated = max positive square wave.
        val pcm = ByteArray(64) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        val level = PttAudioLevel.rms01(pcm)

        assertTrue(level > 0.99f, "expected ~1, was $level")
        assertTrue(level <= 1f)
    }

    @Test
    fun halfAmplitudeIsHalf() {
        // 0x4000 LE repeated ≈ half scale.
        val pcm = ByteArray(64) { i -> if (i % 2 == 0) 0x00.toByte() else 0x40.toByte() }
        val level = PttAudioLevel.rms01(pcm)

        assertTrue(level > 0.49f && level < 0.51f, "expected ~0.5, was $level")
    }
}
