package com.transfer.flash.core.transfer.chunked

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeBitVectorTest {

    @Test
    fun `markReceived returns true only for new marks and rejects out of range`() {
        val v = ResumeBitVector(4)
        assertTrue(v.markReceived(0))
        assertFalse(v.markReceived(0))
        assertTrue(v.markReceived(3))
        assertEquals(2, v.receivedCount)
    }

    @Test
    fun `missingIndexes and doneIndexes are ascending complements`() {
        val v = ResumeBitVector(6)
        listOf(5, 1, 3).forEach(v::markReceived)
        assertEquals(listOf(0, 2, 4), v.missingIndexes())
        assertEquals(listOf(1, 3, 5), v.doneIndexes())
        assertFalse(v.isComplete())
        listOf(0, 2, 4).forEach(v::markReceived)
        assertTrue(v.isComplete())
        assertTrue(v.missingIndexes().isEmpty())
    }

    @Test
    fun `serialization roundtrips sparse high indexes`() {
        val v = ResumeBitVector(1000)
        listOf(0, 63, 64, 999, 512).forEach(v::markReceived)
        val restored = ResumeBitVector.fromSerialized(1000, v.toSerialized())
        assertNotNull(restored)
        assertEquals(v.receivedCount, restored!!.receivedCount)
        assertEquals(v.doneIndexes(), restored.doneIndexes())
        assertTrue(restored.isReceived(999))
        assertFalse(restored.isReceived(998))
    }

    @Test
    fun `fromSerialized clears padding bits beyond totalChunks`() {
        // totalChunks=70 -> two words. Word layout: index i -> word i/64, bit i%64.
        // Valid: idx 3 -> word 0 bit 3; idx 69 -> word 1 bit 5.
        // Padding: idx 71 -> word 1 bit 7; idx 127 -> word 1 bit 63.
        val words = LongArray(2)
        words[0] = 1L shl 3
        words[1] = (1L shl 5) or (1L shl 7) or (1L shl 63)
        val bytes = ByteArray(4 + words.size * 8)
        writeI32(bytes, 0, words.size)
        for ((i, w) in words.withIndex()) {
            var v = w
            for (b in 0 until 8) {
                bytes[4 + i * 8 + b] = (v and 0xFFL).toByte()
                v = v ushr 8
            }
        }
        val restored = ResumeBitVector.fromSerialized(70, bytes)
        assertNotNull(restored)
        assertEquals(2, restored!!.receivedCount)
        assertTrue(restored.isReceived(3))
        assertTrue(restored.isReceived(69))
        assertFalse(restored.isReceived(71))
        assertFalse(restored.isComplete())
    }

    @Test
    fun `fromSerialized rejects structurally invalid payloads`() {
        assertNull(ResumeBitVector.fromSerialized(10, null))
        assertNull(ResumeBitVector.fromSerialized(10, ByteArray(3)))
        // Declares 2 words but only 1 present.
        val short = ByteArray(4 + 8)
        writeI32(short, 0, 2)
        assertNull(ResumeBitVector.fromSerialized(128, short))
        // Declares more words than 128 chunks can have (maxWords=2).
        val tooMany = ByteArray(4 + 3 * 8)
        writeI32(tooMany, 0, 3)
        assertNull(ResumeBitVector.fromSerialized(128, tooMany))
        // Trailing garbage byte.
        val trailing = ByteArray(4 + 8 + 1)
        writeI32(trailing, 0, 1)
        assertNull(ResumeBitVector.fromSerialized(128, trailing))
        // Negative word count.
        val negative = ByteArray(4)
        negative[0] = 0x80.toByte()
        assertNull(ResumeBitVector.fromSerialized(128, negative))
    }

    @Test
    fun `reconcile unions remote progress monotonically and ignores foreign indexes`() {
        val local = ResumeBitVector(8)
        local.markReceived(1)
        local.markReceived(2)
        local.reconcile(listOf(2, 5, 100, -1))
        assertEquals(listOf(1, 2, 5), local.doneIndexes())
        local.reconcile(emptyList())
        assertEquals(3, local.receivedCount)
    }

    private fun writeI32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
