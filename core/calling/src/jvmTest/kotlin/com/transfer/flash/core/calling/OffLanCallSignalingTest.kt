@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.IceTransportPolicy
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class OffLanCallSignalingTest {

    @Test
    fun `capability negotiation gates off-LAN UI honestly while preserving direct LAN calling`() {
        val turnSpec = CallIceServerSpec(
            urls = listOf("turn:turn.flash.local:3478?transport=udp"),
            username = "1700000300:alice",
            credential = "hmac-sig",
        )

        // Direct LAN remains clickable even if Hub is disconnected
        val lanOnly = CallRouteCapabilities(
            isDirectLanReachable = true,
            isHubConnected = false,
            hubSupportsOffLanCalling = false,
            peerSupportsOffLanCalling = false,
            isPeerRevoked = false,
        )
        assertTrue(lanOnly.shouldShowCallActions())
        assertTrue(lanOnly.evaluateOffLanEligibility() is OffLanCallEligibility.Unavailable)

        // Hub-only peer with unconfigured Hub hides call buttons honestly
        val hubNoIce = CallRouteCapabilities(
            isDirectLanReachable = false,
            isHubConnected = true,
            hubSupportsOffLanCalling = false,
            peerSupportsOffLanCalling = true,
            isPeerRevoked = false,
        )
        assertFalse(hubNoIce.shouldShowCallActions())

        // RELAY_ONLY without any TURN URL refuses off-LAN calling
        val relayOnlyWithoutTurn = CallRouteCapabilities(
            isDirectLanReachable = false,
            isHubConnected = true,
            hubSupportsOffLanCalling = true,
            peerSupportsOffLanCalling = true,
            isPeerRevoked = false,
            mediaPolicy = CallMediaPathPolicy.RELAY_ONLY,
            iceServers = listOf(CallIceServerSpec(urls = listOf("stun:stun.flash.local:3478"))),
        )
        assertFalse(relayOnlyWithoutTurn.shouldShowCallActions())

        // Revoked peer refuses call actions even on LAN
        val revoked = CallRouteCapabilities(
            isDirectLanReachable = true,
            isHubConnected = true,
            hubSupportsOffLanCalling = true,
            peerSupportsOffLanCalling = true,
            isPeerRevoked = true,
            iceServers = listOf(turnSpec),
        )
        assertFalse(revoked.shouldShowCallActions())

        // Valid TURN configuration enables off-LAN calling and builds Relay RtcConfiguration when requested
        val relayReady = CallRouteCapabilities(
            isDirectLanReachable = false,
            isHubConnected = true,
            hubSupportsOffLanCalling = true,
            peerSupportsOffLanCalling = true,
            isPeerRevoked = false,
            mediaPolicy = CallMediaPathPolicy.RELAY_ONLY,
            iceServers = listOf(turnSpec),
        )
        assertTrue(relayReady.shouldShowCallActions())
        val eligibility = relayReady.evaluateOffLanEligibility()
        assertTrue(eligibility is OffLanCallEligibility.Supported && eligibility.hasRelayServer)
        val rtc = relayReady.toRtcConfiguration()
        assertEquals(IceTransportPolicy.Relay, rtc.iceTransportPolicy)
        assertEquals(1, rtc.iceServers.size)
        assertEquals("1700000300:alice", rtc.iceServers.first().username)
    }

    @Test
    fun `offLan signal guard rejects expired, skewed, replayed, stale sequence, and terminal signals`() {
        val guard = OffLanCallSignalGuard(maxFutureSkewMs = 30_000L, maxTtlMs = 60_000L)
        val baseNow = 1_700_000_000_000L

        fun sig(
            signalId: String = "sig-1",
            callId: String = "call-1",
            from: String = "bob",
            seq: Long = 1L,
            issuedAtMs: Long = baseNow,
            ttlMs: Long = 15_000L,
            payload: String = "FLASH_CALL action=invite callId=call-1 from=bob name=Bob video=false",
        ) = InboundOffLanCallSignal(signalId, callId, from, seq, issuedAtMs, ttlMs, payload)

        assertEquals(OffLanSignalVerdict.Accepted, guard.validateInbound(sig(signalId = "s1", seq = 1L), nowMs = baseNow))

        // Duplicate signalId replay rejected
        val replay = guard.validateInbound(sig(signalId = "s1", seq = 2L), nowMs = baseNow + 100L)
        assertEquals(OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.REPLAYED_SIGNAL_ID), replay)

        // Stale/out-of-order seq rejected
        val staleSeq = guard.validateInbound(sig(signalId = "s2", seq = 1L), nowMs = baseNow + 200L)
        assertEquals(OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.STALE_SEQUENCE), staleSeq)

        // Expired TTL rejected
        val expired = guard.validateInbound(sig(signalId = "s3", seq = 3L, issuedAtMs = baseNow - 20_000L, ttlMs = 10_000L), nowMs = baseNow)
        assertEquals(OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.EXPIRED_TTL), expired)

        // Excessive future skew rejected
        val skewed = guard.validateInbound(sig(signalId = "s4", seq = 4L, issuedAtMs = baseNow + 60_000L), nowMs = baseNow)
        assertEquals(OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.CLOCK_SKEW), skewed)

        // Terminal call blocks late signaling
        guard.markCallTerminal("call-1")
        val afterEnd = guard.validateInbound(sig(signalId = "s5", seq = 5L), nowMs = baseNow + 300L)
        assertEquals(OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.TERMINAL_CALL), afterEnd)

        // Revoked peer rejected on any call
        guard.markPeerRevoked("bob")
        val revoked = guard.validateInbound(sig(signalId = "s6", callId = "call-2", seq = 1L), nowMs = baseNow + 300L)
        assertEquals(OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.REVOKED_PEER), revoked)
    }

    @Test
    fun `coordinator handles off-LAN ring, decline idempotency, late signals, and active teardown on peer revocation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sentFrames = mutableListOf<Pair<String, CallWireFrame>>()
        val coordinator = CallCoordinator(
            localDeviceId = "alice",
            localName = "Alice",
            scope = scope,
            sendFrame = { frame, peerId ->
                sentFrames += peerId to frame
                true
            },
        )
        try {
            val now = 1_700_000_000_000L
            val invite = CallFrameCodec.encode(
                CallWireFrame.Invite(callId = "offlan-call-1", from = "bob", callerName = "Bob", video = true),
            )
            assertTrue(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-1", "offlan-call-1", "bob", seq = 1L, issuedAtMs = now, ttlMs = 15_000L, payloadText = invite),
                    nowMs = now,
                ),
            )
            val ringing = withTimeout(2_000L) {
                coordinator.activeCall.first { it?.state == FlashCallState.RINGING }
            }
            assertEquals(FlashCallState.RINGING, ringing?.state)

            // Duplicate invite signalId rejected safely before hitting coordinator state
            assertFalse(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-1", "offlan-call-1", "bob", seq = 2L, issuedAtMs = now, ttlMs = 15_000L, payloadText = invite),
                    nowMs = now + 50L,
                ),
            )

            // Decline and verify idempotent second decline
            assertTrue(coordinator.decline())
            assertFalse(coordinator.decline())
            assertTrue(sentFrames.any { it.first == "bob" && it.second is CallWireFrame.Decline })

            // Subsequent offer for already-terminated call must be rejected by guard
            val lateOffer = CallFrameCodec.encode(
                CallWireFrame.Offer(callId = "offlan-call-1", from = "bob", sdp = "v=0\r\n"),
            )
            assertFalse(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-3", "offlan-call-1", "bob", seq = 3L, issuedAtMs = now + 100L, ttlMs = 15_000L, payloadText = lateOffer),
                    nowMs = now + 150L,
                ),
            )

            // New call torn down immediately on peer revocation
            val invite2 = CallFrameCodec.encode(
                CallWireFrame.Invite(callId = "offlan-call-2", from = "bob", callerName = "Bob", video = false),
            )
            assertTrue(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-4", "offlan-call-2", "bob", seq = 1L, issuedAtMs = now + 500L, ttlMs = 15_000L, payloadText = invite2),
                    nowMs = now + 500L,
                ),
            )
            coordinator.terminateCallsForRevokedPeer("bob")
            assertFalse(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-5", "offlan-call-2", "bob", seq = 2L, issuedAtMs = now + 600L, ttlMs = 15_000L, payloadText = invite2),
                    nowMs = now + 600L,
                ),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `native PeerConnection accepts relay-only RTCConfiguration with TURN credentials and generates valid offer`() = runBlocking {
        assumeFalse(
            "native WebRTC smoke test skipped on CI (set FLASH_HW_TESTS=1 to run)",
            System.getenv("CI") == "true" && System.getenv("FLASH_HW_TESTS") != "1",
        )
        val caps = CallRouteCapabilities(
            isDirectLanReachable = false,
            isHubConnected = true,
            hubSupportsOffLanCalling = true,
            peerSupportsOffLanCalling = true,
            isPeerRevoked = false,
            mediaPolicy = CallMediaPathPolicy.RELAY_ONLY,
            iceServers = listOf(
                CallIceServerSpec(
                    urls = listOf("turn:127.0.0.1:3478?transport=udp"),
                    username = "1700000300:alice",
                    credential = "test-cred",
                ),
            ),
        )
        val pc = PeerConnection(caps.toRtcConfiguration())
        try {
            pc.createDataChannel("offlan-media-smoke")
            val offer = pc.createOffer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = false))
            assertEquals(SessionDescriptionType.Offer, offer.type)
            assertTrue(offer.sdp.contains("m=application"))
            pc.setLocalDescription(offer)
        } finally {
            pc.close()
        }
    }

    @Test
    fun `revoking peer terminates active group call leg and blocks late group signaling`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = CallCoordinator(
            localDeviceId = "alice",
            localName = "Alice",
            scope = scope,
            sendFrame = { _, _ -> true },
        )
        try {
            val now = 1_700_000_000_000L
            val groupInvite = CallFrameCodec.encode(
                CallWireFrame.GroupInvite(
                    callId = "grp-call-rev-1",
                    from = "bob",
                    groupId = "grp-1",
                    callerName = "Bob",
                    video = false,
                    members = listOf("alice", "bob"),
                ),
            )
            assertTrue(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("gsig-1", "grp-call-rev-1", "bob", seq = 1L, issuedAtMs = now, ttlMs = 15_000L, payloadText = groupInvite),
                    nowMs = now,
                ),
            )
            val ringing = withTimeout(2_000L) {
                coordinator.activeCall.first { it?.state == FlashCallState.RINGING && it.groupId == "grp-1" }
            }
            assertEquals("grp-1", ringing?.groupId)

            coordinator.terminateCallsForRevokedPeer("bob")
            withTimeout(2_000L) {
                coordinator.activeCall.first { it == null || it.state == FlashCallState.ENDED }
            }
            assertFalse(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("gsig-2", "grp-call-rev-1", "bob", seq = 2L, issuedAtMs = now + 50L, ttlMs = 15_000L, payloadText = groupInvite),
                    nowMs = now + 50L,
                ),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `captured invite for ended call wrapped in different envelope callId is rejected before replay state or dispatch while matching and LAN frames work`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sentFrames = mutableListOf<Pair<String, CallWireFrame>>()
        val coordinator = CallCoordinator(
            localDeviceId = "alice",
            localName = "Alice",
            scope = scope,
            sendFrame = { frame, peerId ->
                sentFrames += peerId to frame
                true
            },
        )
        try {
            val now = 1_700_000_000_000L
            val capturedInvite = CallFrameCodec.encode(
                CallWireFrame.Invite(
                    callId = "call-ended-1",
                    from = "bob",
                    callerName = "Bob",
                    video = false,
                ),
            )
            // Deliver initial call cleanly and terminate it via decline
            assertTrue(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-1", "call-ended-1", "bob", seq = 1L, issuedAtMs = now, ttlMs = 15_000L, payloadText = capturedInvite),
                    nowMs = now,
                ),
            )
            withTimeout(2_000L) {
                coordinator.activeCall.first { it?.state == FlashCallState.RINGING }
            }
            assertTrue(coordinator.decline())
            withTimeout(2_000L) {
                coordinator.activeCall.first { it == null || it.state == FlashCallState.ENDED }
            }

            // Attacker wraps the captured invite for terminal call-ended-1 in a brand-new envelope callId ("call-fresh-env")
            assertFalse(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-new-env", "call-fresh-env", "bob", seq = 1L, issuedAtMs = now + 100L, ttlMs = 15_000L, payloadText = capturedInvite),
                    nowMs = now + 100L,
                ),
            )
            assertFalse(coordinator.activeCall.value?.callId == "call-ended-1" && coordinator.activeCall.value?.state == FlashCallState.RINGING)

            // Prove replay state (sig-new-env, seq=1) was NOT recorded for call-fresh-env: a valid matching invite still succeeds
            val matchingInvite = CallFrameCodec.encode(
                CallWireFrame.Invite(
                    callId = "call-fresh-env",
                    from = "bob",
                    callerName = "Bob",
                    video = false,
                ),
            )
            assertTrue(
                coordinator.onInboundOffLanSignal(
                    InboundOffLanCallSignal("sig-new-env", "call-fresh-env", "bob", seq = 1L, issuedAtMs = now + 120L, ttlMs = 15_000L, payloadText = matchingInvite),
                    nowMs = now + 120L,
                ),
            )
            val ringing = withTimeout(2_000L) {
                coordinator.activeCall.first { it?.callId == "call-fresh-env" && it.state == FlashCallState.RINGING }
            }
            assertEquals("call-fresh-env", ringing?.callId)
            assertTrue(coordinator.decline())

            // Direct LAN signaling continues working normally
            val lanInvite = CallFrameCodec.encode(
                CallWireFrame.Invite(
                    callId = "lan-call-1",
                    from = "carol",
                    callerName = "Carol",
                    video = false,
                ),
            )
            assertTrue(coordinator.onInboundText("carol", lanInvite))
            val lanRinging = withTimeout(2_000L) {
                coordinator.activeCall.first { it?.callId == "lan-call-1" && it.state == FlashCallState.RINGING }
            }
            assertEquals("lan-call-1", lanRinging?.callId)
        } finally {
            scope.cancel()
        }
    }
}
