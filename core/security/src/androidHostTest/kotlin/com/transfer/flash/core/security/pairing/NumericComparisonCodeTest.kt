package com.transfer.flash.core.security.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericComparisonCodeTest {

    private val fpA = "3a7f2b9c4d5e6f708192a3b4c5d6e7f80112233445566778899aabbccddeeff0"
    private val fpB = "BEEFCAFE12345678:90:AB:CD:EF:11:22:33:44:55:66:77:88:99:AA:BB:CC"

    @Test
    fun `derive is deterministic`() {
        val first = NumericComparisonCode.derive(fpA, fpB)
        repeat(20) {
            assertEquals(first, NumericComparisonCode.derive(fpA, fpB))
        }
    }

    @Test
    fun `derive is symmetric - argument order is irrelevant`() {
        assertEquals(NumericComparisonCode.derive(fpA, fpB), NumericComparisonCode.derive(fpB, fpA))
    }

    @Test
    fun `derive ignores case and separators`() {
        val pretty = "AA:BB-CC DD EE 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17 18 19 1A"
        val plain = "aabbccddeE0102030405060708090a0b0c0d0e0f101112131415161718191a"
        assertEquals(
            NumericComparisonCode.derive(plain, fpA),
            NumericComparisonCode.derive(pretty, fpA),
        )
    }

    @Test
    fun `code is always six digits within range`() {
        repeat(500) { i ->
            val a = (i * 37).toString(16).padStart(48, 'f') + "aa"
            val b = ((i + 91) * 53).toString(16).padStart(48, '0') + "bb"
            val code = NumericComparisonCode.derive(a, b)
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
            val value = code.toInt()
            assertTrue("out of range: $code", value in 0..999_999)
        }
    }

    @Test
    fun `uniformity sanity - codes spread across the space`() {
        val seed = java.util.Random(0xC0FFEEL)
        val distinct = mutableSetOf<String>()
        val buckets = IntArray(10) // decades of 100k
        repeat(5_000) {
            val a = ByteArray(32).also(seed::nextBytes).joinToString("") { "%02x".format(it) }
            val b = ByteArray(32).also(seed::nextBytes).joinToString("") { "%02x".format(it) }
            val code = NumericComparisonCode.derive(a, b)
            distinct += code
            buckets[code.toInt() / 100_000]++
        }
        // Expected collisions among 5000 draws from 10^6 values ≈ 12; allow headroom.
        assertTrue("too few distinct codes: ${distinct.size}", distinct.size > 4_900)
        // Every decade populated, none dominating — catches degenerate truncation bugs.
        assertTrue(buckets.all { it > 300 })
        assertTrue(buckets.all { it < 700 })
    }

    @Test
    fun `different fingerprints produce different codes generally`() {
        var differing = 0
        repeat(100) { i ->
            val other = i.toString(16).padStart(64, '0')
            if (NumericComparisonCode.derive(fpA, other) != NumericComparisonCode.derive(fpA, fpA)) {
                differing++
            }
        }
        // Self-pair is one fixed value; nearly every other partner must differ.
        assertTrue(differing > 95)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank fingerprint rejected`() {
        NumericComparisonCode.derive("", "  --  ")
    }

    @Test
    fun `confirmation hash is deterministic and differs per code`() {
        val h1 = NumericComparisonCode.confirmationHashHex("123456")
        val h2 = NumericComparisonCode.confirmationHashHex("123456")
        val h3 = NumericComparisonCode.confirmationHashHex("123457")
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
        assertEquals(64, h1.length) // SHA-256 hex
        assertTrue(NumericComparisonCode.hashesEqual(h1.uppercase(), h2))
        assertTrue(!NumericComparisonCode.hashesEqual(h1, h3))
    }
}
