package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlashGroupCallSessionTest {

    private val callId = "group-call-001"
    private val groupId = "group-chat-001"
    private val myDeviceId = "me-001"
    private val hostDeviceId = "host-001"
    private val peer2DeviceId = "peer-002"

    private val sentFrames = mutableListOf<Pair<CallWireFrame, String>>()

    private fun TestScope.newSession(
        direction: FlashCallDirection = FlashCallDirection.INCOMING,
    ): FlashGroupCallSession = FlashGroupCallSession(
        callId = callId,
        groupId = groupId,
        groupName = "Test Group",
        direction = direction,
        video = false,
        localDeviceId = myDeviceId,
        localName = "My Name",
        scope = this,
        sendFrame = { frame, peerId ->
            sentFrames += frame to peerId
            true
        },
    )

    @Test
    fun forwardedGroupJoin_doesNotCorruptHostConnectedState() = runTest {
        val session = newSession()
        session.startIncomingRinging(peerId = hostDeviceId, callerName = "Host")

        // 1. Host leg established and connected
        session.setLegStateForTesting(hostDeviceId, FlashCallParticipantState.CONNECTED)

        // Verify initial state
        var uiState = session.state.value
        val hostParticipant = uiState.participants.firstOrNull { it.peerId == hostDeviceId }
        assertNotNull(hostParticipant)
        assertEquals(FlashCallParticipantState.CONNECTED, hostParticipant!!.state)

        // 2. Now peer2 answers on host, and host forwards GroupJoin to us
        val forwardedJoin = CallWireFrame.GroupJoin(
            callId = callId,
            from = peer2DeviceId,
            groupId = groupId,
            participantName = "Peer 2",
        )
        session.onInboundFrame(frame = forwardedJoin, peerId = hostDeviceId)

        // 3. Verify:
        // - Host leg must RETAIN its CONNECTED state (not regressed to CONNECTING)
        // - Peer 2 leg must be added and in CONNECTING state
        uiState = session.state.value
        val hostAfter = uiState.participants.firstOrNull { it.peerId == hostDeviceId }
        assertEquals(FlashCallParticipantState.CONNECTED, hostAfter?.state)

        val peer2After = uiState.participants.firstOrNull { it.peerId == peer2DeviceId }
        assertNotNull(peer2After)
        assertEquals(FlashCallParticipantState.CONNECTING, peer2After?.state)
    }

    @Test
    fun forwardedGroupJoin_doesNotReBroadcastGroupJoin() = runTest {
        val session = newSession()
        session.startIncomingRinging(peerId = hostDeviceId, callerName = "Host")

        // Receive forwarded GroupJoin from host
        val forwardedJoin = CallWireFrame.GroupJoin(
            callId = callId,
            from = peer2DeviceId,
            groupId = groupId,
            participantName = "Peer 2",
        )
        sentFrames.clear()
        session.onInboundFrame(frame = forwardedJoin, peerId = hostDeviceId)

        // Should NOT emit any GroupJoin frame out (only GroupAccept initiates fanout)
        val emittedJoins = sentFrames.filter { it.first is CallWireFrame.GroupJoin }
        assertTrue("GroupJoin should not be re-fanned out in an echo storm", emittedJoins.isEmpty())
    }
}
