package com.transfer.flash.debug.radiospike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.transfer.flash.ui.radio.FakeRadioGatewayController
import com.transfer.flash.ui.radio.FlashRadioGatewayScreen
import com.transfer.flash.ui.radio.RadioConnectionStatus
import com.transfer.flash.ui.radio.RadioGatewayUiState
import com.transfer.flash.ui.radio.RadioVoicePhase
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Debug-only entry point for the Radio Gateway UI (Task 4e).
 *
 * Allows the owner or testers to exercise every screen state, permission denial, candidate
 * confirmation, scan simulation, and security authorization dialog without physical radio hardware.
 * Resides exclusively in app/src/debug/ and is excluded from release builds.
 *
 * Redesign v3 opens on a connected station with the audio link listening, because that is the state the
 * pairing sheet, the talk bar and the "your radios" list are designed around; the master switch still
 * drops the page back to its off state.
 */
class RadioGatewayDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlashTheme {
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
                                com.transfer.flash.ui.radio.DiscoveredRadioDevice(
                                    id = "38:D2:00:01:34:FD",
                                    name = "UV-Pro",
                                    category = com.transfer.flash.ui.radio.RadioDeviceCategory.KNOWN_RADIO,
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
                    onBack = { finish() },
                )
            }
        }
    }
}
