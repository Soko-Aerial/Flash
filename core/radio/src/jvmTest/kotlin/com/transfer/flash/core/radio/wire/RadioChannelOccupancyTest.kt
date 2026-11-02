package com.transfer.flash.core.radio.wire

import com.transfer.flash.core.radio.driver.InMemoryRadioStore
import com.transfer.flash.core.radio.driver.TxPacingPolicy
import com.transfer.flash.core.radio.kiss.Ax25Address
import com.transfer.flash.core.radio.kiss.Ax25FrameCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPT-O26-02: what a message actually costs on the air, measured through the **real** encoder, the **real** AX.25 codec
 * and the **real** pacing policy - not re-derived from the audit's table.
 *
 * The numbers are printed as `PERF-R O26-02` lines (EXP-133). They are arithmetic over the shipped constants, so they are
 * reproducible anywhere; what they are **not** is an on-air measurement. Bit stuffing, the radio's own TXDELAY and
 * CSMA behaviour, collisions and the real 1200-baud modem are measured by `BT-00`/`PERF-R02` with two radios, and are
 * NOT MEASURED here.
 */
class RadioChannelOccupancyTest {

    private val crypto = JdkRadioCrypto()
    private val pairKey = ByteArray(32) { it.toByte() }
    private val now = 1_800_000_000_000L

    private fun session(): RadioSession =
        RadioSession("alice", crypto, InMemoryRadioStore(), InMemoryRadioStore()).apply { addPeer("bob", pairKey) }

    /** The AX.25 frame the engine would hand to the TNC, for the first segment of an encoded message. */
    private fun ax25Bytes(infoBytes: Int): Int = Ax25FrameCodec.encodeUi(
        destination = Ax25Address("FLASH"),
        source = Ax25Address("M2CAB", 13),
        info = ByteArray(infoBytes),
    ).size

    @Test
    fun occupancyOfRealTextMessagesOnTheDefaultPolicy() {
        val alice = session()
        val policy = TxPacingPolicy() // the shipped defaults: 1200 baud, 300 ms TXDELAY, 20 ms tail, 500 ms gap, 0..1500 ms jitter
        val averageJitterMs = policy.jitterMs / 2.0

        for (bodyBytes in listOf(50, 188, 500, 1_000, 3_008)) {
            val body = ByteArray(bodyBytes) { ('a'.code + (it % 26)).toByte() }
            val enc = alice.encode("bob", RadioKind.TEXT, body, now) as RadioEncode.Frames
            val segments = enc.frames.size
            val infoBytes = enc.frames.first().size
            val ax25 = ax25Bytes(infoBytes)
            val airtimeMs = policy.airtimeMs(ax25)
            val cycleMs = airtimeMs + policy.minGapMs + averageJitterMs
            val messageOccupancyMs = segments * airtimeMs
            val messageWindowMs = segments * cycleMs
            val segmentGoodput = RadioWire.maxBodyPerSegment() / (cycleMs / 1000.0)
            val messageGoodput = bodyBytes / (messageWindowMs / 1000.0)
            println(
                "PERF-R O26-02 bodyBytes=$bodyBytes segments=$segments infoBytes=$infoBytes ax25Bytes=$ax25 " +
                    "airtimePerFrameMs=$airtimeMs cyclePerFrameMs=${"%.0f".format(cycleMs)} " +
                    "transmitterOccupiedMs=$messageOccupancyMs windowMs=${"%.0f".format(messageWindowMs)} " +
                    "segmentGoodputBytesPerSecond=${"%.1f".format(segmentGoodput)} " +
                    "messageGoodputBytesPerSecond=${"%.1f".format(messageGoodput)}",
            )

            // Behaviour this measurement stands on: the segmentation and the frame size are the wire format's.
            assertEquals((bodyBytes + RadioWire.maxBodyPerSegment() - 1) / RadioWire.maxBodyPerSegment(), segments)
            assertTrue(ax25 == infoBytes + 16, "AX.25 UI adds 14 address bytes + control + PID: $ax25 vs $infoBytes")
        }
    }

    @Test
    fun theDocumentedOneKilobyteCaseReproduces() {
        val alice = session()
        val policy = TxPacingPolicy()
        val enc = alice.encode("bob", RadioKind.TEXT, ByteArray(1_000), now) as RadioEncode.Frames

        assertEquals(6, enc.frames.size, "1000 body bytes / 188 per segment")
        assertEquals(220, enc.frames.first().size, "the AX.25 information field is the 220-byte budget")
        assertEquals(236, ax25Bytes(enc.frames.first().size), "the largest AX.25 frame Flash hands to a TNC")
        assertEquals(2_080L, policy.airtimeMs(236), "the frame the radio keys up for, TXDELAY and tail included")

        // 2080 ms on the air + 500 ms quiet gap + half of the 0..1500 ms jitter, per segment.
        val cycleMs = policy.airtimeMs(236) + policy.minGapMs + policy.jitterMs / 2.0
        assertEquals(3_330.0, cycleMs)
        assertEquals(56.4, RadioWire.maxBodyPerSegment() / (cycleMs / 1000.0), absoluteTolerance = 0.1)
        assertEquals(
            19_980L,
            (6 * cycleMs).toLong(),
            "six segments occupy about 20 s of channel time, which is why a 1 KB text takes half a minute",
        )
    }

    @Test
    fun maximumMessageOccupiesAboutFiftyThreeSeconds() {
        val alice = session()
        val policy = TxPacingPolicy()
        val body = ByteArray(3_008)
        val enc = alice.encode("bob", RadioKind.TEXT, body, now) as RadioEncode.Frames
        assertEquals(16, enc.frames.size, "the wire format's 16-segment cap")
        val windowMs = 16 * (policy.airtimeMs(236) + policy.minGapMs + policy.jitterMs / 2.0)
        assertTrue(windowMs in 52_000.0..54_000.0, "16 segments take about 53 s of channel time, was $windowMs")
    }
}
