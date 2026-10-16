package com.transfer.flash.ui.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADUI-U01: Unit tests for [FlashRadioMath] view logic and truth tables.
 */
class FlashRadioMathTest {

    @Test
    fun testVisibleModesHidesMode3() {
        val modes = FlashRadioMath.visibleModes()
        assertEquals(4, modes.size)
        assertTrue(RadioGatewayMode.FIELD_COMPANION in modes)
        assertTrue(RadioGatewayMode.BASE_STATION_RELAY in modes)
        assertTrue(RadioGatewayMode.TACTICAL_RADAR in modes)
        assertTrue(RadioGatewayMode.ALL_IN_ONE in modes)
        assertFalse(RadioGatewayMode.WALKIE_TALKIE_BRIDGE in modes, "Mode 3 must be hidden pending audio spike")
    }

    @Test
    fun testModeSelectableOnlyIfInAvailableModes() {
        val available = setOf(RadioGatewayMode.FIELD_COMPANION)
        assertTrue(FlashRadioMath.isModeSelectable(RadioGatewayMode.FIELD_COMPANION, available))
        assertFalse(FlashRadioMath.isModeSelectable(RadioGatewayMode.BASE_STATION_RELAY, available))
        assertFalse(FlashRadioMath.isModeSelectable(RadioGatewayMode.TACTICAL_RADAR, available))
        assertFalse(FlashRadioMath.isModeSelectable(RadioGatewayMode.ALL_IN_ONE, available))
    }

    @Test
    fun testModeBadgeText() {
        val available = setOf(RadioGatewayMode.FIELD_COMPANION)
        assertEquals(
            FlashRadioCopy.BADGE_ACTIVE,
            FlashRadioMath.modeBadgeText(RadioGatewayMode.FIELD_COMPANION, RadioGatewayMode.FIELD_COMPANION, available)
        )
        assertEquals(
            FlashRadioCopy.BADGE_COMING_LATER,
            FlashRadioMath.modeBadgeText(RadioGatewayMode.BASE_STATION_RELAY, RadioGatewayMode.FIELD_COMPANION, available)
        )
    }

    @Test
    fun testCategoryBadgeText() {
        assertEquals("RADIO", FlashRadioMath.categoryBadgeText(RadioDeviceCategory.KNOWN_RADIO))
        assertEquals("SERIAL SPP", FlashRadioMath.categoryBadgeText(RadioDeviceCategory.SERIAL_SPP))
        assertEquals("OTHER", FlashRadioMath.categoryBadgeText(RadioDeviceCategory.OTHER))
    }

    @Test
    fun testSortDiscoveredDevices() {
        val d1 = DiscoveredRadioDevice("1", "Alpha-Headset", RadioDeviceCategory.OTHER, isPaired = false, rssi = -50)
        val d2 = DiscoveredRadioDevice("2", "Serial-Adapter", RadioDeviceCategory.SERIAL_SPP, isPaired = false, rssi = -60)
        val d3 = DiscoveredRadioDevice("3", "VR-N76", RadioDeviceCategory.KNOWN_RADIO, isPaired = false, rssi = -70)
        val d4 = DiscoveredRadioDevice("4", "UV-Pro", RadioDeviceCategory.KNOWN_RADIO, isPaired = true, rssi = -80)

        val sorted = FlashRadioMath.sortDiscoveredDevices(listOf(d1, d2, d3, d4))

        // Order: KNOWN_RADIO (paired first, so UV-Pro then VR-N76), then SERIAL_SPP, then OTHER
        assertEquals("UV-Pro", sorted[0].name)
        assertEquals("VR-N76", sorted[1].name)
        assertEquals("Serial-Adapter", sorted[2].name)
        assertEquals("Alpha-Headset", sorted[3].name)
    }

    @Test
    fun testCanSendTestMessage() {
        assertTrue(FlashRadioMath.canSendTestMessage(RadioConnectionStatus.CONNECTED, isSending = false))
        assertFalse(FlashRadioMath.canSendTestMessage(RadioConnectionStatus.CONNECTED, isSending = true))
        assertFalse(FlashRadioMath.canSendTestMessage(RadioConnectionStatus.CONNECTING, isSending = false))
        assertFalse(FlashRadioMath.canSendTestMessage(RadioConnectionStatus.OFF, isSending = false))
    }

    @Test
    fun testCanScanAndStopScan() {
        assertTrue(FlashRadioMath.canScan(enabled = true, isScanning = false))
        assertFalse(FlashRadioMath.canScan(enabled = true, isScanning = true))
        assertFalse(FlashRadioMath.canScan(enabled = false, isScanning = false))

        assertTrue(FlashRadioMath.canStopScan(isScanning = true))
        assertFalse(FlashRadioMath.canStopScan(isScanning = false))
    }

    @Test
    fun testCanReconnect() {
        assertTrue(FlashRadioMath.canReconnect(RadioConnectionStatus.LINK_LOST, hasRememberedRadio = true))
        assertTrue(FlashRadioMath.canReconnect(RadioConnectionStatus.FAILED, hasRememberedRadio = true))
        assertTrue(FlashRadioMath.canReconnect(RadioConnectionStatus.CHECKLIST_READY, hasRememberedRadio = true))
        assertFalse(FlashRadioMath.canReconnect(RadioConnectionStatus.CONNECTED, hasRememberedRadio = true))
        assertFalse(FlashRadioMath.canReconnect(RadioConnectionStatus.LINK_LOST, hasRememberedRadio = false))
    }

    @Test
    fun testEveryStatusAndFailureProducesNonEmptySentenceAndNextStep() {
        for (status in RadioConnectionStatus.entries) {
            val sentence = FlashRadioMath.statusSentence(status, RadioFailureReason.NONE, "VR-N76")
            val nextStep = FlashRadioMath.statusNextStep(status, RadioFailureReason.NONE)
            assertTrue(sentence.isNotBlank(), "Status $status must have non-blank sentence")
            assertTrue(nextStep.isNotBlank(), "Status $status must have non-blank nextStep")
        }

        for (reason in RadioFailureReason.entries) {
            if (reason == RadioFailureReason.NONE) continue
            val sentence = FlashRadioMath.statusSentence(RadioConnectionStatus.FAILED, reason, "VR-N76")
            val nextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.FAILED, reason)
            assertTrue(sentence.isNotBlank(), "Reason $reason must have non-blank sentence")
            assertTrue(nextStep.isNotBlank(), "Reason $reason must have non-blank nextStep")
        }
    }
}
