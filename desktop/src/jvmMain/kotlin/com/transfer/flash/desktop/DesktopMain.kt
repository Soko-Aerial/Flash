@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogSink
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.theme.FlashTheme
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

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
    installDesktopLogSink()
    val engine = remember { DesktopEngine() }
    engine.start()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Flash",
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
    ) {
        // Appearance. The selection is owned here because it has to sit ABOVE `FlashTheme` — the
        // theme cannot hold the state that selects it. Seeded from the persisted value so it
        // survives a restart, and resolved through the SHARED `FlashSettingsMath.resolveDarkTheme`
        // so desktop and Android cannot drift on what "System" means.
        //
        // This replaces `FlashTheme { }`, whose argument-less form pinned the desktop to whatever
        // `isSystemInDarkTheme()` reported: the Settings → Appearance control existed, moved, and
        // changed nothing.
        var themeMode by remember { mutableStateOf(engine.storedThemeMode()) }
        val darkTheme = FlashSettingsMath.resolveDarkTheme(
            mode = themeMode,
            systemDark = isSystemInDarkTheme(),
        )
        FlashTheme(darkTheme = darkTheme) {
            // Baseline text colour for the whole desktop window.
            //
            // `FlashTypography` sets no colour, so an unstyled `FlashText` falls through to
            // `BasicText`'s default of `LocalContentColor` — and **nothing in this repo provides
            // that local**. On Android it is supplied by whatever Material3 surface the content sits
            // in (`Scaffold`, `AlertDialog`, `ModalBottomSheet`); the desktop shell is a plain `Box`
            // with a `.background(...)`, so there is no such surface and every unstyled string drew
            // `Color.Black`.
            //
            // On the dark palette that is black-on-black: the whole Transfer Details and Peer Details
            // panes (`DesktopDetailPanes.kt`) were unreadable, and the clear-received-files
            // confirmation's title and body were invisible. Providing the token once here fixes all
            // of them, and reaches the sheets and dialogs too — a `Dialog` layer inherits the
            // ambient `CompositionLocalContext`, so `FlashOverlayLayer` sees this value even though
            // it composes into its own scene layer.
            CompositionLocalProvider(LocalContentColor provides FlashTheme.colors.textPrimary) {
                DesktopShell(
                    engine = engine,
                    themeMode = themeMode,
                    onThemeModeSelected = { mode ->
                        themeMode = mode
                        engine.storeThemeMode(mode)
                    },
                )
            }
        }
    }

    // Teardown, in a `DisposableEffect` and NOT as a bare statement — this one line is why pairing
    // worked in the harness and not in the app (2026-09-14).
    //
    // A composable body is not a `main` function: it re-executes on every recomposition, and
    // `Window(...)` does **not** block until the window closes — `application { }` is what keeps the
    // process alive (its `runBlocking` parks the main thread, which is exactly what a thread dump
    // shows). So a trailing `engine.stop()` runs ~immediately after the first composition, a few
    // milliseconds after `start()`:
    //
    //  - `scope.cancel()` kills the engine's scope while `assemble()` is still in flight. The
    //    blocking part of the bring-up still runs (the WS server binds, the transports start and
    //    keep their own loops), so the console looks healthy — `Found Flash V760`, multicast bound —
    //    while every `scope.launch` from then on (the dial triggers, the roster collector, the
    //    session collectors) is created on a cancelled scope and never runs.
    //  - Result: a peer in the roster, `active sessions=[]` forever, no dial attempt, no session-up
    //    pairing hello, and a Pair tap that does nothing but report "Couldn't reach …".
    //  - `discoveryImpl?.stopAll()`/`networkImpl?.stop()` in `stop()` no-op, because at that instant
    //    `assemble()` has not yet assigned them — which is why the transports outlived their engine.
    //
    // Keyed on `Unit`, so it disposes only when this content leaves the composition (exit or
    // `exitApplication`), never on a recomposition. `onDispose` runs on the Compose thread, so
    // `stop()` stays a plain synchronous call.
    DisposableEffect(Unit) {
        onDispose { engine.stop() }
    }
}

/**
 * Tees `FlashLog` to **and** `<stateDir>/desktop.log` (default `~/.flash/desktop.log`).
 *
 * The file exists because the console is not a reliable record. `:desktop:run` is a Gradle
 * `JavaExec` under a progress renderer that rewrites lines, and stderr from the forked JVM
 * interleaves with Gradle's own output — so a diagnosis has twice now been argued from the
 * *absence* of a line in a pasted console, which is not evidence anyone should have to rely on.
 * The log file is complete, ordered, and identical on every run.
 *
 * Truncated per run (`append = false`) rather than appended: what a hardware run needs is one
 * run's timeline, and an append-only file quietly grows on a machine where the app is relaunched
 * all day. Format and stream are unchanged from the default JVM sink (`I/WS: message`, stderr) so
 * every existing grep in the runbook keeps working.
 *
 * Deliberately not routed through the engine: `DesktopEngine` is also constructed by tests, which
 * must not write to a user's home directory.
 */
private fun installDesktopLogSink() {
    val file = try {
        File(System.getProperty("user.home", "."), ".flash").apply { mkdirs() }
            .resolve("desktop.log")
    } catch (_: Throwable) {
        return
    }
    val writer = try {
        PrintWriter(FileWriter(file, false), true)
    } catch (_: Throwable) {
        // Unwritable home directory: keep the default sink rather than fail the launch.
        return
    }
    FlashLog.installSink(
        FlashLogSink { level: FlashLogLevel, tag: String, message: String, throwable: Throwable? ->
            // A logging failure must never reach a caller — same contract as the platform sink.
            try {
                System.err.println("${level.name.first()}/$tag: $message")
                throwable?.printStackTrace(System.err)
                writer.println("${level.name.first()}/$tag: $message")
                throwable?.printStackTrace(writer)
            } catch (_: Throwable) {
                // ignore
            }
        },
    )
}
