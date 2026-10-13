@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.crypto.SoftwareFlashCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-05 / ADR-121: end-to-end sealing of hub-relayed chat payloads. */
class HubSealingTest {

    private val sender = SoftwareFlashCrypto()
    private val senderPub = sender.identityPublicKeyEncoded
    private val recipientKey = HubSealingKeyPair.generate()

    private fun seal(
        body: String = "hello from the hub-only side",
        msgId: String = "m-1",
        sentAt: Long = 1_700_000_000_000L,
        senderId: String = "dev-a",
        recipientId: String = "dev-b",
        to: ByteArray = recipientKey.publicKeyEncoded,
    ): String = HubSealing.seal(sender, senderId, recipientId, to, msgId, sentAt, "CHAT_FRAME", body)

    private fun open(
        payload: String,
        key: HubSealingKeyPair = recipientKey,
        senderIdentity: ByteArray = senderPub,
        msgId: String = "m-1",
        sentAt: Long = 1_700_000_000_000L,
        senderId: String = "dev-a",
        recipientId: String = "dev-b",
    ): UnsealedPayload? = HubSealing.open(key, senderIdentity, senderId, recipientId, msgId, sentAt, payload)

    @Test
    fun sealThenOpenRoundTripsTypeAndBody() {
        val body = "multi\nline text é中"
        val opened = assertNotNull(open(seal(body)))
        assertEquals("CHAT_FRAME", opened.innerType)
        assertEquals(body, opened.body)
    }

    @Test
    fun theSealedPayloadNeverContainsThePlaintext() {
        val secret = "the-launch-code-is-4711"
        val payload = seal(secret)
        assertFalse(payload.contains(secret))
        assertFalse(payload.contains("CHAT_FRAME"))
        assertFalse(Base64.decode(payload.split('.')[3]).decodeToString().contains(secret))
    }

    @Test
    fun twoSealsOfTheSameTextDiffer() {
        assertNotEquals(seal(), seal())
    }

    @Test
    fun aFlippedCiphertextByteIsRejected() {
        val parts = seal().split('.').toMutableList()
        val ct = Base64.decode(parts[3])
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 0x01).toByte()
        parts[3] = Base64.encode(ct)
        assertNull(open(parts.joinToString(".")))
    }

    @Test
    fun aSwappedEphemeralKeyIsRejected() {
        val other = HubSealingKeyPair.generate().publicKeyEncoded
        val parts = seal().split('.').toMutableList()
        parts[1] = Base64.encode(other)
        assertNull(open(parts.joinToString(".")))
    }

    @Test
    fun everyBoundFieldIsChecked() {
        val payload = seal()
        assertNotNull(open(payload))
        assertNull(open(payload, msgId = "m-2"), "a hub replaying the payload under another id")
        assertNull(open(payload, sentAt = 1_700_000_000_001L), "another send time")
        assertNull(open(payload, recipientId = "dev-c"), "another recipient")
        assertNull(open(payload, senderId = "dev-x"), "another claimed sender")
    }

    @Test
    fun aPayloadSignedByAnotherDeviceIsRejected() {
        val impostor = SoftwareFlashCrypto()
        val forged = HubSealing.seal(
            impostor, "dev-a", "dev-b", recipientKey.publicKeyEncoded, "m-1", 1_700_000_000_000L, "CHAT_FRAME", "x",
        )
        // The hub itself can encrypt to the recipient's PUBLIC key; it cannot sign as dev-a.
        assertNull(open(forged))
        assertNotNull(open(forged, senderIdentity = impostor.identityPublicKeyEncoded))
    }

    @Test
    fun aDifferentRecipientKeyCannotOpen() {
        assertNull(open(seal(), key = HubSealingKeyPair.generate()))
    }

    @Test
    fun malformedPayloadsReturnNullInsteadOfThrowing() {
        for (bad in listOf("", "v1", "v2.a.b.c.d", "v1.!!.!!.!!.!!", "v1.AAAA.AAAA.AAAA.AAAA", "a.b.c.d.e.f")) {
            assertNull(open(bad), bad)
        }
    }

    @Test
    fun theAadIsTheDocumentedCanonicalByteString() {
        // Independent encoding of: tag, wire version (int, 4 bytes), sender, recipient, msgId, sentAt (long, 8 bytes),
        // each field with a 4-byte big-endian length prefix.
        fun field(b: ByteArray): ByteArray = byteArrayOf(0, 0, 0, b.size.toByte()) + b
        fun text(s: String): ByteArray = field(s.encodeToByteArray())
        val expected = text("flash-hub-seal-v1-aad") +
            field(byteArrayOf(0, 0, 0, 2)) +
            text("dev-a") + text("dev-b") + text("m-1") +
            field(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 5))
        assertContentEquals(expected, HubSealing.aad("dev-a", "dev-b", "m-1", 261L))
    }

    @Test
    fun aRestoredKeyPairOpensWhatTheOriginalPublicKeyWasSealedTo() {
        val restored = HubSealingKeyPair.restore(recipientKey.publicKeyEncoded, recipientKey.exportPrivateKeyPkcs8())
        assertEquals("hi", open(seal("hi"), key = restored)?.body)
    }

    @Test
    fun theBindingSignatureProvesTheIdentityKeyVouchesForTheSealingKey() {
        val sig = recipientKey.bindingSignature(sender, "dev-b")
        assertTrue(HubSealingKeyPair.verifyBinding(senderPub, "dev-b", recipientKey.publicKeyEncoded, sig))
        assertFalse(HubSealingKeyPair.verifyBinding(senderPub, "dev-c", recipientKey.publicKeyEncoded, sig), "other device id")
        assertFalse(
            HubSealingKeyPair.verifyBinding(senderPub, "dev-b", HubSealingKeyPair.generate().publicKeyEncoded, sig),
            "other sealing key",
        )
        assertFalse(
            HubSealingKeyPair.verifyBinding(SoftwareFlashCrypto().identityPublicKeyEncoded, "dev-b", recipientKey.publicKeyEncoded, sig),
            "other identity key",
        )
        assertFalse(HubSealingKeyPair.verifyBinding(senderPub, "dev-b", recipientKey.publicKeyEncoded, "not base64 !!"))
    }

    @Test
    fun theClientBindingBytesAreExactlyTheSharedGoldenVector() {
        // ADR-124: the hub verifies these very bytes (`PUT /api/v1/device/sealing-key`). The literal is the same one
        // `CertGoldenVectorTest` (hub-protocol) and the server's SealingKeyEndpointTest assert.
        val hex = HubSealingKeyPair.bindingBytes("dev-alpha", ByteArray(16) { it.toByte() })
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals(
            "00000014666c6173682d6875622d7365616c6b65792d7631000000096465762d616c70686100000010000102030405060708090a0b0c0d0e0f",
            hex,
        )
    }
}
