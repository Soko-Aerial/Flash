package com.transfer.flash.core.security.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * RFC 5869 Appendix A test vectors for HKDF-SHA256.
 * Source: https://www.rfc-editor.org/rfc/rfc5869#appendix-A (Test Cases 1 and 2).
 */
class HkdfTest {

    @Test
    fun `rfc5869 test case 1 - basic SHA-256`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)

        val okm = Hkdf.expand(prk, info, 42)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" + "34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `rfc5869 test case 2 - longer inputs and outputs`() {
        // IKM[i] = i for i in 0..79; salt[i] = 0x60 + i; info[i] = 0xb0 + i (all 80 bytes)
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }

        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"), prk)

        val okm = Hkdf.expand(prk, info, 82)
        assertArrayEquals(
            hex(
                "b11e398dc80327a1c8e7f78c596a4934" +
                    "4f012eda2d4efad8a050cc4c19afa97c" +
                    "59045a99cac7827271cb41c65e590e09" +
                    "da3275600c2f09b8367793a9aca3db71" +
                    "cc30c58179ec3e87c14c01d5c1f3434f" +
                    "1d87",
            ),
            okm,
        )
    }

    @Test
    fun `derive combines extract and expand`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        assertArrayEquals(Hkdf.expand(Hkdf.extract(salt, ikm), info, 42), Hkdf.derive(ikm, salt, info, 42))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
