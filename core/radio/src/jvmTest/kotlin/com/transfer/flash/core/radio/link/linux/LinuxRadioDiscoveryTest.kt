package com.transfer.flash.core.radio.link.linux

import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.core.radio.link.PairResult
import com.transfer.flash.core.radio.link.PortInfo
import com.transfer.flash.core.radio.link.PortKind
import com.transfer.flash.core.radio.link.SerialPortCatalog
import com.transfer.flash.core.radio.link.SerialSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinuxRadioDiscoveryTest {

    private class MockByteLink(override val description: String) : ByteLink {
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int = 0
        override suspend fun write(data: ByteArray) {}
        override fun close() {}
    }

    private class MockSerialPortCatalog(
        private val ports: List<PortInfo>,
        private val openHandler: (String) -> ByteLink = { MockByteLink(it) }
    ) : SerialPortCatalog {
        override fun listPorts(): List<PortInfo> = ports
        override fun open(systemName: String, settings: SerialSettings): ByteLink = openHandler(systemName)
    }

    @Test
    fun listsSerialAndBluetoothCtlDevicesWithAccurateRanking() = runTest {
        val mockCatalog = MockSerialPortCatalog(
            listOf(
                PortInfo("ttyUSB0", "Silicon Labs CP210x USB to UART", PortKind.USB_SERIAL),
                PortInfo("rfcomm0", "Bluetooth Serial Port", PortKind.BLUETOOTH),
            )
        )
        val mockRunner: (List<String>) -> List<String> = { cmd ->
            if (cmd.contains("devices")) {
                listOf(
                    "Device 08:A5:DF:FB:19:EE Kojo's A15",
                    "Device 38:D2:00:01:35:B5 VR-N76 Radio",
                )
            } else {
                emptyList()
            }
        }

        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = mockCatalog,
            commandRunner = mockRunner,
        )

        val devices = discovery.pairedDevices()
        assertEquals(4, devices.size)

        // Known radio must rank first
        assertEquals("VR-N76 Radio", devices[0].name)
        assertEquals("38:D2:00:01:35:B5", devices[0].bluetoothAddress)
        assertEquals(CandidateCategory.KNOWN_RADIO, devices[0].category)

        // Serial SPP devices
        val addresses = devices.map { it.bluetoothAddress }
        assertTrue(addresses.contains("ttyUSB0"))
        assertTrue(addresses.contains("rfcomm0"))
        assertTrue(addresses.contains("08:A5:DF:FB:19:EE"))
    }

    @Test
    fun scanStreamsPairedDevicesWithoutError() = runTest {
        val mockCatalog = MockSerialPortCatalog(
            listOf(PortInfo("ttyUSB0", "VR-N76 USB", PortKind.USB_SERIAL))
        )
        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = mockCatalog,
            commandRunner = { emptyList() },
        )

        val firstDevice = discovery.scan(timeoutMs = 100).first()
        assertEquals("ttyUSB0", firstDevice.bluetoothAddress)
        assertEquals(CandidateCategory.KNOWN_RADIO, firstDevice.category)
    }

    @Test
    fun pairUsbSerialSucceedsImmediately() = runTest {
        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = MockSerialPortCatalog(emptyList()),
            commandRunner = { emptyList() },
        )
        val device = DiscoveredDevice(
            name = "USB Radio",
            bluetoothAddress = "ttyUSB0",
            isPaired = true,
            kind = CandidateKind.USB_SERIAL,
        )
        val result = discovery.pair(device)
        assertTrue(result is PairResult.Success)
    }

    @Test
    fun openLinkConnectsDirectPortOrFallback() = runTest {
        var openedPort: String? = null
        val mockCatalog = MockSerialPortCatalog(
            listOf(PortInfo("ttyUSB0", "USB Serial", PortKind.USB_SERIAL)),
            openHandler = {
                openedPort = it
                MockByteLink(it)
            }
        )
        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = mockCatalog,
            commandRunner = { emptyList() },
        )

        val radio = RememberedRadio(
            bluetoothAddress = "ttyUSB0",
            displayName = "My Radio",
            lastKnownPortName = "ttyUSB0",
        )
        val link = discovery.openLink(radio)
        assertEquals("ttyUSB0", openedPort)
        assertEquals("ttyUSB0", link.description)
    }

    @Test
    fun openLinkThrowsDescriptiveExceptionWhenRadioNotFound() = runTest {
        val mockCatalog = MockSerialPortCatalog(emptyList())
        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = mockCatalog,
            commandRunner = { emptyList() },
        )

        val radio = RememberedRadio(
            bluetoothAddress = "38:D2:00:01:35:B5",
            displayName = "VR-N76",
        )
        try {
            discovery.openLink(radio)
            fail("Expected LinkException")
        } catch (e: LinkException) {
            assertTrue(e.message?.contains("VR-N76") == true)
            assertTrue(e.message?.contains("38:D2:00:01:35:B5") == true)
            assertFalse(e.message?.contains("Windows") == true)
        }
    }
}
