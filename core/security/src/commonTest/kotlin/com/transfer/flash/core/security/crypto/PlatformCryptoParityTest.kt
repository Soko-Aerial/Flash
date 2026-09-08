package com.transfer.flash.core.security.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executes every [PlatformCrypto] primitive on **each** target (Phase 07).
 *
 * The existing JUnit 4 suites live in `androidHostTest` and therefore only ever exercise the
 * `androidMain` `actual`s. This suite is in `commonTest`, so it also runs under `jvmTest` — which
 * is the only thing that distinguishes "the desktop crypto compiles" from "the desktop crypto
 * works". Any future Kotlin/Native target inherits it automatically and must pass it before its
 * `actual`s can be trusted.
 *
 * Fixed vectors (rather than round-trips alone) where a round-trip would pass even if a target
 * silently substituted a different algorithm:
 * - SHA-256 of `"abc"` — FIPS 180-4 Appendix B.1.
 * - HMAC-SHA256 RFC 4231 test case 2 (`key = "Jefe"`, `data = "what do ya want for nothing?"`).
 *
 * Algorithm-level HKDF and fingerprint formatting are pinned separately by `HkdfTest` (RFC 5869
 * Appendix A) and `FlashFingerprintTest`; those run on the Android host JVM only, so anything they
 * would catch on a new target has a counterpart here.
 */
class PlatformCryptoParityTest {

    @Test
    fun sha256_matches_fips_180_4_vector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHexLower(),
        )
    }

    @Test
    fun hmacSha256_matches_rfc_4231_case_2() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256(
                key = "Jefe".encodeToByteArray(),
                data = "what do ya want for nothing?".encodeToByteArray(),
            ).toHexLower(),
        )
    }

    @Test
    fun secureRandomBytes_returns_requested_size_and_does_not_repeat() {
        assertEquals(12, secureRandomBytes(12).size)
        assertFalse(secureRandomBytes(32).contentEquals(secureRandomBytes(32)))
    }

    @Test
    fun constantTimeBytesEqual_matches_on_equal_and_rejects_length_mismatch() {
        assertTrue(constantTimeBytesEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeBytesEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        // Must be false, not an exception — FlashFingerprintTest asserts the same contract.
        assertFalse(constantTimeBytesEqual(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    @Test
    fun aesGcm_roundtrips_and_rejects_a_tampered_tag() {
        val key = secureRandomBytes(FlashCrypto.SESSION_KEY_SIZE_BYTES)
        val nonce = secureRandomBytes(E2eFrameCodec.NONCE_BYTES)
        val aad = "flash-parity-aad".encodeToByteArray()
        val plaintext = "the quick brown fox".encodeToByteArray()

        val sealed = aesGcmSeal(key, nonce, aad, plaintext)
        // 128-bit tag appended to the ciphertext (JCA layout).
        assertEquals(plaintext.size + E2eFrameCodec.TAG_BITS / Byte.SIZE_BITS, sealed.size)
        assertTrue(aesGcmOpen(key, nonce, aad, sealed).contentEquals(plaintext))

        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        // Exception type is platform-defined (AEADBadTagException on JVM); only the throw is
        // contractual. E2eFrameCodecTest pins the JVM type.
        assertFails { aesGcmOpen(key, nonce, aad, tampered) }
    }

    @Test
    fun aesGcm_rejects_a_wrong_aad() {
        val key = secureRandomBytes(FlashCrypto.SESSION_KEY_SIZE_BYTES)
        val nonce = secureRandomBytes(E2eFrameCodec.NONCE_BYTES)
        val sealed = aesGcmSeal(key, nonce, "aad-a".encodeToByteArray(), "payload".encodeToByteArray())
        assertFails { aesGcmOpen(key, nonce, "aad-b".encodeToByteArray(), sealed) }
    }

    @Test
    fun ecP256_sign_verify_roundtrips_and_rejects_tampering() {
        val keyPair = ecP256GenerateKeyPair()
        val data = "flash handshake payload".encodeToByteArray()
        val signature = ecP256Sign(keyPair.privateKey, data)

        assertTrue(ecP256Verify(keyPair.publicKeyEncoded, data, signature))
        assertFalse(ecP256Verify(keyPair.publicKeyEncoded, "other payload".encodeToByteArray(), signature))
        assertFalse(ecP256Verify(ecP256GenerateKeyPair().publicKeyEncoded, data, signature))
        // Malformed key must return false, never throw (FlashCrypto.verify's documented contract).
        assertFalse(ecP256Verify(byteArrayOf(9, 9, 9), data, signature))
    }

    @Test
    fun ecP256_shared_secret_agrees_in_both_directions() {
        val alice = ecP256GenerateKeyPair()
        val bob = ecP256GenerateKeyPair()

        val atAlice = ecP256SharedSecret(alice.privateKey, bob.publicKeyEncoded)
        val atBob = ecP256SharedSecret(bob.privateKey, alice.publicKeyEncoded)

        assertTrue(atAlice.contentEquals(atBob))
        // P-256 field element: the raw agreement output is 32 bytes before HKDF.
        assertEquals(32, atAlice.size)
    }

    @Test
    fun ephemeral_keypairs_are_distinct_and_produce_distinct_session_keys() {
        val crypto = SoftwareFlashCrypto()
        val a = crypto.generateEphemeralEcdhKeyPair()
        val b = crypto.generateEphemeralEcdhKeyPair()
        assertFalse(a.publicKeyEncoded.contentEquals(b.publicKeyEncoded))

        val c = crypto.generateEphemeralEcdhKeyPair()
        val first = crypto.ecdhSessionKey(a, b.publicKeyEncoded)
        val second = crypto.ecdhSessionKey(a, c.publicKeyEncoded)
        assertEquals(FlashCrypto.SESSION_KEY_SIZE_BYTES, first.size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun e2e_frame_codec_roundtrips_on_this_target() {
        val key = secureRandomBytes(FlashCrypto.SESSION_KEY_SIZE_BYTES)
        val payload = """{"kind":"parity","body":"héllo"}"""
        val frame = E2eFrameCodec.encrypt(payload, key)
        assertEquals(payload, E2eFrameCodec.decrypt(frame, key))
    }
}
