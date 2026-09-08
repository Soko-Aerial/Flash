package com.transfer.flash.core.security.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashFingerprintTest {

    // Expected value precomputed independently (PowerShell SHA-256) for the fixed input.
    private val vectorInput = "Flash fingerprint test vector v1".toByteArray(Charsets.UTF_8)
    private val vectorDigestHex =
        "82A67EF30321DB5226075A8F1A7548D6D02EC26B6286EF8C007E61471102F4EB"

    @Test
    fun `sha-256 fingerprint matches known fixed vector`() {
        assertArrayEquals(hex(vectorDigestHex), FlashFingerprint.fingerprint(vectorInput))
    }

    @Test
    fun `fingerprint is 32 bytes`() {
        assertEquals(32, FlashFingerprint.fingerprint(vectorInput).size)
    }

    @Test
    fun `formatHexGroups produces stable uppercase colon grouping`() {
        val digest = FlashFingerprint.fingerprint(vectorInput)
        assertEquals(
            "82:A6:7E:F3:03:21:DB:52:26:07:5A:8F:1A:75:48:D6:D0:2E:C2:6B:62:86:EF:8C:00:7E:61:47:11:02:F4:EB",
            FlashFingerprint.formatHexGroups(digest),
        )
    }

    @Test
    fun `formatHexGroups zero-pads`() {
        assertEquals("00:0F", FlashFingerprint.formatHexGroups(byteArrayOf(0x00, 0x0F)))
    }

    @Test
    fun `constantTimeEquals matches identical arrays only`() {
        val a = hex(vectorDigestHex)
        assertTrue(FlashFingerprint.constantTimeEquals(a, a.copyOf()))
        assertFalse(FlashFingerprint.constantTimeEquals(a, hex(vectorDigestHex.lowercase().replaceFirst("82", "83"))))
        assertFalse(FlashFingerprint.constantTimeEquals(a, a.copyOfRange(0, a.size - 1)))
    }

    @Test
    fun `fingerprint of encoded public keys differs across identities`() {
        val first = SoftwareFlashCrypto()
        val second = SoftwareFlashCrypto()
        assertFalse(
            FlashFingerprint.fingerprint(first.identityPublicKeyEncoded)
                .contentEquals(FlashFingerprint.fingerprint(second.identityPublicKeyEncoded)),
        )
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
