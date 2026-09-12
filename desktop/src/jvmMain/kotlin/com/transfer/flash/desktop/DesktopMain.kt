package com.transfer.flash.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Flash desktop entry point (Phase 21, sub-step 21-3).
 *
 * Assembles the desktop composition root ([DesktopEngine] — the no-Hilt, no-`Context`
 * equivalent of `:app`'s `AppEngine`) and opens one window hosting a thin shell over the four
 * shared `:ui:chat` tab screens (Option B: `:app`'s `FlashApp`/`FlashShell` stays Android-only;
 * `:desktop` cannot depend on an `android.application` module).
 *
 * Theme: `FlashTheme` with its system-dark default, exactly like the app's shell. The desktop
 * window has no dynamic-color (Material You) source, so `dynamicAccent` stays off.
 *
 * **Gate status (see the phase file's C3 correction):** this entry point exists and compiles,
 * but running it is NOT the Phase 16 interop gate — that gate needs a physical Android endpoint
 * on the same LAN and remains CLOSED until a human runs G1–G6. Runtime smoke-testing of the
 * desktop window is manual testing, not a gate scenario.
 */
public fun main() = application {
    val engine = remember { DesktopEngine() }
    engine.start()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Flash",
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
    ) {
        FlashTheme {
            DesktopShell(engine = engine)
        }
    }

    // Window disposal reaches here only via exitApplication; stop the engine afterwards so
    // discovery/network resources do not outlive the frame. (Compose Desktop's `application`
    // lambda is non-suspending; a plain call is fine because stop() is synchronous tear-down.)
    engine.stop()
}
