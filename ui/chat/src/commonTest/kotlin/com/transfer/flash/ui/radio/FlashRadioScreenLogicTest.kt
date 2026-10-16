package com.transfer.flash.ui.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RADUI-U06: Unit and lifecycle logic tests for [RadioGatewayController] and [FakeRadioGatewayController].
 */
class FlashRadioScreenLogicTest {

    @Test
    fun testScanStopsWhenStoppedOrLeft() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(enabled = true)
        )

        controller.startScan()
        assertTrue(controller.state.value.isScanning)
        assertEquals(RadioConnectionStatus.SCANNING, controller.state.value.connectionStatus)
        assertTrue(controller.actionsLog.contains("startScan"))

        // Simulate screen exit / disposal invoking stopScan()
        controller.stopScan()
        assertFalse(controller.state.value.isScanning, "isScanning must be false after stopScan")
        assertEquals(0, controller.state.value.scanCountdownSeconds)
        assertTrue(controller.actionsLog.contains("stopScan"))
    }

    @Test
    fun testCandidateConfirmationFlow() {
        val candidate = DiscoveredRadioDevice(
            id = "38:D2:00:01:35:B5",
            name = "VR-N76",
            category = RadioDeviceCategory.KNOWN_RADIO,
            isPaired = true,
        )
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                candidateRadio = candidate,
                connectionStatus = RadioConnectionStatus.CONFIRMING_CANDIDATE,
            )
        )

        controller.confirmCandidate(candidate.id)
        assertNull(controller.state.value.candidateRadio, "Candidate must be cleared after confirmation")
        assertEquals("VR-N76", controller.state.value.rememberedRadioName)
        assertEquals("38:D2:00:01:35:B5", controller.state.value.rememberedRadioAddress)
        assertEquals(RadioConnectionStatus.CONNECTED, controller.state.value.connectionStatus)
    }

    @Test
    fun testPairDeviceFlow() {
        val device = DiscoveredRadioDevice(
            id = "38:D2:00:01:34:FD",
            name = "UV-Pro",
            category = RadioDeviceCategory.KNOWN_RADIO,
            isPaired = false,
        )
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                discoveredDevices = listOf(device),
                isScanning = true,
            )
        )

        controller.pair(device.id)
        assertFalse(controller.state.value.isScanning, "Pairing must halt active inquiry scan")
        assertEquals("UV-Pro", controller.state.value.rememberedRadioName)
        assertEquals("38:D2:00:01:34:FD", controller.state.value.rememberedRadioAddress)
        assertEquals(RadioConnectionStatus.CONNECTED, controller.state.value.connectionStatus)
    }

    @Test
    fun testForgetRadioClearsIdentity() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                rememberedRadioName = "VR-N76",
                rememberedRadioAddress = "38:D2:00:01:35:B5",
                connectionStatus = RadioConnectionStatus.CONNECTED,
            )
        )

        controller.forget()
        assertNull(controller.state.value.rememberedRadioName)
        assertNull(controller.state.value.rememberedRadioAddress)
        assertEquals(RadioConnectionStatus.CHECKLIST_READY, controller.state.value.connectionStatus)
    }

    @Test
    fun testReconnectRestoresConnected() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                rememberedRadioName = "VR-N76",
                rememberedRadioAddress = "38:D2:00:01:35:B5",
                connectionStatus = RadioConnectionStatus.LINK_LOST,
                failureReason = RadioFailureReason.LINK_LOST,
            )
        )

        controller.reconnect()
        assertEquals(RadioConnectionStatus.CONNECTED, controller.state.value.connectionStatus)
        assertEquals(RadioFailureReason.NONE, controller.state.value.failureReason)
    }

    @Test
    fun testSimulateFailures() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(enabled = true)
        )

        for (reason in RadioFailureReason.entries) {
            if (reason == RadioFailureReason.NONE) continue
            controller.simulateFailure(reason)
            assertEquals(RadioConnectionStatus.FAILED, controller.state.value.connectionStatus)
            assertEquals(reason, controller.state.value.failureReason)
            assertTrue(controller.state.value.statusSentence.isNotBlank())
            assertTrue(controller.state.value.statusNextStep.isNotBlank())
        }
    }

    @Test
    fun testSendTestMessageSetsTransmissionFlags() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                connectionStatus = RadioConnectionStatus.CONNECTED,
            )
        )

        controller.sendTestMessage()
        assertTrue(controller.actionsLog.contains("sendTestMessage"))
        assertTrue(controller.state.value.isSendingTestMessage)
        assertEquals(true, controller.state.value.testMessageSentSuccess)
    }

    @Test
    fun testRequestNearbyDevicesPermissionClearsPermissionFailure() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                nearbyDevicesPermissionGranted = false,
                failureReason = RadioFailureReason.PERMISSION_DENIED,
            )
        )

        controller.requestNearbyDevicesPermission()
        assertTrue(controller.state.value.nearbyDevicesPermissionGranted)
        assertEquals(RadioFailureReason.NONE, controller.state.value.failureReason)
    }
}
