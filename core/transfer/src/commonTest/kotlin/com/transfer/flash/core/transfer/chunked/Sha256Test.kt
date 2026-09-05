package com.transfer.flash.core.transfer.chunked

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 13B-3 moved this suite from `androidHostTest` to `commonTest` together with [Sha256] itself, so
 * the known-answer vectors below now execute on the desktop `jvm()` target as well as on the
 * Android host-test JVM (CONVENTIONS.md R3.1). That is the acceptance criterion for re-basing the
 * digest onto okio: SHA-256 is a fixed function, so a byte difference between the old
 * `java.security.MessageDigest` path and the new [okio.HashingSink] one can only show up as a
 * failed vector.
 *
 * JUnit 4 → `kotlin.test` conversion note: `assertEquals` takes its message LAST here, the
 * opposite of `org.junit.Assert`.
 */
class Sha256Test {

    @Test
    fun `known vector abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digestHex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `known vector empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digestHex(ByteArray(0)),
        )
    }

    /** FIPS 180-2 B.2 — a 448-bit message, so the digest spans two compression blocks. */
    @Test
    fun `known vector two block message`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digestHex(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `digest concatenates its vararg chunks`() {
        assertEquals(
            Sha256.digestHex("abc".encodeToByteArray()),
            Sha256.hex(
                Sha256.digest(
                    "a".encodeToByteArray(),
                    "b".encodeToByteArray(),
                    "c".encodeToByteArray(),
                ),
            ),
        )
    }

    @Test
    fun `incremental updates match one-shot digest across chunk boundaries`() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val incremental = IncrementalSha256()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(7919, data.size - offset)
            incremental.update(data, offset, len)
            offset += len
        }
        assertEquals(Sha256.digestHex(data), incremental.digestHex())
    }

    /**
     * Pins the finish-and-reset semantics inherited from `MessageDigest.digest()`: the second read
     * is the digest of no input, not a repeat of the first. Pipelines call `digestHex()` exactly
     * once per file, so this documents the contract rather than a behaviour anything relies on.
     */
    @Test
    fun `digestRaw finishes and empties the accumulator`() {
        val accumulator = IncrementalSha256()
        accumulator.update("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            accumulator.digestHex(),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            accumulator.digestHex(),
            "a second digest read must see an empty accumulator",
        )
    }

    @Test
    fun `reset discards buffered input`() {
        val accumulator = IncrementalSha256()
        accumulator.update("discard-me".encodeToByteArray())
        accumulator.reset()
        accumulator.update("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            accumulator.digestHex(),
        )
    }

    @Test
    fun `constant time equals distinguishes digests and tolerates length mismatch`() {
        val a = Sha256.digest("abc".encodeToByteArray())
        val b = Sha256.digest("abd".encodeToByteArray())
        assertTrue(Sha256.rawEqualsConstantTime(a, a.copyOf()))
        assertFalse(Sha256.rawEqualsConstantTime(a, b))
        assertFalse(Sha256.rawEqualsConstantTime(a, a.copyOfRange(0, 16)))
        assertTrue(Sha256.hexEqualsConstantTime("aa", "aa"))
        assertFalse(Sha256.hexEqualsConstantTime("aa", "ab"))
        assertFalse(Sha256.hexEqualsConstantTime("aa", "aaa"))
    }

    /**
     * The three edge cases the `MessageDigest.isEqual` port has to reproduce exactly: the identity
     * fast path, the `lenB == 0` special case that decides on length alone, and a shorter `a` than
     * `b` — where every byte the loop does compare matches, so `result |= lenA - lenB` is the only
     * term that can reject.
     */
    @Test
    fun `constant time equals handles identity empties and a shorter first array`() {
        val digest = Sha256.digest("abc".encodeToByteArray())
        assertTrue(Sha256.rawEqualsConstantTime(digest, digest))
        assertTrue(Sha256.rawEqualsConstantTime(ByteArray(0), ByteArray(0)))
        assertFalse(Sha256.rawEqualsConstantTime(byteArrayOf(1), ByteArray(0)))
        assertFalse(Sha256.rawEqualsConstantTime(ByteArray(0), byteArrayOf(1)))
        assertFalse(Sha256.rawEqualsConstantTime(digest, digest.copyOf(RAW_PLUS_PADDING)))
        assertTrue(Sha256.hexEqualsConstantTime("", ""))
        assertFalse(Sha256.hexEqualsConstantTime("", "a"))
    }

    /** High bytes must compare through `Byte.toInt()` sign extension without false equality. */
    @Test
    fun `constant time equals compares negative bytes correctly`() {
        assertTrue(Sha256.rawEqualsConstantTime(byteArrayOf(-1, 0, 127), byteArrayOf(-1, 0, 127)))
        assertFalse(Sha256.rawEqualsConstantTime(byteArrayOf(-1), byteArrayOf(1)))
        assertFalse(Sha256.rawEqualsConstantTime(byteArrayOf(-128), byteArrayOf(0)))
    }

    @Test
    fun `hex validation and normalization`() {
        val valid = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertTrue(Sha256.isValidHex(valid))
        assertTrue(Sha256.isValidHex(valid.lowercase()))
        assertFalse(Sha256.isValidHex(valid.drop(1)))
        assertFalse(Sha256.isValidHex("zz" + valid.drop(2)))
        assertEquals(valid.lowercase(), Sha256.normalizeHex(valid))
        assertFailsWith<IllegalArgumentException> { Sha256.normalizeHex(valid.drop(1)) }
    }

    @Test
    fun `hex formats every byte value in lowercase`() {
        val all = ByteArray(256) { it.toByte() }
        val formatted = Sha256.hex(all)
        assertEquals(512, formatted.length)
        assertEquals("000102", formatted.take(6))
        assertEquals("fdfeff", formatted.takeLast(6))
        assertTrue(formatted.none { it in 'A'..'F' })
    }

    private companion object {
        /** 64 bytes: a digest zero-padded past [Sha256.RAW_LENGTH], so `lenA < lenB`. */
        const val RAW_PLUS_PADDING = 64
    }
}
