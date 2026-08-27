@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.common.protocol.FlashProtocol
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Frame-level end-to-end encryption (D4, plan step C2.7).
 *
 * Wire format produced by [encrypt]:
 * ```
 * [ 12-byte random nonce | ciphertext + 16-byte GCM tag ]
 * ```
 *
 * Algorithm: AES-256/GCM/NoPadding, 128-bit tag. AAD binds every frame to the Flash protocol
 * version (`"flash-e2e-v${FlashProtocol.VERSION}"`) so frames cannot be replayed across a
 * version-negotiated session of a different protocol generation.
 *
 * ## Nonce discipline (researched — NIST SP 800-38D; https://neilmadden.blog/2024/05/23/galois-counter-mode-and-random-nonces/)
 * - Nonces are 96-bit values from [SecureRandom], freshly generated per frame and prepended to
 *   the ciphertext. Reusing a (key, nonce) pair in GCM is catastrophic: it recovers the
 *   authentication subkey. Random 96-bit nonces keep collision probability below NIST's 2^-32
 *   bound for up to ~2^32 encryptions under one key.
 * - Session keys are per-pairing, derived at pairing time from ephemeral ECDH ([FlashCrypto]);
 *   they are never persisted and never reused across re-pairings.
 *
 * ## Rekey policy (placeholder — lands with mesh/D5)
 * v1 uses one session key per pairing for the lifetime of the pairing session; the ~2^32-frame
 * budget is far beyond v1's message volume. When mesh relay (D5) introduces long-lived
 * multi-hop sessions, add explicit epoch/rekey negotiation here and document the counter in
 * docs/protocol.md before shipping.
 */
internal object E2eFrameCodec {

    /** GCM-standard 96-bit nonce length. */
    const val NONCE_BYTES = 12

    /** Full-strength GCM authentication tag. */
    const val TAG_BITS = 128

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES = "AES"

    private val random = SecureRandom()

    /** Additional authenticated data binding frames to the negotiated protocol version. */
    private fun aad(): ByteArray = "flash-e2e-v${FlashProtocol.VERSION}".toByteArray()

    /**
     * Encrypts a JSON payload into `[nonce | ciphertext+tag]` under the shared 32-byte
     * AES-256 session key.
     */
    fun encrypt(payloadJson: String, sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == FlashCrypto.SESSION_KEY_SIZE_BYTES) {
            "Session key must be ${FlashCrypto.SESSION_KEY_SIZE_BYTES} bytes (AES-256), was ${sessionKey.size}"
        }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, AES),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad())
        val ciphertext = cipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))
        return nonce + ciphertext
    }

    /**
     * Inverse of [encrypt]. Throws [javax.crypto.AEADBadTagException] when the frame was
     * tampered with or encrypted under a different key/AAD — callers must treat that as an
     * unauthenticated/garbage frame, not attempt delivery.
     */
    fun decrypt(frame: ByteArray, sessionKey: ByteArray): String {
        require(sessionKey.size == FlashCrypto.SESSION_KEY_SIZE_BYTES) {
            "Session key must be ${FlashCrypto.SESSION_KEY_SIZE_BYTES} bytes (AES-256), was ${sessionKey.size}"
        }
        require(frame.size > NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS) {
            "E2E frame too short (${frame.size} bytes) to contain nonce + tag"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, AES),
            GCMParameterSpec(TAG_BITS, frame, 0, NONCE_BYTES),
        )
        cipher.updateAAD(aad())
        val plaintext = cipher.doFinal(frame, NONCE_BYTES, frame.size - NONCE_BYTES)
        return String(plaintext, Charsets.UTF_8)
    }
}
