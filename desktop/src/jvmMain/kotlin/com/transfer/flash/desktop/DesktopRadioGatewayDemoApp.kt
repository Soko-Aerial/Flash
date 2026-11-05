package com.transfer.flash.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.radio.DiscoveredRadioDevice
import com.transfer.flash.ui.radio.FakeRadioGatewayController
import com.transfer.flash.ui.radio.FlashRadioGatewayScreen
import com.transfer.flash.ui.radio.RadioConnectionStatus
import com.transfer.flash.ui.radio.RadioDeviceCategory
import com.transfer.flash.ui.radio.RadioGatewayUiState
import com.transfer.flash.ui.radio.RadioVoicePhase
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Desktop demo application for the Radio Gateway UI (Task 4e).
 *
 * Allows exercising all UI states, device rankings, live scans, and security authorization
 * on Windows desktop without requiring a physical radio peripheral.
 *
 * Redesign v3 opens on a connected station with the audio link listening - the state the plate, the
 * paired-radio list and the talk bar are designed around. The shipped desktop host reports no radio
 * voice, so this demo drives the fake's voice state to show the panel; the real screen shows nothing
 * where the host has no audio.
 */
public fun main() {
    application {
        val windowState = rememberWindowState(width = 1180.dp, height = 900.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Flash Radio Gateway Demo",
        ) {
            FlashTheme(darkTheme = true) {
                val controller = remember {
                    FakeRadioGatewayController(
                        initialState = RadioGatewayUiState(
                            enabled = true,
                            connectionStatus = RadioConnectionStatus.CONNECTED,
                            rememberedRadioName = "VR-N76",
                            rememberedRadioAddress = "38:D2:00:01:35:B5",
                            isAuthorisedForEncryption = true,
                            operatorCallsign = "W1AW-7",
                        ),
                    ).apply {
                        simulateDiscoveredDevices(
                            listOf(
                                DiscoveredRadioDevice(
                                    id = "38:D2:00:01:34:FD",
                                    name = "UV-Pro",
                                    category = RadioDeviceCategory.KNOWN_RADIO,
                                    isPaired = true,
                                    rssi = -71,
                                ),
                            ),
                        )
                        simulateVoice(RadioVoicePhase.LISTENING, rxLevel = 0.35f)
                    }
                }
                FlashRadioGatewayScreen(
                    controller = controller,
                    onBack = ::exitApplication,
                )
            }
        }
    }
}
