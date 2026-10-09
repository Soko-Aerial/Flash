package com.transfer.flash.core.network.kiss

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden vectors derived by hand from the KISS specification (Phil Karn, 1987): FEND 0xC0, FESC 0xDB, TFEND 0xDC, TFESC 0xDD;
 * command byte = port << 4 | command; commands 0 data, 1 TXDELAY, 2 P, 3 SlotTime, 4 TXtail, 5 FullDuplex, 6 SetHardware,
 * 0xFF return. BT-01.
 */
class KissFrameCodecTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun plainDataFrame() {
        assertContentEquals(bytes(0xC0, 0x00, 0x41, 0x42, 0xC0), KissFrameCodec.encodeData(bytes(0x41, 0x42)))
    }

    @Test
    fun fendInDataIsEscaped() {
        assertContentEquals(bytes(0xC0, 0x00, 0xDB, 0xDC, 0xC0), KissFrameCodec.encodeData(bytes(0xC0)))
    }

    @Test
    fun fescInDataIsEscaped() {
        assertContentEquals(bytes(0xC0, 0x00, 0xDB, 0xDD, 0xC0), KissFrameCodec.encodeData(bytes(0xDB)))
    }

    @Test
    fun mixedEscapes() {
        assertContentEquals(
            bytes(0xC0, 0x00, 0x01, 0xDB, 0xDC, 0xDB, 0xDD, 0x02, 0xC0),
            KissFrameCodec.encodeData(bytes(0x01, 0xC0, 0xDB, 0x02)),
        )
    }

    @Test
    fun alreadyEscapedLookingBytesAreEscapedAgain() {
        // DB DC in the payload is two ordinary bytes: DB -> DB DD, DC stays.
        assertContentEquals(bytes(0xC0, 0x00, 0xDB, 0xDD, 0xDC, 0xC0), KissFrameCodec.encodeData(bytes(0xDB, 0xDC)))
    }

    @Test
    fun parameterCommands() {
        assertContentEquals(bytes(0xC0, 0x01, 0x1E, 0xC0), KissFrameCodec.encodeParameter(Kiss.CMD_TXDELAY, 0x1E))
        assertContentEquals(bytes(0xC0, 0x02, 0x40, 0xC0), KissFrameCodec.encodeParameter(Kiss.CMD_PERSISTENCE, 0x40))
        assertContentEquals(bytes(0xC0, 0x03, 0x0A, 0xC0), KissFrameCodec.encodeParameter(Kiss.CMD_SLOT_TIME, 0x0A))
        assertContentEquals(bytes(0xC0, 0x04, 0x02, 0xC0), KissFrameCodec.encodeParameter(Kiss.CMD_TX_TAIL, 0x02))
        assertContentEquals(bytes(0xC0, 0x05, 0x01, 0xC0), KissFrameCodec.encodeParameter(Kiss.CMD_FULL_DUPLEX, 1))
    }

    @Test
    fun parameterValueEqualToFendIsEscaped() {
        assertContentEquals(bytes(0xC0, 0x02, 0xDB, 0xDC, 0xC0), KissFrameCodec.encodeParameter(Kiss.CMD_PERSISTENCE, 0xC0))
    }

    @Test
    fun portNibble() {
        assertContentEquals(bytes(0xC0, 0x10, 0x41, 0xC0), KissFrameCodec.encodeData(bytes(0x41), port = 1))
        // port 12 command 0 is the byte 0xC0 itself and must be escaped
        assertContentEquals(bytes(0xC0, 0xDB, 0xDC, 0x41, 0xC0), KissFrameCodec.encodeData(bytes(0x41), port = 12))
    }

    @Test
    fun returnToNormal() {
        assertContentEquals(bytes(0xC0, 0xFF, 0xC0), KissFrameCodec.encodeReturnToNormal())
    }

    @Test
    fun decoderRoundTripEveryByteValue() {
        val all = ByteArray(256) { it.toByte() }
        val dec = KissStreamDecoder()
        val frames = dec.feed(KissFrameCodec.encodeData(all))
        assertEquals(1, frames.size)
        assertContentEquals(all, frames[0].data)
        assertTrue(frames[0].isData)
    }

    @Test
    fun decoderToleratesAnyFragmentation() {
        val wire = KissFrameCodec.encodeData(bytes(1, 0xC0, 0xDB, 2)) + KissFrameCodec.encodeParameter(Kiss.CMD_TXDELAY, 30)
        for (chunk in listOf(1, 2, 3, 5, 100)) {
            val dec = KissStreamDecoder()
            val out = mutableListOf<KissFrame>()
            var i = 0
            while (i < wire.size) {
                val n = minOf(chunk, wire.size - i)
                out += dec.feed(wire, i, n)
                i += n
            }
            assertEquals(2, out.size, "chunk=$chunk")
            assertContentEquals(bytes(1, 0xC0, 0xDB, 2), out[0].data)
            assertEquals(Kiss.CMD_TXDELAY, out[1].command)
            assertContentEquals(bytes(30), out[1].data)
        }
    }

    @Test
    fun runsOfFendAreOneDelimiter() {
        val dec = KissStreamDecoder()
        val frames = dec.feed(bytes(0xC0, 0xC0, 0xC0, 0x00, 0x41, 0xC0, 0xC0, 0xC0))
        assertEquals(1, frames.size)
        assertEquals(1, dec.statistics.framesOk)
        assertTrue(dec.statistics.emptyFrames >= 3)
    }

    @Test
    fun frameDelimitersCanBeShared() {
        // FEND frame1 FEND frame2 FEND: the middle FEND closes one and opens the next
        val frames = KissStreamDecoder().feed(bytes(0xC0, 0x00, 0x41, 0xC0, 0x00, 0x42, 0xC0))
        assertEquals(2, frames.size)
        assertContentEquals(bytes(0x41), frames[0].data)
        assertContentEquals(bytes(0x42), frames[1].data)
    }

    @Test
    fun garbageBeforeFirstFendIsCountedAndIgnored() {
        val dec = KissStreamDecoder()
        val frames = dec.feed(bytes(0x01, 0x02, 0x03, 0xC0, 0x00, 0x41, 0xC0))
        assertEquals(1, frames.size)
        assertEquals(3, dec.statistics.garbageBytes)
    }

    @Test
    fun badEscapeDropsOnlyThatFrame() {
        val dec = KissStreamDecoder()
        // FESC followed by 0x41 is invalid; the next frame must still decode.
        val frames = dec.feed(bytes(0xC0, 0x00, 0xDB, 0x41, 0x99, 0xC0, 0x00, 0x42, 0xC0))
        assertEquals(1, frames.size)
        assertContentEquals(bytes(0x42), frames[0].data)
        assertEquals(1, dec.statistics.badEscapes)
    }

    @Test
    fun fescDirectlyBeforeFendIsABadEscape() {
        val dec = KissStreamDecoder()
        val frames = dec.feed(bytes(0xC0, 0x00, 0x41, 0xDB, 0xC0, 0x00, 0x42, 0xC0))
        assertEquals(1, frames.size)
        assertContentEquals(bytes(0x42), frames[0].data)
        assertEquals(1, dec.statistics.badEscapes)
    }

    @Test
    fun oversizeFrameIsDroppedAndDecoderResynchronises() {
        val dec = KissStreamDecoder(maxFrameBytes = 16)
        val big = KissFrameCodec.encodeData(ByteArray(100) { 0x55 })
        val small = KissFrameCodec.encodeData(bytes(1, 2, 3))
        val frames = dec.feed(big + small)
        assertEquals(1, frames.size)
        assertContentEquals(bytes(1, 2, 3), frames[0].data)
        assertEquals(1, dec.statistics.oversize)
    }

    @Test
    fun partialFrameIsNotDeliveredUntilClosed() {
        val dec = KissStreamDecoder()
        assertTrue(dec.feed(bytes(0xC0, 0x00, 0x41, 0x42)).isEmpty())
        assertEquals(1, dec.feed(bytes(0xC0)).size)
    }

    @Test
    fun resetDiscardsHalfAFrame() {
        val dec = KissStreamDecoder()
        dec.feed(bytes(0xC0, 0x00, 0x41))
        dec.reset()
        // After reset the old partial must not join the new bytes: bytes before the first FEND are garbage.
        val frames = dec.feed(bytes(0x42, 0xC0, 0x00, 0x43, 0xC0))
        assertEquals(1, frames.size)
        assertContentEquals(bytes(0x43), frames[0].data)
    }

    @Test
    fun randomNoiseNeverThrowsAndRealFramesSurvive() {
        val rnd = Random(42)
        repeat(200) {
            val dec = KissStreamDecoder(maxFrameBytes = 64)
            val noise = ByteArray(rnd.nextInt(0, 400)).also { rnd.nextBytes(it) }
            dec.feed(noise)
            // A FEND flushes whatever half-frame the noise left; then a real frame must decode.
            val good = rnd.nextBytes(rnd.nextInt(1, 40))
            val frames = dec.feed(byteArrayOf(0xC0.toByte()) + KissFrameCodec.encodeData(good))
            assertTrue(frames.any { it.isData && it.data.contentEquals(good) }, "iteration $it")
        }
    }

    @Test
    fun randomRoundTrip() {
        val rnd = Random(7)
        repeat(300) {
            val payload = rnd.nextBytes(rnd.nextInt(0, 300))
            val port = rnd.nextInt(0, 16)
            val cmd = rnd.nextInt(0, 16)
            val wire = KissFrameCodec.encode(port, cmd, payload)
            val frames = KissStreamDecoder().feed(wire)
            assertEquals(1, frames.size)
            assertEquals(port, frames[0].port)
            assertEquals(cmd, frames[0].command)
            assertContentEquals(payload, frames[0].data)
        }
    }

    @Test
    fun commandByteWithNoDataIsAFrame() {
        val frames = KissStreamDecoder().feed(bytes(0xC0, 0xFF, 0xC0))
        assertEquals(1, frames.size)
        assertEquals(15, frames[0].port)
        assertEquals(15, frames[0].command)
        assertEquals(0, frames[0].data.size)
    }
}
