package com.transfer.flash.core.network.radio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Published known-answer tests for the primitives behind the radio frame. */
class RadioCryptoVectorsTest {
    private val crypto = JdkRadioCrypto()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun hkdfRfc5869TestCase1() {
        val okm = RadioHkdf.derive(
            crypto,
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun hkdfRfc5869TestCase3EmptySaltAndInfo() {
        val okm = RadioHkdf.derive(crypto, ikm = ByteArray(22) { 0x0b }, salt = ByteArray(0), info = ByteArray(0), length = 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm.hex())
    }

    @Test
    fun hmacSha256Rfc4231TestCase2() {
        val mac = crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.hex())
    }

    @Test
    fun aes256GcmNistTestCase16() {
        // McGrew & Viega, "The Galois/Counter Mode of Operation", test case 16 (AES-256, 96-bit IV, 20-byte AAD)
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val pt = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val ct = hex(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
        )
        val tag = hex("76fc6ece0f4e1768cddf8853bb2d551b")
        val sealed = crypto.aeadSeal(key, iv, aad, pt)
        assertContentEquals(ct + tag, sealed)
        assertContentEquals(pt, crypto.aeadOpen(key, iv, aad, sealed))
    }

    @Test
    fun aeadOpenReturnsNullOnForgeryInsteadOfThrowing() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12)
        val sealed = crypto.aeadSeal(key, nonce, ByteArray(0), "x".encodeToByteArray())
        assertNull(crypto.aeadOpen(key, nonce, ByteArray(0), sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertNull(crypto.aeadOpen(key, nonce, byteArrayOf(1), sealed))
        assertNull(crypto.aeadOpen(key, nonce, ByteArray(0), ByteArray(3)))
    }

    @Test
    fun ecdsaRawSignatureRoundTripThroughTheJca() {
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val scheme = JdkRadioSignatureScheme(kp.private)
        val data = "flash".encodeToByteArray()
        repeat(50) { // 50 signatures exercise short r/s and high-bit padding cases
            val sig = scheme.sign(data + byteArrayOf(it.toByte()))
            assertEquals(64, sig.size)
            assertEquals(true, scheme.verify(kp.public.encoded, data + byteArrayOf(it.toByte()), sig))
            assertEquals(false, scheme.verify(kp.public.encoded, data, sig))
        }
        assertEquals(false, scheme.verify(kp.public.encoded, data, ByteArray(63)))
        assertEquals(false, scheme.verify(ByteArray(5), data, ByteArray(64)))
    }
}
