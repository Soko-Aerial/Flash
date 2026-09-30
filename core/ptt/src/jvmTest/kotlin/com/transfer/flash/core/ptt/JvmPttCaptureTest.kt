package com.transfer.flash.core.ptt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmPttCaptureTest {
    private val lines = FakePcmLines()
    private val packets = Recorder<Pair<ByteArray, Long>>()
    private val lost = Recorder<Unit>()

    private fun capture(
        rateHz: Int = 16_000,
        stalledReadLimitMs: Long = 1_000L,
        clock: () -> Long = { 777L },
    ) = JvmPttCapture(
        lines = lines,
        requestedRateHz = rateHz,
        packetMs = 20,
        onPacket = { pcm, ts -> packets.add(pcm to ts) },
        onCaptureLost = { lost.add(Unit) },
        clockMs = clock,
        stalledReadLimitMs = stalledReadLimitMs,
    )

    private fun awaitUntil(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(2)
        }
    }

    private fun pcm(fill: Int) = ByteArray(640) { (it % 30 + fill).toByte() }

    @Test
    fun `it opens the requested rate and reports it`() {
        val capture = capture()

        val started = assertIs<PttCaptureStart.Started>(capture.start())

        assertEquals(16_000, started.actualRateHz)
        assertEquals(20, started.actualPacketMs)
        assertEquals(listOf(16_000), lines.inputRatesTried.snapshot())
        capture.stop()
    }

    @Test
    fun `it falls back to 8 kHz and 60 ms packets when the requested rate is refused`() {
        val capture = JvmPttCapture(FakePcmLines(setOf(8_000)), 16_000, 20, { _, _ -> }, {})

        val started = assertIs<PttCaptureStart.Started>(capture.start())

        assertEquals(8_000, started.actualRateHz)
        assertEquals(60, started.actualPacketMs)
        capture.stop()
    }

    @Test
    fun `it fails when no rate opens`() {
        val capture = JvmPttCapture(FakePcmLines(emptySet()), 16_000, 20, { _, _ -> }, {})

        assertEquals(PttCaptureStart.Failed, capture.start())
        capture.stop()
    }

    @Test
    fun `it delivers whole packets, and only once packets are enabled`() {
        val capture = capture()
        capture.start()
        val mic = lines.inputs.snapshot().single()
        val sound = pcm(5)

        // Armed but not enabled: read and discarded, nothing delivered.
        mic.feed(sound)
        awaitUntil("first packet consumed") { mic.consumedBytes >= 640 }
        assertEquals(0, packets.size)

        capture.enablePackets()
        mic.feed(sound.copyOfRange(0, 300))
        mic.feed(sound.copyOfRange(300, 640)) // a packet arrives in two partial reads
        awaitUntil("a packet delivered") { packets.size == 1 }

        val (delivered, timestamp) = packets.snapshot().single()
        assertContentEquals(sound, delivered)
        assertEquals(777L, timestamp)
        assertEquals(0, lost.size)
        capture.stop()
    }

    @Test
    fun `a read error while armed reports the loss once and releases the line`() {
        val capture = capture()
        capture.start()
        val mic = lines.inputs.snapshot().single()

        mic.failReads = true

        awaitUntil("loss reported") { lost.size == 1 }
        awaitUntil("line released") { mic.closed }
        Thread.sleep(50)
        assertEquals(1, lost.size, "reported exactly once")
        capture.stop()
        assertEquals(1, lost.size, "stop() after a loss does not report again")
    }

    @Test
    fun `a line that delivers nothing for too long is treated as lost`() {
        val capture = capture(stalledReadLimitMs = 40L, clock = { System.nanoTime() / 1_000_000L })
        capture.start()

        awaitUntil("stall reported as loss") { lost.size == 1 }
        assertTrue(lines.inputs.snapshot().single().closed)
        capture.stop()
    }

    @Test
    fun `stop frees the line and never reports a loss`() {
        val capture = capture()
        capture.start()
        val mic = lines.inputs.snapshot().single()
        capture.enablePackets()

        capture.stop()

        assertTrue(mic.stopped && mic.closed)
        Thread.sleep(50)
        assertEquals(0, lost.size)
    }

    @Test
    fun `start is idempotent while running`() {
        val capture = capture()
        capture.start()

        val again = assertIs<PttCaptureStart.Started>(capture.start())

        assertEquals(16_000, again.actualRateHz)
        assertEquals(1, lines.inputs.size, "no second line opened")
        capture.stop()
    }

    @Test
    fun `enablePackets before start is a no-op`() {
        val capture = capture()
        capture.enablePackets()
        capture.start()
        val mic = lines.inputs.snapshot().single()

        mic.feed(pcm(1))
        awaitUntil("packet consumed") { mic.consumedBytes >= 640 }

        assertFalse(packets.size > 0, "the gate only opens on a running capture")
        capture.stop()
    }
}
