package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.trust.FlashTrustStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * End-to-end test of [FlashPairingCoordinator] with two instances wired directly to each other —
 * no sockets, no phone, no desktop UI.
 *
 * It exists because this coordinator is the ONE implementation shared by `:desktop:run` and the
 * interop harness (`HarnessPairingConsole`), i.e. by both things the hardware ladder uses to check
 * pairing. A defect here is a defect in both, and before this file the desktop's copy had no test at
 * all — the app's twin was covered, the desktop's was covered only by a hand-run gate.
 *
 * What it pins, in the order the ladder exercises it:
 * - both sides derive the **same** 6-digit code (L2 step c — the security check);
 * - both sides persist trust in their own store, and publish it for the UI (L2 step e);
 * - `beginPair` **before** the peer's hello arrives still completes (the `fingerprintKnown=false`
 *   case the phone hit on 2026-09-14, where the tap lands ahead of the hello);
 * - the two failure messages keep distinct causes — no session (`Couldn't reach`) versus a silent
 *   peer (`Still can't reach`) — because those strings are the human's whole diagnostic surface.
 *
 * Expiry is NOT tested here: it needs the state machine's real 30 s clock. `PairingSessionStateMachineTest`
 * owns that, and it can drive the clock.
 */
class FlashPairingCoordinatorTest {

    private class RecordingTrustStore : FlashTrustStore {
        val trusted = ConcurrentHashMap<FlashDeviceId, String>()

        override fun isTrusted(deviceId: FlashDeviceId): Boolean = trusted.containsKey(deviceId)

        override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
            trusted[deviceId] = friendlyName
            return FlashResult.Success(Unit)
        }

        override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
            trusted.remove(deviceId)
            return FlashResult.Success(Unit)
        }

        override fun getTrustedPeers(): Map<FlashDeviceId, String> = trusted.toMap()

        /** What TLS pinned per peer. v2 pairing refuses a fingerprint that is not this key (ADR-042). */
        val pins = ConcurrentHashMap<FlashDeviceId, String>()
        val verified: MutableSet<FlashDeviceId> = ConcurrentHashMap.newKeySet()

        override fun getPin(deviceId: FlashDeviceId): String? = pins[deviceId]

        override fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit> {
            verified += deviceId
            return FlashResult.Success(Unit)
        }

        override fun isVerified(deviceId: FlashDeviceId): Boolean = deviceId in verified
    }

    /** Two coordinators, each one's `sendToPeer` delivering straight into the other's `onInbound`. */
    private class Pair(
        fpA: String = FP_A,
        fpB: String = FP_B,
    ) {
        val trustA = RecordingTrustStore()
        val trustB = RecordingTrustStore()
        val messagesA = mutableListOf<String>()

        /** Flip to simulate "there is no live session in that direction". */
        var deliverable = true

        lateinit var a: FlashPairingCoordinator
        lateinit var b: FlashPairingCoordinator

        init {
            // Stand-in for the TLS handshake: each side pinned the other's real identity key.
            trustA.pins[FlashDeviceId(ID_B)] = fpB
            trustB.pins[FlashDeviceId(ID_A)] = fpA
            // Unconfined so the coordinators' own collectors subscribe DURING construction: a
            // MutableSharedFlow with no subscriber drops its first emission, and this test drives the
            // protocol synchronously — a dropped event would read as a protocol failure.
            val scope = CoroutineScope(Dispatchers.Unconfined)
            a = FlashPairingCoordinator(
                localFingerprintHex = fpA,
                localDeviceId = ID_A,
                localName = "Harness A",
                localModel = "desktop",
                ephemeralPublicKey = byteArrayOf(1, 2, 3),
                trustStore = trustA,
                scope = scope,
                sendToPeer = { _, text ->
                    if (deliverable) {
                        b.onInbound(ID_A, text)
                        true
                    } else {
                        false
                    }
                },
            )
            b = FlashPairingCoordinator(
                localFingerprintHex = fpB,
                localDeviceId = ID_B,
                localName = "Harness B",
                localModel = "phone",
                ephemeralPublicKey = byteArrayOf(4, 5, 6),
                trustStore = trustB,
                scope = scope,
                sendToPeer = { _, text ->
                    a.onInbound(ID_B, text)
                    true
                },
            )
            scope.launch { a.messages.collect { messagesA += it } }
        }
    }

    @Test
    fun bothSidesDeriveTheSameCode_andEachPersistsTrustInItsOwnStore() = runBlocking {
        val pair = Pair()
        // Both hosts send their hello on the session-up edge; that is what teaches each side the
        // other's fingerprint, and without it neither can derive a code.
        pair.a.onSessionUp(ID_B)
        pair.b.onSessionUp(ID_A)

        pair.a.beginPair(ID_B, "Harness B")
        waitUntil("B must see the request") {
            pair.b.pairing.value?.phase == PairingPhase.RequestReceived
        }

        val codeB = assertNotNull(pair.b.pairing.value, "responder must display a code").numericCode
        val codeA = assertNotNull(pair.a.pairing.value, "initiator must display a code").numericCode
        assertEquals(codeA, codeB, "THE security check: both screens must show the same 6 digits")
        assertEquals(6, codeB.length, "the code is always 6 digits (`NumericComparisonCode.derive`)")

        pair.b.acceptLocal()

        waitUntil("both sides must trust each other") {
            pair.trustA.trusted.containsKey(FlashDeviceId(ID_B)) &&
                pair.trustB.trusted.containsKey(FlashDeviceId(ID_A))
        }
        // The published list is what the desktop's Nearby row reads; it must have moved too, or the
        // row keeps showing "Pair" after a successful pairing (the bug the flow exists to fix).
        assertTrue(pair.a.trustedPeers.value.any { it.id == ID_B }, "A must publish the new trusted peer")
        assertTrue(pair.b.trustedPeers.value.any { it.id == ID_A }, "B must publish the new trusted peer")
        // ADR-042: a v2 pairing is recorded as verified on both sides and published as such.
        assertTrue(FlashDeviceId(ID_B) in pair.trustA.verified && FlashDeviceId(ID_A) in pair.trustB.verified)
        assertTrue(pair.a.trustedPeers.value.single { it.id == ID_B }.verified)
    }

    @Test
    fun aPeerAdvertisingOnlyV1_isRefusedWithAnUpdateMessage() = runBlocking {
        // Owner decision 2026-09-23: no new pairing with a v1 peer, whose code could be forced.
        val pair = Pair()
        // A 2.0.0-beta hello: no `v` field.
        pair.a.onInbound(ID_B, "FLASH_PAIR t=hello fp=$FP_B")

        pair.a.beginPair(ID_B, "Old Phone")

        waitUntil("A must tell the user to update the other device") {
            pair.messagesA.any { it.contains("older Flash") }
        }
        assertTrue(pair.b.pairing.value == null, "no request may reach the v1 peer")
    }

    @Test
    fun aClaimedFingerprintThatIsNotTheTlsPin_neverShowsACode() = runBlocking {
        // B's TLS handshake pinned a DIFFERENT key for A than the one A's pairing frames claim, which is
        // what a man-in-the-middle relaying the real fingerprints looks like.
        val pair = Pair()
        pair.trustB.pins[FlashDeviceId(ID_A)] = "ffff000011112222"
        pair.a.onSessionUp(ID_B)
        pair.b.onSessionUp(ID_A)

        pair.a.beginPair(ID_B, "Harness B")
        delay(FINGERPRINT_POLL_WINDOW_MS)

        assertTrue(pair.b.pairing.value?.numericCode.isNullOrEmpty(), "no code for an unauthenticated claimant")
        assertTrue(pair.trustB.trusted.isEmpty(), "and no trust")
    }

    @Test
    fun beginPair_beforeThePeersHelloArrives_stillCompletes() = runBlocking {
        // The exact shape the phone hits: the user taps Pair while the peer's hello is still in
        // flight, so there is no fingerprint yet. `beginPair` must record the intent, prompt the peer
        // for its hello, and pick the fingerprint up when it lands — not fail.
        val pair = Pair()
        pair.a.beginPair(ID_B, "Harness B")
        waitUntil("A must report that it is trying") { pair.messagesA.isNotEmpty() }

        // The peer's session comes up mid-poll (the poll runs every 100 ms for up to 3 s).
        delay(FINGERPRINT_POLL_WINDOW_MS)
        pair.b.onSessionUp(ID_A)

        waitUntil("the request must still go out") {
            pair.b.pairing.value?.phase == PairingPhase.RequestReceived
        }
        pair.b.acceptLocal()
        waitUntil("pairing must complete") { pair.trustA.trusted.containsKey(FlashDeviceId(ID_B)) }
    }

    @Test
    fun beginPair_withNoSession_reportsCouldntReach_thenTheTimeoutWording() = runBlocking {
        val pair = Pair()
        pair.deliverable = false
        val retries = mutableListOf<String>()

        pair.a.beginPair(ID_B, "Harness B") { retries += it }

        // No session at all is the `delivered == false` branch, reported before any waiting happens.
        waitUntil("must report the unreachable peer") {
            pair.messagesA.any { it.startsWith("Couldn't reach") }
        }
        // With nothing delivered there is no fingerprint coming, so the retry callback still fires —
        // with the OTHER wording. Two strings, two causes; the runbook's decoder depends on that.
        waitUntil("must report the silent peer", timeoutMs = RETRY_WAIT_MS) { retries.isNotEmpty() }
        assertTrue(
            retries.single().startsWith("Still can't reach"),
            "expected the timeout wording, got: ${retries.single()}",
        )
    }

    @Test
    fun theCodeIsBoundToTheTwoIdentities_notAConstant() = runBlocking {
        // Guards against the test above passing for a trivial reason. If `derive` were broken into a
        // constant (or the coordinator displayed a fixed string), two devices would "agree" and the
        // security check would be theatre: the whole point is that a DIFFERENT peer yields a
        // DIFFERENT code, so a man in the middle cannot show the same six digits.
        val codeOne = codeFor(Pair())
        val codeOther = codeFor(Pair(fpA = "1111222233334444", fpB = "5555666677778888"))

        assertTrue(codeOne != codeOther, "different identities must not produce the same code")
        assertTrue(codeOne.all { it.isDigit() }, "the displayed code must be digits only: $codeOne")
    }

    /** Drives a pair to the point where the responder displays its code, and returns it. */
    private suspend fun codeFor(pair: Pair): String {
        pair.a.onSessionUp(ID_B)
        pair.b.onSessionUp(ID_A)
        pair.a.beginPair(ID_B, "Harness B")
        waitUntil("responder must display a code") {
            pair.b.pairing.value?.numericCode?.isNotEmpty() == true
        }
        return assertNotNull(pair.b.pairing.value).numericCode
    }

    private suspend fun waitUntil(
        what: String,
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        throw AssertionError("timed out waiting: $what")
    }

    private companion object {
        const val ID_A = "aaaaaaaa-0000-0000-0000-000000000001"
        const val ID_B = "bbbbbbbb-0000-0000-0000-000000000002"
        const val FP_A = "a1b2c3d4e5f60718"
        const val FP_B = "0f1e2d3c4b5a6978"

        /** Comfortably inside the coordinator's 3 s fingerprint poll window, and past one tick. */
        const val FINGERPRINT_POLL_WINDOW_MS = 200L

        /** The retry callback fires only after that 3 s window closes. */
        const val RETRY_WAIT_MS = 6_000L
    }
}
