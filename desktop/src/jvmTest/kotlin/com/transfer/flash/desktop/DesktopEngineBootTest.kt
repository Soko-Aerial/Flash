package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Boots the real [DesktopEngine] — the same `assemble()` `:desktop:run` executes — in a JVM with no
 * window, no phone and no human.
 *
 * **Why this suite exists.** `assemble()` is one long bring-up whose failures are almost all
 * invisible from the outside. The bug it was written after is the archetype: a `require` on
 * `CompositeDiscovery.startAll`'s aggregate result aborted the composition *after* discovery had
 * already started advertising, so the app showed a discovered peer, held zero sessions, never
 * dialed, and printed nothing explaining why — the transfers tab simply never left its loading
 * state. `:desktop:run` cannot catch that class of failure (it needs a human to interpret a GUI),
 * and the Phase 16 harness cannot either (it discards the same result and never boots
 * `DesktopEngine` at all). One assertion — *did the composition reach a runnable state?* — does.
 *
 * **What it deliberately does NOT assert.** That a peer is discovered, dialed, or paired. All three
 * need a real second device on the LAN, so they belong to the hardware gate
 * (`docs/migration/PAIRING-GATE-RUNBOOK.md`), not here. This suite is the check that runs on every
 * machine, every time, and it is the one that would have failed on the evening this was found.
 */
class DesktopEngineBootTest {

    private var stateDir: File? = null
    private var receivedRoot: File? = null
    private var engine: DesktopEngine? = null

    @AfterTest
    fun tearDown() {
        engine?.stop()
        engine = null
        // Temp dirs, and a real mDNS responder + multicast socket die with the engine above. The
        // deletes are best-effort: on Windows a socket or an identity file can still be closing,
        // and a leftover temp directory must never turn a passing boot into a red test.
        stateDir?.deleteRecursively()
        receivedRoot?.deleteRecursively()
    }

    @Test
    fun `assemble reaches ready and reports no start error`() {
        val state = Files.createTempDirectory("flash-desktop-boot-state").toFile()
        val received = Files.createTempDirectory("flash-desktop-boot-received").toFile()
        stateDir = state
        receivedRoot = received

        val booted = DesktopEngine(receivedRoot = received, stateDir = state)
        engine = booted

        booted.start()

        val settled = runBlocking {
            withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                // `start()` launches `assemble()` on the engine's own scope, so both terminal
                // states have to be watched: a composition that throws sets `startError` and never
                // sets `ready`, and waiting on `ready` alone would just time out and report a
                // timeout instead of the actual cause.
                while (!booted.ready.value && booted.startError.value == null) {
                    delay(POLL_MS)
                }
                true
            }
        }

        assertNotNull(settled, "DesktopEngine.start() did not settle within ${BOOT_TIMEOUT_MS}ms")

        // Asserted BEFORE `ready`, and with the throwable in the message: this is the assertion
        // that carries the diagnosis. If a future bring-up step throws, the failure must name the
        // step, not merely report `ready == false`.
        val failure = booted.startError.value
        assertNull(failure, "DesktopEngine.assemble() failed: $failure")

        assertTrue(booted.ready.value, "DesktopEngine reached neither ready nor startError")
    }

    private companion object {
        /** Generous: the composition binds a WS server, a per-interface JmDNS responder set, a
         *  multicast socket per interface, and generates identity/crypto material on first run. */
        const val BOOT_TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L
    }
}
