@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.theme.FlashLaunchSplashGate
import com.transfer.flash.ui.theme.FlashLaunchSplashOverlay
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
import java.io.BufferedWriter
import java.io.FileWriter
import kotlinx.coroutines.launch

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
    // Skiko vsync and framerate tuning to prevent GPU spin on integrated graphics (e.g. Intel UHD 620)
    if (System.getProperty("skiko.vsync.enabled") == null) {
        System.setProperty("skiko.vsync.enabled", "true")
    }
    if (System.getProperty("skiko.fps") == null) {
        System.setProperty("skiko.fps", "60")
    }

    // OPT-O14-06: Cap default Dispatchers.IO thread pool to 16 threads (default is 64 on modern multi-core PCs).
    // Reduces idle thread footprint and stack memory allocation without bottlenecking file I/O or networking.
    if (System.getProperty("kotlinx.coroutines.io.parallelism") == null) {
        System.setProperty("kotlinx.coroutines.io.parallelism", "16")
    }

    // Enforce single-instance: if another instance is already running, forward files or activate, then exit.
    // Before the log sink on purpose (ERROR-744): the sink truncates `desktop.log`, and a duplicate launch used to
    // wipe the running instance's log, the file a bug report depends on.
    if (!SingleInstanceController.acquireOrActivate(args = args)) {
        FlashLog.i("MAIN", "Flash is already running. Request forwarded to existing instance. Exiting duplicate instance.")
        return
    }

    installDesktopLogSink()

    installNativeWebRtcLogging()

    application {
        val engine = remember { DesktopEngine() }
        engine.start()
        // Own the controller above the conditional Window and Settings destinations.
        // A stored-ON gateway starts now and keeps the same manager across tray/tab changes.
        val radioGatewayController = remember(engine) {
            com.transfer.flash.desktop.radio.DesktopRadioGatewayController(
                scope = engine.scope,
                onManagerChanged = com.transfer.flash.desktop.radio.DesktopRadioManagers::publish,
            )
        }

        var pendingFilesToShare by remember {
            mutableStateOf(SingleInstanceController.consumeInitialShareFiles())
        }

        // UI-056 / ADR-080: the launch animation plays once per process, at start-up. The gate lives
        // here, above `if (isWindowVisible)`, because hiding to the tray disposes the window's
        // composition and reopening it must not replay the splash. Skip when launched directly into
        // a file share (e.g. "Send with Flash" from Explorer) so the target sheet appears immediately.
        val launchSplash = remember {
            FlashLaunchSplashGate(nowMillis = { System.nanoTime() / 1_000_000L }).apply {
                decide(
                    enabled = shouldPlayDesktopLaunchSplash(
                        launchAnimationEnabled = engine.settings.value.launchAnimation,
                        hasInitialShareFiles = pendingFilesToShare.isNotEmpty(),
                    ),
                )
            }
        }

    var isWindowVisible by remember { mutableStateOf(true) }
    var isWindowFocused by remember { mutableStateOf(true) }
    var backgroundUnreadCount by remember { mutableStateOf(0) }
    var currentComposeWindow by remember { mutableStateOf<java.awt.Window?>(null) }

    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    fun bringWindowToFront() {
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

    val nav = rememberFlashNavigationState()

    // Phase 3: Desktop notification manager — native OS balloon dispatch retired in favor of rich custom toast window
    val notificationManager = remember {
        DesktopNotificationManager(
            engine = engine,
            scope = engine.scope,
            sendNotification = null,
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

    // OPT-O19-07: Downscale discovery to ECO when window is hidden in tray or minimized (guarded by active workloads).
    LaunchedEffect(isWindowVisible, windowState.isMinimized) {
        val active = isWindowVisible && !windowState.isMinimized
        engine.setWindowActive(active)
        notificationManager.onWindowVisibilityChanged(active)
    }

    val trayState = rememberTrayState()
    val ready by engine.ready.collectAsState()
    val activationInvite by SingleInstanceController.invites.collectAsState()
    LaunchedEffect(activationInvite) {
        if (activationInvite != null) {
            launchSplash.skip()
            launchSplash.finish()
            bringWindowToFront()
        }
    }
    val enterpriseCredentials by engine.enterpriseTrustStore.credentials.collectAsState()
    val desktopSettings by engine.settings.collectAsState()
    val toastCards by notificationManager.toastCards.collectAsState()
    var toastActionError by remember { mutableStateOf<String?>(null) }
    val osReduceMotion by DesktopOsReduceMotion.preferences.collectAsState(initial = false)
    var trayNavTick by remember { mutableStateOf(0) }

    LaunchedEffect(ready, enterpriseCredentials != null) {
        if (ready) nav.updateHubEnrolled(enterpriseCredentials != null)
    }

    // UI-058 H9: `--hub-route=hub/person/<id>` on the command line opens that Hub page once the app is ready.
    val pendingHubRoute = remember {
        val route = if (args.firstOrNull() == SingleInstanceController.OPEN_INVITE_ARGUMENT) null else
            com.transfer.flash.ui.hub.FlashHubMath.routeFromArgs(args)
        kotlinx.coroutines.flow.MutableStateFlow(route)
    }

    DisposableEffect(desktopSettings.windowsContextMenu) {
        if (WindowsContextMenuManager.isSupported && desktopSettings.windowsContextMenu) {
            WindowsContextMenuManager.setContextMenuEnabled(true)
        }
        onDispose {}
    }

    // System Tray
    if (SystemTray.isSupported()) {
        val currentMode by engine.discoveryMode.collectAsState()
        val modeLabel = FlashSettingsMath.discoveryModeShortLabel(currentMode.name)
        val trayTooltip = if (ready) "Flash - Online ($modeLabel)" else "Flash - Connecting..."
        val trayNavItems = desktopTrayNavItems(nav.hubEnrolled)

        Tray(
            icon = painterResource(FlashIcons.Tray.drawableRes),
            state = trayState,
            tooltip = trayTooltip,
            onAction = {
                bringWindowToFront()
            },
            menu = {
                Item(
                    text = if (isWindowVisible) "Hide Flash" else "Open Flash",
                    onClick = {
                        if (isWindowVisible) {
                            isWindowVisible = false
                        } else {
                            bringWindowToFront()
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
                trayNavItems.forEach { navItem ->
                    Item(
                        text = navItem.label,
                        onClick = {
                            bringWindowToFront()
                            trayNavTick++
                            if (navItem.destination == FlashDestination.Transfers) {
                                nav.openTransfers()
                            } else {
                                nav.selectTab(navItem.destination)
                            }
                        },
                    )
                }
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
                    notificationManager.onWindowFocusChanged(true)
                }

                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    isWindowFocused = false
                    notificationManager.onWindowFocusChanged(false)
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
        // OPT-O24-08: Honor OS-level accessibility reduce-motion settings alongside app performance mode
        val reduceMotionResolved = effectivePerformanceMode.reduceMotion || osReduceMotion

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
                    ultraLowUi = effectivePerformanceMode.ultraLowUi,
                    motion = rememberFlashMotion(reduceMotionResolved),
                ) {
                    // Baseline text colour for the whole desktop window.
                    CompositionLocalProvider(LocalContentColor provides FlashTheme.colors.textPrimary) {
                        val ready by engine.ready.collectAsState()
                        val startError by engine.startError.collectAsState()
                        Box(Modifier.fillMaxSize()) {
                            if (args.firstOrNull() != SingleInstanceController.OPEN_INVITE_ARGUMENT && args.contains("--radio-demo")) {
                                com.transfer.flash.ui.radio.FlashRadioGatewayScreen(
                                    controller = remember { com.transfer.flash.ui.radio.FakeRadioGatewayController().apply { setEnabled(true) } },
                                    onBack = { exitApplication() },
                                )
                            } else {
                                DesktopShell(
                                    engine = engine,
                                    themeMode = desktopSettings.themeMode,
                                    onThemeModeSelected = { mode ->
                                        engine.storeThemeMode(mode)
                                    },
                                    window = window,
                                    nav = nav,
                                    trayNavTick = trayNavTick,
                                    externalShareFiles = pendingFilesToShare,
                                    onClearExternalShareFiles = { pendingFilesToShare = emptyList() },
                                    pendingHubRoute = pendingHubRoute,
                                    radioGatewayController = radioGatewayController,
                                    notificationActionError = toastActionError,
                                    onNotificationActionErrorShown = { toastActionError = null },
                                )
                                FlashLaunchSplashOverlay(
                                    gate = launchSplash,
                                    engineSettled = ready || startError != null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // Phase 3: Desktop Unified Custom Notification Center and OS Balloon Retirement.
    // All notifications (offers, completions, failures, chat pings, calls, pairing) route into DesktopTransferToastWindow.
    run {
        val toastDark = FlashSettingsMath.resolveDarkTheme(mode = desktopSettings.themeMode, systemDark = isSystemInDarkTheme())
        val toastPerformance = desktopSettings.performanceMode ?: FlashPerformanceMode.HIGH
        FlashMaterialTheme(darkTheme = toastDark, dynamicColor = desktopSettings.dynamicAccent) {
            FlashTheme(
                darkTheme = toastDark,
                dynamicAccent = desktopSettings.dynamicAccent,
                hapticsEnabled = false,
                minimalChrome = toastPerformance.minimalChrome,
                ultraLowUi = toastPerformance.ultraLowUi,
                motion = rememberFlashMotion(toastPerformance.reduceMotion || osReduceMotion),
            ) {
                DesktopTransferToastWindow(
                    cards = if (desktopSettings.showNotifications) toastCards else emptyList(),
                    appInForeground = isWindowVisible && isWindowFocused && !windowState.isMinimized,
                    onAction = { action, card ->
                        notificationManager.handleToastAction(
                            action = action,
                            card = card,
                            onReviewPairing = { javax.swing.SwingUtilities.invokeLater { bringWindowToFront() } },
                            onActionFailed = { error ->
                                javax.swing.SwingUtilities.invokeLater {
                                    toastActionError = error
                                    bringWindowToFront()
                                }
                            },
                            onOpenConversation = { convId ->
                                javax.swing.SwingUtilities.invokeLater {
                                    isWindowVisible = true
                                    bringWindowToFront()
                                    nav.navigate(FlashDestination.Conversation, conversationId = convId)
                                }
                            },
                        )
                    },
                    onDismiss = { id ->
                        notificationManager.dismissToast(id)
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
        SingleInstanceController.onActivate = {
            bringWindowToFront()
        }
        SingleInstanceController.onShareFiles = { files ->
            launchSplash.finish()
            bringWindowToFront()
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

internal fun shouldPlayDesktopLaunchSplash(
    launchAnimationEnabled: Boolean,
    hasInitialShareFiles: Boolean,
): Boolean = launchAnimationEnabled && !hasInitialShareFiles

internal data class DesktopTrayNavItem(
    val destination: FlashDestination,
    val label: String,
)

internal fun desktopTrayNavItems(hubEnrolled: Boolean): List<DesktopTrayNavItem> = buildList {
    add(DesktopTrayNavItem(FlashDestination.ChatList, "Chats"))
    if (hubEnrolled) {
        add(DesktopTrayNavItem(FlashDestination.Hub, "Hub"))
    }
    add(DesktopTrayNavItem(FlashDestination.Transfers, "Transfers"))
    add(DesktopTrayNavItem(FlashDestination.NearbyDevices, "Nearby Devices"))
    add(DesktopTrayNavItem(FlashDestination.Settings, "Settings"))
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
 * Tees `FlashLog` to stderr and `<stateDir>/desktop.log` (default `~/.flash/desktop.log`).
 *
 * The file exists because the console is not a reliable record. `:desktop:run` is a Gradle
 * `JavaExec` under a progress renderer that rewrites lines, and stderr from the forked JVM
 * interleaves with Gradle's own output — so a diagnosis has twice now been argued from the
 * *absence* of a line in a pasted console, which is not evidence anyone should have to rely on.
 * The worker preserves admitted record order; overflow and unwritable output are counted/best-effort,
 * so the file must not be described as complete or guaranteed durable.
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
        DesktopPaths.stateDir().apply { mkdirs() }.resolve("desktop.log")
    } catch (_: Throwable) {
        return
    }
    val output = try {
        BufferedWriter(FileWriter(file, false), 16_384)
    } catch (_: Throwable) {
        // Unwritable home directory: keep the default sink rather than fail the launch.
        return
    }
    val sink = DesktopFileLogSink(output)
    Runtime.getRuntime().addShutdownHook(Thread({
        // The worker alone drains and closes. Never race it with a second shutdown writer.
        sink.closeAndAwait()
    }, "FlashDesktopLogFlusher"))
    FlashLog.installSink(sink)
}
