package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session half of the group video audit (ERROR-097): a request from a peer that already left must not
 * add a watcher, and every leg has its own voice-priority rung, applied to that leg only. No native media is
 * touched: the legs are bookkeeping ([FlashGroupCallSession.addJoinedLegForTesting]) with no sender, so what
 * is checked is the decision, the frames sent and the state, not an encoder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashGroupCallVideoAuditTest {

    private val callId = "call-1"
    private val groupId = "group-1"
    private val sent = mutableListOf<Pair<CallWireFrame, String>>()
    private var prioritiseVoice = true

    private fun TestScope.session() = FlashGroupCallSession(
        callId = callId,
        groupId = groupId,
        groupName = "G",
        direction = FlashCallDirection.OUTGOING,
        video = true,
        localDeviceId = "me",
        localName = "Me",
        scope = this,
        sendFrame = { frame, peer ->
            sent += frame to peer
            true
        },
        onEnded = {},
        prioritiseVoice = { prioritiseVoice },
        nowMs = { BASE + testScheduler.currentTime },
    )

    private fun hangup(from: String) = CallWireFrame.GroupHangup(callId = callId, from = from, groupId = groupId)
    private fun request(from: String, seq: Long) =
        CallWireFrame.VideoRequest(callId = callId, from = from, seq = seq, quality = 540, focus = false)

    private fun grantsTo(peer: String) = sent.count { (f, p) -> f is CallWireFrame.VideoGrant && p == peer }

    private fun TestScope.settle() {
        Thread.sleep(250)
        runCurrent()
    }

    private val bad = CallQualitySample(rttMs = 400, audioJitterMs = 80, lossFraction = 0.2)
    private val good = CallQualitySample(rttMs = 20, audioJitterMs = 5, lossFraction = 0.0)

    @Test
    fun aVideoRequestThatArrivesAfterItsSenderLeftIsNotGrantedButALivePeersIs() = runTest {
        val s = session()
        s.addJoinedLegForTesting("gone", FlashCallParticipantState.CONNECTED)
        s.addJoinedLegForTesting("here", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        s.onInboundFrame(hangup("gone"), peerId = "gone")
        settle()
        assertEquals(FlashCallParticipantState.LEFT, s.getLegStateForTesting("gone"))
        sent.clear()

        // The request was already in flight when the hangup was processed: nothing would ever remove this watcher.
        s.onInboundFrame(request("gone", seq = 10), peerId = "gone")
        s.onInboundFrame(request("here", seq = 10), peerId = "here")
        settle()

        assertEquals("a departed peer is not given an encoder slot", 0, grantsTo("gone"))
        assertEquals("a live peer still is", 1, grantsTo("here"))
    }

    @Test
    fun aBadLinkCostsThatLegItsVideoAndNoOtherLegs() = runTest {
        val s = session()
        s.addJoinedLegForTesting("weak", FlashCallParticipantState.CONNECTED)
        s.addJoinedLegForTesting("strong", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()

        // The degrade threshold is two bad samples in a row on that leg; one is not enough. The samples are interleaved
        // the way the stats loop delivers them: a healthy leg's sample in between must not break the weak leg's streak.
        s.applyVoicePriorityForTesting("weak", bad)
        assertEquals(VideoConcession.FULL, s.concessionForTesting("weak"))
        s.applyVoicePriorityForTesting("strong", good)
        s.applyVoicePriorityForTesting("weak", bad)
        s.applyVoicePriorityForTesting("strong", good)

        assertEquals(VideoConcession.REDUCED_BITRATE, s.concessionForTesting("weak"))
        assertEquals(VideoConcession.FULL, s.concessionForTesting("strong"))
        assertEquals(VideoConcession.REDUCED_BITRATE.reason, s.state.value.videoLimitReason)
    }

    @Test
    fun aLegGivesItsVideoBackAfterFiveCleanSamples() = runTest {
        val s = session()
        s.addJoinedLegForTesting("weak", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        repeat(2) { s.applyVoicePriorityForTesting("weak", bad) }
        assertEquals(VideoConcession.REDUCED_BITRATE, s.concessionForTesting("weak"))

        repeat(4) { s.applyVoicePriorityForTesting("weak", good) }
        assertEquals("four clean samples are not enough", VideoConcession.REDUCED_BITRATE, s.concessionForTesting("weak"))
        s.applyVoicePriorityForTesting("weak", good)

        assertEquals(VideoConcession.FULL, s.concessionForTesting("weak"))
        assertNull(s.state.value.videoLimitReason)
    }

    @Test
    fun withPrioritiseVoiceOffNoLegIsReducedAndOneAlreadyReducedGoesBackToFull() = runTest {
        val s = session()
        s.addJoinedLegForTesting("weak", FlashCallParticipantState.CONNECTED)
        s.markActiveForTesting()
        repeat(2) { s.applyVoicePriorityForTesting("weak", bad) }
        assertEquals(VideoConcession.REDUCED_BITRATE, s.concessionForTesting("weak"))

        prioritiseVoice = false
        s.applyVoicePriorityForTesting("weak", bad)
        assertEquals(VideoConcession.FULL, s.concessionForTesting("weak"))
        repeat(5) { s.applyVoicePriorityForTesting("weak", bad) }
        assertEquals(VideoConcession.FULL, s.concessionForTesting("weak"))
        assertNull(s.state.value.videoLimitReason)
        assertTrue(sent.none { (f, _) -> f is CallWireFrame.VideoDeny })
    }

    private companion object {
        const val BASE = 1_700_000_000_000L
    }
}
