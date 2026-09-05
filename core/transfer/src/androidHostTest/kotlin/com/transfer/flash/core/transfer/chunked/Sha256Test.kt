package com.transfer.flash.core.transfer.chunked

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256Test {

    @Test
    fun `known vector abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digestHex("abc".toByteArray()),
        )
    }

    @Test
    fun `known vector empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digestHex(ByteArray(0)),
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

    @Test
    fun `constant time equals distinguishes digests and tolerates length mismatch`() {
        val a = Sha256.digest("abc".toByteArray())
        val b = Sha256.digest("abd".toByteArray())
        assertTrue(Sha256.rawEqualsConstantTime(a, a.copyOf()))
        assertFalse(Sha256.rawEqualsConstantTime(a, b))
        assertFalse(Sha256.rawEqualsConstantTime(a, a.copyOfRange(0, 16)))
        assertTrue(Sha256.hexEqualsConstantTime("aa", "aa"))
        assertFalse(Sha256.hexEqualsConstantTime("aa", "ab"))
        assertFalse(Sha256.hexEqualsConstantTime("aa", "aaa"))
    }

    @Test
    fun `hex validation and normalization`() {
        val valid = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertTrue(Sha256.isValidHex(valid))
        assertTrue(Sha256.isValidHex(valid.lowercase()))
        assertFalse(Sha256.isValidHex(valid.drop(1)))
        assertFalse(Sha256.isValidHex("zz" + valid.drop(2)))
        assertEquals(valid.lowercase(), Sha256.normalizeHex(valid))
    }
}
