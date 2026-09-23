package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.testutil.FakeClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for pairing v2 (ADR-042): two [DefaultFlashPairingProtocol] instances wired
 * back-to-back through captured frames, with a pin map standing in for what TLS pinned per peer.
 * The adversarial cases are the reason v2 exists: a forced code, a substituted ephemeral key, a
 * fingerprint that is not the TLS-authenticated key, and a v1 peer.
 */
class DefaultFlashPairingProtocolTest {

    private val aliceFp = "aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000"
    private val bobFp = "bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111"
    private val timeouts = PairingTimeouts(requestExpiryMs = 30_000, decisionWindowMs = 20_000)

    /** Both sides pinned the other's real key during the TLS handshake. */
    private val honestPins = mapOf("dev-a" to aliceFp, "dev-b" to bobFp)

    private class Party(
        name: String,
        deviceId: String,
        fingerprintHex: String,
        clock: FakeClock,
        timeouts: PairingTimeouts,
        pins: Map<String, String>,
        val ephemeralKey: ByteArray = byteArrayOf(deviceId.last().code.toByte(), 42),
    ) {
        val outgoing = mutableListOf<FlashPairingFrame>()
        var forwarded = 0
        val protocol = DefaultFlashPairingProtocol(
            localFingerprintHex = fingerprintHex,
            localDeviceId = deviceId,
            localName = name,
            localModel = "Flash-Test-1",
            ephemeralPublicKeyProvider = { ephemeralKey },
            sendFrame = { frame -> outgoing.add(frame) },
            peerIdentityPin = { peerId -> pins[peerId] },
            timeSource = clock,
            timeouts = timeouts,
        )

        /** Delivers every not-yet-forwarded outbound frame to [peer], optionally rewriting it (a MITM). */
        suspend fun flushTo(peer: Party, rewrite: (FlashPairingFrame) -> FlashPairingFrame = { it }) {
            while (forwarded < outgoing.size) {
                peer.protocol.onFrame(rewrite(outgoing[forwarded]))
                forwarded++
            }
        }
    }

    private fun alice(clock: FakeClock, pins: Map<String, String> = honestPins) =
        Party("Alice", "dev-a", aliceFp, clock, timeouts, pins)

    private fun bob(clock: FakeClock, pins: Map<String, String> = honestPins) =
        Party("Bob", "dev-b", bobFp, clock, timeouts, pins)

    /** Runs request → nonce → reveal. Afterwards both sides hold a code (or have failed). */
    private suspend fun exchangeNonces(i: Party, r: Party) {
        assertTrue(i.protocol.beginRequest("dev-b", "Bob", bobFp) is FlashResult.Success)
        i.flushTo(r) // PAIR_REQUEST
        r.flushTo(i) // PAIR_NONCE
        i.flushTo(r) // PAIR_REVEAL
    }

    @Test
    fun `beginRequest sends a v2 request with a commitment and shows no code yet`() = runTest {
        val clock = FakeClock(1_000)
        val initiator = alice(clock)

        assertTrue(initiator.protocol.beginRequest("dev-b", "Bob", bobFp) is FlashResult.Success)

        assertEquals(PairingPhase.AwaitingPeerNonce, initiator.protocol.session.value.phase)
        assertNull("no code before both nonces exist", initiator.protocol.session.value.code6)
        val frame = initiator.outgoing.single() as FlashPairingFrame.PairRequest
        assertEquals(PairingV2.PROTOCOL_VERSION, frame.protocolVersion)
        assertTrue("commitment present", !frame.commitHex.isNullOrBlank())
        assertEquals(aliceFp, frame.senderFingerprintHex)
        assertEquals(1_000, frame.createdAt)
    }

    @Test
    fun `full v2 handshake - both sides show the same code and reach Confirmed`() = runTest {
        val clock = FakeClock(1_000)
        val initiator = alice(clock)
        val responder = bob(clock)
        val initiatorEvents = mutableListOf<FlashPairingEvent>()
        val responderEvents = mutableListOf<FlashPairingEvent>()
        val iJob = launch(start = CoroutineStart.UNDISPATCHED) { initiator.protocol.events.collect { initiatorEvents += it } }
        val rJob = launch(start = CoroutineStart.UNDISPATCHED) { responder.protocol.events.collect { responderEvents += it } }

        exchangeNonces(initiator, responder)
        testScheduler.runCurrent()

        val request = responderEvents.filterIsInstance<FlashPairingEvent.RequestReceived>().single()
        assertEquals(6, request.code6.length)
        assertEquals("both screens show the same code", request.code6, initiator.protocol.session.value.code6)

        assertTrue(responder.protocol.respondAccept() is FlashResult.Success)
        responder.flushTo(initiator) // PAIR_ACCEPT
        assertTrue(initiator.protocol.session.value.peerAccepted)
        responder.protocol.onFrame(
            FlashPairingFrame.PairConfirm(
                requestId = responder.protocol.session.value.requestId!!,
                codeHashHex = initiator.protocol.session.value.expectedCodeHashHex!!,
            ),
        )
        responder.flushTo(initiator) // PAIRED
        testScheduler.runCurrent()

        assertEquals(PairingPhase.Confirmed, responder.protocol.session.value.phase)
        assertEquals(PairingPhase.Confirmed, initiator.protocol.session.value.phase)
        // Each side ends up with the OTHER's ephemeral key: the one the code covered.
        assertTrue(initiator.protocol.session.value.peerEphemeralPublicKey!!.contentEquals(responder.ephemeralKey))
        assertTrue(responder.protocol.session.value.peerEphemeralPublicKey!!.contentEquals(initiator.ephemeralKey))
        assertTrue(initiatorEvents.any { it is FlashPairingEvent.Confirmed })
        assertTrue(responderEvents.any { it is FlashPairingEvent.Confirmed })
        iJob.cancel()
        rJob.cancel()
    }

    @Test
    fun `a v1 request is refused and gets no nonce`() = runTest {
        val clock = FakeClock(0)
        val responder = bob(clock)

        responder.protocol.onFrame(
            FlashPairingFrame.PairRequest(
                requestId = "rid-v1",
                senderDeviceId = "dev-a",
                senderName = "Old Alice",
                senderModel = "Flash 2.0.0-beta",
                senderFingerprintHex = aliceFp,
                senderEphemeralPublicKey = byteArrayOf(1),
                createdAt = 0,
            ),
        )

        assertEquals(PairingPhase.Failed, responder.protocol.session.value.phase)
        assertEquals("peer-update-required", responder.protocol.session.value.failureReason)
        assertTrue(responder.outgoing.isEmpty())
    }

    @Test
    fun `responder refuses a request whose fingerprint is not the TLS-pinned key`() = runTest {
        val clock = FakeClock(0)
        val initiator = alice(clock)
        // TLS pinned a DIFFERENT key for dev-a than the one the pairing claims.
        val responder = bob(clock, pins = honestPins + ("dev-a" to "cccc" + aliceFp.drop(4)))

        initiator.protocol.beginRequest("dev-b", "Bob", bobFp)
        initiator.flushTo(responder)

        assertEquals("identity-mismatch", responder.protocol.session.value.failureReason)
        assertTrue("no nonce for an unauthenticated claimant", responder.outgoing.isEmpty())
    }

    @Test
    fun `initiator refuses a nonce whose fingerprint is not the TLS-pinned key`() = runTest {
        val clock = FakeClock(0)
        val initiator = alice(clock, pins = honestPins + ("dev-b" to "dddd" + bobFp.drop(4)))
        val responder = bob(clock)

        initiator.protocol.beginRequest("dev-b", "Bob", bobFp)
        initiator.flushTo(responder)
        responder.flushTo(initiator)

        assertEquals("identity-mismatch", initiator.protocol.session.value.failureReason)
        assertTrue(
            "the nonce must NOT be revealed to an unauthenticated responder",
            initiator.outgoing.none { it is FlashPairingFrame.PairReveal },
        )
    }

    @Test
    fun `a reveal that does not open the commitment fails and shows no code`() = runTest {
        val clock = FakeClock(0)
        val initiator = alice(clock)
        val responder = bob(clock)
        val events = mutableListOf<FlashPairingEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { responder.protocol.events.collect { events += it } }

        initiator.protocol.beginRequest("dev-b", "Bob", bobFp)
        initiator.flushTo(responder)
        responder.flushTo(initiator)
        // A MITM that saw N_R tries to swap in a nonce of its choosing.
        initiator.flushTo(responder) { frame ->
            if (frame is FlashPairingFrame.PairReveal) frame.copy(nonce = ByteArray(PairingV2.NONCE_BYTES) { 7 }) else frame
        }
        testScheduler.runCurrent()

        assertEquals("commit-mismatch", responder.protocol.session.value.failureReason)
        assertNull(responder.protocol.session.value.code6)
        assertTrue(events.none { it is FlashPairingEvent.RequestReceived })
        job.cancel()
    }

    @Test
    fun `a substituted ephemeral key makes the two codes differ`() = runTest {
        // The v1 attack that needed no grinding at all: relay the real fingerprints (so every pin
        // check passes) but swap the ephemeral key that becomes the session key.
        val clock = FakeClock(0)
        val initiator = alice(clock)
        val responder = bob(clock)
        val attackerKey = byteArrayOf(99, 99, 99)

        initiator.protocol.beginRequest("dev-b", "Bob", bobFp)
        initiator.flushTo(responder)
        responder.flushTo(initiator) { frame ->
            if (frame is FlashPairingFrame.PairNonce) frame.copy(responderEphemeralPublicKey = attackerKey) else frame
        }
        initiator.flushTo(responder)

        val initiatorCode = initiator.protocol.session.value.code6
        val responderCode = responder.protocol.session.value.code6
        assertTrue(initiatorCode != null && responderCode != null)
        assertNotEquals("the humans comparing screens would see the attack", initiatorCode, responderCode)
    }

    @Test
    fun `PAIRED carrying different key material than the code covered fails the initiator`() = runTest {
        val clock = FakeClock(0)
        val initiator = alice(clock)
        val responder = bob(clock)
        exchangeNonces(initiator, responder)
        responder.protocol.respondAccept()
        responder.flushTo(initiator)
        responder.protocol.onFrame(
            FlashPairingFrame.PairConfirm(
                responder.protocol.session.value.requestId!!,
                initiator.protocol.session.value.expectedCodeHashHex!!,
            ),
        )
        responder.flushTo(initiator) { frame ->
            if (frame is FlashPairingFrame.Paired) frame.copy(peerEphemeralPublicKey = byteArrayOf(5, 5)) else frame
        }

        assertEquals(PairingPhase.Failed, initiator.protocol.session.value.phase)
        assertEquals("paired-material-mismatch", initiator.protocol.session.value.failureReason)
    }

    @Test
    fun `tampered confirmation hash hard-fails the responder`() = runTest {
        val clock = FakeClock(1_000)
        val initiator = alice(clock)
        val responder = bob(clock)
        exchangeNonces(initiator, responder)
        responder.protocol.respondAccept()

        responder.protocol.onFrame(
            FlashPairingFrame.PairConfirm(
                requestId = responder.protocol.session.value.requestId!!,
                codeHashHex = NumericComparisonCode.confirmationHashHex("999999"),
            ),
        )

        assertEquals(PairingPhase.Failed, responder.protocol.session.value.phase)
        assertEquals("code-hash-mismatch", responder.protocol.session.value.failureReason)
        assertTrue(responder.outgoing.none { it is FlashPairingFrame.Paired })
    }

    @Test
    fun `request expires neutrally when engine ticks past deadline`() = runTest {
        val clock = FakeClock(1_000)
        val responder = bob(clock)
        val initiator = alice(clock)
        exchangeNonces(initiator, responder)
        assertEquals(PairingPhase.RequestReceived, responder.protocol.session.value.phase)

        clock.setTo(31_000)
        responder.protocol.onTick(clock.nowMs())

        assertEquals(PairingPhase.Expired, responder.protocol.session.value.phase)
    }

    @Test
    fun `a responder waiting for the reveal also expires`() = runTest {
        val clock = FakeClock(1_000)
        val responder = bob(clock)
        val initiator = alice(clock)
        initiator.protocol.beginRequest("dev-b", "Bob", bobFp)
        initiator.flushTo(responder)
        assertEquals(PairingPhase.AwaitingPeerReveal, responder.protocol.session.value.phase)

        clock.setTo(31_000)
        responder.protocol.onTick(clock.nowMs())

        assertEquals(PairingPhase.Expired, responder.protocol.session.value.phase)
    }

    @Test
    fun `second beginRequest while busy fails without touching the session`() = runTest {
        val clock = FakeClock(0)
        val initiator = alice(clock)
        initiator.protocol.beginRequest("dev-b", "Bob", bobFp)
        val before = initiator.protocol.session.value

        val second = initiator.protocol.beginRequest("dev-c", "Carol", bobFp)

        assertTrue(second is FlashResult.Failure)
        assertEquals(before.requestId, initiator.protocol.session.value.requestId)
    }

    @Test
    fun `respondDecline resets to Idle`() = runTest {
        val clock = FakeClock(0)
        val initiator = alice(clock)
        val responder = bob(clock)
        exchangeNonces(initiator, responder)

        assertTrue(responder.protocol.respondDecline() is FlashResult.Success)
        assertEquals(PairingPhase.Idle, responder.protocol.session.value.phase)
    }

    @Test
    fun `respondAccept without an active request fails cleanly`() = runTest {
        val clock = FakeClock(0)
        assertTrue(bob(clock).protocol.respondAccept() is FlashResult.Failure)
    }
}
