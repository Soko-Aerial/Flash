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

    private fun TestScope.newCoordinator(): CallCoordinator = CallCoordinator(
        localDeviceId = localDeviceId,
        localName = "Me",
        scope = backgroundScope,
        sendFrame = { _, _ -> true },
        isTrustedPeer = { true },
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
}
