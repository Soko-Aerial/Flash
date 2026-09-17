@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.protocol.FlashTextFraming

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
 * - Nonces are 96-bit values from the platform CSPRNG ([secureRandomBytes], `SecureRandom` on JVM
 *   targets), freshly generated per frame and prepended to the ciphertext. Reusing a (key, nonce)
 *   pair in GCM is catastrophic: it recovers the authentication subkey. Random 96-bit nonces keep
 *   collision probability below NIST's 2^-32 bound for up to ~2^32 encryptions under one key.
 * - Session keys are per-pairing, derived at pairing time from ephemeral ECDH ([FlashCrypto]);
 *   they are never persisted and never reused across re-pairings.
 *
 * ## Rekey policy (placeholder — lands with mesh/D5)
 * v1 uses one session key per pairing for the lifetime of the pairing session; the ~2^32-frame
 * budget is far beyond v1's message volume. When mesh relay (D5) introduces long-lived
 * multi-hop sessions, add explicit epoch/rekey negotiation here and document the counter in
 * docs/protocol.md before shipping.
 */
@FlashInternalApi
public object E2eFrameCodec {

    /** GCM-standard 96-bit nonce length. */
    public const val NONCE_BYTES: Int = 12

    /** Full-strength GCM authentication tag. */
    public const val TAG_BITS: Int = 128

    /** Frame prefix for wire-level encrypted chat frames. */
    public const val SEC_PREFIX: String = "FLASH_SEC"

    /** Payload field key for `FLASH_SEC` frames. */
    public const val KEY_PAYLOAD: String = "payload"

    /** Additional authenticated data binding frames to the negotiated protocol version. */
    private fun aad(): ByteArray = "flash-e2e-v${FlashProtocol.VERSION}".encodeToByteArray()

    /** Returns true if [text] starts with the `FLASH_SEC` frame prefix. */
    public fun isSecuredFrame(text: String): Boolean =
        text.startsWith("$SEC_PREFIX ") || text == SEC_PREFIX

    /**
     * Encrypts a payload into `[nonce | ciphertext+tag]` under the shared 32-byte
     * AES-256 session key.
     */
    public fun encrypt(payloadJson: String, sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == FlashCrypto.SESSION_KEY_SIZE_BYTES) {
            "Session key must be ${FlashCrypto.SESSION_KEY_SIZE_BYTES} bytes (AES-256), was ${sessionKey.size}"
        }
        val nonce = secureRandomBytes(NONCE_BYTES)
        val ciphertext = aesGcmSeal(
            key = sessionKey,
            nonce = nonce,
            aad = aad(),
            plaintext = payloadJson.encodeToByteArray(),
        )
        return nonce + ciphertext
    }

    /**
     * Inverse of [encrypt]. Throws when the frame was tampered with or encrypted under a
     * different key/AAD — callers must treat that as an unauthenticated/garbage frame, not
     * attempt delivery. The exception type is platform-defined
     * (`javax.crypto.AEADBadTagException` on JVM targets); see [aesGcmOpen].
     */
    public fun decrypt(frame: ByteArray, sessionKey: ByteArray): String {
        require(sessionKey.size == FlashCrypto.SESSION_KEY_SIZE_BYTES) {
            "Session key must be ${FlashCrypto.SESSION_KEY_SIZE_BYTES} bytes (AES-256), was ${sessionKey.size}"
        }
        require(frame.size > NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS) {
            "E2E frame too short (${frame.size} bytes) to contain nonce + tag"
        }
        val plaintext = aesGcmOpen(
            key = sessionKey,
            nonce = frame.copyOfRange(0, NONCE_BYTES),
            aad = aad(),
            ciphertext = frame.copyOfRange(NONCE_BYTES, frame.size),
        )
        return plaintext.decodeToString()
    }

    /**
     * Encrypts plaintext wire frame [plainText] using [sessionKey] (32-byte AES-256)
     * and wraps it into a `FLASH_SEC` wire frame string.
     */
    public fun encryptToWireFrame(plainText: String, sessionKey: ByteArray): String {
        val encryptedBytes = encrypt(plainText, sessionKey)
        val payloadBase64 = Base64.encode(encryptedBytes)
        return FlashTextFraming.encodeFields(SEC_PREFIX, KEY_PAYLOAD to payloadBase64)
    }

    /**
     * Attempts to decrypt an incoming `FLASH_SEC` wire frame using [sessionKey].
     * Returns the decrypted plaintext frame string, or null if the frame is invalid,
     * not a secured frame, or authentication failed (e.g. bad tag, tampered, wrong key).
     */
    public fun decryptWireFrame(text: String, sessionKey: ByteArray): String? {
        val fields = FlashTextFraming.parseFields(text, SEC_PREFIX) ?: return null
        val payloadBase64 = fields[KEY_PAYLOAD] ?: return null
        val encryptedBytes = runCatching { Base64.decode(payloadBase64) }.getOrNull() ?: return null
        return runCatching { decrypt(encryptedBytes, sessionKey) }.getOrNull()
    }
}
