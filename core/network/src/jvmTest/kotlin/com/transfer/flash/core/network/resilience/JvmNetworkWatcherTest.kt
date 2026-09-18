package com.transfer.flash.core.network.resilience

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JvmNetworkWatcherTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun initialPollRecordsBaselineWithoutFiringCallbacks() {
        var availableFired = false
        var lostFired = false
        var linkChangedFired = false

        val currentInterfaces = mutableListOf(
            NetworkInterfaceSnapshot(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                addresses = listOf("192.168.1.100"),
            ),
        )

        val watcher = JvmNetworkWatcher(
            scope = testScope,
            onAvailable = { availableFired = true },
            onLost = { lostFired = true },
            onLinkChanged = { linkChangedFired = true },
            networkInterfaceProvider = { currentInterfaces },
        )

        watcher.pollOnce()

        assertFalse("Initial poll should not fire onAvailable", availableFired)
        assertFalse("Initial poll should not fire onLost", lostFired)
        assertFalse("Initial poll should not fire onLinkChanged", linkChangedFired)
    }

    @Test
    fun networkBecomesAvailableFiresOnAvailable() {
        var availableCount = 0
        var lostCount = 0
        var linkChangedCount = 0

        val currentInterfaces = mutableListOf<NetworkInterfaceSnapshot>()

        val watcher = JvmNetworkWatcher(
            scope = testScope,
            onAvailable = { availableCount++ },
            onLost = { lostCount++ },
            onLinkChanged = { linkChangedCount++ },
            networkInterfaceProvider = { currentInterfaces },
        )

        // Initial poll with empty interfaces
        watcher.pollOnce()
        assertEquals(0, availableCount)

        // Network comes up
        currentInterfaces.add(
            NetworkInterfaceSnapshot(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                addresses = listOf("192.168.1.50"),
            ),
        )
        watcher.pollOnce()

        assertEquals(1, availableCount)
        assertEquals(0, lostCount)
        assertEquals(0, linkChangedCount)
    }

    @Test
    fun networkLostFiresOnLost() {
        var availableCount = 0
        var lostCount = 0
        var linkChangedCount = 0

        val currentInterfaces = mutableListOf(
            NetworkInterfaceSnapshot(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                addresses = listOf("10.0.0.5"),
            ),
        )

        val watcher = JvmNetworkWatcher(
            scope = testScope,
            onAvailable = { availableCount++ },
            onLost = { lostCount++ },
            onLinkChanged = { linkChangedCount++ },
            networkInterfaceProvider = { currentInterfaces },
        )

        // Baseline
        watcher.pollOnce()

        // Disconnect
        currentInterfaces.clear()
        watcher.pollOnce()

        assertEquals(0, availableCount)
        assertEquals(1, lostCount)
        assertEquals(0, linkChangedCount)
    }

    @Test
    fun networkIpChangedFiresOnLinkChanged() {
        var availableCount = 0
        var lostCount = 0
        var linkChangedCount = 0

        val currentInterfaces = mutableListOf(
            NetworkInterfaceSnapshot(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                addresses = listOf("192.168.1.100"),
            ),
        )

        val watcher = JvmNetworkWatcher(
            scope = testScope,
            onAvailable = { availableCount++ },
            onLost = { lostCount++ },
            onLinkChanged = { linkChangedCount++ },
            networkInterfaceProvider = { currentInterfaces },
        )

        // Baseline
        watcher.pollOnce()

        // IP changed from DHCP renewal or network roam
        currentInterfaces[0] = currentInterfaces[0].copy(addresses = listOf("192.168.1.150"))
        watcher.pollOnce()

        assertEquals(0, availableCount)
        assertEquals(0, lostCount)
        assertEquals(1, linkChangedCount)
    }

    @Test
    fun loopbackOrDownInterfacesIgnored() {
        var availableCount = 0
        val currentInterfaces = mutableListOf(
            NetworkInterfaceSnapshot(
                name = "lo",
                isUp = true,
                isLoopback = true,
                addresses = listOf("127.0.0.1"),
            ),
            NetworkInterfaceSnapshot(
                name = "wlan0",
                isUp = false,
                isLoopback = false,
                addresses = listOf("192.168.1.20"),
            ),
        )

        val watcher = JvmNetworkWatcher(
            scope = testScope,
            onAvailable = { availableCount++ },
            networkInterfaceProvider = { currentInterfaces },
        )

        // Baseline: empty because all are loopback or down
        watcher.pollOnce()

        // Still down
        watcher.pollOnce()
        assertEquals(0, availableCount)

        // Bring wlan0 up
        currentInterfaces[1] = currentInterfaces[1].copy(isUp = true)
        watcher.pollOnce()

        assertEquals(1, availableCount)
    }
}
