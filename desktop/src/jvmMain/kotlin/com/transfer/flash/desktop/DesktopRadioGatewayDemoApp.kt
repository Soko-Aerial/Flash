package com.transfer.flash.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.radio.FakeRadioGatewayController
import com.transfer.flash.ui.radio.FlashRadioGatewayScreen
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Desktop demo application for the Radio Gateway UI (Task 4e).
 *
 * Allows exercising all UI states, device rankings, live scans, and security authorization
 * on Windows desktop without requiring a physical radio peripheral.
 */
public fun main() {
    application {
        val windowState = rememberWindowState(width = 1100.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Flash — Radio Gateway Demo",
        ) {
            FlashTheme(darkTheme = true) {
                val controller = androidx.compose.runtime.remember {
                    FakeRadioGatewayController().apply {
                        setEnabled(true)
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
