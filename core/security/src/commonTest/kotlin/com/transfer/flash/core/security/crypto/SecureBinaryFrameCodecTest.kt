@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureBinaryFrameCodecTest {

    private val sessionKey = ByteArray(32) { (it + 1).toByte() }
    private val otherKey = ByteArray(32) { (it + 50).toByte() }

    @Test
    fun roundtripEncryptionAndDecryption() {
        val payload = "Hello secure binary world! Testing chunk encryption.".encodeToByteArray()
        val encrypted = SecureBinaryFrameCodec.encrypt(payload, sessionKey)

        assertTrue(SecureBinaryFrameCodec.isSecureFrame(encrypted))
        val decrypted = SecureBinaryFrameCodec.decrypt(encrypted, sessionKey)
        assertEquals(payload.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun decryptWithWrongKeyFails() {
        val payload = "Secret payload".encodeToByteArray()
        val encrypted = SecureBinaryFrameCodec.encrypt(payload, sessionKey)

        assertFailsWith<Throwable> {
            SecureBinaryFrameCodec.decrypt(encrypted, otherKey)
        }
        assertNull(SecureBinaryFrameCodec.decryptOrNull(encrypted, otherKey))
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val payload = "Sensitive file chunk data".encodeToByteArray()
        val encrypted = SecureBinaryFrameCodec.encrypt(payload, sessionKey)

        // Tamper with the last byte (part of GCM tag)
        val tampered = encrypted.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()

        assertFailsWith<Throwable> {
            SecureBinaryFrameCodec.decrypt(tampered, sessionKey)
        }
        assertNull(SecureBinaryFrameCodec.decryptOrNull(tampered, sessionKey))
    }

    @Test
    fun tamperedNonceFailsAuthentication() {
        val payload = "Sensitive file chunk data".encodeToByteArray()
        val encrypted = SecureBinaryFrameCodec.encrypt(payload, sessionKey)

        // Tamper with nonce byte (offset 8)
        val tampered = encrypted.copyOf()
        tampered[8] = (tampered[8].toInt() xor 0xFF).toByte()

        assertFailsWith<Throwable> {
            SecureBinaryFrameCodec.decrypt(tampered, sessionKey)
        }
        assertNull(SecureBinaryFrameCodec.decryptOrNull(tampered, sessionKey))
    }

    @Test
    fun plaintextChunkFrameIsNotRecognizedAsSecure() {
        val plainFlshFrame = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 2, 1, 0, 0, 0, 0)
        assertFalse(SecureBinaryFrameCodec.isSecureFrame(plainFlshFrame))
        assertNull(SecureBinaryFrameCodec.decryptOrNull(plainFlshFrame, sessionKey))
    }

    @Test
    fun truncatedFrameIsRejected() {
        val payload = "Short".encodeToByteArray()
        val encrypted = SecureBinaryFrameCodec.encrypt(payload, sessionKey)
        val truncated = encrypted.copyOfRange(0, SecureBinaryFrameCodec.HEADER_SIZE + 5)

        assertFailsWith<Throwable> {
            SecureBinaryFrameCodec.decrypt(truncated, sessionKey)
        }
        assertNull(SecureBinaryFrameCodec.decryptOrNull(truncated, sessionKey))
    }

    @Test
    fun largePayloadRoundtripsEfficiently() {
        val largeData = ByteArray(64 * 1024) { (it % 251).toByte() }
        val encrypted = SecureBinaryFrameCodec.encrypt(largeData, sessionKey)
        val decrypted = SecureBinaryFrameCodec.decrypt(encrypted, sessionKey)

        assertTrue(largeData.contentEquals(decrypted))
    }
}
