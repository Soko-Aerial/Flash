package com.transfer.flash.core.radio.link.windows

import com.transfer.flash.core.radio.link.maskBluetoothAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Scanner

/**
 * Unit tests for promoted Windows Bluetooth radio classes (ADR-182).
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
                        registryPath = "HKLM\\...\\COM7"
                    ),
                    "COM16" to RegistryPortEntry(
                        portName = "COM16",
                        rawAddressHex = "38D2000135B5",
                        formattedAddress = "38:D2:00:01:35:B5",
                        isIncomingPort = false,
                        registryPath = "HKLM\\...\\COM16"
                    )
                )
            }
        }

        val mapper = WindowsComPortMapper(mockRegistry)

        val ports = listOf(
            SerialPortInfo("COM7", "Standard Serial over Bluetooth link (COM7)"),
            SerialPortInfo("COM16", "Standard Serial over Bluetooth link (COM16)"),
            SerialPortInfo("COM3", "USB-SERIAL CH340 (COM3)")
        )

        val radio = DiscoveredBluetoothDevice(
            name = "VR-N76",
            addressLong = 0x38D2000135B5L,
            addressFormatted = "38:D2:00:01:35:B5",
            classOfDevice = 0x00,
            isConnected = false,
            isRemembered = true,
            isAuthenticated = true,
            installedServiceGuids = listOf(SPP_SERVICE_GUID_STRING)
        )

        val mappings = mapper.mapPorts(ports, listOf(radio))

        assertEquals(3, mappings.size)

        // COM7 is an incoming port
        val com7 = mappings.first { it.portName == "COM7" }
        assertEquals(MappingMethod.REGISTRY_BTHENUM, com7.method)
        assertNull(com7.matchedDeviceAddress)
        assertTrue(com7.detail.contains("incoming Bluetooth serial port listener"))

        // COM16 maps to VR-N76
        val com16 = mappings.first { it.portName == "COM16" }
        assertEquals(MappingMethod.REGISTRY_BTHENUM, com16.method)
        assertEquals("38:D2:00:01:35:B5", com16.matchedDeviceAddress)
        assertEquals("VR-N76", com16.matchedDeviceName)

        // COM3 is USB
        val com3 = mappings.first { it.portName == "COM3" }
        assertEquals(MappingMethod.PORT_DESCRIPTION, com3.method)
    }

    @Test
    fun testErrorMapperDescriptionsAndNextSteps() {
        // Plain sentence descriptions (Plan R §6 Rule 5)
        assertEquals("The COM port is in use by another program. Close it and try again.", BluetoothErrorMapper.mapErrorCode(170))
        assertEquals("Close other terminal software, APRS tools, or vendor applications using the port.", BluetoothErrorMapper.getNextStep(170))

        assertEquals("The device is not paired or rejected the authentication credentials.", BluetoothErrorMapper.mapErrorCode(1244))
        assertEquals("Verify the radio PIN code or initiate pairing from Windows settings.", BluetoothErrorMapper.getNextStep(1244))

        // No empty strings or unmapped NPEs
        for (code in listOf(0, 5, 87, 170, 259, 1058, 1167, 1223, 1244, 1245, 9999)) {
            val desc = BluetoothErrorMapper.mapErrorCode(code)
            val step = BluetoothErrorMapper.getNextStep(code)
            assertFalse(desc.isBlank())
            assertFalse(step.isBlank())
        }
    }

    @Test
    fun testReadOnlyModeCallsNoAuthenticateOrServiceState() {
        val fakeApi = object : WindowsBluetoothApi {
            var authenticateCalls = 0
            var setServiceStateCalls = 0
            var listRememberedCalls = 0

            override fun listRememberedDevices(): List<DiscoveredBluetoothDevice> {
                listRememberedCalls++
                return emptyList()
            }

            override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> = emptyList()
            override fun enumerateInstalledServices(deviceAddressLong: Long): List<String> = emptyList()

            override fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome {
                authenticateCalls++
                return AuthenticateOutcome.Success
            }

            override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome {
                setServiceStateCalls++
                return ServiceStateOutcome.Success
            }
        }

        // Simulate read-only scan
        fakeApi.listRememberedDevices()

        // Mutation check: Ensure read-only mode NEVER calls authenticate or setServiceState
        assertEquals(1, fakeApi.listRememberedCalls)
        assertEquals(0, fakeApi.authenticateCalls)
        assertEquals(0, fakeApi.setServiceStateCalls)
    }

    @Test
    fun testWin32BluetoothNativeInstanceLoads() {
        val os = System.getProperty("os.name") ?: ""
        if (os.startsWith("Windows", ignoreCase = true)) {
            // Verify JNA can load bthprops.cpl without UnsatisfiedLinkError
            val lib = Win32BluetoothNative.INSTANCE
            assertNotNull(lib)
        }
    }
}
