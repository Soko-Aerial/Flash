@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.pairing

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.security.pairing.FlashPairingFrame
import com.transfer.flash.core.security.pairing.PairingWireCodec
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.security.trust.VouchVerdict
import com.transfer.flash.ui.chat.FlashPairingPhase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app host's half of the **hello request/answer** handshake (2026-09-14).
 *
 * The bug this closes: a hello used to be sent exactly once, on the session-up edge, and never
 * asked for again. Any interleaving that lost that single frame — a session coming up in a different
 * order than the peer's announcement assumed — left the initiator waiting out `FINGERPRINT_WAIT_MS`
 * and reporting `Still can't reach …, then Pair again` **forever**, while every other signal looked
 * healthy. `FlashPairingCoordinator` in `:core:security` got the fix first; this suite covers the
 * app's twin, which is the implementation the phone actually runs.
 *
 * No sockets and no peer: `sendToPeer` is a spy, which is all these three rules need. The end-to-end
 * two-coordinator flow (matching codes, durable trust) is `FlashPairingCoordinatorTest`'s job in
 * `:core:security`, and is deliberately not duplicated here.
 */
class PairingCoordinatorHelloTest {

    private class RecordingTrustStore(var revoked: Boolean = false) : FlashTrustStore {

        // Vouched pins (ADR-044 V2): this fake does not exercise them.

        override fun vouchingGroups(deviceId: FlashDeviceId): Set<String> = emptySet()

        override fun applyVouch(deviceId: FlashDeviceId, fingerprintHex: String, groupId: String): VouchVerdict = VouchVerdict.INVALID

        override fun revokeVouch(deviceId: FlashDeviceId, groupId: String) = Unit

        val trusted = ConcurrentHashMap<FlashDeviceId, String>()
        val pins = ConcurrentHashMap<FlashDeviceId, String>()
        val verified: MutableSet<FlashDeviceId> = ConcurrentHashMap.newKeySet()

        override fun isTrusted(deviceId: FlashDeviceId): Boolean = trusted.containsKey(deviceId)
        override fun isRevoked(deviceId: FlashDeviceId): Boolean = revoked
        override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> {
            trusted[deviceId] = friendlyName
            return FlashResult.Success(Unit)
        }
        override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> {
            trusted.remove(deviceId)
            return FlashResult.Success(Unit)
        }
        override fun getTrustedPeers(): Map<FlashDeviceId, String> = trusted.toMap()
        override fun getPin(deviceId: FlashDeviceId): String? = pins[deviceId]
        override fun savePin(deviceId: FlashDeviceId, fingerprintHex: String): FlashResult<Unit> {
            pins[deviceId] = fingerprintHex
            return FlashResult.Success(Unit)
        }
        override fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit> {
            verified += deviceId
            return FlashResult.Success(Unit)
        }
        override fun isVerified(deviceId: FlashDeviceId): Boolean = deviceId in verified
    }

    /** [sent] records every line this device put on the wire, in order. */
    private fun coordinator(
        sent: MutableList<String>,
        trustStore: RecordingTrustStore = RecordingTrustStore(),
        timeSource: FlashTimeSource = SystemTimeSource,
        features: () -> Set<String> = { emptySet() },
    ): PairingCoordinator = PairingCoordinator(
        localFingerprintHex = OUR_FP,
        localDeviceId = LOCAL_ID,
        localName = "Phone",
        localModel = "Pixel",
        ephemeralPublicKey = byteArrayOf(1, 2, 3),
        trustStore = trustStore,
        timeSource = timeSource,
        peerFeatures = { features() },
        scope = CoroutineScope(Dispatchers.Default),
        sendToPeer = { _, text ->
            synchronized(sent) { sent += text }
            true
        },
    )

    @Test
    fun unreadableFramesResendOnlyToRevokedPr1PeersOncePerWindow() {
        val sent = mutableListOf<String>()
        val trustStore = RecordingTrustStore()
        var supportsRevoke = true
        var now = 50_000L
        val coordinator = coordinator(
            sent, trustStore,
            object : FlashTimeSource { override fun nowMs(): Long = now },
            features = { if (supportsRevoke) setOf(PairingWireCodec.FEATURE_PAIR_REVOKE) else emptySet() },
        )
        coordinator.onUnreadableSecuredFrame(PEER_ID)
        assertTrue(sent.isEmpty())
        trustStore.revoked = true
        supportsRevoke = false
        coordinator.onUnreadableSecuredFrame(PEER_ID)
        assertTrue(sent.isEmpty())

        supportsRevoke = true
        coordinator.onUnreadableSecuredFrame(PEER_ID)
        now += 9_999L
        coordinator.onUnreadableSecuredFrame(PEER_ID)
        assertEquals(listOf(PairingWireCodec.encode(FlashPairingFrame.PairRevoke())), sent)
        now += 1L
        coordinator.onUnreadableSecuredFrame(PEER_ID)
        assertEquals(2, sent.size)
    }

    @Test
    fun onInbound_requestHello_answersWithAPlainHello() {
        val sent = mutableListOf<String>()
        coordinator(sent).onInbound(PEER_ID, PairingWireCodec.encodeHello(PEER_FP, request = true))

        val answer = synchronized(sent) { sent.singleOrNull() }
        assertTrue("a request must draw exactly one answer; got $sent", answer != null)
        // The answer carries OUR fingerprint — that is the entire point of the exchange: the
        // initiator cannot derive the numeric-comparison code without the responder's identity.
        assertEquals(
            PairingWireCodec.Inbound.Hello(OUR_FP, request = false, protocolVersion = PairingWireCodec.PROTOCOL_VERSION),
            PairingWireCodec.decode(answer!!),
        )
    }

    @Test
    fun onInbound_plainHello_sendsNothing() {
        // The termination half. A plain hello is an announcement or an answer; reacting to it would
        // make two peers answer each other forever.
        val sent = mutableListOf<String>()
        coordinator(sent).onInbound(PEER_ID, PairingWireCodec.encodeHello(PEER_FP))

        assertTrue("a plain hello must draw no reply; got $sent", synchronized(sent) { sent }.isEmpty())
    }

    @Test
    fun beginPair_withNoHelloInFlight_reAsksUntilTheWindowCloses() = runBlocking {
        // The actual defect. With no fingerprint cached and a peer that never volunteers one, the
        // old code asked once and then only re-waited, so the single frame was a single point of
        // failure. It must now re-ask on a 400 ms cadence for as long as the window is open.
        val sent = mutableListOf<String>()
        val coordinator = coordinator(sent)

        val job = launch(Dispatchers.Default) { coordinator.beginPair(PEER_ID, "Peer") }
        delay(RE_ASK_OBSERVATION_MS)
        job.cancelAndJoin()

        val asks = synchronized(sent) { sent.toList() }
        assertTrue(
            "expected a re-ask burst inside the 3 s window, got ${asks.size} send(s)",
            asks.size >= MIN_EXPECTED_ASKS,
        )
        // Every one of them is a REQUEST. An ask without the flag would be silently ignored by a
        // peer that is waiting to be asked, which is precisely the failure this replaced.
        assertTrue("every ask must carry the flag: $asks", asks.all { it.contains("hrq=1") })
        assertFalse("no answer may be echoed by the asker", asks.any { it.contains("hrq=0") })
    }

    @Test
    fun failedPairing_exposesPeerDeviceId_staysVisible_andRetrySucceeds() = runBlocking {
        val trustA = RecordingTrustStore()
        val trustB = RecordingTrustStore()
        trustA.pins[FlashDeviceId(PEER_ID)] = "ffff000011112222"
        trustB.pins[FlashDeviceId(LOCAL_ID)] = OUR_FP
        val scope = CoroutineScope(Dispatchers.Unconfined)
        lateinit var a: PairingCoordinator
        lateinit var b: PairingCoordinator
        a = PairingCoordinator(
            localFingerprintHex = OUR_FP,
            localDeviceId = LOCAL_ID,
            localName = "Phone A",
            localModel = "Pixel",
            ephemeralPublicKey = byteArrayOf(1, 2, 3),
            trustStore = trustA,
            scope = scope,
            sendToPeer = { _, text -> b.onInbound(LOCAL_ID, text); true },
        )
        b = PairingCoordinator(
            localFingerprintHex = PEER_FP,
            localDeviceId = PEER_ID,
            localName = "Phone B",
            localModel = "Pixel",
            ephemeralPublicKey = byteArrayOf(4, 5, 6),
            trustStore = trustB,
            scope = scope,
            sendToPeer = { _, text -> a.onInbound(PEER_ID, text); true },
        )
        a.onSessionUp(PEER_ID)
        b.onSessionUp(LOCAL_ID)

        a.beginPair(PEER_ID, "Phone B")
        waitUntil("A must fail on pin mismatch") {
            a.pairing.value?.phase == FlashPairingPhase.Failed
        }
        assertEquals(PEER_ID, a.pairing.value?.peerDeviceId)

        // Stays visible past the old 2.5s terminal linger until user dismisses or retries.
        delay(2_700L)
        assertEquals(FlashPairingPhase.Failed, a.pairing.value?.phase)

        // Fix the pin and retry.
        trustA.pins[FlashDeviceId(PEER_ID)] = PEER_FP
        b.declineLocal()
        a.declineLocal()
        waitUntil("both reset to idle") { a.pairing.value == null && b.pairing.value == null }

        a.beginPair(PEER_ID, "Phone B")
        waitUntil("B receives retried request") {
            b.pairing.value?.phase == FlashPairingPhase.RequestReceived
        }
        assertEquals(LOCAL_ID, b.pairing.value?.peerDeviceId)
        b.acceptLocal()
        a.acceptLocal()
        waitUntil("retry pairing completes") {
            trustA.trusted.containsKey(FlashDeviceId(PEER_ID)) &&
                trustB.trusted.containsKey(FlashDeviceId(LOCAL_ID))
        }
    }

    @Test
    fun newPairingStartedDuringPairedLinger_isNotWipedByOldLingerJob() = runBlocking {
        val trustA = RecordingTrustStore()
        val trustB = RecordingTrustStore()
        trustA.pins[FlashDeviceId(PEER_ID)] = PEER_FP
        trustB.pins[FlashDeviceId(LOCAL_ID)] = OUR_FP
        val scope = CoroutineScope(Dispatchers.Unconfined)
        lateinit var a: PairingCoordinator
        lateinit var b: PairingCoordinator
        a = PairingCoordinator(
            localFingerprintHex = OUR_FP,
            localDeviceId = LOCAL_ID,
            localName = "Phone A",
            localModel = "Pixel",
            ephemeralPublicKey = byteArrayOf(1, 2, 3),
            trustStore = trustA,
            scope = scope,
            sendToPeer = { _, text -> b.onInbound(LOCAL_ID, text); true },
        )
        b = PairingCoordinator(
            localFingerprintHex = PEER_FP,
            localDeviceId = PEER_ID,
            localName = "Phone B",
            localModel = "Pixel",
            ephemeralPublicKey = byteArrayOf(4, 5, 6),
            trustStore = trustB,
            scope = scope,
            sendToPeer = { _, text -> a.onInbound(PEER_ID, text); true },
        )
        a.onSessionUp(PEER_ID)
        b.onSessionUp(LOCAL_ID)

        a.beginPair(PEER_ID, "Phone B")
        waitUntil("B sees request") { b.pairing.value?.phase == FlashPairingPhase.RequestReceived }
        b.acceptLocal()
        a.acceptLocal()
        waitUntil("both enter Paired linger") {
            a.pairing.value?.phase == FlashPairingPhase.Paired &&
                b.pairing.value?.phase == FlashPairingPhase.Paired
        }

        // Start a fresh pairing while the 1.8s PAIRED_LINGER_MS timer is active.
        a.beginPair(PEER_ID, "Phone B")
        waitUntil("new pairing active on both sides") {
            a.pairing.value?.phase == FlashPairingPhase.RequestReceived &&
                b.pairing.value?.phase == FlashPairingPhase.RequestReceived
        }

        delay(2_100L)
        assertEquals(FlashPairingPhase.RequestReceived, a.pairing.value?.phase)
        assertEquals(FlashPairingPhase.RequestReceived, b.pairing.value?.phase)
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
        const val LOCAL_ID = "11111111-0000-0000-0000-000000000001"
        const val PEER_ID = "22222222-0000-0000-0000-000000000002"
        const val OUR_FP = "a1b2c3d4e5f60718"
        const val PEER_FP = "0f1e2d3c4b5a6978"

        /** Past the 4th ask (0/400/800/1200 ms) but well inside `FINGERPRINT_WAIT_MS` (3000 ms). */
        const val RE_ASK_OBSERVATION_MS = 1_300L

        /** 4 asks land in [RE_ASK_OBSERVATION_MS]; 3 tolerates scheduler slack without passing on 1. */
        const val MIN_EXPECTED_ASKS = 3
    }
}
