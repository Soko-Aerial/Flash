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
import kotlinx.coroutines.flow.toList
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
    fun scanStreamsPairedAndLiveDiscoveredDevices() = runTest {
        val mockCatalog = MockSerialPortCatalog(
            listOf(PortInfo("ttyUSB0", "VR-N76 USB", PortKind.USB_SERIAL))
        )
        val mockLiveScanner = LinuxRadioDiscovery.LiveScanner { _, onDiscovered ->
            onDiscovered(
                DiscoveredDevice(
                    name = "UV-Pro Radio",
                    bluetoothAddress = "AA:BB:CC:11:22:33",
                    isPaired = false,
                    kind = CandidateKind.BLUETOOTH_SERIAL,
                    category = CandidateCategory.KNOWN_RADIO,
                )
            )
            onDiscovered(
                DiscoveredDevice(
                    name = "Infinix HOT 50",
                    bluetoothAddress = "90:9D:AC:F2:70:8E",
                    isPaired = false,
                    kind = CandidateKind.BLUETOOTH_SERIAL,
                    category = CandidateCategory.OTHER,
                )
            )
        }

        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = mockCatalog,
            commandRunner = { emptyList() },
            liveScanner = mockLiveScanner,
        )

        val flowDevices = mutableListOf<DiscoveredDevice>()
        discovery.scan(timeoutMs = 1000).collect { flowDevices.add(it) }

        assertEquals(3, flowDevices.size)
        assertEquals("ttyUSB0", flowDevices[0].bluetoothAddress)
        assertTrue(flowDevices[0].isPaired)

        assertEquals("AA:BB:CC:11:22:33", flowDevices[1].bluetoothAddress)
        assertEquals("UV-Pro Radio", flowDevices[1].name)
        assertFalse(flowDevices[1].isPaired)
        assertEquals(CandidateCategory.KNOWN_RADIO, flowDevices[1].category)

        assertEquals("90:9D:AC:F2:70:8E", flowDevices[2].bluetoothAddress)
        assertEquals("Infinix HOT 50", flowDevices[2].name)
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
    fun pairBluetoothExecutesAutomatedAgentAndTrust() = runTest {
        var executedScript: String? = null
        val commandsRun = mutableListOf<List<String>>()

        val discovery = LinuxRadioDiscovery(
            serialPortCatalog = MockSerialPortCatalog(emptyList()),
            commandRunner = { cmd ->
                commandsRun.add(cmd)
                listOf("Device 38:D2:00:01:35:B5 VR-N76")
            },
            scriptRunner = { cmd, script ->
                executedScript = script
                listOf("Pairing successful")
            }
        )

        val device = DiscoveredDevice(
            name = "VR-N76",
            bluetoothAddress = "38:D2:00:01:35:B5",
            isPaired = false,
            kind = CandidateKind.BLUETOOTH_SERIAL,
        )

        val result = discovery.pair(device)
        assertTrue(result is PairResult.Success)

        // Verify the script fed to bluetoothctl contained automated agent and trust commands
        assertTrue(executedScript?.contains("agent NoInputNoOutput") == true)
        assertTrue(executedScript?.contains("default-agent") == true)
        assertTrue(executedScript?.contains("pair 38:D2:00:01:35:B5") == true)
        assertTrue(executedScript?.contains("trust 38:D2:00:01:35:B5") == true)

        // Verify trust was also explicitly confirmed via commandRunner
        assertTrue(commandsRun.any { it.contains("trust") && it.contains("38:D2:00:01:35:B5") })
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
            bluetoothAddress = "invalid-address",
            displayName = "VR-N76",
        )
        try {
            discovery.openLink(radio)
            fail("Expected LinkException")
        } catch (e: LinkException) {
            assertTrue(e.message?.contains("VR-N76") == true)
            assertTrue(e.message?.contains("invalid-address") == true)
            assertFalse(e.message?.contains("Windows") == true)
        }
    }

    @Test
    fun linuxRfcommSocketPacksSockAddrRcCorrectly() {
        val bytes = LinuxRfcommSocket.sockaddr("08:A5:DF:FB:19:EE", channel = 1)
        assertEquals(10, bytes.size)

        // AF_BLUETOOTH = 31 (little-endian: 31, 0)
        assertEquals(31.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[1])

        // BDADDR is reversed (little endian): EE, 19, FB, DF, A5, 08
        assertEquals(0xEE.toByte(), bytes[2])
        assertEquals(0x19.toByte(), bytes[3])
        assertEquals(0xFB.toByte(), bytes[4])
        assertEquals(0xDF.toByte(), bytes[5])
        assertEquals(0xA5.toByte(), bytes[6])
        assertEquals(0x08.toByte(), bytes[7])

        // Channel = 1
        assertEquals(1.toByte(), bytes[8])
        assertEquals(0.toByte(), bytes[9])
    }
}
