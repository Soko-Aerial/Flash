package com.transfer.flash.desktop.radiospike

import com.transfer.flash.core.radio.link.windows.AuthenticateOutcome
import com.transfer.flash.core.radio.link.windows.BluetoothSearchParams
import com.transfer.flash.core.radio.link.windows.DiscoveredBluetoothDevice
import com.transfer.flash.core.radio.link.windows.SPP_SERVICE_GUID_STRING
import com.transfer.flash.core.radio.link.windows.ServiceStateOutcome
import com.transfer.flash.core.radio.link.windows.WindowsBluetoothApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Scanner

/**
 * Safety tests of the BT-R11 spike CLI (kept in :desktop because the CLI stays here).
 *
 * They were part of the original `RadioSpikeTest` and were lost when the Windows code moved into
 * `:core:radio` (the recording fake went with the old file). They guard the owner flag
 * `--i-am-the-owner-and-the-radio-is-on`: without it the CLI must make NO Bluetooth call at all.
 * Only a recording fake is used; no radio and no Bluetooth adapter is touched.
 */
class RadioSpikeCliSafetyTest {

    /** Records every call; the real Win32 API is never loaded. */
    private class RecordingBluetoothApi : WindowsBluetoothApi {
        sealed interface Call {
            data class ListRemembered(val n: Int = 0) : Call
            data class Search(val params: BluetoothSearchParams) : Call
            data class Services(val address: Long) : Call
            data class Authenticate(val address: Long, val pin: String?) : Call
            data class SetService(val address: Long, val guid: String, val enable: Boolean) : Call
        }

        val calls = mutableListOf<Call>()
        val remembered = mutableListOf<DiscoveredBluetoothDevice>()
        val inquiry = mutableListOf<DiscoveredBluetoothDevice>()

        override fun listRememberedDevices(): List<DiscoveredBluetoothDevice> {
            calls += Call.ListRemembered()
            return remembered.toList()
        }

        override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> {
            calls += Call.Search(params)
            return inquiry.toList()
        }

        override fun enumerateInstalledServices(deviceAddressLong: Long): List<String> {
            calls += Call.Services(deviceAddressLong)
            return emptyList()
        }

        override fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome {
            calls += Call.Authenticate(deviceAddressLong, pin)
            return AuthenticateOutcome.Success
        }

        override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome {
            calls += Call.SetService(deviceAddressLong, serviceGuid, enable)
            return ServiceStateOutcome.Success
        }

        fun mutations() = calls.filter { it is Call.Authenticate || it is Call.SetService }
    }

    private fun device(name: String, remembered: Boolean) = DiscoveredBluetoothDevice(
        name = name,
        addressLong = 0x38D2000135B5L,
        addressFormatted = "38:D2:00:01:35:B5",
        classOfDevice = 0,
        isConnected = false,
        isRemembered = remembered,
        isAuthenticated = remembered,
    )

    @Test
    fun activeModeRefusesToRunWithoutOwnerFlag() {
        val api = RecordingBluetoothApi()
        val exitCode = RadioSpikeRunner.run(
            args = arrayOf("--active"), // the owner flag is missing
            bluetoothApi = api,
            portProvider = { emptyList() },
        )
        assertEquals("active mode must refuse and return 1 without the owner flag", 1, exitCode)
        assertTrue("no Bluetooth call at all without the owner flag: ${api.calls}", api.calls.isEmpty())
    }

    @Test
    fun readOnlyModeNeverAuthenticatesOrChangesServices() {
        val api = RecordingBluetoothApi().apply { remembered += device("VR-N76 Radio", remembered = true) }
        val exitCode = RadioSpikeRunner.run(
            args = arrayOf(),
            bluetoothApi = api,
            portProvider = { emptyList() },
        )
        assertEquals(0, exitCode)
        assertTrue("read-only mode made mutating calls: ${api.mutations()}", api.mutations().isEmpty())
        assertTrue("read-only mode must not start an inquiry", api.calls.none { it is RecordingBluetoothApi.Call.Search })
    }

    @Test
    fun activeModeRunsWithOwnerFlagAndPairsOnlyTheChosenDevice() {
        val api = RecordingBluetoothApi().apply { inquiry += device("VR-N76 Test", remembered = false) }
        // Simulated console: ENTER for step 1, "0" picks the first device, ENTER for steps 2 and 3.
        val scanner = Scanner("\n0\n\n\n")
        val exitCode = RadioSpikeRunner.run(
            args = arrayOf("--active", "--i-am-the-owner-and-the-radio-is-on", "--pin", "1234"),
            bluetoothApi = api,
            consoleScanner = scanner,
            portProvider = { emptyList() },
        )
        assertEquals(0, exitCode)

        val searches = api.calls.filterIsInstance<RecordingBluetoothApi.Call.Search>()
        assertEquals(1, searches.size)
        assertTrue(searches[0].params.issueInquiry)

        val auths = api.calls.filterIsInstance<RecordingBluetoothApi.Call.Authenticate>()
        assertEquals(1, auths.size)
        assertEquals(0x38D2000135B5L, auths[0].address)
        assertEquals("1234", auths[0].pin)

        val services = api.calls.filterIsInstance<RecordingBluetoothApi.Call.SetService>()
        assertEquals(1, services.size)
        assertEquals(SPP_SERVICE_GUID_STRING, services[0].guid)
        assertTrue(services[0].enable)
    }
}
