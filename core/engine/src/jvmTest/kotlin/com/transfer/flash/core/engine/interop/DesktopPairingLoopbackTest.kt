package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.pairing.PairingPhase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Pairing end to end over the **real** WS wire, between two harness endpoints on loopback.
 *
 * The sibling self-test proves the harness can push a file; this proves it can *pair*, which the
 * harness could not do at all before 2026-09-14 (`interopHarness --args="pair"` exists now). It is
 * the CLI half of the ladder's L2, verified without a phone.
 *
 * What it cannot prove: cross-platform interop. Both endpoints are desktop JVMs, so this is harness
 * verification, not a substitute for L2/L3 against the phone — the gate verdict stays with the
 * human-run scenarios in `PAIRING-GATE-RUNBOOK.md`.
 *
 * What it does cover, and why each step is load-bearing:
 * - the hello exchange rides **session-up**, so the fingerprint is learned from the wire;
 * - the initiator learns the responder's **address and port by dialing**, not by discovery (the same
 *   shape as the phone tapping Pair against a discovered peer);
 * - both sides derive the **same 6-digit code**, which is the security property;
 * - only an explicit `acceptLocal()` completes it, and trust then lands in each side's own store.
 */
class DesktopPairingLoopbackTest {

    @Test
    fun twoHarnessEndpoints_pairOverTheWire_andAgreeOnTheCode() {
        val responderDir = File(System.getProperty("java.io.tmpdir"), "flash-pairtest-resp").apply { mkdirs() }
        val initiatorDir = File(System.getProperty("java.io.tmpdir"), "flash-pairtest-init").apply { mkdirs() }
        // Distinct state dirs are not cosmetic: the state dir holds the device id, and the device id
        // is the multicast self-filter and the session key. Two endpoints sharing one would hide from
        // each other.
        val responder = DesktopEndpointFixture("pairtest-resp", responderDir)
        val initiator = DesktopEndpointFixture("pairtest-init", initiatorDir)
        // The coordinators' status lines ("Paired with…" / "Pairing ended (Failed)") are the only
        // place the reason for a stall is written down; collect them so a failure can quote them.
        val responderMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val initiatorMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        watcher.launch { responder.pairing.messages.collect { responderMessages += it } }
        watcher.launch { initiator.pairing.messages.collect { initiatorMessages += it } }

        // Clean slate on both sides, BEFORE anything starts. These fixtures keep durable trust in
        // `java.io.tmpdir`, so without this the second run of this test would find the peer already
        // trusted and could pass without pairing at all — a test that passes for the wrong reason is
        // worse than no test, and this one is the only automated cover the desktop's pairing has.
        listOf(responder, initiator).forEach { fixture ->
            fixture.trustStore.getTrustedPeers().keys.toList().forEach { fixture.trustStore.revokeTrust(it) }
        }

        try {
            val responderPort = responder.start()
            assertTrue(responderPort > 0, "responder must bind a port")
            initiator.start()

            runBlocking {
                val connect = withTimeout(15_000) {
                    initiator.network.connectManual("127.0.0.1", responderPort)
                }
                assertTrue(connect is FlashResult.Success, "dial must succeed: $connect")
                val session = (connect as FlashResult.Success).value
                val responderId = session.peer.id.value

                // The responder's hello arrives on ITS session-up edge (it learns about the session
                // from the inbound connection), so the initiator's fingerprint cache fills in on its
                // own. That race is exactly what `beginPair`'s pending-fingerprint path exists for,
                // and this test starts before that hello has necessarily landed.
                initiator.pairing.beginPair(responderId, session.peer.friendlyName) { message ->
                    println("[pairtest] retry signal: $message")
                }

                waitUntil(
                    what = "responder must see the request",
                    // This is the wait that fails when the fingerprint exchange is slow, and it used
                    // to report nothing at all — the run that failed printed "timed out waiting:
                    // responder must see the request — " and stopped there, which is the same
                    // information a human gets from the product ("Still can't reach …"). The two
                    // coordinators' own status lines and each side's session ids are the difference
                    // between "the request never left" (initiator has no fingerprint) and "it left and
                    // was ignored" (the responder's phase would show it).
                    diagnostics = {
                        "initiator phase=${initiator.pairing.pairing.value?.phase} msgs=$initiatorMessages" +
                            " | responder phase=${responder.pairing.pairing.value?.phase} " +
                            "msgs=$responderMessages" +
                            " | sessions initiator=${initiator.network.activeSessions.value.keys.map { it.value }}" +
                            " responder=${responder.network.activeSessions.value.keys.map { it.value }}"
                    },
                ) {
                    responder.pairing.pairing.value?.phase == PairingPhase.RequestReceived
                }

                val responderCode = assertNotNull(responder.pairing.pairing.value).numericCode
                val initiatorCode = assertNotNull(initiator.pairing.pairing.value).numericCode
                assertEquals(
                    initiatorCode,
                    responderCode,
                    "the two devices must display the same 6 digits — this is the whole security check",
                )

                // The human's decision, made here by the test: nothing completes without it.
                responder.pairing.acceptLocal()

                waitUntil(
                    what = "both sides must persist trust",
                    // Reported on timeout: the phases and the coordinators' own status lines are what
                    // localise a stall in a four-frame handshake (request → accept → confirm → paired).
                    diagnostics = {
                        "responder phase=${responder.pairing.pairing.value?.phase} " +
                            "trusted=${responder.trustStore.getTrustedPeers().keys} msgs=$responderMessages" +
                            " | initiator phase=${initiator.pairing.pairing.value?.phase} " +
                            "trusted=${initiator.trustStore.getTrustedPeers().keys} msgs=$initiatorMessages"
                    },
                ) {
                    // Each side must trust the OTHER one's id — the responder trusts the initiator's
                    // `session.peer` id, not its own (an easy way to write an assertion that can never
                    // be satisfied).
                    responder.trustStore.isTrusted(initiator.identity.deviceId) &&
                        initiator.trustStore.isTrusted(FlashDeviceId(responderId))
                }
                assertEquals(
                    1,
                    responder.trustStore.getTrustedPeers().size,
                    "exactly one peer must have been trusted on the responder",
                )
                assertTrue(
                    responder.pairing.trustedPeers.value.any { it.id == initiator.identity.deviceId.value },
                    "the responder must publish the new trusted peer (the Nearby row reads this)",
                )
            }
        } finally {
            watcher.cancel()
            initiator.stop()
            responder.stop()
        }
    }

    private suspend fun waitUntil(
        what: String,
        timeoutMs: Long = 20_000L,
        diagnostics: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        throw AssertionError("timed out waiting: $what — ${diagnostics()}")
    }
}
