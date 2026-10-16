package com.transfer.flash.core.radio.link.windows

import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.core.radio.link.PortInfo
import com.transfer.flash.core.radio.link.PortKind
import com.transfer.flash.core.radio.link.SerialPortCatalog
import com.transfer.flash.core.radio.link.SerialSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WindowsRadioDiscoveryTest {

    private class MockByteLink(override val description: String) : ByteLink {
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int = 0
        override suspend fun write(data: ByteArray) {}
        override fun close() {}
    }

    private class MockSerialPortCatalog(
        private val availablePorts: Map<String, ByteLink>
    ) : SerialPortCatalog {
        val openedPorts = mutableListOf<String>()

        override fun listPorts(): List<PortInfo> {
            return availablePorts.keys.map {
                PortInfo(it, "Mock Port $it", PortKind.BLUETOOTH)
            }
        }

        override fun open(name: String, settings: SerialSettings): ByteLink {
            openedPorts.add(name)
            return availablePorts[name] ?: throw LinkException("Port $name not found")
        }
    }

    private class NoOpWindowsBluetoothApi : WindowsBluetoothApi {
        override fun listRememberedDevices(): List<DiscoveredBluetoothDevice> = emptyList()
        override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> = emptyList()
        override fun enumerateInstalledServices(deviceAddressLong: Long): List<String> = emptyList()
        override fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome = AuthenticateOutcome.Success
        override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome = ServiceStateOutcome.Success
    }

    @Test
    fun radlnkU14_dynamicRegistryResolutionHandlesComPortRenumbering() = runTest {
        val macAddress = "38:D2:00:01:35:B5"
        val rawHex = "38D2000135B5"

        // Remembered radio recorded COM16 in the past
        val remembered = RememberedRadio(
            bluetoothAddress = macAddress,
            displayName = "VR-N76",
            lastKnownPortName = "COM16"
        )

        // Registry now has renumbered the port to COM23 for this MAC
        val fakeRegistry = mapOf(
            "COM23" to RegistryPortEntry(
                portName = "COM23",
                rawAddressHex = rawHex,
                formattedAddress = macAddress,
                isIncomingPort = false,
                registryPath = "SYSTEM\\CurrentControlSet\\Enum\\BTHENUM\\..."
            ),
            "COM10" to RegistryPortEntry(
                portName = "COM10",
                rawAddressHex = "000000000000",
                formattedAddress = "00:00:00:00:00:00",
                isIncomingPort = true,
                registryPath = "SYSTEM\\CurrentControlSet\\Enum\\BTHENUM\\..."
            )
        )

        val catalog = MockSerialPortCatalog(
            mapOf("COM23" to MockByteLink("COM23"))
        )

        val discovery = WindowsRadioDiscovery(
            windowsBluetoothApi = NoOpWindowsBluetoothApi(),
            registryReader = object : WindowsRegistryPortReader {
                override fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry> = fakeRegistry
            },
            serialPortCatalog = catalog
        )

        val link = discovery.openLink(remembered)
        assertEquals("COM23", link.description)
        assertEquals("Must open dynamically resolved COM23, ignoring outdated COM16", listOf("COM23"), catalog.openedPorts)
    }

    @Test
    fun radlnkU15_allZeroAddressIncomingListenerPortIsNeverOpened() = runTest {
        val fakeRegistry = mapOf(
            "COM5" to RegistryPortEntry(
                portName = "COM5",
                rawAddressHex = "000000000000",
                formattedAddress = "00:00:00:00:00:00",
                isIncomingPort = true,
                registryPath = "SYSTEM\\CurrentControlSet\\Enum\\BTHENUM\\..."
            )
        )

        val catalog = MockSerialPortCatalog(
            mapOf("COM5" to MockByteLink("COM5"))
        )

        val discovery = WindowsRadioDiscovery(
            windowsBluetoothApi = NoOpWindowsBluetoothApi(),
            registryReader = object : WindowsRegistryPortReader {
                override fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry> = fakeRegistry
            },
            serialPortCatalog = catalog
        )

        // Attempting to open a zero address must throw IllegalArgumentException
        try {
            discovery.openLink(RememberedRadio("00:00:00:00:00:00", "Incoming Listener"))
            fail("Expected exception when opening zero address")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // Attempting fallback to COM5 must be refused as incoming listener
        try {
            discovery.openLink(
                RememberedRadio("38:D2:00:01:35:B5", "VR-N76", lastKnownPortName = "COM5")
            )
            fail("Expected LinkException when fallback port is an incoming listener")
        } catch (e: LinkException) {
            assertTrue(e.message?.contains("incoming listener", ignoreCase = true) == true)
        }

        assertEquals("Listener port must NEVER be opened", 0, catalog.openedPorts.size)
    }
}
