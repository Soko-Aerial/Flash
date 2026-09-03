package com.transfer.flash.core.common

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashDeviceTest {

    @Test
    fun flash_device_id_validation() {
        val id = FlashDeviceId("dev-123")
        assertEquals("dev-123", id.value)
        assertEquals("dev-123", id.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun flash_device_id_blank_throws() {
        FlashDeviceId("   ")
    }

    @Test
    fun flash_device_equality_and_defaults() {
        val dev1 = FlashDevice(
            id = FlashDeviceId("dev-1"),
            friendlyName = "Device One",
            transportType = FlashTransportType.LAN
        )
        val dev2 = FlashDevice(
            id = FlashDeviceId("dev-1"),
            friendlyName = "Device One",
            transportType = FlashTransportType.LAN,
            presence = FlashPeerPresence.Online,
            protocolVersion = 1
        )
        val dev3 = dev1.copy(transportType = FlashTransportType.WIFI_DIRECT)

        assertEquals(dev1, dev2)
        assertNotEquals(dev1, dev3)
        assertEquals(FlashPeerPresence.Online, dev1.presence)
        assertEquals(1, dev1.protocolVersion)
    }

    @Test
    fun transport_type_from_string() {
        assertEquals(FlashTransportType.LAN, FlashTransportType.fromString("LAN"))
        assertEquals(FlashTransportType.WIFI_DIRECT, FlashTransportType.fromString("wifi_direct"))
        assertEquals(FlashTransportType.WEBSOCKET, FlashTransportType.fromString("WebSocket"))
        assertEquals(FlashTransportType.UNKNOWN, FlashTransportType.fromString("non_existent_type"))
    }
}
