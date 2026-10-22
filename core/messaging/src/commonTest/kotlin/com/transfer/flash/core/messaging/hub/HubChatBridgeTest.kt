@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.EnterpriseDepartment
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.enterprise.EnterpriseJoinRequest
import com.transfer.flash.core.enterprise.EnterpriseJoinResult
import com.transfer.flash.core.enterprise.HubDirectoryApi
import com.transfer.flash.core.enterprise.HubPeerDirectory
import com.transfer.flash.core.enterprise.HubPeerStanding
import com.transfer.flash.core.security.enterprise.HubSealing
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-122: the inbound and outbound rules of the hub bridge, with a scripted relay and no database. */
class HubChatBridgeTest {

    private val log = mutableListOf<String>()
    private val relay = FakeHubRelay(log)

    private class RecordingSink(private val log: MutableList<String>, var failWith: Throwable? = null) : HubInboundSink {
        val direct = mutableListOf<Pair<MessageWireFrame, String>>()
        val group = mutableListOf<Pair<GroupWireFrame, String>>()

        override suspend fun direct(frame: MessageWireFrame, from: String) {
            failWith?.let { throw it }
            direct += frame to from
            log += "stored"
        }

        override suspend fun group(frame: GroupWireFrame, from: String) {
            failWith?.let { throw it }
            group += frame to from
            log += "stored"
        }
    }

    private fun bridge(
        paired: Set<String> = emptySet(),
        standing: Map<String, HubPeerStanding> = emptyMap(),
        content: Boolean = true,
        linked: Set<String> = emptySet(),
        sealing: HubSealingPort? = null,
    ) = HubChatBridge(
        localDeviceId = "me",
        port = relay,
        contentViaHub = { content },
        sealing = sealing,
        standing = { standing[it] ?: HubPeerStanding.UNKNOWN },
        isPaired = { it in paired },
        groupLinked = { it in linked },
    )

    private fun text(id: String, from: String = "alice", body: String = "hello") = HubInbound(
        from = from,
        id = id,
        type = HubChatBridge.CHAT_FRAME,
        sentAt = 1_000L,
        payload = ChatTextFrameCodec.encode(
            MessageWireFrame.TextMessage(id, "me", from, "Alice", body, 1_000L),
        )!!,
    )

    // ----- RECV_ACK only after the durable store -----

    @Test
    fun theHubIsToldAMessageIsStoredOnlyAfterTheStoreReturned() = runTest {
        val sink = RecordingSink(log)
        val outcome = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER)).handleInbound(text("m-1"), sink)
        assertEquals(HubInboundOutcome.STORED, outcome)
        assertEquals(listOf("stored", "recvAck:m-1"), log, "the store must come first")
    }

    @Test
    fun theAcknowledgementNamesTheSenderOfTheMessageItConfirms() = runTest {
        val sink = RecordingSink(log)
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER, "bob" to HubPeerStanding.MEMBER))
        b.handleInbound(text("same-id", from = "alice"), sink)
        b.handleInbound(text("same-id", from = "bob"), sink)
        assertEquals(
            listOf("same-id" to "alice", "same-id" to "bob"),
            relay.recvAckSenders.filter { it.second != null },
            "REV-08: with two senders reusing one id the hub must be told WHICH copy is stored",
        )
    }

    @Test
    fun twoSendersReusingOneMessageIdAreBothStored() = runTest {
        // ERROR-396: the duplicate set was keyed by id alone, so bob's message was dropped as a duplicate of alice's.
        val sink = RecordingSink(log)
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER, "bob" to HubPeerStanding.MEMBER))
        assertEquals(HubInboundOutcome.STORED, b.handleInbound(text("same-id", from = "alice"), sink))
        assertEquals(HubInboundOutcome.STORED, b.handleInbound(text("same-id", from = "bob"), sink))
        assertEquals(2, sink.direct.size, "both senders' messages are stored")
        // ...while a real redelivery from the same sender is still a duplicate.
        assertEquals(HubInboundOutcome.DUPLICATE, b.handleInbound(text("same-id", from = "bob"), sink))
        assertEquals(2, sink.direct.size)
    }

    @Test
    fun aFailedStoreIsNotAcknowledgedSoTheHubRedelivers() = runTest {
        val sink = RecordingSink(log, failWith = IllegalStateException("disk full"))
        val outcome = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER)).handleInbound(text("m-2"), sink)
        assertEquals(HubInboundOutcome.RETRY_LATER, outcome)
        assertTrue(relay.recvAcks.isEmpty(), "no RECV_ACK for a message that was not stored")
    }

    // ----- dedupe -----

    @Test
    fun aRedeliveredMessageIsStoredOnceAndAcknowledgedAgain() = runTest {
        val sink = RecordingSink(log)
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER))
        assertEquals(HubInboundOutcome.STORED, b.handleInbound(text("m-3"), sink))
        assertEquals(HubInboundOutcome.DUPLICATE, b.handleInbound(text("m-3"), sink))
        assertEquals(1, sink.direct.size)
        assertEquals(listOf("m-3", "m-3"), relay.recvAcks)
    }

    // ----- sender trust (CLI-14) -----

    @Test
    fun aSenderThatIsNeitherPairedNorInTheDirectoryIsDroppedAndAcknowledged() = runTest {
        val sink = RecordingSink(log)
        val outcome = bridge().handleInbound(text("m-4", from = "mallory"), sink)
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(sink.direct.isEmpty())
        assertEquals(listOf("m-4"), relay.recvAcks, "acknowledged so the hub does not redeliver it for ever")
    }

    @Test
    fun aPairedSenderNeedsNoDirectoryEntry() = runTest {
        val sink = RecordingSink(log)
        assertEquals(HubInboundOutcome.STORED, bridge(paired = setOf("alice")).handleInbound(text("m-5"), sink))
    }

    @Test
    fun anUnreadableDirectoryMeansTryLaterNotDropped() = runTest {
        val sink = RecordingSink(log)
        val outcome = bridge(standing = mapOf("alice" to HubPeerStanding.UNAVAILABLE)).handleInbound(text("m-6"), sink)
        assertEquals(HubInboundOutcome.RETRY_LATER, outcome)
        assertTrue(relay.recvAcks.isEmpty())
    }

    @Test
    fun crossDepartmentSenderIsRejectedWhenDirectoryOnlyCoversOwnDepartment() = runTest {
        val fakeDirectoryApi = object : HubDirectoryApi {
            override suspend fun fetchVisibleGroups(hubUrl: String, deviceId: String, department: String?): FlashResult<List<EnterpriseDirectoryGroup>> =
                FlashResult.Success(emptyList())

            override suspend fun fetchDepartmentMembers(hubUrl: String, departmentId: String): FlashResult<List<EnterpriseDirectoryMember>> =
                // In caller's department ("engineering"), only Alice is present. Carol in "marketing" is omitted.
                FlashResult.Success(listOf(
                    EnterpriseDirectoryMember(
                        deviceId = "alice",
                        friendlyName = "Alice",
                        department = "engineering",
                        role = "member",
                        online = true,
                    )
                ))

            override suspend fun fetchDepartments(hubUrl: String): FlashResult<List<EnterpriseDepartment>> =
                FlashResult.Success(emptyList())

            override suspend fun requestJoin(hubUrl: String, groupId: String, message: String?): FlashResult<EnterpriseJoinResult> =
                FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("unused"))

            override suspend fun fetchJoinRequests(hubUrl: String, groupId: String?): FlashResult<List<EnterpriseJoinRequest>> =
                FlashResult.Success(emptyList())

            override suspend fun decideJoinRequest(hubUrl: String, requestId: String, approve: Boolean, reason: String?): FlashResult<EnterpriseJoinResult> =
                FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("unused"))
        }

        val hubPeers = HubPeerDirectory(
            directory = fakeDirectoryApi,
            hubUrl = { "https://hub.example.com" },
        )

        val sink = RecordingSink(log)
        val b = HubChatBridge(
            localDeviceId = "me",
            port = relay,
            contentViaHub = { true },
            standing = { id -> hubPeers.standing(id) },
            isPaired = { false },
        )

        // 1. Inbound message from own-department colleague ("alice"):
        // HubPeerDirectory lists alice -> standing is MEMBER -> message accepted and stored
        val aliceOutcome = b.handleInbound(text("m-alice", from = "alice"), sink)
        assertEquals(HubInboundOutcome.STORED, aliceOutcome)
        assertEquals(1, sink.direct.size)
        assertEquals("alice", sink.direct.first().second)

        // 2. Inbound message from cross-department colleague ("carol", marketing):
        // HubPeerDirectory only fetched own department -> lookup("carol") is null -> standing is UNKNOWN
        // Unpaired + UNKNOWN -> message is rejected and dropped
        val carolOutcome = b.handleInbound(text("m-carol", from = "carol"), sink)
        assertEquals(HubInboundOutcome.REJECTED, carolOutcome)
        assertEquals(1, sink.direct.size, "Carol's message must NOT be stored")
        assertTrue(relay.recvAcks.contains("m-carol"), "Dropped message is acknowledged to prevent endless hub redelivery")
    }

    @Test
    fun aFrameThatNamesAnotherAuthorThanTheAuthenticatedSenderIsRejected() = runTest {
        val forged = text("m-7", from = "alice").copy(
            payload = ChatTextFrameCodec.encode(MessageWireFrame.TextMessage("m-7", "me", "bob", "Bob", "hi", 1_000L))!!,
        )
        val sink = RecordingSink(log)
        assertEquals(
            HubInboundOutcome.REJECTED,
            bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER)).handleInbound(forged, sink),
        )
        assertTrue(sink.direct.isEmpty())
    }

    @Test
    fun aReceiptAndADeleteMustAlsoBelongToTheirSender() = runTest {
        val sink = RecordingSink(log)
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER))
        val receipt = HubInbound(
            "alice", "r-1", HubChatBridge.CHAT_FRAME, 1L,
            ChatTextFrameCodec.encode(MessageWireFrame.DeliveryReceipt("m-1", "me", "alice", 5L))!!,
        )
        assertEquals(HubInboundOutcome.STORED, b.handleInbound(receipt, sink))
        val forgedDelete = HubInbound(
            "alice", "d-1", HubChatBridge.CHAT_FRAME, 1L,
            DirectMessageActionCodec.encode(MessageWireFrame.DeleteForEveryone("m-1", "me", "bob")),
        )
        assertEquals(HubInboundOutcome.REJECTED, b.handleInbound(forgedDelete, sink))
    }

    // ----- groups (CLI-15) -----

    @Test
    fun aPlainTextGroupMessageIsNoLongerTurnedIntoAGroupFrameWithTheEnvelopeIdAsGroup() = runTest {
        // The old fallback made GroupWireFrame.Message(groupId = envelope id, text = whatever arrived).
        val sink = RecordingSink(log)
        val junk = HubInbound("alice", "g-1", HubChatBridge.LEGACY_GROUP, 1L, "just some words")
        val outcome = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), linked = setOf("g-1")).handleInbound(junk, sink)
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(sink.group.isEmpty())
        assertEquals(listOf("g-1"), relay.recvAcks)
    }

    private fun groupMessage(from: String = "alice", groupId: String = "grp") = GroupWireFrame.Message(
        groupId = groupId, messageId = "gm-1", from = from, senderName = "Alice", sentAt = 1L, text = "hi team",
    )

    @Test
    fun aGroupMessageIsAcceptedOnlyForALinkedGroupAndFromItsAuthor() = runTest {
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), linked = setOf("grp"))
        val sink = RecordingSink(log)
        fun inbound(frame: GroupWireFrame, from: String = "alice", id: String = "gm-1") =
            HubInbound(from, id, HubChatBridge.GROUP_FRAME, 1L, GroupFrameCodec.encode(frame))

        assertEquals(HubInboundOutcome.STORED, b.handleInbound(inbound(groupMessage()), sink))
        assertEquals(HubInboundOutcome.REJECTED, b.handleInbound(inbound(groupMessage(groupId = "other"), id = "gm-2"), sink))
        assertEquals(HubInboundOutcome.REJECTED, b.handleInbound(inbound(groupMessage(from = "bob"), id = "gm-3"), sink))
        assertEquals(1, sink.group.size)
    }

    @Test
    fun membershipAndSecretFramesNeverComeInThroughTheHub() = runTest {
        val bundleLike = GroupWireFrame.Leave(groupId = "grp", from = "alice", memberId = "alice", operationId = "op", membershipVersion = 1L)
        val inbound = HubInbound("alice", "gl-1", HubChatBridge.GROUP_FRAME, 1L, GroupFrameCodec.encode(bundleLike))
        val sink = RecordingSink(log)
        val outcome = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), linked = setOf("grp")).handleInbound(inbound, sink)
        assertEquals(HubInboundOutcome.REJECTED, outcome)
        assertTrue(sink.group.isEmpty())
    }

    // ----- content policy (CLI-05) -----

    @Test
    fun readableContentIsRefusedWhileTheUserHasNotSwitchedItOn() = runTest {
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), content = false)
        val sink = RecordingSink(log)
        assertEquals(HubInboundOutcome.REJECTED, b.handleInbound(text("m-8"), sink))
        assertTrue(sink.direct.isEmpty())
        assertEquals(1, b.droppedPlain.value)
    }

    @Test
    fun withTheSettingOffNothingReadableIsSentToTheHub() = runTest {
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), content = false)
        assertFalse(b.canCarry("alice"))
        val frame = MessageWireFrame.TextMessage("m-9", "alice", "me", "Me", "secret", 1L)
        assertFalse(b.sendDirect("alice", "m-9", 1L, frame))
        assertTrue(relay.sent.isEmpty(), "the hub must not see readable content unless the user allowed it")
    }

    @Test
    fun withTheSettingOnAMemberGetsTheFrameAndANonMemberDoesNot() = runTest {
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), content = true)
        val frame = MessageWireFrame.TextMessage("m-10", "alice", "me", "Me", "hello", 1L)
        assertTrue(b.canCarry("alice"))
        assertTrue(b.sendDirect("alice", "m-10", 1L, frame))
        assertEquals(HubChatBridge.CHAT_FRAME, relay.sent.single().type)
        assertFalse(b.canCarry("stranger"), "a hub send to someone outside the directory could only fail")
    }

    @Test
    fun anUnusableSealingKeyKeepsTheMessageQueuedEvenWhenReadableContentIsAllowed() = runTest {
        // F65 of the 2026-10-22 review: AVAILABLE with no envelope used to fall through to the readable send, so a
        // message the user chose to seal went to the hub as plaintext.
        val sealing = ScriptedHubSealing(HubSealingStatus.AVAILABLE, sealUnavailable = true)
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER), content = true, sealing = sealing)
        val frame = MessageWireFrame.TextMessage("m-11", "alice", "me", "Me", "secret", 1L)

        assertFalse(b.sendDirect("alice", "m-11", 1L, frame), "nothing is sent while the key cannot seal")
        assertTrue(relay.sent.isEmpty(), "and never as readable text, despite the opt-in being on")

        // When the directory serves a usable key again the same message goes out sealed, never readable.
        sealing.sealUnavailable = false
        assertTrue(b.sendDirect("alice", "m-11", 1L, frame))
        assertEquals(HubSealing.ENVELOPE_TYPE, relay.sent.single().type)
        assertTrue(relay.sent.single().payload.contains("\n"), "the body travelled inside the envelope")
    }

    @Test
    fun anOfflineRelayCarriesNothing() = runTest {
        relay.usable = false
        assertFalse(bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER)).canCarry("alice"))
    }

    // ----- the hub's verdicts -----

    @Test
    fun aHeldMessageIsAcceptedAndNotAFailure() = runTest {
        val b = bridge()
        val note = b.onAck(HubAckEvent("m-1", "alice", HubAckKind.HELD), "Alice")
        assertFalse(note.failed)
        assertTrue(note.text.contains("Alice"))
        assertTrue(b.isHubAccepted("m-1"))
        assertEquals(note, b.notes.value["m-1"])
    }

    @Test
    fun everyRefusalIsAFailureWithAFriendlySentence() = runTest {
        val b = bridge()
        for (kind in listOf(HubAckKind.UNDELIVERABLE, HubAckKind.QUOTA, HubAckKind.REFUSED, HubAckKind.EXPIRED, HubAckKind.DROPPED)) {
            assertTrue(kind.isFailure)
            val note = b.onAck(HubAckEvent("m-$kind", "alice", kind), "Alice")
            assertTrue(note.failed, "$kind")
            assertFalse(note.text.contains("FLASH_"), "no protocol words: ${note.text}")
            assertFalse(b.isHubAccepted("m-$kind"))
        }
        assertFalse(HubAckKind.DELIVERED.isFailure)
        assertFalse(HubAckKind.HELD.isFailure)
    }

    @Test
    fun typingFromANonMemberIsNotAllowed() = runTest {
        val b = bridge(standing = mapOf("alice" to HubPeerStanding.MEMBER))
        assertTrue(b.isSenderAllowed("alice"))
        assertFalse(b.isSenderAllowed("mallory"))
        assertTrue(b.sendTyping("conv", "alice"))
        assertEquals(listOf("conv" to "alice"), relay.typingSent)
    }
}
