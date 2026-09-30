package com.transfer.flash.core.ptt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmPttPlayoutTest {
    private val lost = Recorder<Unit>()

    private fun playout(lines: PttPcmLines) = JvmPttPlayout(
        lines = lines,
        sampleRateHz = 16_000,
        packetMs = 20,
        onPlayoutLost = { lost.add(Unit) },
    )

    private fun awaitUntil(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(2)
        }
    }

    private fun packet(fill: Int) = ByteArray(640) { ((it % 40) + fill).toByte() }

    @Test
    fun `start fails when the output line cannot be opened`() {
        val playout = playout(FakePcmLines(emptySet()))

        assertFalse(playout.start())
    }

    @Test
    fun `offer is refused before start and after stop`() {
        val lines = FakePcmLines()
        val playout = playout(lines)
        assertFalse(playout.offer(0, 0, packet(1)), "not started")

        assertTrue(playout.start())
        assertTrue(playout.offer(0, 0, packet(1)))
        playout.stop()

        assertFalse(playout.offer(1, 20, packet(2)), "stopped")
    }

    @Test
    fun `it plays silence first, then the offered packets in order`() {
        val lines = FakePcmLines()
        val playout = playout(lines)
        assertTrue(playout.start())
        val speaker = lines.outputs.snapshot().single()
        awaitUntil("silence written while starving") { speaker.writes.size >= 1 }

        for (seq in 0L..5L) assertTrue(playout.offer(seq, seq * 20, packet(seq.toInt() + 1)))

        awaitUntil("all six packets written") {
            val written = speaker.writes.snapshot()
            (0..5).all { k -> written.any { it.contentEquals(packet(k + 1)) } }
        }
        val written = speaker.writes.snapshot()
        assertTrue(written.all { it.size == 640 }, "one packet duration per write")
        val positions = (0..5).map { k -> written.indexOfFirst { it.contentEquals(packet(k + 1)) } }
        assertEquals(positions.sorted(), positions, "in sequence order")
        assertContentEquals(ByteArray(640), written.first(), "starving plays silence until the buffer has depth")
        assertTrue(playout.snapshot().readyTotal >= 6L)
        playout.stop()
        assertEquals(0, lost.size)
    }

    @Test
    fun `a failing write reports the loss once and releases the line`() {
        val speaker = FakePcmOutput(failAfterWrites = 3)
        val lines = object : PttPcmLines {
            override fun openInput(rateHz: Int, bufferBytes: Int): PttPcmInput? = null
            override fun openOutput(rateHz: Int, bufferBytes: Int): PttPcmOutput = speaker
        }
        val playout = playout(lines)

        assertTrue(playout.start())

        awaitUntil("loss reported") { lost.size == 1 }
        awaitUntil("line released") { speaker.closed }
        playout.stop()
        assertEquals(1, lost.size, "stop() after a loss does not report again")
    }

    @Test
    fun `stop flushes and frees the line and never reports a loss`() {
        val lines = FakePcmLines()
        val playout = playout(lines)
        playout.start()
        val speaker = lines.outputs.snapshot().single()

        playout.stop()

        assertTrue(speaker.flushed && speaker.stopped && speaker.closed)
        Thread.sleep(50)
        assertEquals(0, lost.size)
    }
}
