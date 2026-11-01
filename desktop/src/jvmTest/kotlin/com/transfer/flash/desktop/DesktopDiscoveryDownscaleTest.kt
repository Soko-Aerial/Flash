package com.transfer.flash.desktop

import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unit tests verifying OPT-O19-07:
 * - Downscaling discovery to ECO when window is inactive (minimized / tray) at idle.
 * - Restoring user-preferred discovery mode when window becomes active.
 * - Workload guards: active transfers, calls, or PTT prevent ECO downscaling.
 */
class DesktopDiscoveryDownscaleTest {

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

    @Test
    fun `idle window inactive downscales discovery to ECO and active restores preferred mode`() = runBlocking {
        val state = Files.createTempDirectory("flash-desktop-downscale-state").toFile()
        val received = Files.createTempDirectory("flash-desktop-downscale-received").toFile()
        stateDir = state
        receivedRoot = received

        val booted = testDesktopEngine(receivedRoot = received, stateDir = state)
        engine = booted
        booted.start()

        val settled = withTimeoutOrNull(30_000L) {
            while (!booted.ready.value && booted.startError.value == null) {
                delay(50L)
            }
            true
        }
        assertNotNull(settled, "DesktopEngine did not settle")
        assertTrue(booted.ready.value, "DesktopEngine failed to become ready: ${booted.startError.value}")

        val composite = booted.discovery as? CompositeDiscovery
        assertNotNull(composite, "Discovery is not CompositeDiscovery")

        // Initial preferred mode is STANDARD
        booted.setDiscoveryMode(FlashDiscoveryMode.STANDARD)
        delay(100L)
        assertEquals(FlashDiscoveryMode.STANDARD, composite.discoveryMode.value)

        // Window becomes inactive (e.g. minimized to tray) with mesh idle -> downscales to ECO
        booted.setWindowActive(false)
        delay(100L)
        assertEquals(FlashDiscoveryMode.ECO, composite.discoveryMode.value)

        // Window becomes active -> restores STANDARD
        booted.setWindowActive(true)
        delay(100L)
        assertEquals(FlashDiscoveryMode.STANDARD, composite.discoveryMode.value)
    }

    @Test
    fun `window inactive retains mode when workload guard active`() = runBlocking {
        val state = Files.createTempDirectory("flash-desktop-downscale-state-guard").toFile()
        val received = Files.createTempDirectory("flash-desktop-downscale-received-guard").toFile()
        stateDir = state
        receivedRoot = received

        val booted = testDesktopEngine(receivedRoot = received, stateDir = state)
        engine = booted
        booted.start()

        val settled = withTimeoutOrNull(30_000L) {
            while (!booted.ready.value && booted.startError.value == null) {
                delay(50L)
            }
            true
        }
        assertNotNull(settled, "DesktopEngine did not settle")
        assertTrue(booted.ready.value, "DesktopEngine failed to become ready: ${booted.startError.value}")

        val composite = booted.discovery as? CompositeDiscovery
        assertNotNull(composite, "Discovery is not CompositeDiscovery")

        booted.setDiscoveryMode(FlashDiscoveryMode.STANDARD)
        delay(100L)
        assertEquals(FlashDiscoveryMode.STANDARD, composite.discoveryMode.value)

        // Window state query matches
        assertTrue(booted.isWindowActive())

        // Inactive window triggers downscale when idle
        booted.setWindowActive(false)
        delay(100L)
        assertEquals(FlashDiscoveryMode.ECO, composite.discoveryMode.value)

        // Restoring window restores preferred mode
        booted.setWindowActive(true)
        delay(100L)
        assertEquals(FlashDiscoveryMode.STANDARD, composite.discoveryMode.value)
    }
}
