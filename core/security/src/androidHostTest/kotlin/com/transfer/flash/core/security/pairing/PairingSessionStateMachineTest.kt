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
    )

    private fun reduce(state: PairingSessionState, event: PairingSessionEvent) =
        PairingSessionStateMachine.reduce(state, event, timeouts, localFp)

    // ------------------------------------------------------------- entry

    @Test
    fun `requestReceived from Idle enters RequestReceived with code and deadline`() {
        val s = reduce(PairingSessionState.IDLE, requestEvent())
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
    fun `code is role independent - responder and initiator derive the same display code`() {
        val responderCode = reduce(PairingSessionState.IDLE, requestEvent()).code6
        val initiatorCode = reduce(
            PairingSessionState.IDLE,
            PairingSessionEvent.BeginRequested(
                "req-2",
                "peer-1",
                "Pixel 9",
                peerFp,
                receivedAt,
            ),
        ).code6
        assertEquals(responderCode, initiatorCode)
    }

    @Test
    fun `second requestReceived while busy is ignored`() {
        val first = reduce(PairingSessionState.IDLE, requestEvent())
        val second = reduce(first, requestEvent(requestId = "req-2"))
        assertEquals("req-1", second.requestId)
        assertEquals(PairingPhase.RequestReceived, second.phase)
    }

    // ------------------------------------------ decision window / accept

    @Test
    fun `promptShown starts the per-side decision window`() {
        val requested = reduce(PairingSessionState.IDLE, requestEvent())
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
            reduce(PairingSessionState.IDLE, requestEvent()),
            PairingSessionEvent.LocalAccept,
        )
        assertEquals(PairingPhase.AwaitingPeerConfirmation, accepted.phase)
        assertNull(accepted.decisionDeadlineMs)
    }

    @Test
    fun `localAccept from AwaitingLocalDecision also works`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.PromptShown(receivedAt))
        s = reduce(s, PairingSessionEvent.LocalAccept)
        assertEquals(PairingPhase.AwaitingPeerConfirmation, s.phase)
    }

    @Test
    fun `localDecline resets to Idle - decline is not an error`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.LocalDecline)
        assertEquals(PairingPhase.Idle, s.phase)
        assertNull(s.requestId)
        assertNull(s.failureReason)
    }

    // --------------------------------------------------- confirmation path

    @Test
    fun `matching PeerConfirmed completes the session as Confirmed`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.LocalAccept)
        val expectedHash = NumericComparisonCode.confirmationHashHex(s.code6!!)
        s = reduce(s, PairingSessionEvent.PeerConfirmed(expectedHash.uppercase()))
        assertEquals(PairingPhase.Confirmed, s.phase)
        assertNull(s.failureReason)
    }

    @Test
    fun `mismatched PeerConfirmed hard-fails with code-hash-mismatch`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.LocalAccept)
        val wrongHash = NumericComparisonCode.confirmationHashHex("000000")
        s = reduce(s, PairingSessionEvent.PeerConfirmed(wrongHash))
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("code-hash-mismatch", s.failureReason)
    }

    @Test
    fun `initiator path - Paired frame confirms and carries peer key material`() {
        var s = reduce(
            PairingSessionState.IDLE,
            PairingSessionEvent.BeginRequested("req-9", "peer-1", null, peerFp, receivedAt),
        )
        assertEquals(PairingPhase.AwaitingPeerConfirmation, s.phase)
        s = reduce(s, PairingSessionEvent.PeerAccepted("req-9"))
        assertTrue(s.peerAccepted)
        s = reduce(s, PairingSessionEvent.Paired(peerFp, byteArrayOf(9, 8)))
        assertEquals(PairingPhase.Confirmed, s.phase)
        assertTrue(s.peerEphemeralPublicKey!!.contentEquals(byteArrayOf(9, 8)))
    }

    @Test
    fun `stale PeerAccepted requestId ignored in AwaitingPeerConfirmation`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.LocalAccept)
        s = reduce(s, PairingSessionEvent.PeerAccepted("other-request"))
        assertFalse(s.peerAccepted)
        assertEquals(PairingPhase.AwaitingPeerConfirmation, s.phase)
    }

    @Test
    fun `PeerConfirmed before local accept is ignored`() {
        val s = reduce(PairingSessionState.IDLE, requestEvent())
        val after = reduce(s, PairingSessionEvent.PeerConfirmed(s.expectedCodeHashHex!!))
        assertEquals(PairingPhase.RequestReceived, after.phase)
    }

    // ------------------------------------------------------------ decline

    @Test
    fun `PeerDeclined while awaiting lands in DeclinedByPeer`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.LocalAccept)
        s = reduce(s, PairingSessionEvent.PeerDeclined)
        assertEquals(PairingPhase.DeclinedByPeer, s.phase)
    }

    @Test
    fun `PeerDeclined while undecided is ignored`() {
        val s = reduce(PairingSessionState.IDLE, requestEvent())
        assertEquals(PairingPhase.RequestReceived, reduce(s, PairingSessionEvent.PeerDeclined).phase)
    }

    // ------------------------------------------------------------- expiry

    @Test
    fun `tick past request expiry expires from RequestReceived - neutral outcome`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 29_999))
        assertEquals(PairingPhase.RequestReceived, s.phase)
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 30_000)) // boundary counts
        assertEquals(PairingPhase.Expired, s.phase)
        assertNull(s.failureReason)
    }

    @Test
    fun `decision window expiring earlier than request expiry still expires`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.PromptShown(receivedAt + 10_000)) // deadline +25s
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 24_999))
        assertEquals(PairingPhase.AwaitingLocalDecision, s.phase)
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 25_000))
        assertEquals(PairingPhase.Expired, s.phase)
    }

    @Test
    fun `awaiting peer confirmation can also expire`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
        s = reduce(s, PairingSessionEvent.LocalAccept)
        s = reduce(s, PairingSessionEvent.Tick(receivedAt + 30_000))
        assertEquals(PairingPhase.Expired, s.phase)
    }

    @Test
    fun `Expired is distinct from Failed`() {
        var s = reduce(PairingSessionState.IDLE, requestEvent())
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
        var s = reduce(PairingSessionState.IDLE, requestEvent())
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
        val blankFpEvent = PairingSessionEvent.RequestReceived(
            requestId = "req-1",
            peerDeviceId = "peer-1",
            peerName = "Pixel 9",
            peerFingerprintHex = ":::",
            peerEphemeralPublicKey = peerKey,
            receivedAtMs = receivedAt,
        )
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
        var s = reduce(
            PairingSessionState.IDLE,
            PairingSessionEvent.BeginRequested("req-9", "peer-1", null, peerFp, receivedAt),
        )
        s = reduce(s, PairingSessionEvent.PeerAccepted("req-9"))
        s = reduce(s, PairingSessionEvent.Paired("", byteArrayOf(9, 8)))
        assertEquals(PairingPhase.Failed, s.phase)
        assertEquals("invalid-fingerprint", s.failureReason)
    }

    // ----------------------------------------------------------- terminal

    @Test
    fun `terminal states absorb all further events`() {
        val terminals = listOf(
            reduce(
                reduce(reduce(PairingSessionState.IDLE, requestEvent()), PairingSessionEvent.LocalAccept),
                PairingSessionEvent.PeerConfirmed(
                    NumericComparisonCode.confirmationHashHex(
                        reduce(reduce(PairingSessionState.IDLE, requestEvent()), PairingSessionEvent.LocalAccept).code6!!,
                    ),
                ),
            ),
            run {
                var s = reduce(PairingSessionState.IDLE, requestEvent())
                s = reduce(s, PairingSessionEvent.LocalAccept)
                reduce(s, PairingSessionEvent.PeerDeclined)
            },
            reduce(
                reduce(PairingSessionState.IDLE, requestEvent()),
                PairingSessionEvent.Tick(receivedAt + 99_999),
            ),
            reduce(
                reduce(PairingSessionState.IDLE, requestEvent()),
                PairingSessionEvent.LocalAccept,
            ).let { reduce(it, PairingSessionEvent.PeerConfirmed("deadbeef")) },
        )
        for (terminal in terminals) {
            val after = reduce(terminal, requestEvent(requestId = "req-new"))
            assertEquals(terminal, after)
        }
    }
}
