package com.transfer.flash.core.security.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSessionStateMachineTest {

    private val localFp = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"
    private val peerFp = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222"
    private val timeouts = PairingTimeouts(requestExpiryMs = 30_000, decisionWindowMs = 15_000)
    private val receivedAt = 1_000_000L
    private val peerKey = byteArrayOf(1, 2, 3)

    // v2 material (ADR-042). The peer is the initiator in the responder-side helpers.
    private val localKey = byteArrayOf(4, 5, 6)
    private val initiatorNonce = ByteArray(PairingV2.NONCE_BYTES) { 1 }
    private val responderNonce = ByteArray(PairingV2.NONCE_BYTES) { 2 }

    /** A valid v2 PAIR_REQUEST from peer-1, whose fingerprint TLS pinned. */
    private fun requestEvent(
        requestId: String = "req-1",
        at: Long = receivedAt,
    ) = PairingSessionEvent.RequestReceived(
        requestId = requestId,
        peerDeviceId = "peer-1",
        peerName = "Pixel 9",
        peerFingerprintHex = peerFp,
        peerEphemeralPublicKey = peerKey,
        receivedAtMs = at,
        protocolVersion = PairingV2.PROTOCOL_VERSION,
        peerCommitHex = PairingV2.commitHex(peerFp, peerKey, initiatorNonce),
        pinnedPeerFingerprintHex = peerFp,
        localEphemeralPublicKey = localKey,
        localNonce = responderNonce,
    )

    private fun reduce(state: PairingSessionState, event: PairingSessionEvent) =
        PairingSessionStateMachine.reduce(state, event, timeouts, localFp)

    /** Responder with the code on screen: valid request, then the initiator opens its commitment. */
    private fun requested(requestId: String = "req-1", at: Long = receivedAt): PairingSessionState =
        reduce(reduce(PairingSessionState.IDLE, requestEvent(requestId, at)), PairingSessionEvent.PeerReveal(requestId, initiatorNonce))

    /** Initiator with the code derived: request sent, then the responder's nonce arrives. */
    private fun initiated(requestId: String = "req-9"): PairingSessionState =
        reduce(
            reduce(
                PairingSessionState.IDLE,
                PairingSessionEvent.BeginRequested(requestId, "peer-1", null, peerFp, receivedAt, localKey, initiatorNonce),
            ),
            PairingSessionEvent.PeerNonce(requestId, peerFp, peerKey, responderNonce, pinnedPeerFingerprintHex = peerFp),
        )

    // ------------------------------------------------------------- entry

    @Test
    fun `requestReceived from Idle enters RequestReceived with code and deadline`() {
        val s = requested()
        assertEquals(PairingPhase.RequestReceived, s.phase)
        assertEquals("req-1", s.requestId)
        assertEquals("peer-1", s.peerDeviceId)
        assertEquals(peerFp, s.peerFingerprintHex)
        assertTrue(s.code6!!.length == 6)
        assertEquals(NumericComparisonCode.confirmationHashHex(s.code6), s.expectedCodeHashHex)
        assertEquals(receivedAt + 30_000, s.expiresAtMs)
        assertNull(s.decisionDeadlineMs)
        assertFalse(s.peerAccepted)
    }

    @Test
    fun `initiator and responder derive the same code from the same v2 exchange`() {
        val responderCode = requested().code6
        // The same exchange seen from the initiator (peerFp's device): local and peer swap roles.
        val asInitiator = { st: PairingSessionState, ev: PairingSessionEvent ->
            PairingSessionStateMachine.reduce(st, ev, timeouts, peerFp)
        }
        var s = asInitiator(
            PairingSessionState.IDLE,
            PairingSessionEvent.BeginRequested("req-1", "local-1", null, localFp, receivedAt, peerKey, initiatorNonce),
        )
        s = asInitiator(s, PairingSessionEvent.PeerNonce("req-1", localFp, localKey, responderNonce, localFp))
        assertEquals(responderCode, s.code6)
    }

    @Test
    fun `no code exists before the initiator opens its commitment`() {
        val waiting = reduce(PairingSessionState.IDLE, requestEvent())
        assertEquals(PairingPhase.AwaitingPeerReveal, waiting.phase)
        assertNull(waiting.code6)
    }

    @Test
    fun `v1 request without version or commitment is refused`() {
        val v1 = requestEvent().copy(protocolVersion = 1, peerCommitHex = null)
        val s = reduce(PairingSessionState.IDLE, v1)
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("peer-update-required", s.failureReason)
    }

    @Test
    fun `request whose fingerprint is not the TLS-pinned key is refused`() {
        val s = reduce(PairingSessionState.IDLE, requestEvent().copy(pinnedPeerFingerprintHex = localFp))
        assertEquals("identity-mismatch", s.failureReason)
    }

    @Test
    fun `request with no TLS pin at all is refused`() {
        val s = reduce(PairingSessionState.IDLE, requestEvent().copy(pinnedPeerFingerprintHex = null))
        assertEquals("identity-mismatch", s.failureReason)
    }

    @Test
    fun `reveal that does not open the commitment fails`() {
        val s = reduce(
            reduce(PairingSessionState.IDLE, requestEvent()),
            PairingSessionEvent.PeerReveal("req-1", ByteArray(PairingV2.NONCE_BYTES) { 9 }),
        )
        assertEquals("commit-mismatch", s.failureReason)
        assertNull(s.code6)
    }

    @Test
    fun `nonce whose fingerprint is not the TLS-pinned key fails the initiator`() {
        var s = reduce(
            PairingSessionState.IDLE,
            PairingSessionEvent.BeginRequested("req-9", "peer-1", null, peerFp, receivedAt, localKey, initiatorNonce),
        )
        s = reduce(s, PairingSessionEvent.PeerNonce("req-9", peerFp, peerKey, responderNonce, pinnedPeerFingerprintHex = localFp))
        assertEquals("identity-mismatch", s.failureReason)
    }

    @Test
    fun `PAIRED with a different ephemeral key than the code covered fails`() {
        var s = reduce(initiated(), PairingSessionEvent.PeerAccepted("req-9"))
        s = reduce(s, PairingSessionEvent.Paired(peerFp, byteArrayOf(7, 7)))
        assertEquals("paired-material-mismatch", s.failureReason)
    }

    @Test
    fun `second requestReceived while busy is ignored`() {
        val first = requested()
        val second = reduce(first, requestEvent(requestId = "req-2"))
        assertEquals("req-1", second.requestId)
        assertEquals(PairingPhase.RequestReceived, second.phase)
    }

    // ------------------------------------------ decision window / accept

    @Test
    fun `promptShown starts the per-side decision window`() {
        val requested = requested()
        val shown = reduce(requested, PairingSessionEvent.PromptShown(nowMs = receivedAt + 100))
        assertEquals(PairingPhase.AwaitingLocalDecision, shown.phase)
        assertEquals(receivedAt + 100 + 15_000, shown.decisionDeadlineMs)
    }

    @Test
    fun `promptShown outside RequestReceived is ignored`() {
        val unchanged = reduce(PairingSessionState.IDLE, PairingSessionEvent.PromptShown(0))
        assertEquals(PairingPhase.Idle, unchanged.phase)
    }

    @Test
    fun `localAccept from RequestReceived moves to AwaitingPeerConfirmation`() {
        val accepted = reduce(
            requested(),
            PairingSessionEvent.LocalAccept,
        )
        assertEquals(PairingPhase.AwaitingPeerConfirmation, accepted.phase)
        assertNull(accepted.decisionDeadlineMs)
    }

    @Test
    fun `localAccept from AwaitingLocalDecision also works`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.PromptShown(receivedAt))
        s = reduce(s, PairingSessionEvent.LocalAccept)
        assertEquals(PairingPhase.AwaitingPeerConfirmation, s.phase)
    }

    @Test
    fun `localDecline resets to Idle - decline is not an error`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.LocalDecline)
        assertEquals(PairingPhase.Idle, s.phase)
        assertNull(s.requestId)
        assertNull(s.failureReason)
    }

    // --------------------------------------------------- confirmation path

    @Test
    fun `matching PeerConfirmed completes the session as Confirmed`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.LocalAccept)
        val expectedHash = NumericComparisonCode.confirmationHashHex(s.code6!!)
        s = reduce(s, PairingSessionEvent.PeerConfirmed(expectedHash.uppercase()))
        assertEquals(PairingPhase.Confirmed, s.phase)
        assertNull(s.failureReason)
    }

    @Test
    fun `mismatched PeerConfirmed hard-fails with code-hash-mismatch`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.LocalAccept)
        val wrongHash = NumericComparisonCode.confirmationHashHex("000000")
        s = reduce(s, PairingSessionEvent.PeerConfirmed(wrongHash))
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("code-hash-mismatch", s.failureReason)
    }

    @Test
    fun `initiator path - Paired frame confirms and carries peer key material`() {
        var s = initiated()
        assertEquals(PairingPhase.AwaitingPeerConfirmation, s.phase)
        s = reduce(s, PairingSessionEvent.PeerAccepted("req-9"))
        assertTrue(s.peerAccepted)
        s = reduce(s, PairingSessionEvent.Paired(peerFp, peerKey))
        assertEquals(PairingPhase.Confirmed, s.phase)
        assertTrue(s.peerEphemeralPublicKey!!.contentEquals(peerKey))
    }

    @Test
    fun `stale PeerAccepted requestId ignored in AwaitingPeerConfirmation`() {
        var s = initiated()
        s = reduce(s, PairingSessionEvent.PeerAccepted("other-request"))
        assertFalse(s.peerAccepted)
        assertEquals(PairingPhase.AwaitingPeerConfirmation, s.phase)
    }

    @Test
    fun `PeerConfirmed before local accept is ignored`() {
        val s = requested()
        val after = reduce(s, PairingSessionEvent.PeerConfirmed(s.expectedCodeHashHex!!))
        assertEquals(PairingPhase.RequestReceived, after.phase)
    }

    // ------------------------------------------------------------ decline

    @Test
    fun `PeerDeclined while awaiting lands in DeclinedByPeer`() {
        var s = initiated()
        s = reduce(s, PairingSessionEvent.PeerDeclined)
        assertEquals(PairingPhase.DeclinedByPeer, s.phase)
    }

    @Test
    fun `PeerDeclined while undecided is ignored`() {
        val s = requested()
        assertEquals(PairingPhase.RequestReceived, reduce(s, PairingSessionEvent.PeerDeclined).phase)
    }

    // ------------------------------------------------------------- expiry

    @Test
    fun `tick past request expiry expires from RequestReceived - neutral outcome`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 29_999))
        assertEquals(PairingPhase.RequestReceived, s.phase)
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 30_000)) // boundary counts
        assertEquals(PairingPhase.Expired, s.phase)
        assertNull(s.failureReason)
    }

    @Test
    fun `decision window expiring earlier than request expiry still expires`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.PromptShown(receivedAt + 10_000)) // deadline +25s
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 24_999))
        assertEquals(PairingPhase.AwaitingLocalDecision, s.phase)
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 25_000))
        assertEquals(PairingPhase.Expired, s.phase)
    }

    @Test
    fun `awaiting peer confirmation can also expire`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.LocalAccept)
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 30_000))
        assertEquals(PairingPhase.Expired, s.phase)
    }

    @Test
    fun `Expired is distinct from Failed`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 60_000))
        assertEquals(PairingPhase.Expired, s.phase)
        assertNotEquals(PairingPhase.Failed, s.phase)
        assertNull(s.failureReason)
    }

    @Test
    fun `ticks while Idle are no-ops`() {
        val s = reduce(PairingSessionState.IDLE, PairingSessionEvent.Tick(Long.MAX_VALUE))
        assertEquals(PairingPhase.Idle, s.phase)
    }

    // ------------------------------------------------------------ failure

    @Test
    fun `protocolError during active session fails with reason`() {
        var s = requested()
        s = reduce(s, PairingSessionEvent.ProtocolError("bad-frame"))
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("bad-frame", s.failureReason)
    }

    @Test
    fun `protocolError while idle is ignored`() {
        val s = reduce(PairingSessionState.IDLE, PairingSessionEvent.ProtocolError("noise"))
        assertEquals(PairingPhase.Idle, s.phase)
    }

    @Test
    fun `requestReceived with blank or invalid fingerprint fails closed`() {
        val blankFpEvent = requestEvent().copy(peerFingerprintHex = ":::")
        val s = reduce(PairingSessionState.IDLE, blankFpEvent)
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("invalid-fingerprint", s.failureReason)
    }

    @Test
    fun `beginRequested with blank or invalid fingerprint fails closed`() {
        val event = PairingSessionEvent.BeginRequested(
            requestId = "req-1",
            peerDeviceId = "peer-1",
            peerName = "Pixel 9",
            peerFingerprintHex = "   ",
            startedAtMs = receivedAt,
        )
        val s = reduce(PairingSessionState.IDLE, event)
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("invalid-fingerprint", s.failureReason)
    }

    @Test
    fun `paired with blank or invalid fingerprint fails closed`() {
        var s = reduce(initiated(), PairingSessionEvent.PeerAccepted("req-9"))
        s = reduce(s, PairingSessionEvent.Paired("", peerKey))
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("invalid-fingerprint", s.failureReason)
    }

    // ----------------------------------------------------------- terminal

    @Test
    fun `terminal states absorb all further events`() {
        val terminals = listOf(
            reduce(
                reduce(requested(), PairingSessionEvent.LocalAccept),
                PairingSessionEvent.PeerConfirmed(
                    NumericComparisonCode.confirmationHashHex(
                        reduce(requested(), PairingSessionEvent.LocalAccept).code6!!,
                    ),
                ),
            ),
            reduce(initiated(), PairingSessionEvent.PeerDeclined),
            reduce(
                requested(),
                PairingSessionEvent.Tick(receivedAt + 99_999),
            ),
            reduce(
                requested(),
                PairingSessionEvent.LocalAccept,
            ).let { reduce(it, PairingSessionEvent.PeerConfirmed("deadbeef")) },
        )
        for (terminal in terminals) {
            val after = reduce(terminal, requestEvent(requestId = "req-new"))
            assertEquals(terminal, after)
        }
    }
}
