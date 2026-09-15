package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The link that was broken, asserted without a phone.
 *
 * The failure this suite is named after looked like a *pairing* bug and was not one: the desktop
 * discovered the phone, showed it in the roster, and then Pair said "Couldn't reach" because the
 * engine held **zero sessions** — the auto-dial sweep that turns a discovered row into a live
 * session never ran. Nothing about that is specific to a phone: two engines in this one JVM
 * discover each other through the same `CompositeDiscovery`, and each must arrive at a live session
 * with the other through the same sweep. So this is that chain, end to end and hermetic —
 * `discoveredEndpoints` → auto-dial → `activeSessions` → the session [DesktopEngine.pairing] sends
 * on. If a future change re-couples the sweep to anything that can fail, this goes red instead of
 * the user finding it on a hardware run.
 *
 * Only the two LOCAL edges are asserted. That a *remote* phone is also dialed needs the hardware
 * gate ([docs/migration/PAIRING-GATE-RUNBOOK.md]).
 */
class DesktopEngineAutoDialTest {

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
        val state = Files.createTempDirectory("flash-desktop-$name-state").toFile()
        val received = Files.createTempDirectory("flash-desktop-$name-received").toFile()
        tempDirs += state
        tempDirs += received
        return DesktopEngine(receivedRoot = received, stateDir = state).also { engines += it }
    }

    @Test
    fun `two engines on one host discover each other and end up with a live session each`() = runBlocking {
        val alpha = engine("alpha")
        val beta = engine("beta")
        val alphaId = alpha.localDeviceId
        val betaId = beta.localDeviceId

        alpha.start()
        beta.start()

        val booted = withTimeoutOrNull(BOOT_TIMEOUT_MS) {
            while (!(alpha.ready.value && beta.ready.value) &&
                alpha.startError.value == null && beta.startError.value == null
            ) {
                delay(POLL_MS)
            }
            alpha.ready.value && beta.ready.value
        }
        assertNotNull(
            booted?.takeIf { it },
            "engines did not both reach ready — alpha=${alpha.startError.value} beta=${beta.startError.value}",
        )

        // 1. Discovery: each roster has to carry the OTHER engine's device id. This half already
        //    worked when the bug was found (multicast reaches peers), and it is asserted so a
        //    failure below cannot be misread as "discovery is broken".
        val alphaSaw = await("alpha discovered beta", DISCOVERY_TIMEOUT_MS) {
            alpha.discovery?.discoveredEndpoints?.value?.any { it.deviceId.value == betaId } == true
        }
        assertTrue(alphaSaw, "alpha never discovered beta (roster=${rosterOf(alpha)})")
        val betaSaw = await("beta discovered alpha", DISCOVERY_TIMEOUT_MS) {
            beta.discovery?.discoveredEndpoints?.value?.any { it.deviceId.value == alphaId } == true
        }
        assertTrue(betaSaw, "beta never discovered alpha (roster=${rosterOf(beta)})")

        // 2. The auto-dial sweep: a discovered peer must become a LIVE session. This is the
        //    assertion with teeth — it is the step that silently did not happen for the user, and
        //    without it the pairing "Couldn't reach" wording is the same on both sides of the bug.
        //
        //    Deliberately the UNION of the two directions, not both of them. Two engines that
        //    discover each other dial *simultaneously*, which is `registerSession`'s connect-glare
        //    case (ERROR-023): one of the two crossing connections is a loser whose dial hangs until
        //    the 6s `ConnectionTimeout`. Requiring both directions here would therefore encode an
        //    assumption this test cannot hold — and asserting the union is enough for its purpose,
        //    because "no session anywhere" is precisely the failure it exists to catch.
        //
        //    Measured 2026-09-14 with `--rerun-tasks`: one run ended with alpha holding a session
        //    and beta holding none after the full 30s window — i.e. the two sides did NOT converge.
        //    That is a glare finding of its own (recorded in the migration log), not something this
        //    test should paper over, and not what it is asserting.
        val dialed = await("a session exists between the two engines", DIAL_TIMEOUT_MS) {
            alpha.network?.activeSessions?.value?.keys?.any { it.value == betaId } == true ||
                beta.network?.activeSessions?.value?.keys?.any { it.value == alphaId } == true
        }
        assertTrue(
            dialed,
            "both engines discovered each other but neither ever held a session — the auto-dial " +
                "sweep did not run or its dial never completed. " +
                "alpha sessions=${sessionsOf(alpha)} roster=${rosterOf(alpha)} | " +
                "beta sessions=${sessionsOf(beta)} roster=${rosterOf(beta)}",
        )
    }

    /** Polls [condition] until it holds, the whole thing bounded by [timeoutMs]. */
    private suspend fun await(what: String, timeoutMs: Long, condition: () -> Boolean): Boolean {
        val held = withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_MS)
            true
        }
        if (held != true) println("[auto-dial-test] timed out waiting for: $what")
        return held == true
    }

    private fun rosterOf(engine: DesktopEngine): String =
        engine.discovery?.discoveredEndpoints?.value
            ?.joinToString { "'${it.friendlyName}' id=${it.deviceId.value}" }
            .orEmpty()

    private fun sessionsOf(engine: DesktopEngine): String =
        engine.network?.activeSessions?.value?.keys?.joinToString { it.value }.orEmpty()

    private companion object {
        const val BOOT_TIMEOUT_MS = 30_000L
        const val DISCOVERY_TIMEOUT_MS = 30_000L

        /** 3x the engine's 5s `AUTO_CONNECT_SWEEP_MS`, plus room for the WS handshake. */
        const val DIAL_TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
