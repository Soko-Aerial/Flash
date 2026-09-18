@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashProtocol

/**
 * Binary frame-level end-to-end encryption for chunked file transfers and binary wire payloads.
 *
 * Wire format produced by [encrypt]:
 * ```
 * offset  size  field
 * 0       4     MAGIC = 'F','S','E','C' (0x46 0x53 0x45 0x43)
 * 4       1     VERSION = 2 (matches FlashProtocol.VERSION)
 * 5       1     ENVELOPE_TYPE = 1 (AES_256_GCM_128)
 * 6       12    NONCE (12-byte random CSPRNG nonce)
 * 18      4     CIPHERTEXT_LENGTH (uint32 LE; validated against remaining bytes)
 * 22      ...   CIPHERTEXT (payload ciphertext + 16-byte GCM authentication tag)
 * ```
 *
 * Algorithm: AES-256-GCM with 128-bit authentication tag, keyed by the 32-byte shared session key
 * derived via ECDH P-256 during peer pairing. AAD binds every frame to the protocol version
 * (`"flash-binary-e2e-v${FlashProtocol.VERSION}"`).
 *
 * Sits directly in front of [com.transfer.flash.core.transfer.chunked.ChunkFrame] on the wire:
 * when communicating with a paired peer holding a session key, all binary frames (FILE_START,
 * CHUNK, ACK_BATCH, COMPLETE) are sealed into FSEC envelopes. Senders without a session key
 * fall back to plain FLSH frames (opportunistic encryption).
 */
@FlashInternalApi
public object SecureBinaryFrameCodec {

    /** Magic prefix identifying Flash Secure Binary Frames: 'F','S','E','C'. */
    public val MAGIC: ByteArray = byteArrayOf(
        'F'.code.toByte(),
        'S'.code.toByte(),
        'E'.code.toByte(),
        'C'.code.toByte(),
    )

    /** Framing version; pinned to FlashProtocol.VERSION (v2). */
    public const val VERSION: Int = 2

    /** Envelope cipher type: 1 = AES-256-GCM with 128-bit tag. */
    public const val ENVELOPE_TYPE_AES_GCM: Byte = 1

    /** GCM-standard 96-bit (12-byte) nonce length. */
    public const val NONCE_BYTES: Int = 12

    /** Full-strength GCM authentication tag length in bytes (128 bits). */
    public const val TAG_BYTES: Int = 16

    /** Total fixed header size preceding ciphertext: 4 (magic) + 1 (ver) + 1 (type) + 12 (nonce) + 4 (len) = 22. */
    public const val HEADER_SIZE: Int = 22

    /** Minimum size of a valid FSEC frame (header + 16-byte tag with 0-byte payload). */
    public const val MIN_FRAME_SIZE: Int = HEADER_SIZE + TAG_BYTES

    private fun aad(): ByteArray = "flash-binary-e2e-v${FlashProtocol.VERSION}".encodeToByteArray()

    /** Returns true if [bytes] starts with the `FSEC` frame magic. */
    public fun isSecureFrame(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return false
        }
        return true
    }

    /**
     * Encrypts binary [plaintext] into an `FSEC` wire frame using [sessionKey] (32-byte AES-256).
     */
    public fun encrypt(plaintext: ByteArray, sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == FlashCrypto.SESSION_KEY_SIZE_BYTES) {
            "Session key must be ${FlashCrypto.SESSION_KEY_SIZE_BYTES} bytes (AES-256), was ${sessionKey.size}"
        }
        val nonce = secureRandomBytes(NONCE_BYTES)
        val ciphertext = aesGcmSeal(
            key = sessionKey,
            nonce = nonce,
            aad = aad(),
            plaintext = plaintext,
        )
        val result = ByteArray(HEADER_SIZE + ciphertext.size)
        MAGIC.copyInto(result, destinationOffset = 0)
        result[4] = VERSION.toByte()
        result[5] = ENVELOPE_TYPE_AES_GCM
        nonce.copyInto(result, destinationOffset = 6)
        writeI32Le(result, 18, ciphertext.size)
        ciphertext.copyInto(result, destinationOffset = HEADER_SIZE)
        return result
    }

    /**
     * Decrypts an `FSEC` wire frame using [sessionKey].
     * Throws an exception if the frame is malformed, truncated, or tampered with.
     */
    public fun decrypt(frame: ByteArray, sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == FlashCrypto.SESSION_KEY_SIZE_BYTES) {
            "Session key must be ${FlashCrypto.SESSION_KEY_SIZE_BYTES} bytes (AES-256), was ${sessionKey.size}"
        }
        require(frame.size >= MIN_FRAME_SIZE) {
            "Secure binary frame too short (${frame.size} bytes), min is $MIN_FRAME_SIZE"
        }
        for (i in MAGIC.indices) {
            require(frame[i] == MAGIC[i]) { "Invalid magic for secure binary frame" }
        }
        require(frame[4].toInt() and 0xFF == VERSION) {
            "Unsupported secure binary frame version: ${frame[4]}"
        }
        require(frame[5] == ENVELOPE_TYPE_AES_GCM) {
            "Unsupported envelope cipher type: ${frame[5]}"
        }
        val declaredCiphertextLength = readI32Le(frame, 18)
        val actualCiphertextLength = frame.size - HEADER_SIZE
        require(declaredCiphertextLength == actualCiphertextLength) {
            "Ciphertext length mismatch: declared $declaredCiphertextLength != actual $actualCiphertextLength"
        }
        val nonce = frame.copyOfRange(6, 18)
        val ciphertext = frame.copyOfRange(HEADER_SIZE, frame.size)
        return aesGcmOpen(
            key = sessionKey,
            nonce = nonce,
            aad = aad(),
            ciphertext = ciphertext,
        )
    }

    /**
     * Safely decrypts an `FSEC` wire frame if [sessionKey] is provided and valid.
     * Returns null if [sessionKey] is null, frame is not a valid secure frame, or authentication fails.
     */
    public fun decryptOrNull(frame: ByteArray, sessionKey: ByteArray?): ByteArray? {
        if (sessionKey == null || !isSecureFrame(frame)) return null
        return runCatching { decrypt(frame, sessionKey) }.getOrNull()
    }

    private fun writeI32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readI32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
