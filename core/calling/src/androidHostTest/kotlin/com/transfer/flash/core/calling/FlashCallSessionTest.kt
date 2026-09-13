package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session state-machine tests that do NOT require the WebRTC native engine.
 *
 * On a JVM unit test `MediaDevices.getUserMedia` cannot succeed (no Android framework,
 * no `libjingle_peerconnection_so`), so `startMedia()` always fails here. That is the
 * lever these tests use: every "what goes on the wire before media exists" invariant is
 * observable, which is exactly the class of bug that stranded calls in CONNECTING.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashCallSessionTest {

    private val callId = "call-0001"
    private val peerId = "peer-0001"
    private val sent = mutableListOf<CallWireFrame>()

    private fun TestScope.newSession(
        direction: FlashCallDirection,
        video: Boolean = false,
    ): FlashCallSession = FlashCallSession(
        callId = callId,
        peerId = peerId,
        peerName = "Peer",
        direction = direction,
        video = video,
        localDeviceId = "me-0001",
        localName = "Me",
        scope = this,
        sendFrame = { frame ->
            sent += frame
            true
        },
    )

    // ------------------------------------------------------------------
    // The "stuck on Connecting…" regression
    // ------------------------------------------------------------------

    /**
     * Accept must never announce readiness before the PeerConnection exists.
     *
     * The caller sends its offer the instant it sees `Accept` — one LAN RTT — while
     * getUserMedia + the first PeerConnectionFactory init take >100 ms. Sending `Accept`
     * first meant the offer landed with `peerConnection == null`, was dropped, and both
     * devices sat in CONNECTING forever with no timeout on either side.
     */
    @Test
    fun accept_sendsNoAcceptFrameWhenMediaCannotStart() = runTest {
        val session = newSession(FlashCallDirection.INCOMING)

        assertFalse("media cannot start on the JVM", session.accept())

        assertTrue(
            "Accept must not go out before media is ready: $sent",
            sent.none { it is CallWireFrame.Accept },
        )
        assertTrue(
            "the caller must be told to stop ringing: $sent",
            sent.any { it is CallWireFrame.Decline },
        )
        assertEquals(FlashCallState.ENDED, session.state.value.state)
        assertEquals(FlashCallEndReason.ERROR, session.state.value.endReason)
    }

    /** Same invariant on the caller side: no offer is ever sent without media. */
    @Test
    fun onAccept_sendsNoOfferWhenMediaCannotStart() = runTest {
        val session = newSession(FlashCallDirection.OUTGOING)
        assertTrue(session.startOutgoing())

        session.onInboundFrame(CallWireFrame.Accept(callId = callId, from = peerId))
        testScheduler.advanceUntilIdle()

        assertTrue(
            "an offer without a local description is unanswerable: $sent",
            sent.none { it is CallWireFrame.Offer },
        )
        assertTrue(sent.any { it is CallWireFrame.Hangup })
        assertEquals(FlashCallState.ENDED, session.state.value.state)
        assertEquals(FlashCallEndReason.ERROR, session.state.value.endReason)
    }

    /** An offer that arrives before accept is buffered, not treated as a failure. */
    @Test
    fun earlyOffer_whileRinging_doesNotKillTheCall() = runTest {
        val session = newSession(FlashCallDirection.INCOMING)

        session.onInboundFrame(CallWireFrame.Offer(callId = callId, from = peerId, sdp = "v=0"))
        session.onInboundFrame(
            CallWireFrame.IceCandidate(
                callId = callId,
                from = peerId,
                sdpMid = "0",
                sdpMLineIndex = 0,
                candidate = "candidate:1 1 udp 2130706431 192.168.0.2 45822 typ host",
            ),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(FlashCallState.RINGING, session.state.value.state)
        assertTrue("early signaling must not put anything on the wire: $sent", sent.isEmpty())
    }

    // ------------------------------------------------------------------
    // Video track publication
    // ------------------------------------------------------------------

    /**
     * The video tracks must be OBSERVABLE, and this test enforces it by construction:
     * `.value` only compiles on a flow.
     *
     * They used to be plain getters over the media stream — a snapshot. The renderer is
     * composed the instant the call screen appears, ~130 ms before getUserMedia returns,
     * so it read null and nothing ever told it to look again: both video tiles stayed
     * black for the whole call. Media cannot start on the JVM, so the tracks stay null
     * here — what is asserted is that nothing throws and nothing is published without
     * media behind it.
     */
    @Test
    fun videoTracks_publishNothingUntilMediaExists() = runTest {
        val session = newSession(FlashCallDirection.INCOMING, video = true)

        assertNull(session.localVideoTrack.value)
        assertNull(session.remoteVideoTrack.value)

        assertFalse("media cannot start on the JVM", session.accept())

        assertNull("a failed startMedia must publish no camera", session.localVideoTrack.value)
        assertNull(session.remoteVideoTrack.value)
    }

    // ------------------------------------------------------------------
    // Terminal frames and timeouts
    // ------------------------------------------------------------------

    /** Hangup/Decline bypass the media gate: they must land even mid-startup. */
    @Test
    fun inboundHangup_whileRinging_endsImmediately() = runTest {
        val session = newSession(FlashCallDirection.INCOMING)

        session.onInboundFrame(CallWireFrame.Hangup(callId = callId, from = peerId))

        assertEquals(FlashCallState.ENDED, session.state.value.state)
        assertEquals(FlashCallEndReason.NORMAL, session.state.value.endReason)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun inboundDecline_whileDialing_endsWithDeclined() = runTest {
        val session = newSession(FlashCallDirection.OUTGOING)
        assertTrue(session.startOutgoing())

        session.onInboundFrame(CallWireFrame.Decline(callId = callId, from = peerId))

        assertEquals(FlashCallState.ENDED, session.state.value.state)
        assertEquals(FlashCallEndReason.DECLINED, session.state.value.endReason)
    }

    @Test
    fun outgoing_dialTimeout_endsWithNoAnswer() = runTest {
        val session = newSession(FlashCallDirection.OUTGOING)
        assertTrue(session.startOutgoing())
        assertEquals(1, sent.size)
        assertTrue(sent.first() is CallWireFrame.Invite)

        testScheduler.advanceTimeBy(46_000L)
        testScheduler.runCurrent()

        assertEquals(FlashCallState.ENDED, session.state.value.state)
        assertEquals(FlashCallEndReason.NO_ANSWER, session.state.value.endReason)
        assertTrue("the peer must stop ringing: $sent", sent.any { it is CallWireFrame.Hangup })
    }

    @Test
    fun decline_sendsDeclineAndEnds() = runTest {
        val session = newSession(FlashCallDirection.INCOMING)

        session.decline()

        assertTrue(sent.single() is CallWireFrame.Decline)
        assertEquals(FlashCallState.ENDED, session.state.value.state)
    }

    @Test
    fun accept_afterEnd_isRejected() = runTest {
        val session = newSession(FlashCallDirection.INCOMING)
        session.onInboundFrame(CallWireFrame.Hangup(callId = callId, from = peerId))

        assertFalse(session.accept())
        assertTrue(sent.isEmpty())
    }

    // ------------------------------------------------------------------
    // Audio routing and stats defaults
    // ------------------------------------------------------------------

    /**
     * A video call starts on the speaker and a voice call on the earpiece, decided at
     * construction so the platform route is already correct on the first attach — the router
     * reads this flag before media exists, and a HAL that opens the mic on the wrong route
     * does not re-open it later.
     */
    @Test
    fun speaker_defaultsToOnForVideoAndOffForVoice() = runTest {
        assertTrue(newSession(FlashCallDirection.OUTGOING, video = true).state.value.speakerOn)
        assertFalse(newSession(FlashCallDirection.OUTGOING, video = false).state.value.speakerOn)
    }

    /**
     * The latency badge is driven off this flow and renders nothing while it is null. Emitting
     * a zeroed report before the first getStats() would put "0 ms · 0 kbps" on screen for the
     * first second of every call.
     */
    @Test
    fun stats_publishNothingUntilMediaExists() = runTest {
        val session = newSession(FlashCallDirection.INCOMING, video = true)

        assertNull(session.stats.value)

        assertFalse("media cannot start on the JVM", session.accept())

        assertNull("a failed startMedia must publish no stats", session.stats.value)
    }
}
