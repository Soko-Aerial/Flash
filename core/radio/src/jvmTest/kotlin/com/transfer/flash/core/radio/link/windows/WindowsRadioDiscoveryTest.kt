package com.transfer.flash.core.radio.link.windows

import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.core.radio.link.PortInfo
import com.transfer.flash.core.radio.link.PortKind
import com.transfer.flash.core.radio.link.SerialPortCatalog
import com.transfer.flash.core.radio.link.SerialSettings
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.link.PairResult
import kotlinx.coroutines.flow.toList
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
    fun radlnkU43_theRadioEntryWithoutAnOutgoingComPortIsDemotedSoOneRadioIsOffered() = runTest {
        fun device(address: String) = DiscoveredBluetoothDevice(
            name = "VR-N76", addressLong = 0L, addressFormatted = address, classOfDevice = 0,
            isConnected = false, isRemembered = true, isAuthenticated = true,
        )
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun listRememberedDevices() = listOf(device("38:D2:00:01:34:FD"), device("38:D2:00:01:35:B5"))
        }
        val registry = mapOf(
            "COM15" to RegistryPortEntry("COM15", "38D2000134FD", "38:D2:00:01:34:FD", false, "x"),
        )
        val discovery = WindowsRadioDiscovery(
            windowsBluetoothApi = api,
            registryReader = object : WindowsRegistryPortReader {
                override fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry> = registry
            },
            serialPortCatalog = MockSerialPortCatalog(emptyMap()),
        )

        val paired = discovery.pairedDevices()

        val byAddress = paired.associateBy { it.bluetoothAddress }
        assertEquals(CandidateCategory.KNOWN_RADIO, byAddress.getValue("38:D2:00:01:34:FD").category)
        assertEquals(CandidateCategory.OTHER, byAddress.getValue("38:D2:00:01:35:B5").category)
        assertEquals("Both entries stay listed", 2, paired.size)
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

    // --- 2026-10-23: owner report - "Use this radio" did nothing, re-pairing after unpairing failed, scan showed nothing until done ---

    private fun device(address: String, name: String = "VR-N76", authenticated: Boolean, remembered: Boolean = true) =
        DiscoveredBluetoothDevice(
            name = name, addressLong = parseBluetoothAddress(address), addressFormatted = address, classOfDevice = 0,
            isConnected = false, isRemembered = remembered, isAuthenticated = authenticated,
        )

    private fun registryOf(vararg entries: RegistryPortEntry) = object : WindowsRegistryPortReader {
        override fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry> = entries.associateBy { it.portName.uppercase() }
    }

    private fun discoveryFor(
        api: WindowsBluetoothApi,
        registry: WindowsRegistryPortReader = registryOf(),
        catalog: MockSerialPortCatalog = MockSerialPortCatalog(emptyMap()),
    ) = WindowsRadioDiscovery(windowsBluetoothApi = api, registryReader = registry, serialPortCatalog = catalog, portWaitMs = 0L)

    private val controlPort = RegistryPortEntry("COM15", "38D2000134FD", "38:D2:00:01:34:FD", false, "x")

    @Test
    fun radlnkU44_aRememberedButUnauthenticatedRadioIsOfferedAsNotPaired() = runTest {
        // Microsoft: "Not all remembered devices are authenticated" - only fAuthenticated means paired.
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun listRememberedDevices() = listOf(device("38:D2:00:01:34:FD", authenticated = false))
        }
        val discovery = discoveryFor(api)

        assertTrue("A radio Windows forgot the bond of is not a paired candidate", discovery.pairedDevices().isEmpty())
        val scanned = discovery.scan(2_000L).toList().single { it.bluetoothAddress == "38:D2:00:01:34:FD" }
        assertFalse("The row must offer Pair, not Use this radio", scanned.isPaired)
    }

    @Test
    fun radlnkU45_theScanEmitsWhatWindowsKnowsBeforeTheFirstInquiryAndEachRoundAfterwards() = runTest {
        val events = mutableListOf<String>()
        var round = 0
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun listRememberedDevices(): List<DiscoveredBluetoothDevice> {
                events += "known"
                return listOf(device("F4:4E:FD:00:06:D3", "AA-ER3", authenticated = true))
            }
            override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> {
                round++
                events += "inquiry#$round"
                val found = mutableListOf(device("F4:4E:FD:00:06:D3", "AA-ER3", authenticated = true))
                if (round >= 2) found += device("38:D2:00:01:34:FD", authenticated = false, remembered = false)
                return found
            }
        }

        val found = discoveryFor(api).scan(12_000L).toList()

        assertEquals("Rounds of about 2.5 s fill a 12 s scan", 5, round)
        assertEquals("What Windows already knows is read before the first inquiry", "known", events.first())
        assertEquals("Each device is emitted once, not once per round", 2, found.size)
    }

    @Test
    fun radlnkU46_aDeviceFoundInARoundIsEmittedBeforeTheScanEnds() = runTest {
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        var round = 0
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> {
                round++
                order += "inquiry#$round"
                Thread.sleep(300) // a real inquiry blocks; the collector gets to run between rounds
                return if (round >= 2) listOf(device("38:D2:00:01:34:FD", authenticated = false, remembered = false)) else emptyList()
            }
        }

        discoveryFor(api).scan(12_000L).collect { order += "emit:${it.bluetoothAddress}" }

        val emitAt = order.indexOf("emit:38:D2:00:01:34:FD")
        assertTrue("The device must be emitted", emitAt >= 0)
        assertTrue("Emitted while the scan is still running, not after its last round: $order", emitAt < order.indexOf("inquiry#5"))
    }

    @Test
    fun radlnkU47_openingTheAudioEntryOfATwiceListedRadioOpensItsControlTwin() = runTest {
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun listRememberedDevices() = listOf(
                device("38:D2:00:01:34:FD", authenticated = true),
                device("38:D2:00:01:35:B5", authenticated = true),
            )
        }
        val catalog = MockSerialPortCatalog(mapOf("COM15" to MockByteLink("COM15")))
        val discovery = discoveryFor(api, registryOf(controlPort), catalog)

        val link = discovery.openLink(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))

        assertEquals("COM15", link.description)
        assertEquals(listOf("COM15"), catalog.openedPorts)
    }

    @Test
    fun radlnkU48_aRadioWindowsNoLongerHasABondWithIsNotOpenedAndSaysSo() = runTest {
        // COM15 can survive an unpair; opening it then fails with "The network location cannot be reached".
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun listRememberedDevices() = listOf(device("38:D2:00:01:34:FD", authenticated = false))
        }
        val catalog = MockSerialPortCatalog(mapOf("COM15" to MockByteLink("COM15")))
        val discovery = discoveryFor(api, registryOf(controlPort), catalog)

        try {
            discovery.openLink(RememberedRadio("38:D2:00:01:34:FD", "VR-N76"))
            fail("Expected LinkException")
        } catch (e: LinkException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not paired", ignoreCase = true))
        }
        assertEquals("No port may be opened without a bond", 0, catalog.openedPorts.size)
    }

    @Test
    fun radlnkU49_pairingRefusedByTheRadioIsPairingRefusedNotAWrongPin() = runTest {
        // Win32 1244 = ERROR_NOT_AUTHENTICATED; Windows logs BTHUSB 16 "mutual authentication ... failed". No PIN is sent.
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun authenticateDevice(deviceAddressLong: Long, pin: String?) =
                AuthenticateOutcome.Failed(1244, "not authenticated")
        }

        val result = discoveryFor(api).pair(
            com.transfer.flash.core.radio.link.DiscoveredDevice("VR-N76", "38:D2:00:01:34:FD", isPaired = false),
        )

        assertEquals(PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED), result)
    }

    @Test
    fun radlnkU50_aSuccessfulPairingEnablesTheSerialServiceAndSucceeds() = runTest {
        val enabled = mutableListOf<String>()
        val api = object : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
            override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome {
                enabled += "$serviceGuid:$enable"
                return ServiceStateOutcome.Success
            }
        }

        val result = discoveryFor(api, registryOf(controlPort)).pair(
            com.transfer.flash.core.radio.link.DiscoveredDevice("VR-N76", "38:D2:00:01:34:FD", isPaired = false),
        )

        assertEquals(PairResult.Success, result)
        assertEquals(listOf("$SPP_SERVICE_GUID_STRING:true"), enabled)
    }

    @Test
    fun radlnkU51_theAuthenticationResponseImageMatchesTheDocumentedLayout() {
        val address = parseBluetoothAddress("38:D2:00:01:34:FD")

        val numeric = AuthenticationResponses.build(address, AuthenticationResponses.METHOD_NUMERIC_COMPARISON)!!
        val buf = java.nio.ByteBuffer.wrap(numeric).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(48, numeric.size)
        assertEquals(address, buf.getLong(0))
        assertEquals(3, buf.getInt(8))
        assertEquals("numeric comparison answers yes", 1, buf.getInt(12))
        assertEquals("negativeResponse is FALSE", 0, numeric[44].toInt())

        val legacy = AuthenticationResponses.build(address, AuthenticationResponses.METHOD_LEGACY, "1234")!!
        assertEquals("1234", String(legacy, 12, 4, Charsets.US_ASCII))
        assertEquals(4, legacy[28].toInt())

        assertEquals("passkey notification is a valid response", 4,
            java.nio.ByteBuffer.wrap(AuthenticationResponses.build(address, 4)!!).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8))
        assertEquals("OOB and passkey entry are not valid responses", null, AuthenticationResponses.build(address, 2))
        assertEquals(null, AuthenticationResponses.build(address, 5))
    }

    // --- 2026-10-23: radio voice on the desktop - the audio channel is a raw RFCOMM socket, not a COM port ---

    @Test
    fun radvU60_theSockaddrBthImageMatchesTheDocumentedPackedLayout() {
        val address = parseBluetoothAddress("38:D2:00:01:34:FD")
        val raw = WindowsRfcomm.sockaddr(address, "39144315-32fa-40db-85ed-fbfeba2d86e6")
        val b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        assertEquals(30, raw.size)
        assertEquals("AF_BTH", 32, b.getShort(0).toInt())
        assertEquals(address, b.getLong(2))
        assertEquals("GUID Data1 is little endian", 0x39144315, b.getInt(10))
        assertEquals("GUID Data2", 0x32fa, b.getShort(14).toInt() and 0xFFFF)
        assertEquals("GUID Data3", 0x40db, b.getShort(16).toInt() and 0xFFFF)
        assertEquals("GUID Data4 keeps its written order", "85ed", "%02x%02x".format(raw[18], raw[19]))
        assertEquals("fbfeba2d86e6", raw.copyOfRange(20, 26).joinToString("") { "%02x".format(it) })
        assertEquals("channel 0 = look the service up by SDP", 0, b.getInt(26))
    }

    private class RfcommFake(val failFor: Set<String>) : WindowsBluetoothApi by NoOpWindowsBluetoothApi() {
        val tried = mutableListOf<String>()
        override fun listRememberedDevices() = listOf(
            DiscoveredBluetoothDevice("VR-N76", parseBluetoothAddress("38:D2:00:01:34:FD"), "38:D2:00:01:34:FD", 0, false, true, true),
            DiscoveredBluetoothDevice("VR-N76", parseBluetoothAddress("38:D2:00:01:35:B5"), "38:D2:00:01:35:B5", 0, false, true, true),
        )
        override fun openRfcomm(deviceAddressLong: Long, serviceUuid: String, description: String): ByteLink {
            val text = formatBluetoothAddress(deviceAddressLong)
            tried += text
            if (text in failFor) throw LinkException("refused $text")
            return MockByteLink(description)
        }
    }

    @Test
    fun radvU61_theAudioChannelIsTriedOnTheChosenEntryThenItsSameNameTwin() = runTest {
        val api = RfcommFake(failFor = setOf("38:D2:00:01:34:FD"))

        val link = discoveryFor(api).openAudioLink(RememberedRadio("38:D2:00:01:34:FD", "VR-N76"))

        assertEquals(listOf("38:D2:00:01:34:FD", "38:D2:00:01:35:B5"), api.tried)
        assertEquals("audio 38:D2:00:01:35:B5", link.description)
    }

    @Test
    fun radvU62_whenNoEntryAcceptsTheFirstReasonIsReported() = runTest {
        val api = RfcommFake(failFor = setOf("38:D2:00:01:34:FD", "38:D2:00:01:35:B5"))

        try {
            discoveryFor(api).openAudioLink(RememberedRadio("38:D2:00:01:34:FD", "VR-N76"))
            fail("Expected LinkException")
        } catch (e: LinkException) {
            assertEquals("refused 38:D2:00:01:34:FD", e.message)
        }
    }

    @Test
    fun radvU63_windowsOffersTheAudioChannelAndWinsockErrorsAreWorded() {
        assertTrue(discoveryFor(RfcommFake(emptySet())).supportsAudioLink)
        assertTrue(WindowsRfcomm.connectFailure(10060).contains("in time"))
        assertTrue(WindowsRfcomm.connectFailure(10061).contains("refused"))
        assertTrue(WindowsRfcomm.connectFailure(99999).contains("99999"))
    }

    @Test
    fun radvU64_theEntryThatOwnsTheSerialPortIsTriedFirstEvenWhenTheOtherWasChosen() = runTest {
        val api = RfcommFake(failFor = emptySet())

        val link = discoveryFor(api, registryOf(controlPort)).openAudioLink(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))

        assertEquals(listOf("38:D2:00:01:34:FD"), api.tried)
        assertEquals("audio 38:D2:00:01:34:FD", link.description)
    }
}
