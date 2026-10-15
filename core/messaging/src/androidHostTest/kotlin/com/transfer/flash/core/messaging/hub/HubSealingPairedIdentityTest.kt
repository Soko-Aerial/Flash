@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.enterprise.HubPeerStanding
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-164b: a colleague this device paired with directly keeps the identity key saved at pairing. When the hub directory
 * serves another identity key (a hub that swaps keys), nothing is sealed, sent or opened, and the status cannot be
 * confirmed away.
 */
class HubSealingPairedIdentityTest {

    private class JdkCrypto : FlashCrypto {
        private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val identityPublicKeyEncoded: ByteArray get() = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign()

        override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
            Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(data) }.verify(signature)
        } catch (_: Throwable) {
            false
        }

        override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = throw UnsupportedOperationException()
        override fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray = throw UnsupportedOperationException()
    }

    private class Person(val id: String) {
        val crypto = JdkCrypto()
        val sealingKey: HubSealingKeyPair = HubSealingKeyPair.generate()

        fun entry() = EnterpriseDirectoryMember(
            deviceId = id, friendlyName = id, department = "Ops",
            subjectPublicKey = Base64.encode(crypto.identityPublicKeyEncoded),
            sealingPublicKey = Base64.encode(sealingKey.publicKeyEncoded),
            sealingSignature = sealingKey.bindingSignature(crypto, id),
        )

        fun fingerprintHex(): String =
            FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private val alice = Person("alice")
    private val bob = Person("bob")
    private val impostor = Person("bob") // a hub-made fake with its own identity and sealing key, claiming bob's id

    private fun sealingFor(directoryEntry: () -> EnterpriseDirectoryMember?, saved: String?) =
        DirectoryHubSealing(
            crypto = alice.crypto,
            localDeviceId = "alice",
            ownKey = { alice.sealingKey },
            pairedIdentityFingerprint = { if (it == "bob") saved else null },
            lookup = { if (it == "bob") directoryEntry() else null },
        )

    @Test
    fun `a paired colleague whose directory key matches the saved one is sealable`() = runBlocking {
        val s = sealingFor({ bob.entry() }, bob.fingerprintHex().uppercase())
        assertEquals(HubSealingStatus.AVAILABLE, s.statusFor("bob"))
        assertTrue(s.seal("bob", "m1", 1L, "chat", "hello") != null)
    }

    @Test
    fun `colons and lower case in the saved fingerprint still match`() = runBlocking {
        val colon = bob.fingerprintHex().uppercase().chunked(2).joinToString(":")
        assertEquals(HubSealingStatus.AVAILABLE, sealingFor({ bob.entry() }, colon).statusFor("bob"))
        assertEquals(HubSealingStatus.AVAILABLE, sealingFor({ bob.entry() }, bob.fingerprintHex().lowercase()).statusFor("bob"))
    }

    @Test
    fun `a swapped identity key for a paired colleague is refused and nothing is sealed`() = runBlocking {
        val s = sealingFor({ impostor.entry() }, bob.fingerprintHex())
        assertEquals(HubSealingStatus.IDENTITY_MISMATCH, s.statusFor("bob"))
        assertNull(s.seal("bob", "m1", 1L, "chat", "secret"))
        assertEquals(false, s.canSealFor("bob"))
        assertEquals(false, s.confirmKeyChange("bob"))
    }

    @Test
    fun `an unpaired colleague is unaffected`() = runBlocking {
        assertEquals(HubSealingStatus.AVAILABLE, sealingFor({ impostor.entry() }, null).statusFor("bob"))
    }

    @Test
    fun `a sealed message from a swapped identity is not opened`() = runBlocking {
        val sealedByImpostor = DirectoryHubSealing(
            crypto = impostor.crypto, localDeviceId = "bob", ownKey = { impostor.sealingKey },
            lookup = { if (it == "alice") alice.entry() else null },
        ).seal("alice", "m1", 1L, "chat", "forged")!!
        val receiver = sealingFor({ impostor.entry() }, bob.fingerprintHex())
        assertNull(receiver.open("bob", "m1", 1L, sealedByImpostor))
    }

    @Test
    fun `the bridge holds a message and never falls back to readable text on a mismatch`() = runBlocking {
        val relay = FakeHubRelay()
        val bridge = HubChatBridge(
            localDeviceId = "alice", port = relay, contentViaHub = { true },
            sealing = sealingFor({ impostor.entry() }, bob.fingerprintHex()),
            standing = { HubPeerStanding.MEMBER },
        )
        assertEquals(false, bridge.canCarry("bob"))
        assertEquals(HubWaitReason.IDENTITY_MISMATCH, bridge.waiting.value["bob"])
        val frame = com.transfer.flash.core.messaging.protocol.MessageWireFrame.TextMessage("m-1", "bob", "alice", "Alice", "secret", 1L)
        assertEquals(false, bridge.sendDirect("bob", "m-1", 1L, frame))
        assertTrue("nothing at all reached the relay", relay.sent.isEmpty())
    }
}
