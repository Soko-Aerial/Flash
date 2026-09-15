package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.security.pairing.PairingPhase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The desktop's **initiator** half of pairing, asserted on the real [DesktopEngine].
 *
 * The complaint this exists for: tapping Pair in the desktop window showed the request dialog on the
 * *phone* and nothing at all on the PC. That is a two-sided claim, and the two sides live in different
 * layers — the engine publishes `pairing` (a `StateFlow<PairingUi?>`), and the shell renders a dialog
 * from it. A headless test can only decide the first: does a `beginPair` on one engine make **both**
 * engines publish a state the UI can render?
 *
 *  - `alpha` is the initiator (the desktop the human tapped on) and must reach
 *    `AwaitingPeerConfirmation` — the "compare this code with the other screen" state. If this is null,
 *    the dialog cannot appear no matter what the UI does, and the bug is in the coordinator.
 *  - `beta` is the responder (the phone) and must reach `RequestReceived` — the state whose dialog the
 *    user DID see. Asserting it here keeps the test honest about the direction it is not testing.
 *
 * Both are ordinary `DesktopEngine`s over the real WS transport, so the fingerprints ride the same
 * `FLASH_PAIR` hello the product uses; nothing is stubbed.
 */
class DesktopEnginePairingTest {

    private val tempDirs = mutableListOf<File>()
    private val engines = mutableListOf<DesktopEngine>()

    @AfterTest
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        engines.clear()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun engine(name: String): DesktopEngine {
        val state = Files.createTempDirectory("flash-pairstate-$name-state").toFile()
        val received = Files.createTempDirectory("flash-pairstate-$name-received").toFile()
        tempDirs += state
        tempDirs += received
        return DesktopEngine(receivedRoot = received, stateDir = state).also { engines += it }
    }

    @Test
    fun `beginPair on the desktop publishes a pairing UI on both engines`() = runBlocking {
        val alpha = engine("alpha")
        val beta = engine("beta")
        val betaId = beta.localDeviceId
        val alphaId = alpha.localDeviceId

        alpha.start()
        beta.start()

        val booted = await(BOOT_TIMEOUT_MS) { alpha.ready.value && beta.ready.value }
        assertTrue(booted, "engines did not boot: alpha=${alpha.startError.value} beta=${beta.startError.value}")

        // A session must exist first — pairing rides it, and this is also what delivers the peer's
        // fingerprint hello the initiator needs before it can derive the code.
        val sessionUp = await(SESSION_TIMEOUT_MS) {
            alpha.network?.activeSessions?.value?.keys?.any { it.value == betaId } == true &&
                beta.network?.activeSessions?.value?.keys?.any { it.value == alphaId } == true
        }
        assertTrue(
            sessionUp,
            "no session between the engines — pairing cannot ride one. " +
                "alpha=${alpha.network?.activeSessions?.value?.keys?.map { it.value }} " +
                "beta=${beta.network?.activeSessions?.value?.keys?.map { it.value }}",
        )

        // The tap. `onNeedRetry` is captured rather than left as the default empty lambda, because in
        // the shell it IS the empty default — which is how a "couldn't reach / try again" outcome can
        // be swallowed silently.
        val retries = mutableListOf<String>()
        alpha.pairing.beginPair(betaId, "beta") { retries += it }

        val initiatorUi = await(PAIR_TIMEOUT_MS) { alpha.pairing.pairing.value != null }
        assertTrue(
            initiatorUi,
            "the desktop's Pair tap never published a pairing UI (alpha.pairing.pairing == null) — the " +
                "shell has nothing to render, which is exactly the reported 'no dialog on the PC'. " +
                "retries=$retries messages=${alpha.pairing.messages.replayCache}",
        )

        val initiatorPhase = assertNotNull(alpha.pairing.pairing.value).phase
        assertTrue(
            initiatorPhase == com.transfer.flash.core.security.pairing.PairingPhase.AwaitingPeerConfirmation,
            "the initiator must publish AwaitingPeerConfirmation (compare-the-code), was $initiatorPhase",
        )

        // The other half of the same claim: the peer shows an actionable request.
        val responderUi = await(PAIR_TIMEOUT_MS) {
            beta.pairing.pairing.value?.phase == com.transfer.flash.core.security.pairing.PairingPhase.RequestReceived
        }
        assertTrue(
            responderUi,
            "the responder never published RequestReceived, was ${beta.pairing.pairing.value?.phase}",
        )

        // And the two codes must match — the entire security property of the flow.
        val alphaCode = assertNotNull(alpha.pairing.pairing.value).numericCode
        val betaCode = assertNotNull(beta.pairing.pairing.value).numericCode
        assertTrue(alphaCode.isNotBlank() && alphaCode == betaCode, "codes differ: $alphaCode vs $betaCode")

        // ── Does ACCEPTING work? ────────────────────────────────────────────────────────────────
        // The dialog's Accept button calls exactly `acceptLocal()`, so this is the same path the
        // button takes, minus the click. Four frames (request → accept → confirm → paired) and then
        // durable trust on both sides, which is what "paired" means to the rest of the product.
        beta.pairing.acceptLocal()

        val confirmed = await(PAIR_TIMEOUT_MS) {
            alpha.pairing.pairing.value?.phase == PairingPhase.Confirmed &&
                beta.pairing.pairing.value?.phase == PairingPhase.Confirmed
        }
        assertTrue(
            confirmed,
            "both sides did not reach Confirmed — alpha=${alpha.pairing.pairing.value?.phase} " +
                "beta=${beta.pairing.pairing.value?.phase} messages=${alpha.pairing.messages.replayCache}",
        )

        val trustedBothWays = await(PAIR_TIMEOUT_MS) {
            alpha.trust.isTrusted(FlashDeviceId(betaId)) && beta.trust.isTrusted(FlashDeviceId(alphaId))
        }
        assertTrue(
            trustedBothWays,
            "a Confirmed pairing must persist trust on BOTH sides: " +
                "alpha trusts ${alpha.trust.getTrustedPeers().keys} " +
                "beta trusts ${beta.trust.getTrustedPeers().keys}",
        )
    }

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        } == true

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val SESSION_TIMEOUT_MS = 30_000L
        const val PAIR_TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
