@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import com.shepeliev.webrtckmp.WebRtc
import dev.onvoid.webrtc.logging.Logging
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationState
import com.transfer.flash.ui.navigation.rememberFlashNavigationState
import com.transfer.flash.ui.icons.FlashIcons
import org.jetbrains.compose.resources.painterResource
import java.awt.SystemTray
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
public fun main(args: Array<String> = emptyArray()) {
    installDesktopLogSink()

    // Skiko vsync and framerate tuning to prevent GPU spin on integrated graphics (e.g. Intel UHD 620)
    if (System.getProperty("skiko.vsync.enabled") == null) {
        System.setProperty("skiko.vsync.enabled", "true")
    }
    if (System.getProperty("skiko.fps") == null) {
        System.setProperty("skiko.fps", "60")
    }

    // Enforce single-instance: if another instance is already running, forward files or activate, then exit.
    if (!SingleInstanceController.acquireOrActivate(args = args)) {
        FlashLog.i("MAIN", "Flash is already running. Request forwarded to existing instance. Exiting duplicate instance.")
        return
    }

    installNativeWebRtcLogging()

    application {
        val engine = remember { DesktopEngine() }
        engine.start()

    var isWindowVisible by remember { mutableStateOf(true) }
    var isWindowFocused by remember { mutableStateOf(true) }
    var backgroundUnreadCount by remember { mutableStateOf(0) }
    var currentComposeWindow by remember { mutableStateOf<java.awt.Window?>(null) }

    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
    val trayState = rememberTrayState()
    val desktopSettings by engine.settings.collectAsState()
    val nav = rememberFlashNavigationState()

    var pendingFilesToShare by remember {
        mutableStateOf(SingleInstanceController.consumeInitialShareFiles())
    }

    DisposableEffect(desktopSettings.windowsContextMenu) {
        if (WindowsContextMenuManager.isSupported && desktopSettings.windowsContextMenu) {
            WindowsContextMenuManager.setContextMenuEnabled(true)
        }
        onDispose {}
    }

    // Desktop notification manager
    val notificationManager = remember {
        DesktopNotificationManager(
            engine = engine,
            scope = engine.scope,
            sendNotification = { trayState.sendNotification(it) },
            isWindowVisible = { isWindowVisible },
            activeConversationId = { nav.current.conversationId },
            isNotificationsEnabled = { engine.settings.value.showNotifications },
            isWindowMinimized = { windowState.isMinimized },
            isWindowFocused = { isWindowFocused },
        ).apply {
            onBackgroundMessageReceived = {
                backgroundUnreadCount++
                DesktopTaskbarBadgeManager.updateBadge(currentComposeWindow, backgroundUnreadCount)
            }
        }
    }

    DisposableEffect(notificationManager) {
        notificationManager.start()
        onDispose { notificationManager.stop() }
    }

    // System Tray
    if (SystemTray.isSupported()) {
        val ready by engine.ready.collectAsState()
        val currentMode by engine.discoveryMode.collectAsState()
        val modeLabel = FlashSettingsMath.discoveryModeShortLabel(currentMode.name)
        val trayTooltip = if (ready) "Flash - Online ($modeLabel)" else "Flash - Connecting..."

        Tray(
            icon = painterResource(FlashIcons.Tray.drawableRes),
            state = trayState,
            tooltip = trayTooltip,
            onAction = {
                isWindowVisible = true
                windowState.isMinimized = false
                backgroundUnreadCount = 0
                DesktopTaskbarBadgeManager.clearBadge(currentComposeWindow)
            },
            menu = {
                Item(
                    text = if (isWindowVisible) "Hide Flash" else "Open Flash",
                    onClick = {
                        if (isWindowVisible) {
                            isWindowVisible = false
                        } else {
                            isWindowVisible = true
                            windowState.isMinimized = false
                            backgroundUnreadCount = 0
                            DesktopTaskbarBadgeManager.clearBadge(currentComposeWindow)
                        }
                    },
                )
                Separator()
                CheckboxItem(
                    text = "Discoverable",
                    checked = currentMode != FlashDiscoveryMode.GHOST,
                    onCheckedChange = { isDiscoverable ->
                        engine.setDiscoveryMode(
                            if (isDiscoverable) FlashDiscoveryMode.STANDARD else FlashDiscoveryMode.GHOST
                        )
                    },
                )
                Item(
                    text = "Mode: $modeLabel (Switch)",
                    onClick = {
                        val next = when (currentMode) {
                            FlashDiscoveryMode.STANDARD -> FlashDiscoveryMode.GHOST
                            FlashDiscoveryMode.GHOST -> FlashDiscoveryMode.ECO
                            FlashDiscoveryMode.ECO -> FlashDiscoveryMode.BOOST
                            FlashDiscoveryMode.BOOST, FlashDiscoveryMode.RECEIVE_KIOSK -> FlashDiscoveryMode.STANDARD
                        }
                        engine.setDiscoveryMode(next)
                    },
                )
                Separator()
                Item(
                    text = "Chats",
                    onClick = {
                        isWindowVisible = true
                        windowState.isMinimized = false
                        backgroundUnreadCount = 0
                        DesktopTaskbarBadgeManager.clearBadge(currentComposeWindow)
                        nav.selectTab(FlashDestination.ChatList)
                    },
                )
                Item(
                    text = "Transfers",
                    onClick = {
                        isWindowVisible = true
                        windowState.isMinimized = false
                        nav.selectTab(FlashDestination.Transfers)
                    },
                )
                Item(
                    text = "Nearby Devices",
                    onClick = {
                        isWindowVisible = true
                        windowState.isMinimized = false
                        nav.selectTab(FlashDestination.NearbyDevices)
                    },
                )
                Item(
                    text = "Settings",
                    onClick = {
                        isWindowVisible = true
                        windowState.isMinimized = false
                        nav.selectTab(FlashDestination.Settings)
                    },
                )
                Separator()
                Item(
                    text = "Quit Flash",
                    onClick = ::exitApplication,
                )
            },
        )
    }

    val appIconPainter = remember {
        BitmapPainter(DesktopTaskbarBadgeManager.renderIcon(64, badgeCount = 0).toComposeImageBitmap())
    }

    if (isWindowVisible) {
        Window(
            onCloseRequest = {
                if (desktopSettings.closeToTray && SystemTray.isSupported()) {
                    isWindowVisible = false
                } else {
                    exitApplication()
                }
            },
            title = "Flash",
            icon = appIconPainter,
            state = windowState,
        ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        DisposableEffect(window, density) {
            currentComposeWindow = window
            val minWidthPx = (640 * density.density).toInt()
            val minHeightPx = (480 * density.density).toInt()
            window.minimumSize = java.awt.Dimension(minWidthPx, minHeightPx)

            // Supply multi-resolution high-DPI icons (16, 24, 32, 48, 64px) for OS title bar, Alt+Tab, and taskbar
            window.iconImages = DesktopTaskbarBadgeManager.getBaseIcons()

            // Ensure taskbar reflects current badge state
            DesktopTaskbarBadgeManager.updateBadge(window, backgroundUnreadCount)

            val focusListener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                    isWindowFocused = true
                    backgroundUnreadCount = 0
                    DesktopTaskbarBadgeManager.clearBadge(window)
                }

                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    isWindowFocused = false
                }
            }
            window.addWindowFocusListener(focusListener)
            isWindowFocused = window.isFocused

            onDispose {
                window.removeWindowFocusListener(focusListener)
                if (currentComposeWindow == window) {
                    currentComposeWindow = null
                }
            }
        }

        // Reactive settings: Theme Mode and Performance Mode are observed directly from the engine's
        // StateFlow so changes in Settings take effect immediately without requiring an app restart.
        val desktopSettings by engine.settings.collectAsState()
        val darkTheme = FlashSettingsMath.resolveDarkTheme(
            mode = desktopSettings.themeMode,
            systemDark = isSystemInDarkTheme(),
        )
        val effectivePerformanceMode = desktopSettings.performanceMode ?: FlashPerformanceMode.HIGH
        val reduceMotionResolved = effectivePerformanceMode.reduceMotion

        val baseDensity = androidx.compose.ui.platform.LocalDensity.current
        val effectiveDensity = remember(baseDensity, desktopSettings.uiScale) {
            androidx.compose.ui.unit.Density(
                density = baseDensity.density * desktopSettings.uiScale.coerceIn(0.75f, 1.5f),
                fontScale = baseDensity.fontScale,
            )
        }

        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides effectiveDensity,
        ) {
            FlashMaterialTheme(
                darkTheme = darkTheme,
                dynamicColor = desktopSettings.dynamicAccent,
            ) {
                FlashTheme(
                    darkTheme = darkTheme,
                    dynamicAccent = desktopSettings.dynamicAccent,
                    hapticsEnabled = false,
                    minimalChrome = effectivePerformanceMode.minimalChrome,
                    motion = rememberFlashMotion(reduceMotionResolved),
                ) {
                    // Baseline text colour for the whole desktop window.
                    CompositionLocalProvider(LocalContentColor provides FlashTheme.colors.textPrimary) {
                        DesktopShell(
                            engine = engine,
                            themeMode = desktopSettings.themeMode,
                            onThemeModeSelected = { mode ->
                                engine.storeThemeMode(mode)
                            },
                            window = window,
                            nav = nav,
                            externalShareFiles = pendingFilesToShare,
                            onClearExternalShareFiles = { pendingFilesToShare = emptyList() },
                        )
                    }
                }
            }
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
        SingleInstanceController.onActivate = {
            isWindowVisible = true
            windowState.isMinimized = false
            currentComposeWindow?.let { win ->
                win.isVisible = true
                if (win is java.awt.Frame) {
                    val state = win.extendedState
                    if ((state and java.awt.Frame.ICONIFIED) != 0) {
                        win.extendedState = state and java.awt.Frame.ICONIFIED.inv()
                    }
                }
                win.toFront()
                win.requestFocus()
            }
            backgroundUnreadCount = 0
            DesktopTaskbarBadgeManager.clearBadge(currentComposeWindow)
        }
        SingleInstanceController.onShareFiles = { files ->
            isWindowVisible = true
            windowState.isMinimized = false
            currentComposeWindow?.let { win ->
                win.isVisible = true
                if (win is java.awt.Frame) {
                    val state = win.extendedState
                    if ((state and java.awt.Frame.ICONIFIED) != 0) {
                        win.extendedState = state and java.awt.Frame.ICONIFIED.inv()
                    }
                }
                win.toFront()
                win.requestFocus()
            }
            backgroundUnreadCount = 0
            DesktopTaskbarBadgeManager.clearBadge(currentComposeWindow)
            pendingFilesToShare = (pendingFilesToShare + files).distinctBy { it.absolutePath }
        }
        onDispose {
            SingleInstanceController.onActivate = null
            SingleInstanceController.onShareFiles = null
            SingleInstanceController.release()
            engine.stop()
        }
    }
    }
}

/**
 * Forwards webrtc-java's NATIVE log (WASAPI/COM HRESULTs, ADM device opens, APM init) to the
 * console. Java exceptions never carry these failures — the silent buzz/zeros failure mode is
 * only visible here. WARNING, not INFO: INFO logs every ICE ping and buries the call.
 * Raise to [Logging.Severity.INFO] (or VERBOSE) for one run when chasing a live audio fault.
 * Must run before the first call: the fork reads the builder once at factory init.
 * Test-safe: `DesktopEngine` in tests never touches this (no factory init without a call).
 */
private fun installNativeWebRtcLogging() {
    try {
        WebRtc.configureBuilder { loggingSeverity = Logging.Severity.WARNING }
    } catch (_: Throwable) {
        // Native logging is diagnostic-only; never fail the launch for it.
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
