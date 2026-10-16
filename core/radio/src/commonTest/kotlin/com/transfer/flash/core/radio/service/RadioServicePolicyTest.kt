package com.transfer.flash.core.radio.service

import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioCandidateDevice
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioServicePolicyTest {

    @Test
    fun radlnkU16_shouldServiceRunTrueOnlyForConnectedOrConnectingWhenEnabled() {
        val radio = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
        val candidate = RadioCandidateDevice("VR-N76", "38:D2:00:01:35:B5", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)

        // 1. Off state
        assertFalse(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = false)))

        // 2. Enabled but in checklist / detecting / scanning / pairing / failed
        assertFalse(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.ChecklistShown)))
        assertFalse(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Detecting)))
        assertFalse(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Scanning(emptyList()))))
        assertFalse(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Pairing(candidate))))
        assertFalse(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Failed(RadioConnectFailureReason.LINK_LOST))))

        // 3. Enabled and connecting
        assertTrue(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Connecting(candidate))))

        // 4. Enabled and connected
        assertTrue(RadioServicePolicy.shouldServiceRun(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Connected(radio))))

        // 5. Enabled and connected, but retries stopped after limit
        assertFalse(RadioServicePolicy.shouldServiceRun(
            RadioGatewayState(
                isEnabled = true,
                connectState = RadioConnectState.Connected(radio),
                isRetryingStopped = true
            )
        ))
    }

    @Test
    fun radlnkU17_honestNotificationTitlesAndTextsReflectActualState() {
        val radio = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")

        // 1. Connected state
        val s1 = RadioGatewayState(
            isEnabled = true,
            connectState = RadioConnectState.Connected(radio),
            linkState = TncLinkState.Connected("COM16", 1000L),
            rememberedRadio = radio
        )
        val notif1 = RadioServicePolicy.notificationContent(s1)
        assertEquals("Radio Gateway Active", notif1.title)
        assertTrue(notif1.text.contains("Connected to VR-N76"))

        // 2. Waiting (retry) state
        val s2 = RadioGatewayState(
            isEnabled = true,
            connectState = RadioConnectState.Connected(radio),
            linkState = TncLinkState.Waiting(attempt = 2, retryInMs = 3000L, reason = "eof"),
            rememberedRadio = radio
        )
        val notif2 = RadioServicePolicy.notificationContent(s2)
        assertEquals("Radio Gateway Active", notif2.title)
        assertTrue(notif2.text.contains("attempt 2"))

        // 3. Connecting state
        val s3 = RadioGatewayState(
            isEnabled = true,
            connectState = RadioConnectState.Connecting(
                RadioCandidateDevice("VR-N76", "38:D2:00:01:35:B5", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)
            ),
            linkState = TncLinkState.Connecting(attempt = 1),
            rememberedRadio = radio
        )
        val notif3 = RadioServicePolicy.notificationContent(s3)
        assertEquals("Radio Gateway Active", notif3.title)
        assertTrue(notif3.text.contains("Connecting to VR-N76"))
    }
}
