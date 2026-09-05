package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlashDiscoveryModelTest {

    @Test
    fun flashDiscoveryState_defaultsAreIdle() {
        val state = FlashDiscoveryState()
        assertFalse(state.isDiscovering)
        assertFalse(state.isAdvertising)
        assertEquals(0, state.advertisedPort)
        assertEquals("Idle", state.statusMessage)
    }

    @Test
    fun flashDiscoveredEndpoint_delegatesToDevice() {
        val device = FlashDevice(
            id = FlashDeviceId("peer-xyz-123"),
            friendlyName = "Living Room TV",
            transportType = FlashTransportType.LAN,
            presence = FlashPeerPresence.Online,
            protocolVersion = 2,
        )

        val endpoint = FlashDiscoveredEndpoint(
            device = device,
            hostAddress = "192.168.1.150",
            port = 45820,
            serviceName = "Flash Living Room TV",
        )

        assertEquals("peer-xyz-123", endpoint.deviceId.value)
        assertEquals("Living Room TV", endpoint.friendlyName)
        assertEquals(FlashTransportType.LAN, endpoint.transportType)
        assertEquals("192.168.1.150", endpoint.hostAddress)
        assertEquals(45820, endpoint.port)
        assertEquals("Flash Living Room TV", endpoint.serviceName)
    }
}
