package com.transfer.flash.core.transfer.chunked

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moved from `androidHostTest` to `commonTest` by Phase 13B-3c, when the backing store changed from
 * `java.util.BitSet` to a `LongArray`. The six behavioural tests below are the ones that were there,
 * re-pointed from JUnit's asserts to `kotlin.test`'s (identical argument order for every form used
 * here); the byte-level tests after them are new, and exist because [ResumeBitVector.toSerialized]
 * output is **persisted resume state** — a payload written by an older build has to keep restoring
 * on a newer one.
 */
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

    @Test
    fun `receivedIndexesNotIn returns only the delta and never mutates either vector`() {
        val confirmed = ResumeBitVector(200)
        listOf(0, 63, 64, 127, 199).forEach { confirmed.markReceived(it) }
        val persisted = ResumeBitVector(200)
        listOf(0, 64).forEach { persisted.markReceived(it) }

        assertEquals(listOf(63, 127, 199), confirmed.receivedIndexesNotIn(persisted))
        assertEquals(listOf(0, 63, 64, 127, 199), confirmed.doneIndexes())
        assertEquals(listOf(0, 64), persisted.doneIndexes())
    }

    @Test
    fun `receivedIndexesNotIn is empty when persisted is a superset`() {
        val confirmed = ResumeBitVector(8).also { it.markReceived(2) }
        val persisted = ResumeBitVector(8).also {
            it.markReceived(2)
            it.markReceived(3)
        }

        assertTrue(confirmed.receivedIndexesNotIn(persisted).isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Byte-level vectors for the persisted format. Phase 13B-3c.
    // ---------------------------------------------------------------------------------------------

    /**
     * The exact bytes, for seven shapes chosen to pin the two things a reimplementation gets wrong:
     * the **little-endian** word layout, and the fact that `wordCount` counts words up to the highest
     * set bit rather than the vector's capacity. Written as labelled concatenations so both are
     * visible on the page — a capacity-sized dump of case 5 would be `10000000` and not `01000000`.
     */
    @Test
    fun `serialized bytes match the byte level vectors`() {
        for ((label, vector, expectedHex) in serializedVectors()) {
            assertEquals(expectedHex, Sha256.hex(vector.toSerialized()), "$label: serialized bytes")
            assertEquals(
                expectedHex.length / 2,
                vector.toSerialized().size,
                "$label: payload length",
            )
        }
    }

    /** Every vector above must restore to the same done-set it was written from. */
    @Test
    fun `every byte level vector round trips through fromSerialized`() {
        for ((label, vector, _) in serializedVectors()) {
            val restored = assertNotNull(
                ResumeBitVector.fromSerialized(vector.totalChunks, vector.toSerialized()),
                "$label: did not restore",
            )
            assertEquals(vector.doneIndexes(), restored.doneIndexes(), "$label: done-set")
            assertEquals(vector.receivedCount, restored.receivedCount, "$label: receivedCount")
        }
    }

    /**
     * An empty vector serializes to a bare zero word count and nothing else — four bytes, whatever
     * `totalChunks` is. This is the case a fixed-size implementation gets wrong most visibly.
     */
    @Test
    fun `an empty vector serializes to four bytes for any size`() {
        for (total in listOf(1, 63, 64, 65, 1000, 1_000_000)) {
            val bytes = ResumeBitVector(total).toSerialized()
            assertEquals("00000000", Sha256.hex(bytes), "totalChunks=$total")
            val restored = assertNotNull(
                ResumeBitVector.fromSerialized(total, bytes),
                "totalChunks=$total: zero-word payload must restore",
            )
            assertEquals(0, restored.receivedCount, "totalChunks=$total: restored count")
            assertTrue(restored.missingIndexes().size == total, "totalChunks=$total: all missing")
        }
    }

    /**
     * A payload holding fewer words than the vector's capacity — the normal case for a mostly-empty
     * transfer — must restore into the high words as zeros rather than being rejected or misaligned.
     */
    @Test
    fun `a trimmed payload restores into a larger vector`() {
        val v = ResumeBitVector(1000)
        v.markReceived(3)
        val bytes = v.toSerialized()
        assertEquals(4 + 8, bytes.size, "one word, not sixteen")
        val restored = assertNotNull(ResumeBitVector.fromSerialized(1000, bytes))
        assertEquals(listOf(3), restored.doneIndexes())
        assertFalse(restored.isReceived(999), "high words must come back clear")
        assertEquals(999, restored.missingIndexes().size)
    }

    /**
     * `receivedCount` is a population count over the words, and `doneIndexes` walks set bits by
     * clearing the lowest one. Both are new implementations of what `BitSet.cardinality()` and
     * `BitSet.nextSetBit` did, so a word with every bit set and a word with only its top bit set are
     * both worth asserting.
     */
    @Test
    fun `a full word and a top bit only word are counted and walked correctly`() {
        val full = ResumeBitVector(64)
        for (i in 0 until 64) full.markReceived(i)
        assertEquals(64, full.receivedCount)
        assertTrue(full.isComplete())
        assertEquals((0 until 64).toList(), full.doneIndexes())
        assertEquals("01000000" + "ffffffffffffffff", Sha256.hex(full.toSerialized()))

        val top = ResumeBitVector(64)
        top.markReceived(63)
        assertEquals(1, top.receivedCount)
        assertEquals(listOf(63), top.doneIndexes())
        // Bit 63 is the sign bit; it must not be read as a negative shift or a short word.
        assertEquals("01000000" + "0000000000000080", Sha256.hex(top.toSerialized()))
    }

    /** `totalChunks` that is an exact multiple of 64 has no padding bits to mask. */
    @Test
    fun `word aligned totals mask nothing and still reject foreign indexes`() {
        val v = ResumeBitVector(128)
        v.markReceived(127)
        v.reconcile(listOf(128, 200))
        assertEquals(listOf(127), v.doneIndexes())
        assertEquals(1, v.receivedCount)
        assertFalse(v.isReceived(128))
        assertEquals("02000000" + "0000000000000000" + "0000000000000080", Sha256.hex(v.toSerialized()))
    }

    private fun writeI32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}

/** A word of all zeros, as it appears on the wire. */
private const val ZERO_WORD = "0000000000000000"

private fun vector(totalChunks: Int, vararg indexes: Int): ResumeBitVector =
    ResumeBitVector(totalChunks).apply { indexes.forEach { markReceived(it) } }

/**
 * label, vector, expected hex of [ResumeBitVector.toSerialized]. The first group of every expected
 * value is `wordCount` as a uint32 LE, so one word reads as `"01000000"`.
 */
private fun serializedVectors(): List<Triple<String, ResumeBitVector, String>> = listOf(
    Triple(
        "S1 empty, 8 chunks",
        vector(8),
        "00000000",
    ),
    Triple(
        "S2 bit 0 only",
        vector(8, 0),
        "01000000" + "0100000000000000",
    ),
    Triple(
        "S3 all eight bits",
        vector(8, 0, 1, 2, 3, 4, 5, 6, 7),
        "01000000" + "ff00000000000000",
    ),
    Triple(
        // 70 chunks is two words wide, but bit 3 lives in word 0, so word 1 is trimmed away.
        "S4 two word capacity, low bit only",
        vector(70, 3),
        "01000000" + "0800000000000000",
    ),
    Triple(
        // Same capacity, highest bit in word 1: now both words are written, the first one all zeros.
        "S5 two word capacity, high bit only",
        vector(70, 69),
        "02000000" + ZERO_WORD + "2000000000000000",
    ),
    Triple(
        // The word boundary itself: 63 is the top bit of word 0, 64 the bottom bit of word 1.
        "S6 straddling the word boundary",
        vector(128, 63, 64),
        "02000000" + "0000000000000080" + "0100000000000000",
    ),
    Triple(
        // The sparse-high-index shape the round-trip test above uses, pinned byte for byte.
        // 999 is bit 39 of word 15, so sixteen words are written and eleven of them are empty.
        "S7 sparse across sixteen words",
        vector(1000, 0, 63, 64, 512, 999),
        "10000000" +
            "0100000000000080" +
            "0100000000000000" +
            ZERO_WORD.repeat(6) +
            "0100000000000000" +
            ZERO_WORD.repeat(6) +
            "0000000080000000",
    ),
)
