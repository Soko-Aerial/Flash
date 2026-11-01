@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.transfer.ExternalTransferControl
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Caller guards plus real CompositeDiscovery mode changes; no claim of network/power savings. */
class DesktopDiscoveryDownscaleTest {
    private val dirs = mutableListOf<File>()
    private var engine: DesktopEngine? = null

    @AfterTest
    fun tearDown() {
        engine?.stop()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir(label: String) = Files.createTempDirectory("flash-downscale-$label").toFile().also { dirs += it }

    private suspend fun boot(calling: FlashCalling? = null): DesktopEngine {
        val desktop = testDesktopEngine(receivedRoot = dir("received"), stateDir = dir("state"))
        engine = desktop
        if (calling != null) desktop.attachCalling(calling)
        desktop.start()
        await { desktop.ready.value || desktop.startError.value != null }
        assertTrue(desktop.ready.value, "DesktopEngine boot failed: ${desktop.startError.value}")
        return desktop
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000L) {
        while (!condition()) delay(10L)
    }

    private suspend fun expect(desktop: DesktopEngine, mode: FlashDiscoveryMode) {
        val discovery = desktop.discovery as CompositeDiscovery
        await { discovery.discoveryMode.value == mode }
        assertEquals(mode, discovery.discoveryMode.value)
    }

    @Test
    fun `idle inactive window uses ECO and restores selected mode`() = runBlocking {
        val desktop = boot()
        desktop.setDiscoveryMode(FlashDiscoveryMode.STANDARD)
        expect(desktop, FlashDiscoveryMode.STANDARD)
        desktop.setWindowActive(false)
        expect(desktop, FlashDiscoveryMode.ECO)
        desktop.setWindowActive(true)
        expect(desktop, FlashDiscoveryMode.STANDARD)
        desktop.evaluateDiscoveryActiveState()
        expect(desktop, FlashDiscoveryMode.STANDARD)
    }

    @Test
    fun `GHOST remains non advertising when minimized and when selected in tray`() = runBlocking {
        val desktop = boot()
        desktop.setDiscoveryMode(FlashDiscoveryMode.GHOST)
        expect(desktop, FlashDiscoveryMode.GHOST)
        desktop.setWindowActive(false)
        desktop.evaluateDiscoveryActiveState()
        assertEquals(FlashDiscoveryMode.GHOST, (desktop.discovery as CompositeDiscovery).discoveryMode.value)
        desktop.setWindowActive(true)
        desktop.setDiscoveryMode(FlashDiscoveryMode.STANDARD)
        expect(desktop, FlashDiscoveryMode.STANDARD)
        desktop.setWindowActive(false)
        expect(desktop, FlashDiscoveryMode.ECO)
        desktop.setDiscoveryMode(FlashDiscoveryMode.GHOST)
        expect(desktop, FlashDiscoveryMode.GHOST)
    }

    @Test
    fun `transfer start restores minimized discovery and finish re arms ECO`() = runBlocking {
        val desktop = boot()
        val repository = desktop.transfers as RealFlashTransferRepository
        val rows = MutableStateFlow<List<FlashTransfer>>(emptyList())
        val control = object : ExternalTransferControl {
            override fun owns(transferId: String) = transferId == "guard-row"
            override suspend fun accept(transferId: String) {}
            override suspend fun decline(transferId: String) {}
            override suspend fun pause(transferId: String) {}
            override suspend fun resume(transferId: String) {}
            override suspend fun cancel(transferId: String) {}
            override suspend fun pauseForSystem(transferId: String, reason: String) {}
        }
        repository.attachExternalRows(rows, control, source = "desktop-guard-test")
        desktop.setWindowActive(false)
        expect(desktop, FlashDiscoveryMode.ECO)
        val active = FlashTransfer(FlashTransferId("guard-row"), "Peer", "test.bin",
            FlashTransferDirection.Sending, 0L, 1L, FlashTransferState.Transferring)
        rows.value = listOf(active)
        expect(desktop, FlashDiscoveryMode.STANDARD)
        rows.value = listOf(active.copy(state = FlashTransferState.Completed))
        expect(desktop, FlashDiscoveryMode.ECO)
    }

    @Test
    fun `call start restores minimized discovery and end re arms ECO`() = runBlocking {
        val call = MutableStateFlow<FlashCallUiState?>(null)
        val calling = Proxy.newProxyInstance(FlashCalling::class.java.classLoader, arrayOf(FlashCalling::class.java)) { _, method, _ ->
            when (method.name) {
                "getActiveCall" -> call
                "toString" -> "DiscoveryGuardCalling"
                else -> null
            }
        } as FlashCalling
        val desktop = boot(calling)
        desktop.setWindowActive(false)
        expect(desktop, FlashDiscoveryMode.ECO)
        call.value = FlashCallUiState(callId = "guard-call", peerId = "peer", peerName = "Peer",
            direction = FlashCallDirection.OUTGOING, video = false, state = FlashCallState.ACTIVE)
        expect(desktop, FlashDiscoveryMode.STANDARD)
        call.value = call.value!!.copy(state = FlashCallState.ENDED)
        expect(desktop, FlashDiscoveryMode.ECO)
    }

    @Test
    fun `each workload and GHOST independently prevent idle downscale`() {
        for (mode in FlashDiscoveryMode.entries) {
            assertEquals(mode == FlashDiscoveryMode.GHOST,
                desktopDiscoveryScreenActive(false, mode, false, false, false))
            assertTrue(desktopDiscoveryScreenActive(true, mode, false, false, false))
            assertTrue(desktopDiscoveryScreenActive(false, mode, true, false, false))
            assertTrue(desktopDiscoveryScreenActive(false, mode, false, true, false))
            assertTrue(desktopDiscoveryScreenActive(false, mode, false, false, true))
        }
        assertFalse(desktopDiscoveryScreenActive(false, FlashDiscoveryMode.STANDARD, false, false, false))
    }
}
