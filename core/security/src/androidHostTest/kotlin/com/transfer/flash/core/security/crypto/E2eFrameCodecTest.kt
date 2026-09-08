package com.transfer.flash.core.security.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class E2eFrameCodecTest {

    private fun freshSessionKey(): ByteArray = ByteArray(FlashCrypto.SESSION_KEY_SIZE_BYTES).also {
        SecureRandom().nextBytes(it)
    }

    @Test
    fun `encrypt then decrypt roundtrip restores payload`() {
        val key = freshSessionKey()
        val payload = """{"id":"abc-1","type":"MSG_TEXT","payloadJson":"hello","senderId":"devA","sentAt":1234}"""

        val frame = E2eFrameCodec.encrypt(payload, key)

        assertEquals(payload, E2eFrameCodec.decrypt(frame, key))
    }

    @Test
    fun `frame layout is nonce followed by ciphertext`() {
        val key = freshSessionKey()
        val frame = E2eFrameCodec.encrypt("x", key)

        val expectedSize = E2eFrameCodec.NONCE_BYTES + "x".toByteArray().size + E2eFrameCodec.TAG_BITS / Byte.SIZE_BITS
        assertEquals(expectedSize, frame.size)
    }

    @Test
    fun `nonces are random per encryption - same plaintext yields different frames`() {
        val key = freshSessionKey()
        assertFalse(
            E2eFrameCodec.encrypt("same", key).contentEquals(E2eFrameCodec.encrypt("same", key)),
        )
    }

    @Test
    fun `wrong key decryption throws AEADBadTagException`() {
        val frame = E2eFrameCodec.encrypt("secret", freshSessionKey())
        assertThrows(AEADBadTagException::class.java) {
            E2eFrameCodec.decrypt(frame, freshSessionKey())
        }
    }

    @Test
    fun `tampered ciphertext throws AEADBadTagException`() {
        val key = freshSessionKey()
        val frame = E2eFrameCodec.encrypt("secret", key)
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) { E2eFrameCodec.decrypt(frame, key) }
    }

    @Test
    fun `tampered nonce throws AEADBadTagException`() {
        val key = freshSessionKey()
        val frame = E2eFrameCodec.encrypt("secret", key)
        frame[0] = (frame[0].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) { E2eFrameCodec.decrypt(frame, key) }
    }

    @Test
    fun `oversized session keys are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            E2eFrameCodec.encrypt("x", ByteArray(16))
        }
    }
}
