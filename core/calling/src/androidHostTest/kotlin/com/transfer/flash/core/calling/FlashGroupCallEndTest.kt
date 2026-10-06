package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCameraProblem
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERROR-086: a group call left alone could not be closed. The solo grace timer's `endSession`
 * cancelled its own job, so the teardown aborted before ENDED was published, `onEnded` never ran,
 * hang-up became a no-op and the coordinator refused every later call. These tests pin the ending
 * of a group call, and the ring / rejoin / phantom-leg rules that came out of the same audit.
 *
 * Time is virtual: the session's clock is the test scheduler plus [BASE] (a wall-clock-sized
 * offset, because 0 means "never" in the leg bookkeeping). The native teardown hops to the real
 * media dispatcher, so a test that ends a call also lets that hop finish before it returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashGroupCallEndTest {

    private val callId = "call-1"
    private val groupId = "group-1"
    private val sent = mutableListOf<Pair<CallWireFrame, String>>()
    private val endedStates = mutableListOf<FlashCallState>()

    private fun TestScope.session(direction: FlashCallDirection = FlashCallDirection.OUTGOING) = FlashGroupCallSession(
        callId = callId,
        groupId = groupId,
        groupName = "G",
        direction = direction,
        video = false,
        localDeviceId = "me",
        localName = "Me",
        scope = this,
        sendFrame = { frame, peer ->
            sent += frame to peer
            true
        },
        onEnded = { ended -> endedStates += ended.state.value.state },
        nowMs = { BASE + testScheduler.currentTime },
    )

    private fun hangup(from: String) = CallWireFrame.GroupHangup(callId = callId, from = from, groupId = groupId)
    private fun join(from: String) = CallWireFrame.GroupJoin(callId = callId, from = from, groupId = groupId, participantName = from)
    private fun presence(from: String) = CallWireFrame.GroupPresence(callId = callId, from = from, groupId = groupId, callerName = "G", video = false)

    /** Lets the native-teardown hop (a real thread) and the coroutines waiting on it finish. */
    private fun TestScope.settle() {
        Thread.sleep(250)
        runCurrent()
    }

    // ---------------------------------------------------------------- the reported bug

    @Test
    fun theSoloGraceTimerEndsTheCallInsteadOfCancellingItself() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        // The only other participant leaves: a 30 s grace starts.
        s.onInboundFrame(hangup("peer"), peerId = "peer")
        advanceTimeBy(31_000L)
        runCurrent()

        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.NORMAL, s.state.value.endReason)
        assertTrue(s.isSessionEnded)
        assertEquals("onEnded must reach the coordinator exactly once", listOf(FlashCallState.ENDED), endedStates)
        assertTrue(
            "the peers are told this device left",
            sent.any { it.first is CallWireFrame.GroupHangup && it.second == "peer" },
        )
        settle()
    }

    @Test
    fun endedIsPublishedBeforeAnyNativeWork() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.hangUp()
        // Captured inside onEnded: the state the coordinator and the UI see at that moment.
        assertEquals(listOf(FlashCallState.ENDED), endedStates)
        settle()
    }

    @Test
    fun hangUpEndsAnActiveCallOnceAndIsIdempotent() = runTest {
        val s = session()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.CONNECTED)
        s.addJoinedLegForTesting("b", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        s.hangUp()
        s.hangUp()
        s.decline()
        runCurrent()

        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(1, endedStates.size)
        assertEquals(2, sent.count { it.first is CallWireFrame.GroupHangup })
        settle()
    }

    @Test
    fun aMemberTheCallWasOnlyAnnouncedToIsToldItEnded() = runTest {
        // "other" never got a leg here (it is announce-only), but its "join" banner comes from this call's presence,
        // so the goodbye must reach it too or the banner outlives the call.
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "caller", callerName = "Caller", members = listOf("caller", "other", "me"))
        s.hangUp()
        runCurrent()

        assertEquals(
            setOf("caller", "other"),
            sent.filter { it.first is CallWireFrame.GroupHangup }.map { it.second }.toSet(),
        )
        assertEquals("nobody is told twice", 2, sent.count { it.first is CallWireFrame.GroupHangup })
        settle()
    }

    @Test
    fun anEndedCallStaysEndedWhateverWritesToTheStateLater() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.hangUp()

        // A late leg event, a stats tick, a toggle: none may revive the call.
        s.markActiveForTesting()
        s.markConnectingForTesting()
        s.setLegStateForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.toggleMute()
        s.setSpeaker(true)

        assertEquals(FlashCallState.ENDED, s.state.value.state)
        settle()
    }

    // ---------------------------------------------------------------- (b) a returning peer keeps the call

    @Test
    fun aPeerRejoiningInsideTheGraceWindowKeepsTheCall() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        s.onInboundFrame(hangup("peer"), peerId = "peer")
        advanceTimeBy(27_000L)
        // The desktop log of 2026-09-30: the peer rejoins at +27 s and is still connecting at +30 s.
        s.onInboundFrame(join("peer"), peerId = "peer")
        settle()
        advanceTimeBy(4_000L)
        runCurrent()

        assertFalse("the timer must not end a call a peer is rejoining", s.isSessionEnded)
        assertEquals(FlashCallState.ACTIVE, s.state.value.state)

        // It connects while the timer waits: the call carries on.
        s.setLegStateForTesting("peer", FlashCallParticipantState.CONNECTED)
        advanceTimeBy(60_000L)
        runCurrent()
        assertFalse(s.isSessionEnded)
    }

    @Test
    fun aRejoinThatNeverConnectsDoesNotKeepTheCallForever() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.onInboundFrame(hangup("peer"), peerId = "peer")
        advanceTimeBy(27_000L)
        s.onInboundFrame(join("peer"), peerId = "peer")
        settle()

        advanceTimeBy(120_000L)
        runCurrent()

        assertTrue("a leg that keeps failing to connect must not hold the call open", s.isSessionEnded)
        assertEquals(FlashCallState.ENDED, s.state.value.state)
        settle()
    }

    @Test
    fun aSoloTimerThatStoodDownCanBeRearmedByALaterDeparture() = runTest {
        val s = session()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.CONNECTED)
        s.addJoinedLegForTesting("b", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        s.onInboundFrame(hangup("a"), peerId = "a") // b is still connected: no timer
        advanceTimeBy(40_000L)
        runCurrent()
        assertFalse(s.isSessionEnded)

        s.onInboundFrame(hangup("b"), peerId = "b") // now alone
        advanceTimeBy(31_000L)
        runCurrent()
        assertTrue(s.isSessionEnded)
        settle()
    }

    // ---------------------------------------------------------------- (c) ringing

    @Test
    fun theCallerHangingUpStopsTheRingOfAnInvitee() = runTest {
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host")
        assertEquals(FlashCallState.RINGING, s.state.value.state)

        s.onInboundFrame(hangup("host"), peerId = "host")
        runCurrent()

        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.NO_ANSWER, s.state.value.endReason)
        assertEquals(listOf(FlashCallState.ENDED), endedStates)
        settle()
    }

    @Test
    fun aRingContinuesWhileSomeoneElseHasJoinedTheCall() = runTest {
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host")
        // peer2 joined; the host relays it to this ringing device.
        s.onInboundFrame(join("peer2"), peerId = "host")

        s.onInboundFrame(hangup("host"), peerId = "host")
        runCurrent()
        assertFalse("peer2 is in the call, so there is still something to answer", s.isSessionEnded)
        assertEquals(FlashCallState.RINGING, s.state.value.state)

        s.onInboundFrame(hangup("peer2"), peerId = "peer2")
        runCurrent()
        assertTrue(s.isSessionEnded)
        settle()
    }

    @Test
    fun anIncomingCallNobodyAnswersStopsRingingByItself() = runTest {
        val s = session(FlashCallDirection.INCOMING)
        s.startIncomingRinging(peerId = "host", callerName = "Host")

        advanceTimeBy(44_000L)
        runCurrent()
        assertEquals(FlashCallState.RINGING, s.state.value.state)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.NO_ANSWER, s.state.value.endReason)
        settle()
    }

    @Test
    fun anOutgoingCallNobodyAnswersEndsAndTellsTheInvitees() = runTest {
        val s = session()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.INVITED)
        s.addJoinedLegForTesting("b", FlashCallParticipantState.INVITED)
        s.armDialTimeout()

        advanceTimeBy(29_000L)
        runCurrent()
        assertEquals(FlashCallState.DIALING, s.state.value.state)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.NO_ANSWER, s.state.value.endReason)
        assertEquals(
            "both invitees must be told to stop ringing",
            setOf("a", "b"),
            sent.filter { it.first is CallWireFrame.GroupHangup }.map { it.second }.toSet(),
        )
        settle()
    }

    @Test
    fun everyInviteeDecliningEndsTheOutgoingCallAtOnce() = runTest {
        val s = session()
        s.addJoinedLegForTesting("a", FlashCallParticipantState.INVITED)
        s.addJoinedLegForTesting("b", FlashCallParticipantState.INVITED)

        s.onInboundFrame(CallWireFrame.GroupDecline(callId = callId, from = "a", groupId = groupId), peerId = "a")
        runCurrent()
        assertFalse(s.isSessionEnded)

        s.onInboundFrame(CallWireFrame.GroupDecline(callId = callId, from = "b", groupId = groupId), peerId = "b")
        runCurrent()
        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.DECLINED, s.state.value.endReason)
        settle()
    }

    // ---------------------------------------------------------------- (f) a joiner that never connects

    @Test
    fun aJoinerThatConnectsToNobodyGivesUp() = runTest {
        val s = session()
        s.addSettingUpLegForTesting("ghost", pcCreatedAtMs = BASE) // never answers
        s.markConnectingForTesting()
        s.armConnectDeadline()

        advanceTimeBy(44_000L)
        runCurrent()
        assertFalse(s.isSessionEnded)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.ERROR, s.state.value.endReason)
        settle()
    }

    @Test
    fun aJoinerWithALegThatIsAnsweringIsGivenTimeToConnect() = runTest {
        val s = session()
        s.markConnectingForTesting()
        s.armConnectDeadline() // fires at +45 s
        advanceTimeBy(30_000L)
        // A connection rebuilt at +30 s whose peer has answered since: 15 s into a fresh attempt.
        s.addSettingUpLegForTesting("peer", pcCreatedAtMs = BASE + 30_000L, heardAtMs = BASE + 31_000L)

        advanceTimeBy(16_000L)
        runCurrent()
        assertFalse("a peer that is answering is about to connect", s.isSessionEnded)

        s.setLegStateForTesting("peer", FlashCallParticipantState.CONNECTED)
        advanceTimeBy(30_000L)
        runCurrent()
        assertFalse(s.isSessionEnded)
    }

    @Test
    fun aLegThatAnswersButNeverConnectsIsWaitedForOnlyAWhile() = runTest {
        val s = session()
        s.markConnectingForTesting()
        s.armConnectDeadline()
        advanceTimeBy(30_000L)
        s.addSettingUpLegForTesting("peer", pcCreatedAtMs = BASE + 30_000L, heardAtMs = BASE + 31_000L)

        advanceTimeBy(16_000L) // +46 s: the extension applies
        runCurrent()
        assertFalse(s.isSessionEnded)

        advanceTimeBy(60_000L) // the attempt is now far older than the 40 s rejoin window
        runCurrent()
        assertEquals(FlashCallState.ENDED, s.state.value.state)
        assertEquals(FlashCallEndReason.ERROR, s.state.value.endReason)
        settle()
    }

    // ---------------------------------------------------------------- (d) phantom legs

    @Test
    fun aMemberWhoNeverAnswersIsGivenUpOnAndShownAsInvited() = runTest {
        val s = session()
        s.addSettingUpLegForTesting("ghost", pcCreatedAtMs = BASE)
        s.markActiveForTesting()

        advanceTimeBy(14_000L)
        s.pruneUnansweredLegs()
        assertEquals(FlashCallParticipantState.CONNECTING, s.getLegStateForTesting("ghost"))

        advanceTimeBy(2_000L)
        s.pruneUnansweredLegs()
        assertEquals(FlashCallParticipantState.INVITED, s.getLegStateForTesting("ghost"))
        assertEquals(
            FlashCallParticipantState.INVITED,
            s.state.value.participants.single { it.peerId == "ghost" }.state,
        )
    }

    @Test
    fun aMemberWhoAnsweredIsNotGivenUpOn() = runTest {
        val s = session()
        s.addSettingUpLegForTesting("slow", pcCreatedAtMs = BASE, heardAtMs = BASE + 3_000L)
        advanceTimeBy(60_000L)
        s.pruneUnansweredLegs()
        assertEquals(FlashCallParticipantState.CONNECTING, s.getLegStateForTesting("slow"))
    }

    @Test
    fun aPresenceFrameBringsAGivenUpLegBack() = runTest {
        val s = session()
        s.addSettingUpLegForTesting("ghost", pcCreatedAtMs = BASE)
        s.markActiveForTesting()
        advanceTimeBy(16_000L)
        s.pruneUnansweredLegs()
        assertEquals(FlashCallParticipantState.INVITED, s.getLegStateForTesting("ghost"))

        // The member was in the call after all (our first frames to it were lost).
        s.onInboundFrame(presence("ghost"), peerId = "ghost")
        settle()

        assertEquals(FlashCallParticipantState.CONNECTING, s.getLegStateForTesting("ghost"))
    }

    @Test
    fun aStalePresenceFrameDoesNotUndoAHangup() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.addJoinedLegForTesting("other", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.onInboundFrame(hangup("peer"), peerId = "peer")
        settle()
        assertEquals(FlashCallParticipantState.LEFT, s.getLegStateForTesting("peer"))

        s.onInboundFrame(presence("peer"), peerId = "peer")
        settle()

        assertEquals(FlashCallParticipantState.LEFT, s.getLegStateForTesting("peer"))
        assertNotNull(s.state.value.participants.firstOrNull { it.peerId == "peer" })
    }

    @Test
    fun aGivenUpLegNoLongerFillsTheCall() = runTest {
        // A voice call holds 12. Me + 10 connected + one phantom = 12: a real joiner is turned away.
        val s = session()
        repeat(10) { s.addJoinedLegForTesting("m$it", FlashCallParticipantState.CONNECTED) }
        s.addSettingUpLegForTesting("ghost", pcCreatedAtMs = BASE)
        s.markActiveForTesting()

        s.onInboundFrame(join("late"), peerId = "late")
        assertTrue(
            "the phantom took the last place",
            sent.any { it.first is CallWireFrame.GroupFull && it.second == "late" },
        )

        // Once the phantom is given up on, the place is free again.
        sent.clear()
        advanceTimeBy(16_000L)
        s.pruneUnansweredLegs()
        s.onInboundFrame(join("late2"), peerId = "late2")
        settle()
        assertFalse(
            "a real joiner is no longer turned away",
            sent.any { it.first is CallWireFrame.GroupFull && it.second == "late2" },
        )
        assertFalse(s.isSessionEnded)
    }

    // ---------------------------------------------------------------- ERROR-105: camera problems

    @Test
    fun aStoppedCameraIsAnOffCameraAndSaysSo() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        s.reportCameraProblem(FlashCameraProblem.FAILED)
        runCurrent()

        assertEquals(FlashCameraProblem.FAILED, s.state.value.cameraProblem)
        assertTrue(s.state.value.cameraOff)
        // The camera button now means "start it again": it must not claim the camera is on before it opened.
        assertTrue(s.toggleCamera())
        assertTrue(s.state.value.cameraOff)
        assertEquals(FlashCameraProblem.FAILED, s.state.value.cameraProblem)
        settle()
    }

    @Test
    fun aFailedSwitchKeepsTheCameraAndClearsItself() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        s.reportCameraProblem(FlashCameraProblem.SWITCH_FAILED)
        assertEquals(FlashCameraProblem.SWITCH_FAILED, s.state.value.cameraProblem)
        assertFalse(s.state.value.cameraOff)

        advanceTimeBy(SWITCH_PROBLEM_CLEAR_MS + 1)
        runCurrent()
        assertEquals(null, s.state.value.cameraProblem)
    }

    @Test
    fun aCameraProblemAfterTheCallEndedIsIgnored() = runTest {
        val s = session()
        s.addJoinedLegForTesting("peer", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.hangUp()
        settle()

        s.reportCameraProblem(FlashCameraProblem.FAILED)

        assertEquals(null, s.state.value.cameraProblem)
        assertEquals(FlashCallState.ENDED, s.state.value.state)
    }

    private companion object {
        /** Wall-clock-sized: the leg bookkeeping treats 0 as "never". */
        const val BASE = 1_000_000L
    }
}
