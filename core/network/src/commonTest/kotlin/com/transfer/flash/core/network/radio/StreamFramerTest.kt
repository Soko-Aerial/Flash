package com.transfer.flash.core.network.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamFramerTest {
    @Test
    fun roundTripAcrossAnyFragmentation() {
        val msgs = listOf(ByteArray(0), byteArrayOf(1), ByteArray(300) { it.toByte() }, ByteArray(StreamFramer.MAX_PAYLOAD) { 7 })
        val wire = msgs.map { StreamFramer.encode(it) }.reduce { a, b -> a + b }
        for (chunk in listOf(1, 2, 3, 17, 1000, wire.size)) {
            val d = StreamFrameDecoder()
            val got = ArrayList<ByteArray>()
            var i = 0
            while (i < wire.size) {
                val n = minOf(chunk, wire.size - i)
                got += d.feed(wire, i, n)
                i += n
            }
            assertEquals(msgs.size, got.size, "chunk=$chunk")
            msgs.indices.forEach { assertContentEquals(msgs[it], got[it]) }
            assertEquals(0, d.buffered)
        }
    }

    @Test
    fun oversizeHeaderFailsTheStreamInsteadOfAllocating() {
        val d = StreamFrameDecoder()
        assertTrue(d.feed(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)).isEmpty())
        assertTrue(d.failed)
        assertTrue(d.feed(StreamFramer.encode(byteArrayOf(1))).isEmpty(), "a failed stream stays failed")
    }

    @Test
    fun encoderRefusesOversizePayload() {
        assertFailsWith<IllegalArgumentException> { StreamFramer.encode(ByteArray(StreamFramer.MAX_PAYLOAD + 1)) }
    }
}
