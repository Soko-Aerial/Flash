@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.enterprise.HubPeerStanding
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.enterprise.GroupRelaySealing
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.core.security.enterprise.HubSealingPins
import com.transfer.flash.core.security.enterprise.MemoryHubGroupEpochKeyStore
import com.transfer.flash.core.security.enterprise.MemoryHubSealingPinStore
import kotlinx.coroutines.test.runTest
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HubGroupE2eeTest {

    private var now = 1_700_000_000_000L
    private val clock = object : FlashTimeSource {
        override fun nowMs(): Long = now
    }

    private class JdkCrypto : FlashCrypto {
        private val pair: KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        override val identityPublicKeyEncoded: ByteArray get() = pair.public.encoded

        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(pair.private)
                update(data)
            }.sign()

        override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(data)
            }.verify(signature)
        } catch (_: Throwable) {
            false
        }

        override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = throw UnsupportedOperationException()
        override fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray =
            throw UnsupportedOperationException()
    }

    private class MemberNode(
        val id: String,
        var publishKey: Boolean = true,
        val pairedPins: MutableMap<String, String> = HashMap(),
    ) {
        val crypto = JdkCrypto()
        var sealingKey: HubSealingKeyPair? = if (publishKey) HubSealingKeyPair.generate() else null
        val keyStore = MemoryHubGroupEpochKeyStore()
        val pinStore = MemoryHubSealingPinStore()
        val pins = HubSealingPins(pinStore)
        val relay = FakeHubRelay()
        var sentCursor: Int = 0
        val received = mutableListOf<Pair<GroupWireFrame, String>>()

        fun freshSent(): List<FakeHubRelay.Sent> = relay.sent.drop(sentCursor)
        fun resetSent() {
            sentCursor = relay.sent.size
        }

        fun directoryEntry(): EnterpriseDirectoryMember {
            val seal = if (publishKey) sealingKey else null
            return EnterpriseDirectoryMember(
                deviceId = id,
                friendlyName = id.replaceFirstChar { it.uppercase() },
                department = "eng",
                subjectPublicKey = Base64.encode(crypto.identityPublicKeyEncoded),
                sealingPublicKey = seal?.let { Base64.encode(it.publicKeyEncoded) },
                sealingSignature = seal?.bindingSignature(crypto, id),
            )
        }

        fun rotateSealingKey() {
            sealingKey = HubSealingKeyPair.generate()
        }
    }

    private class RecordingGroupSink(val out: MutableList<Pair<GroupWireFrame, String>>) : HubInboundSink {
        override suspend fun direct(frame: MessageWireFrame, from: String) = Unit
        override suspend fun group(frame: GroupWireFrame, from: String) {
            out += frame to from
        }
    }

    private inner class Harness(
        val nodes: Map<String, MemberNode>,
        val linkedGroups: MutableSet<String> = mutableSetOf("g-eng"),
        val membership: MutableMap<String, Pair<Set<String>, Long>> = mutableMapOf(
            "g-eng" to (nodes.keys.toSet() to 1L),
        ),
        val contentViaHub: () -> Boolean = { true },
    ) {
        fun bridgeFor(node: MemberNode): Pair<HubGroupE2eeCoordinator, HubChatBridge> {
            val coordinator = HubGroupE2eeCoordinator(node.id, node.crypto, node.keyStore, clock)
            val sealing = DirectoryHubSealing(
                crypto = node.crypto,
                localDeviceId = node.id,
                ownKey = { node.sealingKey },
                pins = node.pins,
                pairedIdentityFingerprint = { id -> node.pairedPins[id] },
                lookup = { id -> nodes[id]?.directoryEntry() },
            )
            val bridge = HubChatBridge(
                localDeviceId = node.id,
                port = node.relay,
                timeSource = clock,
                contentViaHub = contentViaHub,
                sealing = sealing,
                standing = { id -> if (id in nodes) HubPeerStanding.MEMBER else HubPeerStanding.UNKNOWN },
                isPaired = { false },
                groupLinked = { it in linkedGroups },
                groupE2ee = coordinator,
                groupMembershipProvider = { gid -> membership[gid] },
                groupMemberAllowed = { gid, id -> membership[gid]?.first?.contains(id) == true },
            )
            return coordinator to bridge
        }

        suspend fun routeOutbox(sender: MemberNode, receiver: MemberNode, receiverBridge: HubChatBridge): List<HubInboundOutcome> {
            val sink = RecordingGroupSink(receiver.received)
            val deliverables = sender.freshSent().filter { it.to == receiver.id }
            val outcomes = ArrayList<HubInboundOutcome>(deliverables.size)
            for (d in deliverables) {
                outcomes += receiverBridge.handleInbound(
                    HubInbound(from = sender.id, id = d.id, type = d.type, sentAt = d.sentAt, payload = d.payload),
                    sink,
                )
            }
            return outcomes
        }
    }

    private fun msg(groupId: String, msgId: String, from: String, text: String): GroupWireFrame.Message =
        GroupWireFrame.Message(groupId = groupId, messageId = msgId, from = from, senderName = from, sentAt = now, text = text)

    @Test
    fun encryptedGroupRelaysOpaqueCiphertextAndDeliversToMembers() = runTest {
        val alice = MemberNode("alice")
        val bob = MemberNode("bob")
        val carol = MemberNode("carol")
        val h = Harness(mapOf("alice" to alice, "bob" to bob, "carol" to carol))
        val (_, aliceBridge) = h.bridgeFor(alice)
        val (_, bobBridge) = h.bridgeFor(bob)
        val (_, carolBridge) = h.bridgeFor(carol)

        val secret = "Q4 layoff contingency budget $9.4M"
        val frame = msg("g-eng", "m-1", "alice", secret)
        assertTrue(aliceBridge.sendGroup("bob", "m-1", now, frame))
        assertTrue(aliceBridge.sendGroup("carol", "m-1", now, frame))

        // Hub inspects every wire frame and never observes plaintext text or raw key bytes
        assertTrue(alice.relay.sent.isNotEmpty())
        assertTrue(alice.relay.sent.all { it.type == HubSealing.ENVELOPE_TYPE || it.type == GroupRelaySealing.ENVELOPE_TYPE })
        assertTrue(alice.relay.sent.none { it.type == HubChatBridge.GROUP_FRAME })
        assertTrue(alice.relay.sent.none { secret in it.payload || "layoff" in it.payload })

        val bobOutcomes = h.routeOutbox(alice, bob, bobBridge)
        val carolOutcomes = h.routeOutbox(alice, carol, carolBridge)
        assertTrue(bobOutcomes.all { it == HubInboundOutcome.STORED })
        assertTrue(carolOutcomes.all { it == HubInboundOutcome.STORED })
        assertEquals(secret, (bob.received.single().first as GroupWireFrame.Message).text)
        assertEquals(secret, (carol.received.single().first as GroupWireFrame.Message).text)
        assertEquals(GroupE2eeCompatStatus.ENCRYPTED, aliceBridge.groupE2eeStates.value["g-eng"]?.status)
    }

    @Test
    fun removedMemberCannotDecryptPostRemovalEpochsAndJoinCannotReadHistory() = runTest {
        val alice = MemberNode("alice")
        val bob = MemberNode("bob")
        val carol = MemberNode("carol")
        val dave = MemberNode("dave")
        val h = Harness(
            mapOf("alice" to alice, "bob" to bob, "carol" to carol, "dave" to dave),
            membership = mutableMapOf("g-eng" to (setOf("alice", "bob", "carol") to 1L)),
        )
        val (aliceE2ee, aliceBridge) = h.bridgeFor(alice)
        val (bobE2ee, bobBridge) = h.bridgeFor(bob)
        val (carolE2ee, carolBridge) = h.bridgeFor(carol)
        val (_, daveBridge) = h.bridgeFor(dave)

        // Epoch 1: Alice, Bob, Carol
        assertTrue(aliceBridge.sendGroup("bob", "m-ep1", now, msg("g-eng", "m-ep1", "alice", "epoch1-history")))
        assertTrue(aliceBridge.sendGroup("carol", "m-ep1", now, msg("g-eng", "m-ep1", "alice", "epoch1-history")))
        h.routeOutbox(alice, bob, bobBridge)
        h.routeOutbox(alice, carol, carolBridge)
        assertEquals(1L, aliceE2ee.currentEpochFor("g-eng"))
        val ep1Envelope = alice.relay.sent.last { it.type == GroupRelaySealing.ENVELOPE_TYPE }

        // Remove Carol at rosterVersion = 2 -> rotate to epoch 2 ({alice, bob})
        alice.resetSent()
        h.membership["g-eng"] = setOf("alice", "bob") to 2L
        aliceE2ee.onMembershipChanged("g-eng", setOf("alice", "bob"), 2L)
        bobE2ee.onMembershipChanged("g-eng", setOf("alice", "bob"), 2L)
        carolE2ee.onMembershipChanged("g-eng", setOf("alice", "bob"), 2L)

        val secret2 = "post-removal-secret-epoch2"
        assertTrue(aliceBridge.sendGroup("bob", "m-ep2", now + 1_000L, msg("g-eng", "m-ep2", "alice", secret2)))
        assertEquals(2L, aliceE2ee.currentEpochFor("g-eng"))
        assertEquals(listOf(HubInboundOutcome.STORED, HubInboundOutcome.STORED), h.routeOutbox(alice, bob, bobBridge))
        assertEquals(secret2, (bob.received.last().first as GroupWireFrame.Message).text)

        // Even if a rogue hub forwards epoch-2 frames to removed Carol, Carol lacks epoch 2 key and fails closed
        val ep2Sealed = alice.freshSent().last { it.type == GroupRelaySealing.ENVELOPE_TYPE }
        val outcomeForRemoved = carolBridge.handleInbound(
            HubInbound("alice", ep2Sealed.id, ep2Sealed.type, ep2Sealed.sentAt, ep2Sealed.payload),
            RecordingGroupSink(carol.received),
        )
        assertEquals(HubInboundOutcome.REJECTED, outcomeForRemoved)
        assertFalse(carol.received.any { (it.first as GroupWireFrame.Message).text == secret2 })

        // Now Dave joins at rosterVersion = 3 ({alice, bob, dave}) -> epoch 3; Carol rejoins at rosterVersion = 4
        alice.resetSent()
        h.membership["g-eng"] = setOf("alice", "bob", "dave") to 3L
        aliceE2ee.onMembershipChanged("g-eng", setOf("alice", "bob", "dave"), 3L)
        assertTrue(aliceBridge.sendGroup("dave", "m-ep3", now + 2_000L, msg("g-eng", "m-ep3", "alice", "welcome-dave")))
        assertEquals(3L, aliceE2ee.currentEpochFor("g-eng"))
        h.routeOutbox(alice, dave, daveBridge)
        assertEquals("welcome-dave", (dave.received.single().first as GroupWireFrame.Message).text)

        // Dave CANNOT decrypt epoch-1 history sealed before he joined
        val oldForDave = daveBridge.handleInbound(
            HubInbound("alice", ep1Envelope.id, ep1Envelope.type, ep1Envelope.sentAt, ep1Envelope.payload),
            RecordingGroupSink(dave.received),
        )
        assertEquals(HubInboundOutcome.RETRY_LATER, oldForDave) // never decrypts, key never existed for Dave
        assertEquals(1, dave.received.size)

        // Carol rejoins at rosterVersion = 4 ({alice, bob, carol, dave}) -> epoch 4; Carol STILL cannot decrypt epoch 2/3 gap
        alice.resetSent()
        h.membership["g-eng"] = setOf("alice", "bob", "carol", "dave") to 4L
        aliceE2ee.onMembershipChanged("g-eng", setOf("alice", "bob", "carol", "dave"), 4L)
        assertTrue(aliceBridge.sendGroup("carol", "m-ep4", now + 3_000L, msg("g-eng", "m-ep4", "alice", "welcome-back-carol")))
        assertEquals(4L, aliceE2ee.currentEpochFor("g-eng"))
        h.routeOutbox(alice, carol, carolBridge)
        assertEquals("welcome-back-carol", (carol.received.last().first as GroupWireFrame.Message).text)
        val gapForRejoinedCarol = carolBridge.handleInbound(
            HubInbound("alice", ep2Sealed.id, ep2Sealed.type, ep2Sealed.sentAt, ep2Sealed.payload),
            RecordingGroupSink(carol.received),
        )
        assertEquals(HubInboundOutcome.RETRY_LATER, gapForRejoinedCarol) // still lacks gap key epoch 2
        assertFalse(carol.received.any { (it.first as GroupWireFrame.Message).text == secret2 })
    }

    @Test
    fun legacyMemberOrChangedKeyOrIdentityMismatchFailsClosedWithoutPlaintextFallback() = runTest {
        val alice = MemberNode("alice")
        val legacyBob = MemberNode("bob", publishKey = false)
        val h = Harness(
            mapOf("alice" to alice, "bob" to legacyBob),
            contentViaHub = { true }, // Even with readable opt-in ON, E2EE must fail closed!
        )
        val (_, aliceBridge) = h.bridgeFor(alice)

        // Legacy peer blocks E2EE send cleanly and exposes honest state
        assertFalse(aliceBridge.sendGroup("bob", "m-legacy", now, msg("g-eng", "m-legacy", "alice", "must-not-leak")))
        assertTrue(alice.relay.sent.isEmpty(), "must not silently fall back to plaintext GROUP_FRAME")
        val legacyState = aliceBridge.evaluateGroupE2ee("g-eng")!!
        assertEquals(GroupE2eeCompatStatus.LEGACY_MEMBERS_PRESENT, legacyState.status)
        assertEquals(listOf("bob"), legacyState.legacyMembers)
        assertTrue(legacyState.userDisclosure(true).contains("fail-closed"))

        // Upgrade Bob -> now succeeds
        legacyBob.publishKey = true
        legacyBob.rotateSealingKey()
        assertTrue(aliceBridge.sendGroup("bob", "m-ok", now + 1000L, msg("g-eng", "m-ok", "alice", "now-encrypted")))
        assertEquals(GroupE2eeCompatStatus.ENCRYPTED, aliceBridge.evaluateGroupE2ee("g-eng")?.status)

        // Rotate Bob's sealing key unexpectedly -> KEY_CHANGED blocks send
        legacyBob.rotateSealingKey()
        alice.resetSent()
        assertFalse(aliceBridge.sendGroup("bob", "m-changed", now + 2000L, msg("g-eng", "m-changed", "alice", "held")))
        assertTrue(alice.freshSent().isEmpty())
        assertEquals(GroupE2eeCompatStatus.BLOCKED_KEY_CHANGED, aliceBridge.evaluateGroupE2ee("g-eng")?.status)

        // Confirm key change works, then simulate directory IDENTITY_MISMATCH via pinned fingerprint
        assertTrue(aliceBridge.confirmKeyChange("bob"))
        alice.pairedPins["bob"] = "DEADBEEF00000000000000000000000000000000000000000000000000000000"
        assertFalse(aliceBridge.sendGroup("bob", "m-mismatch", now + 3000L, msg("g-eng", "m-mismatch", "alice", "held")))
        assertEquals(GroupE2eeCompatStatus.BLOCKED_IDENTITY_MISMATCH, aliceBridge.evaluateGroupE2ee("g-eng")?.status)

        // Plaintext inbound downgrade attempt must also be rejected when E2EE is enabled
        val (_, bobBridge) = h.bridgeFor(legacyBob)
        val plainDowngrade = GroupFrameCodec.encode(
            msg("g-eng", "m-downgrade", "bob", "cleartext injection"),
        )
        assertEquals(
            HubInboundOutcome.REJECTED,
            aliceBridge.handleInbound(
                HubInbound("bob", "m-downgrade", HubChatBridge.GROUP_FRAME, now, plainDowngrade),
                RecordingGroupSink(alice.received),
            ),
        )
        assertTrue(alice.received.isEmpty())
        assertNotEquals(
            HubInboundOutcome.STORED,
            bobBridge.handleInbound(HubInbound("alice", "m-downgrade", HubChatBridge.LEGACY_GROUP, now, plainDowngrade), RecordingGroupSink(legacyBob.received)),
        )
    }

    @Test
    fun simultaneousRotationOutOfOrderDeliveryAndOfflineRestartAllConverge() = runTest {
        val alice = MemberNode("alice")
        val bob = MemberNode("bob")
        val h = Harness(mapOf("alice" to alice, "bob" to bob))
        val (_, aliceBridge) = h.bridgeFor(alice)
        val (_, bobBridge) = h.bridgeFor(bob)

        // Simultaneous creation by Alice & Bob concurrently at rosterVersion 1
        assertTrue(aliceBridge.sendGroup("bob", "m-a1", now, msg("g-eng", "m-a1", "alice", "from-alice-concurrent")))
        assertTrue(bobBridge.sendGroup("alice", "m-b1", now + 10L, msg("g-eng", "m-b1", "bob", "from-bob-concurrent")))

        // Deliver Alice's SEALED message BEFORE Alice's KEY_DIST (out-of-order!) -> RETRY_LATER then STORED after KEY_DIST
        val aliceKeyDist = alice.relay.sent.first { it.type == HubSealing.ENVELOPE_TYPE }
        val aliceMsg = alice.relay.sent.first { it.type == GroupRelaySealing.ENVELOPE_TYPE }
        assertEquals(
            HubInboundOutcome.RETRY_LATER,
            bobBridge.handleInbound(HubInbound("alice", aliceMsg.id, aliceMsg.type, aliceMsg.sentAt, aliceMsg.payload), RecordingGroupSink(bob.received)),
        )
        assertEquals(
            HubInboundOutcome.STORED,
            bobBridge.handleInbound(HubInbound("alice", aliceKeyDist.id, aliceKeyDist.type, aliceKeyDist.sentAt, aliceKeyDist.payload), RecordingGroupSink(bob.received)),
        )
        assertEquals(
            HubInboundOutcome.STORED,
            bobBridge.handleInbound(HubInbound("alice", aliceMsg.id, aliceMsg.type, aliceMsg.sentAt, aliceMsg.payload), RecordingGroupSink(bob.received)),
        )
        // Also deliver Bob's concurrent epoch-1 frames to Alice
        assertTrue(h.routeOutbox(bob, alice, aliceBridge).all { it == HubInboundOutcome.STORED })

        assertEquals("from-alice-concurrent", (bob.received.single().first as GroupWireFrame.Message).text)
        assertEquals("from-bob-concurrent", (alice.received.single().first as GroupWireFrame.Message).text)

        // Simulate offline + restart persistence using the same key stores
        bob.relay.usable = false
        assertFalse(bobBridge.sendGroup("alice", "m-offline", now + 20L, msg("g-eng", "m-offline", "bob", "queued-while-offline")))

        val (aliceE2eeRestarted, aliceBridgeRestarted) = h.bridgeFor(alice)
        aliceE2eeRestarted.load()
        val (bobE2eeRestarted, bobBridgeRestarted) = h.bridgeFor(bob)
        bobE2eeRestarted.load()
        bob.relay.usable = true
        bob.resetSent()
        assertTrue(bobBridgeRestarted.sendGroup("alice", "m-after-restart", now + 30L, msg("g-eng", "m-after-restart", "bob", "after-restart-msg")))
        assertTrue(h.routeOutbox(bob, alice, aliceBridgeRestarted).all { it == HubInboundOutcome.STORED })
        assertEquals("after-restart-msg", (alice.received.last().first as GroupWireFrame.Message).text)
        assertEquals(aliceE2eeRestarted.currentEpochFor("g-eng"), bobE2eeRestarted.currentEpochFor("g-eng"))
    }
}
