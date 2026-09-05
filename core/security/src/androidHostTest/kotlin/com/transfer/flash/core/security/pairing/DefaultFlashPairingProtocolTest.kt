package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.testutil.FakeClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFlashPairingProtocolTest {

    private val localFp = "aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000"
    private val peerFp = "bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111"
    private val timeouts = PairingTimeouts(requestExpiryMs = 30_000, decisionWindowMs = 20_000)

    /** One device under test: protocol + captured outbound frames + shared clock. */
    private class Party(
        _name: String,
        deviceId: String,
        fingerprintHex: String,
        clock: FakeClock,
        timeouts: PairingTimeouts,
    ) {
        val protocol = DefaultFlashPairingProtocol(
            localFingerprintHex = fingerprintHex,
            localDeviceId = deviceId,
            localName = _name,
            localModel = "Flash-Test-1",
            ephemeralPublicKeyProvider = { byteArrayOf(deviceId[0].code.toByte(), 42) },
            sendFrame = { frame -> outgoing.add(frame) },
            timeSource = clock,
            timeouts = timeouts,
        )
        val outgoing = mutableListOf<FlashPairingFrame>()
        var forwarded = 0

        /** Delivers every not-yet-forwarded outbound frame to [peer] (fake transport). */
        suspend fun flushTo(peer: Party) {
            while (forwarded < outgoing.size) {
                peer.protocol.onFrame(outgoing[forwarded])
                forwarded++
            }
        }
    }

    @Test
    fun `beginRequest emits PAIR_REQUEST and enters AwaitingPeerConfirmation`() = runTest {
        val clock = FakeClock(1_000)
        val initiator = Party("Alice", "dev-a", localFp, clock, timeouts)

        val result = initiator.protocol.beginRequest("dev-b", "Bob", peerFp)

        assertTrue(result is FlashResult.Success)
        assertEquals(PairingPhase.AwaitingPeerConfirmation, initiator.protocol.session.value.phase)
        val frame = initiator.outgoing.single()
        assertTrue(frame is FlashPairingFrame.PairRequest)
        frame as FlashPairingFrame.PairRequest
        assertEquals("dev-a", frame.senderDeviceId)
        assertEquals(localFp, frame.senderFingerprintHex)
        assertTrue(frame.senderEphemeralPublicKey.contentEquals(byteArrayOf('d'.code.toByte(), 42)))
        assertEquals(1_000, frame.createdAt)
    }

    @Test
    fun `full handshake - both sides reach Confirmed with matching codes`() = runTest {
        val clock = FakeClock(1_000)
        val initiator = Party("Alice", "dev-a", localFp, clock, timeouts)
        val responder = Party("Bob", "dev-b", peerFp, clock, timeouts)

        val initiatorEvents = mutableListOf<FlashPairingEvent>()
        val responderEvents = mutableListOf<FlashPairingEvent>()
        val iJob = launch(start = CoroutineStart.UNDISPATCHED) { initiator.protocol.events.collect { initiatorEvents += it } }
        val rJob = launch(start = CoroutineStart.UNDISPATCHED) { responder.protocol.events.collect { responderEvents += it } }

        assertTrue(initiator.protocol.beginRequest("dev-b", "Bob", peerFp) is FlashResult.Success)
        initiator.flushTo(responder) // PAIR_REQUEST
        testScheduler.runCurrent() // pump UNDISPATCHED event collectors

        // Responder sees the request with the shared display code.
        val requestEvent = responderEvents.filterIsInstance<FlashPairingEvent.RequestReceived>().single()
        assertEquals(6, requestEvent.code6.length)
        assertEquals(clock.nowMs() + timeouts.requestExpiryMs, requestEvent.expiresAtMs)
        assertEquals(requestEvent.code6, responder.protocol.session.value.code6)

        assertTrue(responder.protocol.respondAccept() is FlashResult.Success)
        responder.flushTo(initiator) // PAIR_ACCEPT
        assertTrue(initiator.protocol.session.value.peerAccepted)

        // Initiator proves it derived the SAME code — PAIR_CONFIRM goes to the responder,
        // whose onFrame(PairConfirm) handler auto-sends PAIRED back on confirmation.
        val codeSeenByInitiator = initiator.protocol.session.value.code6!!
        assertEquals(requestEvent.code6, codeSeenByInitiator) // role symmetry on display
        responder.protocol.onFrame(
            FlashPairingFrame.PairConfirm(
                requestId = responder.protocol.session.value.requestId!!,
                codeHashHex = NumericComparisonCode.confirmationHashHex(codeSeenByInitiator),
            ),
        )
        assertEquals(PairingPhase.Confirmed, responder.protocol.session.value.phase)
        responder.flushTo(initiator) // PAIRED back
        testScheduler.runCurrent() // pump UNDISPATCHED event collectors

        assertEquals(PairingPhase.Confirmed, responder.protocol.session.value.phase)
        assertEquals(PairingPhase.Confirmed, initiator.protocol.session.value.phase)
        assertEquals(peerFp, initiator.protocol.session.value.peerFingerprintHex)

        assertTrue(initiatorEvents.any { it is FlashPairingEvent.PeerAccepted })
        assertTrue(initiatorEvents.any { it is FlashPairingEvent.Confirmed })
        assertTrue(responderEvents.any { it is FlashPairingEvent.Confirmed })
        iJob.cancel()
        rJob.cancel()
    }

    @Test
    fun `tampered confirmation hash hard-fails the responder`() = runTest {
        val clock = FakeClock(1_000)
        val initiator = Party("Alice", "dev-a", localFp, clock, timeouts)
        val responder = Party("Bob", "dev-b", peerFp, clock, timeouts)
        val responderEvents = mutableListOf<FlashPairingEvent>()
        val rJob = launch(start = CoroutineStart.UNDISPATCHED) { responder.protocol.events.collect { responderEvents += it } }

        initiator.protocol.beginRequest("dev-b", "Bob", peerFp)
        initiator.flushTo(responder)
        responder.protocol.respondAccept()

        responder.protocol.onFrame(
            FlashPairingFrame.PairConfirm(
                requestId = responder.protocol.session.value.requestId!!,
                codeHashHex = NumericComparisonCode.confirmationHashHex("999999"),
            ),
        )
        testScheduler.runCurrent() // pump UNDISPATCHED event collectors

        assertEquals(PairingPhase.Failed, responder.protocol.session.value.phase)
        assertEquals("code-hash-mismatch", responder.protocol.session.value.failureReason)
        assertTrue(responderEvents.any { it is FlashPairingEvent.Failed })
        assertTrue(responder.outgoing.none { it is FlashPairingFrame.Paired })
        rJob.cancel()
    }

    @Test
    fun `request expires neutrally when engine ticks past deadline`() = runTest {
        val clock = FakeClock(1_000)
        val responder = Party("Bob", "dev-b", peerFp, clock, timeouts)
        val events = mutableListOf<FlashPairingEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { responder.protocol.events.collect { events += it } }

        val initiator = Party("Alice", "dev-a", localFp, clock, timeouts)
        initiator.protocol.beginRequest("dev-b", "Bob", peerFp)
        initiator.flushTo(responder)
        assertEquals(PairingPhase.RequestReceived, responder.protocol.session.value.phase)

        clock.setTo(31_000)
        responder.protocol.onTick(clock.nowMs())
        testScheduler.runCurrent() // pump UNDISPATCHED event collectors

        assertEquals(PairingPhase.Expired, responder.protocol.session.value.phase)
        assertTrue(events.last() is FlashPairingEvent.Expired)
        job.cancel()
    }

    @Test
    fun `second beginRequest while busy fails without touching the session`() = runTest {
        val clock = FakeClock(0)
        val initiator = Party("Alice", "dev-a", localFp, clock, timeouts)
        initiator.protocol.beginRequest("dev-b", "Bob", peerFp)
        val before = initiator.protocol.session.value

        val second = initiator.protocol.beginRequest("dev-c", "Carol", peerFp)

        assertTrue(second is FlashResult.Failure)
        assertEquals(before.requestId, initiator.protocol.session.value.requestId)
    }

    @Test
    fun `respondDecline resets to Idle`() = runTest {
        val clock = FakeClock(0)
        val initiator = Party("Alice", "dev-a", localFp, clock, timeouts)
        val responder = Party("Bob", "dev-b", peerFp, clock, timeouts)
        initiator.protocol.beginRequest("dev-b", "Bob", peerFp)
        initiator.flushTo(responder)

        assertTrue(responder.protocol.respondDecline() is FlashResult.Success)
        assertEquals(PairingPhase.Idle, responder.protocol.session.value.phase)
    }

    @Test
    fun `respondAccept without an active request fails cleanly`() = runTest {
        val clock = FakeClock(0)
        val responder = Party("Bob", "dev-b", peerFp, clock, timeouts)

        val result = responder.protocol.respondAccept()

        assertTrue(result is FlashResult.Failure)
    }
}
