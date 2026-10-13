@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.network.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.network.enterprise.HubPeerStanding
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.HubSealingPins
import com.transfer.flash.core.security.enterprise.MemoryHubSealingPinStore
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-126: two in-memory hosts exchange messages through the REAL [HubChatBridge], [DirectoryHubSealing] and
 * [HubSealingPins]: sealing without the readable switch, no silent downgrade, changed keys held until confirmed,
 * groups never sealed, unverifiable sealed messages counted and never stored.
 */
class HubSealingHostFlowTest {

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
        var sealingKey: HubSealingKeyPair = HubSealingKeyPair.generate()
        var published = true

        fun entry(): EnterpriseDirectoryMember = EnterpriseDirectoryMember(
            deviceId = id, friendlyName = id, department = "Ops",
            subjectPublicKey = Base64.encode(crypto.identityPublicKeyEncoded),
            sealingPublicKey = if (published) Base64.encode(sealingKey.publicKeyEncoded) else null,
            sealingSignature = if (published) sealingKey.bindingSignature(crypto, id) else null,
        )

        fun rotateKey() {
            sealingKey = HubSealingKeyPair.generate()
        }
    }

    private class Sink : HubInboundSink {
        val frames = mutableListOf<MessageWireFrame>()
        val groupFrames = mutableListOf<GroupWireFrame>()
        override suspend fun direct(frame: MessageWireFrame, from: String) {
            frames += frame
        }

        override suspend fun group(frame: GroupWireFrame, from: String) {
            groupFrames += frame
        }
    }

    /** One device: its bridge over its own relay, backed by a shared directory the test edits. */
    private class Host(
        val me: Person,
        val directory: MutableMap<String, Person>,
        val pinStore: MemoryHubSealingPinStore = MemoryHubSealingPinStore(),
        val readable: Boolean = false,
        val directoryDown: () -> Boolean = { false },
    ) {
        val relay = FakeHubRelay()
        val pins = HubSealingPins(pinStore)
        val sealing = DirectoryHubSealing(me.crypto, me.id, { me.sealingKey }, pins) { id ->
            if (directoryDown()) null else directory[id]?.entry()
        }
        var standing: HubPeerStanding = HubPeerStanding.MEMBER
        val bridge = HubChatBridge(
            localDeviceId = me.id, port = relay, contentViaHub = { readable }, sealing = sealing,
            standing = { standing },
        )
        val sink = Sink()
    }

    private val alice = Person("alice")
    private val bob = Person("bob")

    private fun directory() = mutableMapOf("alice" to alice, "bob" to bob)

    private fun text(id: String = "m-1") = MessageWireFrame.TextMessage(id, "bob", "alice", "Alice", "the launch code is 1234", 1_000L)

    private fun deliver(from: Host, to: Host, index: Int = 0): HubInboundOutcome {
        val sent = from.relay.sent[index]
        return runBlocking { to.bridge.handleInbound(HubInbound(from.me.id, sent.id, sent.type, sent.sentAt, sent.payload), to.sink) }
    }

    @Test
    fun `a sealed message travels between two hosts with the readable switch off`() = runBlocking {
        val dir = directory()
        val a = Host(alice, dir)
        val b = Host(bob, dir)
        assertTrue(a.bridge.canCarry("bob"))
        assertTrue(a.bridge.sendDirect("bob", "m-1", 1_000L, text()))
        assertEquals(HubSealing.ENVELOPE_TYPE, a.relay.sent.single().type)
        assertFalse(a.relay.sent.single().payload.contains("launch code"))
        assertEquals(HubInboundOutcome.STORED, deliver(a, b))
        assertEquals(text(), b.sink.frames.single())
    }

    @Test
    fun `a colleague without a published key gets nothing with the readable switch off and the reason is kept`() = runBlocking {
        val dir = directory()
        bob.published = false
        try {
            val a = Host(alice, dir)
            assertFalse(a.bridge.canCarry("bob"))
            assertFalse(a.bridge.sendDirect("bob", "m-1", 1_000L, text()))
            assertTrue("never a plaintext fallback", a.relay.sent.isEmpty())
            assertEquals(HubWaitReason.NEEDS_UPDATE, a.bridge.waiting.value["bob"])
        } finally {
            bob.published = true
        }
    }

    @Test
    fun `a colleague without a published key gets a readable message only when the switch is on`() = runBlocking {
        val dir = directory()
        bob.published = false
        try {
            val a = Host(alice, dir, readable = true)
            assertTrue(a.bridge.canCarry("bob"))
            assertTrue(a.bridge.sendDirect("bob", "m-1", 1_000L, text()))
            assertTrue(a.relay.sent.single().type != HubSealing.ENVELOPE_TYPE)
            assertNull(a.bridge.waiting.value["bob"])
        } finally {
            bob.published = true
        }
    }

    @Test
    fun `a changed key holds every message even with the readable switch on until the user continues`() = runBlocking {
        val dir = directory()
        val store = MemoryHubSealingPinStore()
        val a = Host(alice, dir, store, readable = true)
        assertTrue(a.bridge.sendDirect("bob", "m-1", 1_000L, text("m-1")))

        bob.rotateKey() // reinstall: the directory now serves another key for the same device id
        try {
            assertFalse(a.bridge.canCarry("bob"))
            assertFalse(a.bridge.sendDirect("bob", "m-2", 2_000L, text("m-2")))
            assertEquals("no readable downgrade, nothing sent", 1, a.relay.sent.size)
            assertEquals(HubWaitReason.KEY_CHANGED, a.bridge.waiting.value["bob"])
            assertTrue(a.sealing.pendingKeyChanges.value.containsKey("bob"))
            assertTrue("the message keeps waiting, it is not failed", a.bridge.wouldHold("m-2", "bob"))

            // The hold survives a restart: a new pins object over the same store still blocks.
            val restarted = Host(alice, dir, store, readable = true)
            restarted.pins.load()
            assertFalse(restarted.bridge.canCarry("bob"))

            assertTrue(a.bridge.confirmKeyChange("bob"))
            assertTrue(a.bridge.canCarry("bob"))
            assertTrue(a.bridge.sendDirect("bob", "m-2", 2_000L, text("m-2")))
            assertEquals(HubSealing.ENVELOPE_TYPE, a.relay.sent.last().type)
            assertTrue(a.sealing.pendingKeyChanges.value.isEmpty())
        } finally {
            // keep the shared people untouched for other tests (they are per test instance anyway)
        }
    }

    @Test
    fun `a pending key change keeps holding when the directory cannot be read`() = runBlocking {
        val dir = directory()
        var down = false
        val a = Host(alice, dir, directoryDown = { down })
        assertTrue(a.bridge.sendDirect("bob", "m-1", 1_000L, text()))
        bob.rotateKey()
        assertFalse(a.bridge.canCarry("bob"))
        down = true
        assertFalse("a missing answer must not release the hold", a.bridge.sendDirect("bob", "m-2", 2_000L, text("m-2")))
        assertEquals(1, a.relay.sent.size)
    }

    @Test
    fun `groups are never sealed and need the readable switch`() = runBlocking {
        val dir = directory()
        val frame = GroupWireFrame.Receipt("g-1", "m-9", "alice", 1_000L)
        val off = Host(alice, dir)
        assertFalse("sealing keys exist, but groups are not sealed: without the switch nothing goes", off.bridge.canCarry("bob", group = true))
        assertFalse(off.bridge.sendGroup("bob", "g-1:m", 1_000L, frame) && off.relay.sent.isNotEmpty() && off.relay.sent.single().type == HubSealing.ENVELOPE_TYPE)
        assertTrue(off.relay.sent.none { it.type == HubSealing.ENVELOPE_TYPE })

        val on = Host(alice, dir, readable = true)
        assertTrue(on.bridge.canCarry("bob", group = true))
        assertTrue(on.bridge.sendGroup("bob", "g-1:m", 1_000L, frame))
        assertTrue("group frames travel readable", on.relay.sent.single().type != HubSealing.ENVELOPE_TYPE)
    }

    @Test
    fun `a changed key does not block group frames the user already agreed to send readable`() = runBlocking {
        // Group frames are not sealed, so the sealing key of a colleague is irrelevant to them (ADR-123: group design owed).
        val dir = directory()
        val a = Host(alice, dir, readable = true)
        assertTrue(a.bridge.canCarry("bob"))
        bob.rotateKey()
        assertTrue(a.bridge.canCarry("bob", group = true))
    }

    @Test
    fun `a sealed message that does not verify is counted and never stored`() = runBlocking {
        val dir = directory()
        val a = Host(alice, dir)
        val b = Host(bob, dir)
        assertTrue(a.bridge.sendDirect("bob", "m-1", 1_000L, text()))
        val sent = a.relay.sent.single()
        val parts = sent.payload.split('.').toMutableList()
        val c = parts[3]
        parts[3] = c.substring(0, 2) + (if (c[2] == 'A') 'B' else 'A') + c.substring(3)
        val outcome = b.bridge.handleInbound(HubInbound("alice", sent.id, sent.type, sent.sentAt, parts.joinToString(".")), b.sink)
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(b.sink.frames.isEmpty())
        assertEquals(1, b.bridge.droppedUnverified.value)
    }

    @Test
    fun `a sealed message is kept for later when the sender cannot be looked up right now`() = runBlocking {
        val dir = directory()
        val a = Host(alice, dir)
        assertTrue(a.bridge.sendDirect("bob", "m-1", 1_000L, text()))
        val sent = a.relay.sent.single()
        var down = false
        val b = Host(bob, dir, directoryDown = { down })
        down = true
        b.standing = HubPeerStanding.UNAVAILABLE
        val outcome = b.bridge.handleInbound(HubInbound("alice", sent.id, sent.type, sent.sentAt, sent.payload), b.sink)
        assertTrue(outcome == HubInboundOutcome.RETRY_LATER)
        assertEquals("not counted as a forgery", 0, b.bridge.droppedUnverified.value)
        assertTrue(b.sink.frames.isEmpty())
    }
}
