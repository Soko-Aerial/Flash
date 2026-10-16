package com.transfer.flash.desktop.radiospike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Scanner

/**
 * Unit tests for Task 2 (Windows Bluetooth radio spike tool).
 * Verifies address-to-port mapping, ranking, error mapping, active-mode safety flag gate,
 * read-only non-mutation guarantee, and address masking.
 */
class RadioSpikeTest {

    @Test
    fun testAddressMaskingPreservesOnlyLast3Bytes() {
        val full1 = "38:D2:00:01:35:B5"
        val masked1 = maskBluetoothAddress(full1)
        assertEquals("**:**:**:01:35:B5", masked1)

        val full2 = "4D-3E-E7-DE-49-1F"
        val masked2 = maskBluetoothAddress(full2)
        assertEquals("**:**:**:DE:49:1F", masked2)

        val raw12 = "38D2000135B5"
        val maskedRaw = maskBluetoothAddress(raw12)
        assertEquals("******0135B5", maskedRaw)
    }

    @Test
    fun testDeviceClassificationRanksRadiosFirst() {
        val radio1 = DiscoveredBluetoothDevice(
            name = "VR-N76 VGC Radio",
            addressLong = 0x38D2000135B5L,
            addressFormatted = "38:D2:00:01:35:B5",
            classOfDevice = 0x00,
            isConnected = true,
            isRemembered = true,
            isAuthenticated = true,
            installedServiceGuids = listOf(SPP_SERVICE_GUID_STRING)
        )

        val radio2 = DiscoveredBluetoothDevice(
            name = "BTECH UV-Pro TNC",
            addressLong = 0x112233445566L,
            addressFormatted = "11:22:33:44:55:66",
            classOfDevice = 0x00,
            isConnected = false,
            isRemembered = true,
            isAuthenticated = true
        )

        val genericSpp = DiscoveredBluetoothDevice(
            name = "HC-05 Serial Adapter",
            addressLong = 0xAABBCCDDEEFFL,
            addressFormatted = "AA:BB:CC:DD:EE:FF",
            classOfDevice = 0x00,
            isConnected = false,
            isRemembered = false,
            isAuthenticated = false,
            installedServiceGuids = listOf(SPP_SERVICE_GUID_STRING)
        )

        val audioHeadset = DiscoveredBluetoothDevice(
            name = "Sony WH-1000XM4 Headset",
            addressLong = 0x123456789ABCL,
            addressFormatted = "12:34:56:78:9A:BC",
            classOfDevice = 0x200404,
            isConnected = false,
            isRemembered = true,
            isAuthenticated = true,
            installedServiceGuids = listOf("0000110B-0000-1000-8000-00805F9B34FB") // Audio Sink
        )

        val unranked = listOf(audioHeadset, genericSpp, radio2, radio1)
        val ranked = DeviceClassifier.rankDevices(unranked)

        assertEquals(4, ranked.size)
        // First two must be Category RADIO
        assertEquals(DeviceCategory.RADIO, ranked[0].category)
        assertEquals(DeviceCategory.RADIO, ranked[1].category)
        // Third must be Category SERIAL_SPP
        assertEquals(DeviceCategory.SERIAL_SPP, ranked[2].category)
        // Fourth must be Category OTHER
        assertEquals(DeviceCategory.OTHER, ranked[3].category)

        // radio1 has isConnected=true so it ranks above radio2
        assertEquals("VR-N76 VGC Radio", ranked[0].device.name)
        assertEquals("BTECH UV-Pro TNC", ranked[1].device.name)
    }

    @Test
    fun testComPortMappingWithSeveralPorts() {
        val mockRegistry = object : WindowsRegistryPortReader {
            override fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry> {
                return mapOf(
                    "COM7" to RegistryPortEntry(
                        portName = "COM7",
                        rawAddressHex = "000000000000",
                        formattedAddress = "00:00:00:00:00:00 (Incoming listener)",
                        isIncomingPort = true,
                        registryPath = "HKLM\\...\\BTHENUM\\...\\COM7"
                    ),
                    "COM16" to RegistryPortEntry(
                        portName = "COM16",
                        rawAddressHex = "38D2000135B5",
                        formattedAddress = "38:D2:00:01:35:B5",
                        isIncomingPort = false,
                        registryPath = "HKLM\\...\\BTHENUM\\...\\COM16"
                    )
                )
            }
        }

        val mapper = WindowsComPortMapper(mockRegistry)

        val ports = listOf(
            SerialPortInfo("COM7", "Standard Serial over Bluetooth link (COM7)", "Incoming port"),
            SerialPortInfo("COM16", "Standard Serial over Bluetooth link (COM16)", "Outgoing link"),
            SerialPortInfo("COM3", "Intel(R) Active Management Technology (COM3)", "Hardware serial"),
            SerialPortInfo("COM22", "Bluetooth Serial Port (COM22)", "Unknown Bluetooth")
        )

        val devices = listOf(
            DiscoveredBluetoothDevice(
                name = "VR-N76 Radio",
                addressLong = 0x38D2000135B5L,
                addressFormatted = "38:D2:00:01:35:B5",
                classOfDevice = 0,
                isConnected = true,
                isRemembered = true,
                isAuthenticated = true
            )
        )

        val mappings = mapper.mapPorts(ports, devices)
        assertEquals(4, mappings.size)

        // COM7: incoming port
        val com7 = mappings.first { it.portName == "COM7" }
        assertEquals(MappingMethod.REGISTRY_BTHENUM, com7.method)
        assertNull(com7.matchedDeviceAddress)
        assertTrue(com7.detail.contains("incoming", ignoreCase = true))

        // COM16: outgoing port to VR-N76
        val com16 = mappings.first { it.portName == "COM16" }
        assertEquals(MappingMethod.REGISTRY_BTHENUM, com16.method)
        assertEquals("38:D2:00:01:35:B5", com16.matchedDeviceAddress)
        assertEquals("VR-N76 Radio", com16.matchedDeviceName)

        // COM3: hardware / not found
        val com3 = mappings.first { it.portName == "COM3" }
        assertEquals(MappingMethod.NOT_FOUND, com3.method)

        // COM22: not found in registry
        val com22 = mappings.first { it.portName == "COM22" }
        assertEquals(MappingMethod.NOT_FOUND, com22.method)
    }

    @Test
    fun testBluetoothErrorCodesMappedToPlainSentences() {
        val err0 = BluetoothErrorMapper.mapErrorCode(0)
        assertEquals("Success.", err0)

        val err170 = BluetoothErrorMapper.mapErrorCode(170)
        assertTrue(err170.contains("in use by another program"))
        val next170 = BluetoothErrorMapper.getNextStep(170)
        assertTrue(next170.contains("Close other terminal software"))

        val err1244 = BluetoothErrorMapper.mapErrorCode(1244)
        assertTrue(err1244.contains("not paired or rejected"))

        val err1167 = BluetoothErrorMapper.mapErrorCode(1167)
        assertTrue(err1167.contains("not connected or out of range"))

        val err1223 = BluetoothErrorMapper.mapErrorCode(1223)
        assertTrue(err1223.contains("cancelled by the user"))
    }

    @Test
    fun testActiveModeRefusesToRunWithoutOwnerFlag() {
        val fakeApi = FakeWindowsBluetoothApi()
        val exitCode = RadioSpikeRunner.run(
            args = arrayOf("--active"), // Missing --i-am-the-owner-and-the-radio-is-on
            bluetoothApi = fakeApi,
            portProvider = { emptyList() }
        )

        assertEquals("Active mode must refuse and return 1 when owner flag is missing", 1, exitCode)
        fakeApi.verifyNoMutationsCalled()
        assertTrue("No calls should have been made to Bluetooth API", fakeApi.recordedCalls.isEmpty())
    }

    @Test
    fun testReadOnlyModeCallsNoMutatingFunctions() {
        val fakeApi = FakeWindowsBluetoothApi().apply {
            rememberedDevices.add(
                DiscoveredBluetoothDevice(
                    name = "VR-N76 Radio",
                    addressLong = 0x38D2000135B5L,
                    addressFormatted = "38:D2:00:01:35:B5",
                    classOfDevice = 0,
                    isConnected = false,
                    isRemembered = true,
                    isAuthenticated = true
                )
            )
        }

        val exitCode = RadioSpikeRunner.run(
            args = arrayOf(), // Default read-only mode
            bluetoothApi = fakeApi,
            portProvider = {
                listOf(
                    SerialPortInfo("COM16", "Standard Serial over Bluetooth link (COM16)", "Bluetooth SPP")
                )
            }
        )

        assertEquals(0, exitCode)
        // Verify fake records zero mutating calls
        fakeApi.verifyNoMutationsCalled()

        // Only listRemembered was called
        val listCalls = fakeApi.recordedCalls.filterIsInstance<FakeWindowsBluetoothApi.CallRecord.ListRemembered>()
        assertEquals(1, listCalls.size)

        // Zero search/inquiry, zero authenticate, zero setServiceState
        val inquiries = fakeApi.recordedCalls.filterIsInstance<FakeWindowsBluetoothApi.CallRecord.SearchDevices>()
        assertTrue(inquiries.isEmpty())
    }

    @Test
    fun testActiveModeRunsWithFlagAndFakeApi() {
        val fakeApi = FakeWindowsBluetoothApi().apply {
            inquiryDevices.add(
                DiscoveredBluetoothDevice(
                    name = "VR-N76 Test",
                    addressLong = 0x38D2000135B5L,
                    addressFormatted = "38:D2:00:01:35:B5",
                    classOfDevice = 0,
                    isConnected = false,
                    isRemembered = false,
                    isAuthenticated = false
                )
            )
        }

        // Simulate interactive user pressing ENTER for step 1, 0 for target, ENTER for step 2, ENTER for step 3
        val simulatedInput = "\n0\n\n\n"
        val scanner = Scanner(simulatedInput)

        val exitCode = RadioSpikeRunner.run(
            args = arrayOf("--active", "--i-am-the-owner-and-the-radio-is-on", "--pin", "1234"),
            bluetoothApi = fakeApi,
            consoleScanner = scanner,
            portProvider = { emptyList() }
        )

        assertEquals(0, exitCode)

        // Verify that inquiry, authenticate and setServiceState were called on the fake
        val searches = fakeApi.recordedCalls.filterIsInstance<FakeWindowsBluetoothApi.CallRecord.SearchDevices>()
        assertEquals(1, searches.size)
        assertTrue(searches[0].params.issueInquiry)

        val auths = fakeApi.recordedCalls.filterIsInstance<FakeWindowsBluetoothApi.CallRecord.Authenticate>()
        assertEquals(1, auths.size)
        assertEquals(0x38D2000135B5L, auths[0].address)
        assertEquals("1234", auths[0].pin)

        val srvCalls = fakeApi.recordedCalls.filterIsInstance<FakeWindowsBluetoothApi.CallRecord.SetServiceState>()
        assertEquals(1, srvCalls.size)
        assertEquals(SPP_SERVICE_GUID_STRING, srvCalls[0].guid)
        assertTrue(srvCalls[0].enable)
    }

    @Test
    fun testWin32BluetoothNativeInstanceLoads() {
        val instance = Win32BluetoothNative.INSTANCE
        assertNotNull(instance)
    }
}


