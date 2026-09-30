package com.transfer.flash.core.ptt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The jitter / concealment half of playout, shared by the Android and the desktop device. */
class PttPlayoutCoreTest {
    // 16 kHz, 20 ms: 640 bytes per packet; the 120 ms target depth needs 6 queued packets to start.
    private fun newCore() = PttPlayoutCore(sampleRateHz = 16_000, packetMs = 20)

    private fun packet(fill: Int) = ByteArray(PACKET_BYTES) { ((it % 50) + fill).toByte() }

    private fun PttPlayoutCore.offerRun(seqs: List<Long>) {
        seqs.forEach { assertTrue(offer(it, it * 20, packet(it.toInt() + 1))) }
    }

    @Test
    fun `a packet outside the negotiated size is rejected`() {
        val core = newCore()
        assertFalse(core.offer(0, 0, ByteArray(PACKET_BYTES - 2)))
        assertFalse(core.offer(0, 0, ByteArray(PACKET_BYTES + 2)))
        assertTrue(core.offer(0, 0, ByteArray(PACKET_BYTES)))
    }

    @Test
    fun `it plays silence and counts nothing until enough audio has buffered`() {
        val core = newCore()
        core.offerRun(listOf(0, 1, 2, 3, 4)) // one short of the 6-packet start depth

        val out = core.nextPacket()

        assertContentEquals(ByteArray(PACKET_BYTES), out)
        assertEquals(0L, core.snapshot().readyTotal)
        assertEquals(0f, core.lastAmplitude)
    }

    @Test
    fun `it plays the buffered packets in order once the start depth is reached`() {
        val core = newCore()
        core.offerRun(listOf(0, 1, 2, 3, 4, 5))

        for (seq in 0L..5L) {
            assertContentEquals(packet(seq.toInt() + 1), core.nextPacket(), "packet $seq")
        }

        val snapshot = core.snapshot()
        assertEquals(6L, snapshot.readyTotal)
        assertEquals(0L, snapshot.concealedTotal)
        assertTrue(core.lastAmplitude > 0f)
    }

    @Test
    fun `a missing packet is concealed by repeating the last one at half level`() {
        val core = newCore()
        core.offerRun(listOf(0, 1, 3, 4, 5, 6)) // seq 2 never arrives

        core.nextPacket() // 0
        core.nextPacket() // 1
        val readyLevel = core.lastAmplitude
        val concealed = core.nextPacket() // 2: the gap

        assertContentEquals(packet(2), concealed, "repeats packet 1 (fill = seq + 1)")
        assertEquals(1L, core.snapshot().concealedTotal)
        assertEquals(readyLevel * 0.5f, core.lastAmplitude, "concealed audio is played quieter")
        assertContentEquals(packet(4), core.nextPacket(), "audio resumes with seq 3")
    }

    @Test
    fun `it reports how many packets are waiting`() {
        val core = newCore()
        core.offerRun(listOf(0, 1, 2, 3, 4, 5, 6))

        core.nextPacket()

        assertEquals(6, core.snapshot().depthPackets)
    }

    private companion object {
        const val PACKET_BYTES = 640
    }
}
