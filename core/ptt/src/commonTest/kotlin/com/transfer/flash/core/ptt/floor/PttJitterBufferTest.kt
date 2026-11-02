package com.transfer.flash.core.ptt.floor

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O12-04, the queue half: the jitter buffer used a `TreeMap`, which boxed a `Long` key, allocated a tree node and
 * then a `Poll.Ready` wrapper for every 20 ms packet (50 a second, 3 000 a minute). The ring keeps the same public
 * behaviour — order, gaps, duplicates, u32 wrap, late packets, overflow keeping the head — with none of that state.
 */
class PttJitterRingTest {

    private fun packet(fill: Int) = ByteArray(640) { ((it % 40) + fill).toByte() }

    @Test
    fun `packets are played in sequence and a gap is concealed`() {
        val buffer = PttJitterBuffer(targetDepthMs = 40L)
        buffer.push(0L, 0L, packet(0))
        assertEquals(PttJitterBuffer.Poll.Starving, buffer.poll(20), "one packet is not 40 ms yet")
        buffer.push(1L, 20, packet(1))

        assertEquals(0L, (buffer.poll(20) as PttJitterBuffer.Poll.Ready).seq)
        assertEquals(1L, (buffer.poll(20) as PttJitterBuffer.Poll.Ready).seq)

        // seq 3 and 4 arrive, 2 never did: the buffer conceals exactly the missing slot and moves on.
        buffer.push(3L, 60, packet(3))
        assertEquals(2L, (buffer.poll(20) as PttJitterBuffer.Poll.Concealed).seq)
        assertEquals(3L, (buffer.poll(20) as PttJitterBuffer.Poll.Ready).seq)

        buffer.push(5L, 100, packet(5))
        assertEquals(4L, (buffer.poll(20) as PttJitterBuffer.Poll.Concealed).seq, "still one step at a time")
        assertEquals(5L, (buffer.poll(20) as PttJitterBuffer.Poll.Ready).seq)
        assertEquals(2L, buffer.stats().lost)
    }

    @Test
    fun `the u32 sequence wraps as one stream`() {
        val buffer = PttJitterBuffer(targetDepthMs = 60L)
        val last = 0xFFFF_FFFEL
        listOf(last, last + 1, 0L, 1L).forEachIndexed { i, seq -> buffer.push(seq, i * 20L, packet(i)) }

        for (i in 0 until 4) {
            val poll = buffer.poll(20)
            assertTrue(poll is PttJitterBuffer.Poll.Ready, "packet $i after the wrap must be ready, was $poll")
        }
        assertEquals(0L, buffer.stats().lost, "a wrap is not a loss")
    }

    @Test
    fun `a duplicate is counted, not queued twice`() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(7L, 0L, packet(7))
        buffer.push(7L, 0L, packet(7))
        buffer.push(7L, 0L, packet(7))

        assertEquals(1, buffer.stats().depthPackets, "the duplicate is not a second queued packet")
        assertTrue(buffer.poll(20) is PttJitterBuffer.Poll.Ready)
        assertEquals(2L, buffer.stats().duplicates)
        assertEquals(0, buffer.stats().depthPackets)
    }

    @Test
    fun `a packet whose slot has passed is late`() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(0L, 0L, packet(0))
        buffer.push(1L, 20, packet(1))
        assertTrue(buffer.poll(20) is PttJitterBuffer.Poll.Ready)

        buffer.push(0L, 0L, packet(0)) // already played
        assertEquals(1L, buffer.stats().late)
    }

    @Test
    fun `overflow drops the newest arrival, never the packet about to play`() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L, maxPackets = 8)
        // Not started yet (target 20 ms is one packet, so start on the first poll): fill past capacity.
        for (seq in 0L until 12L) buffer.push(seq, seq * 20, packet(seq.toInt()))

        assertEquals(8, buffer.stats().depthPackets, "capacity is kept")
        assertEquals(4L, buffer.stats().droppedOverflow)
        assertEquals(0L, (buffer.poll(20) as PttJitterBuffer.Poll.Ready).seq, "the head is still there")
        assertEquals(1L, (buffer.poll(20) as PttJitterBuffer.Poll.Ready).seq, "and play continues in order")
    }

    @Test
    fun `reset clears the queue and the counters`() {
        val buffer = PttJitterBuffer(targetDepthMs = 20L)
        buffer.push(0L, 0L, packet(0))
        buffer.push(0L, 0L, packet(0))
        buffer.reset()

        val stats = buffer.stats()
        assertEquals(0, stats.depthPackets)
        assertEquals(0L, stats.received)
        assertEquals(0L, stats.duplicates)
        assertEquals(PttJitterBuffer.Poll.Starving, buffer.poll(20))
    }
}

/**
 * OPT-O12-04, the adaptation half: the start gate is the only part of the jitter buffer that can be learnt across
 * bursts (once playout runs, the tick rate paces it). These tests pin the rules the engine relies on: a short burst
 * is not evidence, a clean long burst buys latency back, a lossy one spends it, and neither can run away.
 */
class PttJitterDepthTest {

    @BeforeTest
    fun resetBefore() = PttJitterDepth.reset()

    @AfterTest
    fun resetAfter() = PttJitterDepth.reset()

    @Test
    fun `it starts from the shipped 120 ms`() {
        assertEquals(120L, PttJitterDepth.targetDepthMs())
    }

    @Test
    fun `a short burst teaches nothing`() {
        PttJitterDepth.observe(readyTotal = 10, concealedTotal = 10)
        assertEquals(PttJitterDepth.DEFAULT_MS, PttJitterDepth.targetDepthMs())
    }

    @Test
    fun `a clean long burst lowers the gate one step per burst and stops at the floor`() {
        repeat(10) { PttJitterDepth.observe(readyTotal = 300, concealedTotal = 0) }
        assertEquals(PttJitterDepth.MIN_MS, PttJitterDepth.targetDepthMs())
    }

    @Test
    fun `a long burst with no loss inside the judgement window does not lower it`() {
        // 200 packets is the floor for "long": 199 concealed-free packets are judged but not "clean enough".
        PttJitterDepth.observe(readyTotal = PttJitterDepth.CLEAN_PACKETS - 1, concealedTotal = 0)
        assertEquals(PttJitterDepth.DEFAULT_MS, PttJitterDepth.targetDepthMs())
    }

    @Test
    fun `concealment above two percent raises the gate and stops at the ceiling`() {
        repeat(10) { PttJitterDepth.observe(readyTotal = 100, concealedTotal = 10) }
        assertEquals(PttJitterDepth.MAX_MS, PttJitterDepth.targetDepthMs())
    }

    @Test
    fun `one concealed packet in a hundred is inside the tolerate band`() {
        PttJitterDepth.observe(readyTotal = 99, concealedTotal = 1)
        assertEquals(PttJitterDepth.DEFAULT_MS, PttJitterDepth.targetDepthMs())
    }

    @Test
    fun `a bad burst raises it and the next clean ones walk it back down`() {
        PttJitterDepth.observe(readyTotal = 100, concealedTotal = 10)
        assertEquals(PttJitterDepth.DEFAULT_MS + PttJitterDepth.STEP_MS, PttJitterDepth.targetDepthMs())

        PttJitterDepth.observe(readyTotal = 300, concealedTotal = 0)
        assertEquals(PttJitterDepth.DEFAULT_MS, PttJitterDepth.targetDepthMs())
    }
}
