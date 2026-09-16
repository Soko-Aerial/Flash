package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 33a — the desktop call lifecycle owner is wired, audio-only, outgoing only.
 *
 * Boots the real [DesktopEngine] headless (same `assemble()` `:desktop:run` executes) and
 * pins the three properties a hardware run cannot check in CI:
 *
 * 1. the shared `CallCoordinator` exists after boot and is idle (no phantom call);
 * 2. the Group Phase 0 trust closure holds: an untrusted peer cannot be called, and the
 *    refusal happens BEFORE any media is touched (safe to assert headless — no microphone
 *    is opened, on any machine);
 * 3. the inbound router is ordered: chat traffic is NOT a call frame, so it must fall
 *    through (`false`) to the chat/pairing/transfer handlers below it.
 *
 * What this deliberately does NOT assert: media flowing. That needs a microphone, a peer
 * and a human — the Phase 33 acceptance test, run by the owner on hardware.
 */
class DesktopCallingTest {

    private var stateDir: File? = null
    private var receivedRoot: File? = null
    private var engine: DesktopEngine? = null

    @AfterTest
    fun tearDown() {
        engine?.stop()
        engine = null
        stateDir?.deleteRecursively()
        receivedRoot?.deleteRecursively()
    }

    private fun bootEngine(): DesktopEngine {
        val state = Files.createTempDirectory("flash-desktop-call-state").toFile()
        val received = Files.createTempDirectory("flash-desktop-call-received").toFile()
        stateDir = state
        receivedRoot = received
        val booted = DesktopEngine(receivedRoot = received, stateDir = state)
        engine = booted
        booted.start()
        val settled = runBlocking {
            withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                while (!booted.ready.value && booted.startError.value == null) {
                    delay(POLL_MS)
                }
                true
            }
        }
        assertNotNull(settled, "DesktopEngine.start() did not settle within ${BOOT_TIMEOUT_MS}ms")
        assertNull(booted.startError.value, "DesktopEngine.assemble() failed: ${booted.startError.value}")
        return booted
    }

    @Test
    fun `call coordinator is built at boot and idle with no call`() {
        val booted = bootEngine()
        val calls = assertNotNull(booted.calls, "engine.calls must exist after assemble")
        assertNull(calls.activeCall.value, "no call may be live on a fresh boot")
    }

    @Test
    fun `an untrusted peer cannot be called and no media is touched`() = runBlocking {
        val calls = assertNotNull(bootEngine().calls)
        // "stranger" is in no trust store. The coordinator must refuse before media capture —
        // startCall returns false rather than throwing, so this is headless-safe everywhere.
        assertFalse(
            calls.startCall("stranger-device", "Stranger", video = false),
            "outbound calls to untrusted peers must be refused (Group Phase 0 closure)",
        )
        assertNull(calls.activeCall.value, "a refused call must not go live")
    }

    @Test
    fun `an untrusted peer cannot be video called and no media is touched`() = runBlocking {
        val calls = assertNotNull(bootEngine().calls)
        assertFalse(
            calls.startCall("stranger-device", "Stranger", video = true),
            "outbound video calls to untrusted peers must be refused (Group Phase 0 closure)",
        )
        assertNull(calls.activeCall.value, "a refused video call must not go live")
    }

    @Test
    fun `chat traffic is not a call frame and falls through`() = runBlocking {
        val calls = assertNotNull(bootEngine().calls)
        assertFalse(
            calls.onInboundText("peer-a", "FLASH_MSG localId=1 text=hi"),
            "chat frames must fall through to the handlers below the calling branch",
        )
        assertFalse(
            calls.onInboundText("peer-a", "not a frame at all"),
            "unrecognized text must fall through",
        )
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 60_000L
        const val POLL_MS = 100L
    }
}
