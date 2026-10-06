package com.transfer.flash.core.swarm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitfieldTest {

    @Test
    fun `empty and complete bitfield states`() {
        val bf = Bitfield(100)
        assertTrue(bf.isEmpty())
        assertFalse(bf.isComplete())
        assertEquals(0, bf.count())
        assertEquals(100, bf.missingIndices().count())
        assertEquals(0, bf.setIndices().count())

        for (i in 0 until 100) {
            bf.set(i, true)
        }
        assertFalse(bf.isEmpty())
        assertTrue(bf.isComplete())
        assertEquals(100, bf.count())
        assertEquals(0, bf.missingIndices().count())
        assertEquals(100, bf.setIndices().count())
    }

    @Test
    fun `word boundary crossing at 64 bits`() {
        val bf = Bitfield(130)
        bf.set(63, true)
        bf.set(64, true)
        bf.set(127, true)
        bf.set(128, true)

        assertTrue(bf.get(63))
        assertTrue(bf.get(64))
        assertFalse(bf.get(65))
        assertTrue(bf.get(127))
        assertTrue(bf.get(128))
        assertEquals(4, bf.count())
    }

    @Test
    fun `byte array serialization round trip`() {
        val bf = Bitfield(200)
        bf.set(0, true)
        bf.set(7, true)
        bf.set(8, true)
        bf.set(63, true)
        bf.set(64, true)
        bf.set(199, true)

        val bytes = bf.toByteArray()
        val restored = Bitfield.fromByteArray(200, bytes)

        assertEquals(bf.count(), restored.count())
        for (i in 0 until 200) {
            assertEquals(bf.get(i), restored.get(i))
        }
    }

    @Test
    fun `ranges round trip`() {
        val bf = Bitfield(50)
        // Range 0: 0..4 (count 5)
        for (i in 0..4) bf.set(i, true)
        // Range 1: 10..11 (count 2)
        for (i in 10..11) bf.set(i, true)
        // Range 2: 49..49 (count 1)
        bf.set(49, true)

        val ranges = bf.toRanges()
        assertEquals(3, ranges.size)
        assertEquals(PieceRange(0, 5), ranges[0])
        assertEquals(PieceRange(10, 2), ranges[1])
        assertEquals(PieceRange(49, 1), ranges[2])

        val restored = Bitfield.fromRanges(50, ranges)
        for (i in 0 until 50) {
            assertEquals(bf.get(i), restored.get(i))
        }
    }

    @Test
    fun `bitfield with initialBits having set bits beyond size clears them`() {
        // Size 3, but initialBits has 8 bits set (0xFF)
        val bf = Bitfield(3, LongArray(1) { 0xFFL })
        assertEquals(3, bf.count())
        assertTrue(bf.get(0))
        assertTrue(bf.get(1))
        assertTrue(bf.get(2))
        assertTrue(bf.isComplete())

        // Size 3, bits 0..2 are false, bits 3..5 are true (0b111000 = 56)
        val bfDirty = Bitfield(3, LongArray(1) { 56L })
        assertEquals(0, bfDirty.count())
        assertFalse(bfDirty.isComplete())
        assertTrue(bfDirty.isEmpty())
    }

    @Test
    fun `bitfield constructor validates initialBits array length`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            Bitfield(10, LongArray(0))
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            Bitfield(10, LongArray(5))
        }
    }
}
