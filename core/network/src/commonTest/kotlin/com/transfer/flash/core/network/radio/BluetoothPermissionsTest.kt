package com.transfer.flash.core.network.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BluetoothPermissionsTest {
    @Test
    fun androidElevenAndLowerAsksNothingAtRuntime() {
        assertEquals(emptyList(), BluetoothPermissions.runtimePermissions(30))
        assertEquals(emptyList(), BluetoothPermissions.mandatory(24))
    }

    @Test
    fun androidTwelveAndUpNeedsConnectAndOptionallyScan() {
        assertEquals(listOf(BluetoothPermissions.CONNECT, BluetoothPermissions.SCAN), BluetoothPermissions.runtimePermissions(31))
        assertEquals(listOf(BluetoothPermissions.CONNECT), BluetoothPermissions.mandatory(35))
    }

    @Test
    fun uuidsAreWellFormedAndDistinct() {
        val uuid = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
        assertTrue(uuid.matches(SPP_UUID_TEXT))
        assertTrue(uuid.matches(FLASH_RFCOMM_UUID_TEXT))
        assertEquals("00001101-0000-1000-8000-00805F9B34FB", SPP_UUID_TEXT)
        assertTrue(FLASH_RFCOMM_UUID_TEXT.lowercase() != SPP_UUID_TEXT.lowercase())
    }
}
