@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.enterprise.HubPeerStanding
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLI-05 (ADR-121): sealed hub content opens only for the right recipient, from the right sender, untampered. */
class HubSealingBridgeTest {

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

        fun directoryEntry(sealingKeyOverride: ByteArray? = null, signatureOverride: String? = null) = EnterpriseDirectoryMember(
            deviceId = id, friendlyName = id, department = "Ops",
            subjectPublicKey = Base64.encode(crypto.identityPublicKeyEncoded),
            sealingPublicKey = Base64.encode(sealingKeyOverride ?: sealingKey.publicKeyEncoded),
            sealingSignature = signatureOverride ?: sealingKey.bindingSignature(crypto, id),
        )
    }

    private val alice = Person("alice")
    private val bob = Person("bob")
    private val eve = Person("eve")

    private fun sealingFor(me: Person, directory: Map<String, EnterpriseDirectoryMember>) =
        DirectoryHubSealing(me.crypto, me.id, { me.sealingKey }) { directory[it] }

    private class Sink : HubInboundSink {
        val frames = mutableListOf<MessageWireFrame>()
        override suspend fun direct(frame: MessageWireFrame, from: String) {
            frames += frame
        }

        override suspend fun group(frame: com.transfer.flash.core.messaging.protocol.GroupWireFrame, from: String) = Unit
    }

    private fun aliceBridge(relay: FakeHubRelay, directory: Map<String, EnterpriseDirectoryMember>) = HubChatBridge(
        localDeviceId = "alice", port = relay, contentViaHub = { false }, sealing = sealingFor(alice, directory),
        standing = { HubPeerStanding.MEMBER },
    )

    private fun bobBridge(relay: FakeHubRelay, directory: Map<String, EnterpriseDirectoryMember>) = HubChatBridge(
        localDeviceId = "bob", port = relay, contentViaHub = { false }, sealing = sealingFor(bob, directory),
        standing = { HubPeerStanding.MEMBER },
    )

    private val frame = MessageWireFrame.TextMessage("m-1", "bob", "alice", "Alice", "the launch code is 1234", 1_000L)

    private fun sealOnce(): FakeHubRelay.Sent {
        val relay = FakeHubRelay()
        val directory = mapOf("bob" to bob.directoryEntry())
        runBlocking {
            assertTrue(aliceBridge(relay, directory).sendDirect("bob", "m-1", 1_000L, frame))
        }
        return relay.sent.single()
    }

    private fun deliverToBob(sent: FakeHubRelay.Sent, from: String = "alice", mutate: (String) -> String = { it }): Pair<HubInboundOutcome, Sink> {
        val relay = FakeHubRelay()
        val sink = Sink()
        val directory = mapOf("alice" to alice.directoryEntry(), "eve" to eve.directoryEntry())
        val outcome = runBlocking {
            bobBridge(relay, directory).handleInbound(HubInbound(from, sent.id, sent.type, sent.sentAt, mutate(sent.payload)), sink)
        }
        return outcome to sink
    }

    @Test
    fun `content is sealed even with the readable setting off and the hub only sees ciphertext`() {
        val sent = sealOnce()
        assertEquals(HubSealing.ENVELOPE_TYPE, sent.type)
        assertFalse("the plaintext must not appear in what the hub receives", sent.payload.contains("launch code"))
    }

    @Test
    fun `the recipient opens a sealed message and stores the original frame`() {
        val (outcome, sink) = deliverToBob(sealOnce())
        assertEquals(HubInboundOutcome.STORED, outcome)
        assertEquals(frame, sink.frames.single())
    }

    @Test
    fun `a tampered sealed message is rejected and never stored`() {
        val sent = sealOnce()
        val (outcome, sink) = deliverToBob(sent) { payload ->
            // Flip one character inside the ciphertext part.
            val parts = payload.split('.').toMutableList()
            val c = parts[3]
            parts[3] = (if (c[2] == 'A') 'B' else 'A').let { c.substring(0, 2) + it + c.substring(3) }
            parts.joinToString(".")
        }
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(sink.frames.isEmpty())
    }

    @Test
    fun `a sealed message re-sent under another sender is rejected`() {
        val (outcome, sink) = deliverToBob(sealOnce(), from = "eve")
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(sink.frames.isEmpty())
    }

    @Test
    fun `a sealed message for someone else cannot be opened`() {
        val sent = sealOnce()
        val relay = FakeHubRelay()
        val sink = Sink()
        val directory = mapOf("alice" to alice.directoryEntry())
        val outcome = runBlocking {
            val evesBridge = HubChatBridge(
                localDeviceId = "eve", port = relay, contentViaHub = { false }, sealing = sealingFor(eve, directory),
                standing = { HubPeerStanding.MEMBER },
            )
            evesBridge.handleInbound(HubInbound("alice", sent.id, sent.type, sent.sentAt, sent.payload), sink)
        }
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(sink.frames.isEmpty())
    }

    @Test
    fun `a sealing key the recipient's identity did not vouch for is never used`() {
        val relay = FakeHubRelay()
        // The hub swaps in eve's sealing key for bob but cannot produce bob's identity signature over it.
        val forged = bob.directoryEntry(sealingKeyOverride = eve.sealingKey.publicKeyEncoded, signatureOverride = bob.sealingKey.bindingSignature(bob.crypto, "bob"))
        val sent = runBlocking {
            val bridge = aliceBridge(relay, mapOf("bob" to forged))
            assertFalse(bridge.canCarry("bob"))
            bridge.sendDirect("bob", "m-1", 1_000L, frame)
        }
        assertFalse(sent)
        assertTrue("nothing readable may be sent instead", relay.sent.isEmpty())
        assertNotNull(forged)
    }

    /** RVW22 F1: a message that cannot be opened for a passing reason is kept, not acknowledged (the hub deletes on ack). */
    private fun deliverWith(sealing: DirectoryHubSealing, sent: FakeHubRelay.Sent): Triple<HubInboundOutcome, Sink, FakeHubRelay> {
        val relay = FakeHubRelay()
        val sink = Sink()
        val outcome = runBlocking {
            HubChatBridge(
                localDeviceId = "bob", port = relay, contentViaHub = { false }, sealing = sealing,
                standing = { HubPeerStanding.MEMBER },
            ).handleInbound(HubInbound("alice", sent.id, sent.type, sent.sentAt, sent.payload), sink)
        }
        return Triple(outcome, sink, relay)
    }

    @Test
    fun `a sealed message is kept and not acknowledged while this device has no sealing key yet`() {
        val sealing = DirectoryHubSealing(bob.crypto, "bob", { null }) { mapOf("alice" to alice.directoryEntry())[it] }
        val (outcome, sink, relay) = deliverWith(sealing, sealOnce())
        assertEquals(HubInboundOutcome.RETRY_LATER, outcome)
        assertTrue(sink.frames.isEmpty())
        assertTrue("the hub must not be told to delete it", relay.recvAcks.isEmpty())
    }

    @Test
    fun `a sealed message is kept and not acknowledged while the directory has no entry for the sender`() {
        val sealing = DirectoryHubSealing(bob.crypto, "bob", { bob.sealingKey }) { null }
        val (outcome, sink, relay) = deliverWith(sealing, sealOnce())
        assertEquals(HubInboundOutcome.RETRY_LATER, outcome)
        assertTrue(sink.frames.isEmpty())
        assertTrue("the hub must not be told to delete it", relay.recvAcks.isEmpty())
    }
}
