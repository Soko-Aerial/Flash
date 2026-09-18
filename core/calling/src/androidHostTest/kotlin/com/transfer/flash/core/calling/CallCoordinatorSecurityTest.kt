package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallCoordinatorSecurityTest {

    private val localDeviceId = "me-0001"
    private val peerB = "peer-b"
    private val attackerC = "attacker-c"
    private val callId = "call-100"

    private fun TestScope.newCoordinator(
        trustedPeers: Set<String> = setOf(peerB, attackerC),
    ): CallCoordinator = CallCoordinator(
        localDeviceId = localDeviceId,
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { _, _ -> true },
        isTrustedPeer = { it in trustedPeers },
    )

    @Test
    fun `onInboundText rejects 1_1 invite when claimed from does not match transport peerId`() = runTest {
        val coordinator = newCoordinator()

        val spoofedText = CallFrameCodec.encode(
            CallWireFrame.Invite(callId = callId, from = peerB, callerName = "Peer B", video = false),
        )

        val consumed = coordinator.onInboundText(peerId = attackerC, text = spoofedText)
        runCurrent()

        assertFalse("Spoofed invite where frame.from != peerId must be rejected", consumed)
        assertNull("No active call session should be created on spoofed invite", coordinator.activeCall.value)
    }

    @Test
    fun `onInboundText rejects 1_1 hangup from unauthorized peer on active call`() = runTest {
        val coordinator = newCoordinator()

        val validInviteText = CallFrameCodec.encode(
            CallWireFrame.Invite(callId = callId, from = peerB, callerName = "Peer B", video = false),
        )
        assertTrue(coordinator.onInboundText(peerId = peerB, text = validInviteText))
        runCurrent()
        assertEquals(FlashCallState.RINGING, coordinator.activeCall.value?.state)

        val spoofedHangupText = CallFrameCodec.encode(
            CallWireFrame.Hangup(callId = callId, from = peerB),
        )
        val spoofConsumed = coordinator.onInboundText(peerId = attackerC, text = spoofedHangupText)
        runCurrent()

        assertFalse("Spoofed hangup from attacker transport peer must be rejected", spoofConsumed)
        assertNotEquals("Call must remain active and not terminated by attacker", FlashCallState.ENDED, coordinator.activeCall.value?.state)
    }

    @Test
    fun `onInboundText accepts valid 1_1 signaling from matching transport peer`() = runTest {
        val coordinator = newCoordinator()

        val validInviteText = CallFrameCodec.encode(
            CallWireFrame.Invite(callId = callId, from = peerB, callerName = "Peer B", video = false),
        )
        assertTrue(coordinator.onInboundText(peerId = peerB, text = validInviteText))
        runCurrent()
        assertEquals(FlashCallState.RINGING, coordinator.activeCall.value?.state)

        val validDeclineText = CallFrameCodec.encode(
            CallWireFrame.Decline(callId = callId, from = peerB),
        )
        assertTrue(coordinator.onInboundText(peerId = peerB, text = validDeclineText))
        runCurrent()
        assertEquals(FlashCallState.ENDED, coordinator.activeCall.value?.state)
    }

    @Test
    fun `onInboundText rejects group call frames when claimed from does not match transport peerId`() = runTest {
        val coordinator = newCoordinator()

        val spoofedGroupInvite = CallFrameCodec.encode(
            CallWireFrame.GroupInvite(callId = callId, from = peerB, groupId = "group-1", callerName = "Peer B", video = false),
        )
        val consumedInvite = coordinator.onInboundText(peerId = attackerC, text = spoofedGroupInvite)
        assertFalse("Spoofed GroupInvite where frame.from != peerId must be rejected", consumedInvite)

        val spoofedGroupPresence = CallFrameCodec.encode(
            CallWireFrame.GroupPresence(callId = callId, from = peerB, groupId = "group-1", callerName = "Peer B", video = false, participantCount = 2),
        )
        val consumedPresence = coordinator.onInboundText(peerId = attackerC, text = spoofedGroupPresence)
        assertFalse("Spoofed GroupPresence where frame.from != peerId must be rejected", consumedPresence)
        assertTrue("Ongoing group calls map must remain empty on spoofed presence", coordinator.ongoingGroupCalls.value.isEmpty())

        val spoofedGroupQuery = CallFrameCodec.encode(
            CallWireFrame.GroupQuery(callId = callId, from = peerB, groupId = "group-1"),
        )
        val consumedQuery = coordinator.onInboundText(peerId = attackerC, text = spoofedGroupQuery)
        assertFalse("Spoofed GroupQuery where frame.from != peerId must be rejected", consumedQuery)
    }

    @Test
    fun `onInboundText rejects GroupPresence and GroupQuery from untrusted peers`() = runTest {
        val coordinator = newCoordinator(trustedPeers = setOf(peerB)) // attackerC is untrusted

        val untrustedPresence = CallFrameCodec.encode(
            CallWireFrame.GroupPresence(callId = callId, from = attackerC, groupId = "group-1", callerName = "Attacker C", video = false, participantCount = 2),
        )
        val consumedPresence = coordinator.onInboundText(peerId = attackerC, text = untrustedPresence)
        assertFalse("GroupPresence from untrusted peer must be rejected", consumedPresence)
        assertTrue("Ongoing group calls map must remain empty on untrusted presence", coordinator.ongoingGroupCalls.value.isEmpty())

        val untrustedQuery = CallFrameCodec.encode(
            CallWireFrame.GroupQuery(callId = callId, from = attackerC, groupId = "group-1"),
        )
        val consumedQuery = coordinator.onInboundText(peerId = attackerC, text = untrustedQuery)
        assertFalse("GroupQuery from untrusted peer must be rejected", consumedQuery)
    }

    @Test
    fun `onInboundText accepts valid GroupPresence and GroupQuery from trusted matching transport peer`() = runTest {
        val coordinator = newCoordinator(trustedPeers = setOf(peerB))

        val validPresence = CallFrameCodec.encode(
            CallWireFrame.GroupPresence(callId = callId, from = peerB, groupId = "group-1", callerName = "Peer B", video = false, participantCount = 2),
        )
        val consumedPresence = coordinator.onInboundText(peerId = peerB, text = validPresence)
        assertTrue("Valid GroupPresence from trusted matching peer must be accepted", consumedPresence)
        assertEquals("Ongoing group calls map should record the presence", 1, coordinator.ongoingGroupCalls.value.size)
        assertEquals("group-1", coordinator.ongoingGroupCalls.value["group-1"]?.groupId)
    }
}
