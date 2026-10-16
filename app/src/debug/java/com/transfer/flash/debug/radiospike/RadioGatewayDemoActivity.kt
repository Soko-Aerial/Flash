package com.transfer.flash.debug.radiospike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.transfer.flash.ui.radio.FakeRadioGatewayController
import com.transfer.flash.ui.radio.FlashRadioGatewayScreen
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Debug-only entry point for the Radio Gateway UI (Task 4e).
 *
 * Allows the owner or testers to exercise every screen state, permission denial, candidate
 * confirmation, scan simulation, and security authorization dialog without physical radio hardware.
 * Resides exclusively in app/src/debug/ and is excluded from release builds.
 */
class RadioGatewayDemoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlashTheme {
                val controller = remember {
                    FakeRadioGatewayController().apply {
                        setEnabled(true)
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
