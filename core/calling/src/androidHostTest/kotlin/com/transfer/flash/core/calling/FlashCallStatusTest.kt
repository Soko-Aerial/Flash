package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-067: the `status` frame in a 1:1 and a group session. Media cannot start on a JVM unit test, so the call state
 * is put in place with the sessions' test hooks; what is checked is what goes on the wire and what the UI state says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashCallStatusTest {

    private val callId = "call-0001"
    private val peerId = "peer-0001"
    private val me = "me-0001"
    private val sent = mutableListOf<CallWireFrame>()

    private fun TestScope.oneToOne(video: Boolean = true): FlashCallSession = FlashCallSession(
        callId = callId, peerId = peerId, peerName = "Peer", direction = FlashCallDirection.OUTGOING, video = video,
        localDeviceId = me, localName = "Me", scope = this,
        sendFrame = { frame -> sent += frame; true },
    ).also { it.setStateForTesting(FlashCallState.ACTIVE) }

    private fun statuses() = sent.filterIsInstance<CallWireFrame.Status>()

    // ------------------------------------------------------------------ 1:1

    @Test
    fun `muting tells the peer and a status is not sent before the call is being set up`() = runTest {
        val session = oneToOne()
        session.setStateForTesting(FlashCallState.DIALING)
        session.toggleMute()
        testScheduler.advanceUntilIdle()
        assertTrue("nothing to say to a call nobody answered: $sent", statuses().isEmpty())

        session.setStateForTesting(FlashCallState.ACTIVE)
        session.toggleMute() // unmute
        testScheduler.advanceUntilIdle()
        assertEquals(true, statuses().single().micOn)
        assertEquals(me, statuses().single().from)
    }

    @Test
    fun `an audio call states no camera and no video wish`() = runTest {
        val session = oneToOne(video = false)
        session.toggleMute()
        testScheduler.advanceUntilIdle()
        val status = statuses().single()
        assertEquals(false, status.micOn)
        assertEquals(null, status.cameraOn)
        assertEquals(null, status.receiveVideo)
    }

    @Test
    fun `the peer's status shows as badges and clears again`() = runTest {
        val session = oneToOne()
        session.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = false, cameraOn = false, handRaised = true))
        var st = session.state.value
        assertTrue(st.peerMicMuted)
        assertTrue(st.peerCameraOff)
        assertTrue(st.peerHandRaised)

        session.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = true, cameraOn = true, handRaised = false))
        st = session.state.value
        assertFalse(st.peerMicMuted)
        assertFalse(st.peerCameraOff)
        assertFalse(st.peerHandRaised)
    }

    @Test
    fun `a status is ignored before the call is being set up`() = runTest {
        val session = oneToOne()
        session.setStateForTesting(FlashCallState.RINGING)
        session.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = false))
        assertFalse(session.state.value.peerMicMuted)
    }

    @Test
    fun `on a voice call the peer's camera is never reported off`() = runTest {
        val session = oneToOne(video = false)
        session.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = false, cameraOn = false))
        assertTrue(session.state.value.peerMicMuted)
        assertFalse(session.state.value.peerCameraOff)
    }

    @Test
    fun `a reaction shows on both screens and goes out once`() = runTest {
        val session = oneToOne()
        assertTrue(session.sendReaction(FlashCallReactionKind.LOVE))
        testScheduler.advanceUntilIdle()
        val out = statuses().last()
        assertEquals(FlashCallReactionKind.LOVE, out.reaction)
        assertTrue(out.reactionSeq > 0)
        // Ours is on our own screen as well.
        assertEquals(listOf(me), session.state.value.reactions.map { it.peerId })
        // Expiry itself runs on the wall clock and is covered in CallStatusBookTest; the timer must not break the call.
        testScheduler.advanceTimeBy(CallStatusBook.REACTION_LIFETIME_MS + 500)
        testScheduler.advanceUntilIdle()
        assertEquals(FlashCallState.ACTIVE, session.state.value.state)
    }

    @Test
    fun `a second reaction inside the gap is refused and sends nothing`() = runTest {
        val session = oneToOne()
        assertTrue(session.sendReaction(FlashCallReactionKind.LIKE))
        assertFalse(session.sendReaction(FlashCallReactionKind.LIKE))
        testScheduler.advanceUntilIdle()
        assertEquals(1, statuses().count { it.reaction != null })
    }

    @Test
    fun `a reaction needs a live call`() = runTest {
        val session = oneToOne()
        session.setStateForTesting(FlashCallState.RINGING)
        assertFalse(session.sendReaction(FlashCallReactionKind.WOW))
    }

    @Test
    fun `the peer's reaction is shown once and its replay is not`() = runTest {
        val session = oneToOne()
        val frame = CallWireFrame.Status(callId, peerId, reaction = FlashCallReactionKind.WOW, reactionSeq = 42)
        session.onInboundFrame(frame)
        session.onInboundFrame(frame)
        assertEquals(1, session.state.value.reactions.size)
        assertEquals(peerId, session.state.value.reactions.single().peerId)
    }

    @Test
    fun `hand and data saver go out and show in the state`() = runTest {
        val session = oneToOne()
        session.setHandRaised(true)
        session.setDataSaver(true)
        testScheduler.advanceUntilIdle()
        assertTrue(session.state.value.handRaised)
        assertTrue(session.state.value.dataSaver)
        val last = statuses().last()
        assertEquals(true, last.handRaised)
        assertEquals(false, last.receiveVideo)

        // Same value again says nothing.
        val before = sent.size
        session.setHandRaised(true)
        session.setDataSaver(true)
        testScheduler.advanceUntilIdle()
        assertEquals(before, sent.size)
    }

    @Test
    fun `data saver on a voice call does nothing`() = runTest {
        val session = oneToOne(video = false)
        session.setDataSaver(true)
        assertFalse(session.state.value.dataSaver)
    }

    @Test
    fun `the peer on data saver is shown, and a voice call never shows it`() = runTest {
        val video = oneToOne()
        video.onInboundFrame(CallWireFrame.Status(callId, peerId, receiveVideo = false))
        testScheduler.advanceUntilIdle()
        assertTrue(video.state.value.peerDataSaver)
        video.onInboundFrame(CallWireFrame.Status(callId, peerId, receiveVideo = true))
        testScheduler.advanceUntilIdle()
        assertFalse(video.state.value.peerDataSaver)

        val voice = oneToOne(video = false)
        voice.onInboundFrame(CallWireFrame.Status(callId, peerId, receiveVideo = false))
        assertFalse(voice.state.value.peerDataSaver)
    }

    @Test
    fun `an ended call is not turned live again by a late status`() = runTest {
        val session = oneToOne()
        session.end(com.transfer.flash.core.calling.model.FlashCallEndReason.NORMAL, notifyPeer = false)
        session.setHandRaised(true)
        session.onInboundFrame(CallWireFrame.Status(callId, peerId, micOn = false))
        assertEquals(FlashCallState.ENDED, session.state.value.state)
        assertFalse(session.state.value.handRaised)
    }

    // ------------------------------------------------------------------ group

    private val groupId = "group-chat-001"
    private val other = "peer-002"
    private val third = "peer-003"
    private val groupSent = mutableListOf<Pair<CallWireFrame, String>>()

    private fun TestScope.group(video: Boolean = true): FlashGroupCallSession = FlashGroupCallSession(
        callId = callId, groupId = groupId, groupName = "G", direction = FlashCallDirection.OUTGOING, video = video,
        localDeviceId = me, localName = "Me", scope = this,
        sendFrame = { frame, to -> groupSent += frame to to; true },
    ).also {
        it.addJoinedLegForTesting(other, FlashCallParticipantState.CONNECTED)
        it.addJoinedLegForTesting(third, FlashCallParticipantState.CONNECTING)
        it.setCallStateForTesting(FlashCallState.ACTIVE)
    }

    private fun groupStatuses() = groupSent.filter { it.first is CallWireFrame.Status }

    @Test
    fun `a group mute reaches every participant who accepted`() = runTest {
        val session = group()
        session.toggleMute()
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(other, third), groupStatuses().map { it.second }.toSet())
        assertTrue(groupStatuses().all { (it.first as CallWireFrame.Status).micOn == false })
    }

    @Test
    fun `a participant who only got an invite hears no status`() = runTest {
        val session = group()
        session.addJoinedLegForTesting("invited-only", FlashCallParticipantState.INVITED)
        session.setCallStateForTesting(FlashCallState.ACTIVE)
        session.setHandRaised(true)
        testScheduler.advanceUntilIdle()
        assertTrue(groupStatuses().none { it.second == "invited-only" })
    }

    @Test
    fun `a participant's status becomes badges on its tile only`() = runTest {
        val session = group()
        session.onInboundFrame(CallWireFrame.Status(callId, other, micOn = false, cameraOn = false, handRaised = true), other)
        val tiles = session.state.value.participants.associateBy { it.peerId }
        assertTrue(tiles.getValue(other).isMuted)
        assertTrue(tiles.getValue(other).cameraOff)
        assertTrue(tiles.getValue(other).handRaised)
        assertFalse(tiles.getValue(third).isMuted)
        assertFalse(tiles.getValue(third).cameraOff)
        assertFalse(tiles.getValue(third).handRaised)
    }

    @Test
    fun `a status claiming to be from someone else than the sender is ignored`() = runTest {
        val session = group()
        session.onInboundFrame(CallWireFrame.Status(callId, third, micOn = false), other)
        assertFalse(session.state.value.participants.first { it.peerId == third }.isMuted)
    }

    @Test
    fun `a status from a stranger with no leg is ignored`() = runTest {
        val session = group()
        session.onInboundFrame(CallWireFrame.Status(callId, "stranger", reaction = FlashCallReactionKind.LIKE, reactionSeq = 9), "stranger")
        assertTrue(session.state.value.reactions.isEmpty())
    }

    @Test
    fun `a participant who left takes its badges with it`() = runTest {
        val session = group()
        session.onInboundFrame(CallWireFrame.Status(callId, other, handRaised = true), other)
        session.onInboundFrame(CallWireFrame.GroupHangup(callId, other, groupId), other)
        testScheduler.advanceUntilIdle()
        assertFalse(session.state.value.participants.first { it.peerId == other }.handRaised)
    }

    @Test
    fun `a group reaction shows for everyone and is sent to the whole call`() = runTest {
        val session = group()
        assertTrue(session.sendReaction(FlashCallReactionKind.LIKE))
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(other, third), groupStatuses().map { it.second }.toSet())
        assertNotNull(session.state.value.reactions.singleOrNull())
        session.onInboundFrame(CallWireFrame.Status(callId, other, reaction = FlashCallReactionKind.LOVE, reactionSeq = 5), other)
        assertEquals(2, session.state.value.reactions.size)
    }

    @Test
    fun `group data saver is a local choice and sends no frame`() = runTest {
        val session = group()
        session.setDataSaver(true)
        testScheduler.advanceUntilIdle()
        assertTrue(session.state.value.dataSaver)
        assertTrue("nobody needs telling: the peers just stop being asked: $groupSent", groupStatuses().isEmpty())
    }
}
