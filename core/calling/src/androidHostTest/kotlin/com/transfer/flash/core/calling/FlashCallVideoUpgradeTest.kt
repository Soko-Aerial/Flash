package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCameraProblem
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-078 (ERROR-105 item E): a 1:1 voice call gets a camera mid-call. Media cannot start on a JVM unit test, so the
 * state rules and the wire are checked here and the negotiation itself is a device check (VUP-01...04).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashCallVideoUpgradeTest {

    private val callId = "call-0001"
    private val peerId = "peer-0001"
    private val sent = mutableListOf<CallWireFrame>()

    private fun TestScope.session(
        direction: FlashCallDirection = FlashCallDirection.OUTGOING,
        video: Boolean = false,
        peerCanUpgrade: Boolean = true,
    ): FlashCallSession = FlashCallSession(
        callId = callId, peerId = peerId, peerName = "Peer", direction = direction, video = video,
        localDeviceId = "me-0001", localName = "Me", scope = this,
        sendFrame = { frame -> sent += frame; true },
        peerCanUpgrade = { peerCanUpgrade },
    ).also { it.setStateForTesting(FlashCallState.ACTIVE) }

    private fun statuses() = sent.filterIsInstance<CallWireFrame.Status>()

    private suspend fun FlashCallSession.hearPeer(
        frame: CallWireFrame.Status = CallWireFrame.Status(callId, peerId, micOn = true),
    ) = onInboundFrame(frame)

    @Test
    fun `the camera button is on offer only to a live call whose peer advertised cv1`() = runTest {
        val capable = session()
        capable.hearPeer()
        assertTrue(capable.state.value.canUpgradeToVideo)

        val old = session(peerCanUpgrade = false)
        old.hearPeer()
        assertFalse("an older build is never offered a video section it cannot answer", old.state.value.canUpgradeToVideo)

        val connecting = session()
        connecting.setStateForTesting(FlashCallState.CONNECTING)
        connecting.hearPeer()
        assertFalse("not before the call is live", connecting.state.value.canUpgradeToVideo)
    }

    @Test
    fun `a video call joined without a camera can add one too`() = runTest {
        val s = session(video = true)
        s.setCameraOffForTesting(true)
        s.hearPeer()
        assertTrue(s.state.value.canUpgradeToVideo)
    }

    @Test
    fun `adding a camera when it is not on offer does nothing and sends nothing`() = runTest {
        val s = session(peerCanUpgrade = false)
        s.hearPeer()
        assertFalse(s.upgradeToVideo())
        testScheduler.advanceUntilIdle()
        assertTrue("nothing went on the wire: $sent", sent.none { it is CallWireFrame.Offer })
        assertTrue(statuses().none { it.videoUpgrade == true })
        assertFalse(s.state.value.video)
        assertNull(s.state.value.cameraProblem)
    }

    @Test
    fun `a camera that cannot be added leaves the call as it was, says so, and clears the message`() = runTest {
        val s = session()
        s.hearPeer()
        assertFalse("no media on a JVM unit test, so the camera cannot be added", s.upgradeToVideo())
        assertEquals(FlashCameraProblem.UPGRADE_FAILED, s.state.value.cameraProblem)
        assertFalse(s.state.value.video)
        assertEquals(FlashCallState.ACTIVE, s.state.value.state)
        assertTrue("no offer for a video section that was never added: $sent", sent.none { it is CallWireFrame.Offer })
        assertTrue(statuses().none { it.videoUpgrade == true })

        testScheduler.advanceTimeBy(SWITCH_PROBLEM_CLEAR_MS + 100)
        assertNull(s.state.value.cameraProblem)
    }

    @Test
    fun `the callee asking for video turns the callers call into a video call with its own camera off`() = runTest {
        val caller = session(direction = FlashCallDirection.OUTGOING)
        caller.hearPeer(CallWireFrame.Status(callId, peerId, micOn = true, cameraOn = true, videoUpgrade = true))
        testScheduler.advanceUntilIdle()

        val st = caller.state.value
        assertTrue("the call now carries video", st.video)
        assertTrue("nothing is sent until the caller turns its own camera on", st.cameraOff)
        assertTrue("the caller can add its camera as well", st.canUpgradeToVideo)
        assertFalse("the callee's camera is on", st.peerCameraOff)
        val told = statuses().last()
        assertEquals("the caller states its camera so the callee's tile is right", false, told.cameraOn)
        assertNull("only the one who adds a camera asks for the offer", told.videoUpgrade)
    }

    @Test
    fun `only the caller acts on the request, the callee never offers`() = runTest {
        val callee = session(direction = FlashCallDirection.INCOMING)
        callee.hearPeer(CallWireFrame.Status(callId, peerId, cameraOn = true, videoUpgrade = true))
        testScheduler.advanceUntilIdle()
        assertFalse("two offerers would be glare", callee.state.value.video)
        assertTrue(sent.none { it is CallWireFrame.Offer })
    }

    @Test
    fun `a voice peer that never asks keeps a voice call`() = runTest {
        val caller = session()
        caller.hearPeer(CallWireFrame.Status(callId, peerId, micOn = false))
        assertFalse(caller.state.value.video)
        assertFalse(caller.state.value.peerCameraOff)
    }

    @Test
    fun `a request the caller could not act on is still owed, not forgotten`() = runTest {
        // No media on a JVM unit test, so the offer cannot be created: the request must stay pending for the retry.
        val caller = session(direction = FlashCallDirection.OUTGOING)
        caller.hearPeer(CallWireFrame.Status(callId, peerId, cameraOn = true, videoUpgrade = true))
        testScheduler.advanceUntilIdle()
        assertTrue("the offer owed after a lost try is kept for the next connection", caller.upgradeOfferPendingForTesting)

        // A signaling drop and return runs the retry; with no media it still cannot deliver, and it must not end the call.
        caller.onSignalingLost()
        caller.onSignalingRestored()
        testScheduler.advanceUntilIdle()
        assertTrue(caller.upgradeOfferPendingForTesting)
        assertEquals(FlashCallState.ACTIVE, caller.state.value.state)
        assertTrue("no offer was invented without a peer connection: $sent", sent.none { it is CallWireFrame.Offer })
    }

    @Test
    fun `a callee whose request is unanswered repeats it on its next status`() = runTest {
        val callee = session(direction = FlashCallDirection.INCOMING)
        callee.hearPeer()
        callee.setUpgradeRequestPendingForTesting(true)
        callee.onSignalingLost()
        callee.onSignalingRestored()
        testScheduler.advanceUntilIdle()
        assertTrue("the request went out again: ${statuses()}", statuses().any { it.videoUpgrade == true })

        sent.clear()
        callee.setUpgradeRequestPendingForTesting(false)
        callee.onSignalingLost()
        callee.onSignalingRestored()
        testScheduler.advanceUntilIdle()
        assertTrue("an answered request is not repeated", statuses().none { it.videoUpgrade == true })
    }
}
