package com.transfer.flash.core.network

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM composition tests for [DefaultFlashNetwork] over real loopback sockets.
 * Context is null so no platform ConnectivityManager is touched; the watcher
 * factory seam returns null and reconnect relies on manual retry only.
 */
class DefaultFlashNetworkTest {

    private class FakeWatcher : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val noopLogger = com.transfer.flash.core.network.tcp.LanSessionLogger { _, _, _, _ -> }

    @Test
    fun startRegistersServerAndTwoNetworksExchangeSessions() = runBlocking {
        val networkA = DefaultFlashNetwork(
            context = null,
            localDeviceId = "dev-a",
            localFriendlyName = "Alice",
            logger = noopLogger,
            probeOverride = com.transfer.flash.core.network.tcp.LanConnectionProbe(
                context = null,
                localDeviceId = "dev-a",
                localFriendlyName = "Alice",
                logger = noopLogger,
            ),
        )
        val networkB = DefaultFlashNetwork(
            context = null,
            localDeviceId = "dev-b",
            localFriendlyName = "Bob",
            logger = noopLogger,
        )

        val resultA = networkA.start()
        assertTrue(
            "A start failed: ${(resultA as? FlashResult.Failure)?.error}",
            resultA is FlashResult.Success,
        )
        val portA = (resultA as FlashResult.Success).value
        val resultB = networkB.start()
        assertTrue(
            "B start failed: ${(resultB as? FlashResult.Failure)?.error}",
            resultB is FlashResult.Success,
        )
        val portB = (resultB as FlashResult.Success).value

        // A knows B's route (C3→C4 seam), connects, both sides register the session.
        networkA.rememberEndpoint("dev-b", "127.0.0.1", portB)

        val sessionResult = networkA.connect(
            FlashDevice(
                id = FlashDeviceId("dev-b"),
                friendlyName = "Bob",
                transportType = FlashTransportType.LAN,
            ),
        )
        assertTrue(sessionResult is FlashResult.Success)

        val deadline = System.currentTimeMillis() + 5_000
        while ((networkB.activeSessions.value.size < 1) && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }

        assertEquals(1, networkA.activeSessions.value.size)
        assertEquals(1, networkB.activeSessions.value.size)
        assertEquals(FlashConnectionHealth.Connected, networkA.connectionHealth.value)

        // Duplicate connect to same peer coalesces: failure + registry unchanged.
        val duplicate = networkA.connect(
            FlashDevice(
                id = FlashDeviceId("dev-b"),
                friendlyName = "Bob",
                transportType = FlashTransportType.LAN,
            ),
        )
        assertTrue(duplicate is FlashResult.Failure)
        assertEquals(1, networkA.activeSessions.value.size)

        networkA.stop()
        networkB.stop()
        Unit
    }

    @Test
    fun stopClearsSessionsAndHealth() = runBlocking {
        val network = DefaultFlashNetwork(
            context = null,
            localDeviceId = "solo",
            localFriendlyName = "Solo",
            logger = noopLogger,
        )
        network.start()
        network.stop()

        assertEquals(0, network.activeSessions.value.size)
        assertEquals(FlashConnectionHealth.Offline, network.connectionHealth.value)
        assertTrue(!network.networkState.value.isRunning)
        Unit
    }
}
