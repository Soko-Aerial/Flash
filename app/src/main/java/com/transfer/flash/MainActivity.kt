@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash

import android.annotation.SuppressLint
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashAdaptiveTwoPane
import com.transfer.flash.ui.adaptive.FlashNavigationRail
import com.transfer.flash.ui.adaptive.FlashNavigationRailTab
import com.transfer.flash.ui.adaptive.FlashPlaceholderDetailPane
import com.transfer.flash.ui.adaptive.FlashTransferDetailPane
import com.transfer.flash.ui.adaptive.FlashNearbyDetailPane
import com.transfer.flash.ui.adaptive.FlashWindowSizeClass
import com.transfer.flash.ui.adaptive.rememberFlashAdaptiveWindowWidthDp
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.chat.FlashSharePayloadUi
import com.transfer.flash.ui.chat.FlashShareItemUi
import com.transfer.flash.ui.chat.FlashShareRecipientUi
import com.transfer.flash.ui.chat.FlashShareTargetSheet
import com.transfer.flash.ui.chat.FlashShareTargetMath
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.chat.FlashEncryptionBadgeState
import com.transfer.flash.ui.chat.FlashEncryptionSheet
import com.transfer.flash.ui.chat.FlashPairingDialog
import com.transfer.flash.ui.chat.FlashSearchScope
import com.transfer.flash.ui.chat.FlashSheetHost
import com.transfer.flash.ui.nearby.FlashManualConnectDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.EmptyFlashChatRepository
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.sweep.SweepRefusal
import com.transfer.flash.core.network.sweep.SweepState
import com.transfer.flash.core.common.perf.FlashMotionPolicy
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.UiScrollGovernor
import com.transfer.flash.core.common.result.getOrNull
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.di.AppEngine
import com.transfer.flash.core.engine.group.GroupFileSender
import com.transfer.flash.debug.DiscoveryEngineHolder
import com.transfer.flash.debug.FlashBackgroundService
import com.transfer.flash.debug.OemBatteryOptimizationHelper
import com.transfer.flash.debug.TranssionGuideDialog
import com.transfer.flash.debug.TranssionSetupGuide
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.navigation.FlashAnimatedScreen
import com.transfer.flash.ui.shell.FlashShellTabs
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath
import com.transfer.flash.ui.navigation.rememberFlashNavigationState
import com.transfer.flash.ui.nearby.FlashNearbyMath
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.nearby.NearbyIdentityUi
import com.transfer.flash.ui.nearby.NearbyNetworkScan
import com.transfer.flash.ui.nearby.NearbyScanBlock
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.nearby.NearbyTrustedPeerUi
import com.transfer.flash.ui.nearby.NearbyUiState
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.calling.FlashCallAudioRouter
import com.transfer.flash.calling.FlashCallPictureInPicture
import com.transfer.flash.calling.FlashCallProximity
import com.transfer.flash.calling.findActivity
import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallAudioRouting
import com.transfer.flash.calling.FlashCallService
import com.transfer.flash.calling.FlashScreenCaptureService
import com.transfer.flash.core.calling.AndroidScreenCaptureHost
import com.transfer.flash.core.calling.AndroidScreenCaptureSupport
import com.transfer.flash.core.ptt.PttSessionEngine
import com.transfer.flash.ptt.PttSessionOverlay
import com.transfer.flash.pairing.PairingUiModel
import com.transfer.flash.ui.calling.FlashCallScreen
import com.transfer.flash.ui.calling.FlashCallShareHost
import com.transfer.flash.ui.settings.FlashDisplayNameDialog
import com.transfer.flash.ui.settings.FlashSettingsModel
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.settings.FlashThemeMode
import com.transfer.flash.core.common.model.FlashChatWallpaper
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.ui.shell.FlashBottomNav
import com.transfer.flash.ui.shell.FlashBottomNavDefaults
import com.transfer.flash.ui.shell.FlashBottomNavItem
import com.transfer.flash.ui.shell.rememberAutoNavVisible
import com.transfer.flash.ui.splash.FlashLaunchSplashProcess
import com.transfer.flash.ui.transfers.FlashTransfersScreen
import com.transfer.flash.ui.transfers.FlashTransfersMath
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.TransfersUiState
import com.transfer.flash.ui.throttleLatest
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.notifications.FlashNotificationManager
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.messaging.model.FlashChatSearchResultsUi
import com.transfer.flash.ui.theme.FlashLaunchSplashGate
import com.transfer.flash.ui.theme.FlashLaunchSplashOverlay
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import com.transfer.flash.ui.theme.rememberSystemReduceMotion
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Showcase host (Phase 8 app shell, docs/ui-page-plan.md): four-tab FlashBottomNav +
 * FlashNavigationState-driven content. Tab switches reset the stack (selectTab); pushes
 * are reserved for Conversation and overlays. Demo tab states below are shaped exactly
 * like the future C5/C3/C1.4 engine mappings so wiring is substitution, not rewrite.
 */
data class FlashReceivedStorageState(
    val totalBytes: Long? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val isClearing: Boolean = false,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appEngine: AppEngine

    // #13: POST_NOTIFICATIONS is a runtime permission on Android 13+ (TIRAMISU). Deferred until
    // after the launch splash settles and paired with an inline rationale / Settings fallback.
    private val hasNotificationPermission = MutableStateFlow(true)
    private val notificationPromptedBefore = MutableStateFlow(false)
    private val notificationBannerDismissed = MutableStateFlow(false)
    private val shouldShowNotificationRationale = MutableStateFlow(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_NOTIFICATIONS_PROMPTED, true)
                .apply()
            notificationPromptedBefore.value = true
            hasNotificationPermission.value = granted
            shouldShowNotificationRationale.value = !granted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
        }

    /**
     * ADR-102 (UI-050g): the system's screen-capture consent dialog. A field-level launcher so it survives configuration
     * changes; [screenCaptureConsentWaiter] carries the answer back to the capturer, which is suspended on it while the
     * dialog is up.
     */
    private var screenCaptureConsentWaiter: CompletableDeferred<Intent?>? = null
    private val screenCaptureConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val waiter = screenCaptureConsentWaiter
            screenCaptureConsentWaiter = null
            waiter?.complete(if (result.resultCode == android.app.Activity.RESULT_OK) result.data else null)
        }

    /**
     * Bug 7: conversation a notification-tap wants to open. Emitted from [onNewIntent] and
     * from the cold-start intent in [onCreate]; consumed by [FlashShell] once the engine
     * is ready (it needs the real repository + navigator), then nulled. A plain
     * MutableStateFlow shared between the activity and the shell in the same process.
     */
    private val pendingNotificationConversation = MutableStateFlow<String?>(null)
    private val pendingCallAnswer = MutableStateFlow(false)
    /**
     * Deferred hardware PTT press (Phase 3): set when a press surfaces the app while
     * backgrounded/unpermitted/pre-boot. The session overlay consumes it (permission
     * prompt included) once the engine is ready — the background itself can never start
     * capture (API 34+ forbids, API 28+ silences).
     */
    private val pendingPttPress = MutableStateFlow(false)

    /**
     * Whether the OS currently exempts Flash from battery optimisation (ERROR-031 / D7).
     *
     * Read here rather than in Compose because the value can only change while the user is away in
     * the system prompt, so [onResume] is exactly the refresh point — and reading it from the
     * activity avoids depending on a lifecycle-aware Compose API just to notice that. Shared with
     * the shell the same way [pendingNotificationConversation] is: one MutableStateFlow, one process.
     */
    private val ignoringBatteryOptimizations = MutableStateFlow(false)

    /** HIB-03: the Transsion set-up guide, opened from the same Settings entry as the battery prompt. */
    private val transsionGuideVisible = MutableStateFlow(false)

    private val pendingShare = MutableStateFlow<FlashSharePayloadUi?>(null)
    private val pendingWidgetSendPicker = MutableStateFlow(false)
    private val widgetSendPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                resolvePendingShare(uris, null)
            }
        }
    private val pendingShortcutTab = MutableStateFlow<FlashDestination?>(null)
    private val pendingJoinLink = MutableStateFlow<String?>(null)
    private val pendingEnrollLink = MutableStateFlow<String?>(null)
    /** UI-058: `hub/announcement/<id>` or `hub/person/<deviceId>` from a notification (explicit intent extra); validated by the Hub before use. */
    private val pendingHubRoute = MutableStateFlow<String?>(null)

    private val receivedStorageState = MutableStateFlow(FlashReceivedStorageState())
    private var receivedStorageJob: Job? = null

    /**
     * ADR-102 (UI-050g): gives `:core:calling` the two things only this activity can provide for a screen share — the
     * system consent dialog and the `mediaProjection` foreground service. Installed on every create (a recreated
     * activity owns a new consent launcher); the capturer reads it per share, so a process that never got here simply
     * cannot present.
     */
    private fun installScreenCaptureSupport() {
        AndroidScreenCaptureSupport.install(object : AndroidScreenCaptureHost {
            override suspend fun requestConsent(): Intent? = requestScreenCaptureConsent()
            override suspend fun startProjection(): Boolean = FlashScreenCaptureService.startAndAwait(this@MainActivity)
            override fun stopProjection() = FlashScreenCaptureService.stop(this@MainActivity)
        })
    }

    /**
     * Shows the system consent dialog and suspends until it is answered (null when refused). Android 14+ lets the user
     * pick the whole screen or a single app (`MediaProjectionConfig.createConfigForUserChoice`); before that the display
     * is the only choice.
     */
    private suspend fun requestScreenCaptureConsent(): Intent? = withContext(Dispatchers.Main) {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return@withContext null
        // One dialog at a time. A second request while the dialog is up waits for the same answer: the launcher delivers
        // exactly one result, so a waiter of its own would sit there until the timeout and then report a refusal nobody
        // made. (Two shares from one consent cannot happen: the session allows one presenter, and Android refuses a
        // second projection on the same consent anyway.)
        screenCaptureConsentWaiter?.let { pending ->
            return@withContext withTimeoutOrNull(SCREEN_CAPTURE_CONSENT_TIMEOUT_MS) { pending.await() }
        }
        val waiter = CompletableDeferred<Intent?>()
        screenCaptureConsentWaiter = waiter
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        screenCaptureConsentLauncher.launch(intent)
        // The dialog is modal. The timeout only guards the case where no answer ever arrives (the activity went away).
        try {
            withTimeoutOrNull(SCREEN_CAPTURE_CONSENT_TIMEOUT_MS) { waiter.await() }
        } finally {
            if (screenCaptureConsentWaiter === waiter) screenCaptureConsentWaiter = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate to take over the theme's splash window.
        //
        // UI-056 / ADR-080: on a cold start the handwritten Ink splash (Compose) follows the system
        // splash and plays to its end. The process-wide gate decides once whether it plays:
        //  - Pending: the "Launch animation" setting is being read. Hold the system splash.
        //  - Playing: release the system splash at once and fade it into the Ink splash.
        //  - Skipped / Finished (setting off, a call answer or PTT press, or not the first window of
        //    this process): the old rule. Hold the system splash for as long as the engine takes to
        //    boot, with no fixed minimum, and release on a start failure so it never traps the user.
        val splashScreen = installSplashScreen()
        val launchSplash = FlashLaunchSplashProcess.gate
        if (shouldSkipLaunchSplashForIntent(intent)) launchSplash.skip()
        splashScreen.setKeepOnScreenCondition {
            when (launchSplash.phase) {
                FlashLaunchSplashGate.Phase.Pending -> true
                FlashLaunchSplashGate.Phase.Playing -> false
                FlashLaunchSplashGate.Phase.Skipped, FlashLaunchSplashGate.Phase.Finished ->
                    !appEngine.ready.value && appEngine.startError.value == null
            }
        }
        splashScreen.setOnExitAnimationListener { provider ->
            // With a listener set, the app must remove the system splash itself (androidx docs).
            if (launchSplash.phase == FlashLaunchSplashGate.Phase.Playing) {
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(SYSTEM_SPLASH_FADE_MS)
                    .withEndAction { provider.remove() }
                    .start()
            } else {
                provider.remove()
            }
        }
        super.onCreate(savedInstanceState)
        if (launchSplash.phase == FlashLaunchSplashGate.Phase.Pending) {
            lifecycleScope.launch {
                // One DataStore read. A read slower than this means a struggling device, which is
                // the wrong moment to add an animation: skip it rather than hold the splash longer.
                val enabled = withTimeoutOrNull(LAUNCH_SETTING_READ_TIMEOUT_MS) {
                    appEngine.settingsStore.launchAnimation.first()
                } ?: false
                launchSplash.decide(enabled)
            }
        }
        enableEdgeToEdge()
        loadNotificationPermissionPrefs()
        refreshNotificationPermissionState()
        installScreenCaptureSupport()
        // OPT-O01-02: initialize notification channels off the main thread to avoid blocking Binder IPCs on launch.
        lifecycleScope.launch(Dispatchers.Default) {
            FlashNotificationManager.initChannels(this@MainActivity)
        }
        // Bug 7: a notification tap can cold-start the activity (no onNewIntent on cold
        // start) — consume the launch intent here too, only on initial create so config-change
        // recreation (uiMode / fontScale / locale) never replays an already-handled intent.
        if (savedInstanceState == null) {
            dispatchIncomingIntent(intent)
        }
        // Boot the real WS mesh stack once, idempotently. The holder de-dupes against the Dev
        // Console / background service, so this never spins up a second server. Failures are
        // captured into appEngine.startError (permission gating lands in Phase 4).
        appEngine.start()
        setContent {
            FlashApp(
                engine = appEngine,
                pendingNotificationConversation = pendingNotificationConversation,
                pendingCallAnswer = pendingCallAnswer,
                pendingPttPress = pendingPttPress,
                pendingShare = pendingShare,
                pendingWidgetSendPicker = pendingWidgetSendPicker,
                onLaunchWidgetSendPicker = { widgetSendPickerLauncher.launch(arrayOf("*/*")) },
                pendingShortcutTab = pendingShortcutTab,
                pendingJoinLink = pendingJoinLink,
                pendingEnrollLink = pendingEnrollLink,
                pendingHubRoute = pendingHubRoute,
                onEnableBackgroundTransfers = ::requestIgnoreBatteryOptimizations,
                ignoringBatteryOptimizations = ignoringBatteryOptimizations,
                transsionGuideVisible = transsionGuideVisible,
                onOpenTranssionTarget = { target ->
                    if (!OemBatteryOptimizationHelper.openTarget(this, target)) {
                        Toast.makeText(
                            this,
                            TranssionTargetFeedback.failureMessage(target),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                receivedStorageState = receivedStorageState,
                onRefreshStorageUsage = ::refreshReceivedStorageUsage,
                onClearReceivedFiles = ::clearReceivedFiles,
                onRestartApp = ::restartApp,
                onExportLogs = ::exportLogs,
                hasNotificationPermission = hasNotificationPermission,
                notificationPromptedBefore = notificationPromptedBefore,
                notificationBannerDismissed = notificationBannerDismissed,
                shouldShowNotificationRationale = shouldShowNotificationRationale,
                onRequestNotificationPermission = ::requestNotificationPermissionFromBanner,
                onOpenNotificationSettings = ::openAppNotificationSettings,
                onDismissNotificationBanner = ::dismissNotificationBanner,
            )
        }
        // OPT-O01-02: Defer recursive scanReceivedFilesBytes() to on-demand navigation in Settings
    }

    override fun onResume() {
        super.onResume()
        // The exemption can only have changed while we were away in the system prompt (or in the
        // OEM battery screen), so re-read it here — this is what makes the Settings row reflect
        // reality instead of whatever was true at launch.
        refreshBatteryOptimizationState()
        refreshNotificationPermissionState()
        FlashBackgroundService.reevaluateKeepAlive()
    }

    override fun onStart() {
        super.onStart()
        // Bug 6: launch while this activity is user-visible. Android 12+ rejects most foreground-
        // service starts from the background, so engine startup must not launch this asynchronously.
        // onStop does not stop the service outright: it only lets an idle mesh (no peer, no transfer, no hub relay,
        // and no "Keep listening in the background" opt-in) do so after a grace period — ADR-232.
        FlashBackgroundService.start(this)
        // Bug 7: notifications must be suppressed only while we're genuinely on screen — not
        // merely while the process lives (the service keeps the process alive across onStop).
        FlashNotificationManager.appForeground = true
        FlashNotificationManager.clearPairingRequest(this)
        FlashNotificationManager.clearPttPing(this)
        DiscoveryEngineHolder.setUiStarted(true)
    }

    override fun onStop() {
        super.onStop()
        FlashNotificationManager.appForeground = false
        DiscoveryEngineHolder.setUiStarted(false)
        // OPT-O13-06: leaving the screen is not a change of mesh state, so the idle-stop timer is asked again here.
        // With nobody connected and nothing queued it lowers the service's foreground profile 30 s from now; a return
        // to the foreground (onStart restarts the service) or a peer arriving in that window cancels the stop.
        FlashBackgroundService.reevaluateIdleStop()
    }

    override fun onDestroy() {
        // ADR-102: the screen-capture host is this activity (a consent launcher and the projection service). Dropping it
        // keeps the process-wide seam from holding a destroyed activity; a recreated one installs its own in onCreate.
        // A share already open keeps the host it was opened with, through its handle.
        // A consent dialog still open now has nobody to deliver its answer to (a recreated activity owns a new
        // launcher), so the capturer is told "refused" at once instead of waiting out the 5-minute guard.
        screenCaptureConsentWaiter?.complete(null)
        screenCaptureConsentWaiter = null
        AndroidScreenCaptureSupport.uninstall()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldSkipLaunchSplashForIntent(intent)) {
            FlashLaunchSplashProcess.gate.skip()
            FlashLaunchSplashProcess.gate.finish()
        }
        dispatchIncomingIntent(intent)
    }

    private fun shouldSkipLaunchSplashForIntent(intent: Intent?): Boolean =
        FlashLaunchSplashProcess.isTimeCriticalLaunch(intent) ||
            LaunchEntryPolicy.shouldSkipLaunchSplash(
                action = intent?.action,
                dataScheme = intent?.data?.scheme,
                hasAnswerCallExtra = intent?.getBooleanExtra(
                    com.transfer.flash.calling.FlashCallActionReceiver.EXTRA_ANSWER_CALL,
                    false,
                ) == true,
                hasPttPressExtra = intent?.getBooleanExtra(PttSessionEngine.EXTRA_PTT_PRESS, false) == true,
                hasConversationExtra = !intent?.getStringExtra(FlashNotificationManager.EXTRA_CONVERSATION_ID).isNullOrBlank(),
                hasHubRouteExtra = !intent?.getStringExtra(EXTRA_HUB_ROUTE).isNullOrBlank(),
            )

    private fun dispatchIncomingIntent(intent: Intent?) {
        if (intent == null) return
        // Bug 7: notification tap on cold start or singleTop resume.
        intent.getStringExtra(FlashNotificationManager.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { pendingNotificationConversation.value = it }
        intent.getStringExtra(EXTRA_HUB_ROUTE)
            ?.takeIf { it.isNotBlank() }
            ?.let { pendingHubRoute.value = it }
        if (intent.getBooleanExtra(com.transfer.flash.calling.FlashCallActionReceiver.EXTRA_ANSWER_CALL, false)) {
            pendingCallAnswer.value = true
        }
        if (intent.getBooleanExtra(PttSessionEngine.EXTRA_PTT_PRESS, false)) {
            pendingPttPress.value = true
        }
        handleIncomingIntent(intent)
    }

    private fun extractUrisFromIntent(intent: Intent): ShareUriPartition {
        val result = linkedSetOf<Uri>()
        // 1. ClipData (preferred in modern Android)
        intent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { result.add(it) }
            }
        }
        // 2. EXTRA_STREAM (single item)
        if (intent.action == Intent.ACTION_SEND) {
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            streamUri?.let { result.add(it) }
        }
        // 3. EXTRA_STREAM (multiple items)
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            streamUris?.let { result.addAll(it) }
        }
        // R-05: this activity is an exported share target, so every URI below was chosen by the calling app. Only plain
        // content: URIs of OTHER apps are opened; file: URIs and Flash's own providers would make Flash read its own
        // private storage on a stranger's behalf. `intent.data` is deliberately NOT folded in (it is not a share payload).
        val (accepted, rejected) = result.partition { ShareUriPolicy.accepts(it.scheme, it.authority, packageName) }
        return ShareUriPartition(accepted = accepted, rejected = rejected)
    }

    private fun resolvePendingShare(uris: List<Uri>, text: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                val (name, size) = resolveContentUriNameAndSize(applicationContext, uri)
                val mime = guessMimeType(name, uri.toString(), applicationContext)
                FlashShareItemUi(
                    uri = uri.toString(),
                    name = name,
                    sizeBytes = size,
                    mimeType = mime,
                )
            }
            pendingShare.value = FlashSharePayloadUi(
                items = items,
                text = text?.ifBlank { null },
            )
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null && data.scheme == "flash") {
                    when (data.host) {
                        "g" -> pendingJoinLink.value = data.toString()
                        "enroll" -> pendingEnrollLink.value = data.toString()
                    }
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val partition = extractUrisFromIntent(intent)
                if (partition.rejected.isNotEmpty()) {
                    val hasFileUri = partition.rejected.any { it.scheme.equals("file", ignoreCase = true) }
                    val message = ShareUriFeedback.rejectedMessage(
                        hasFileUri = hasFileUri,
                        acceptedCount = partition.accepted.size,
                        hasTextFallback = !text.isNullOrBlank(),
                    )
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                if (partition.accepted.isNotEmpty() || !text.isNullOrBlank()) {
                    resolvePendingShare(partition.accepted, text)
                }
            }
            "com.transfer.flash.action.SHORTCUT_SEND" -> {
                pendingWidgetSendPicker.value = true
            }
            "com.transfer.flash.action.SHORTCUT_NEARBY",
            "android.service.quicksettings.action.QS_TILE_PREFERENCES",
            FlashNotificationManager.ACTION_VIEW_NEARBY -> {
                pendingShortcutTab.value = FlashDestination.NearbyDevices
            }
            "com.transfer.flash.action.SHORTCUT_CHATS" -> {
                pendingShortcutTab.value = FlashDestination.ChatList
            }
            FlashNotificationManager.ACTION_VIEW_TRANSFERS -> {
                val transferId = intent.getStringExtra(FlashNotificationManager.EXTRA_TRANSFER_ID)
                if (transferId != null) {
                    FlashNotificationManager.clearTransfer(this, transferId)
                }
                pendingShortcutTab.value = FlashDestination.Transfers
            }
            FlashNotificationManager.ACTION_OPEN_FILE -> {
                val transferId = intent.getStringExtra(FlashNotificationManager.EXTRA_TRANSFER_ID)
                if (transferId != null) {
                    FlashNotificationManager.clearTransfer(this, transferId)
                }
                val path = intent.getStringExtra(FlashNotificationManager.EXTRA_FILE_PATH)
                // The activity is exported, so any app can send this action: only a real file of ours is opened (SEC-D11-01).
                val receivedRoots = listOf(com.transfer.flash.debug.DiscoveryEngineHolder.receivedFilesRoot(this))
                if (path != null && isOpenableReceivedPath(path, receivedRoots)) {
                    openAttachment(this, path, guessMimeType(path, path, this))
                }
                pendingShortcutTab.value = FlashDestination.Transfers
            }
        }
    }

    private fun refreshReceivedStorageUsage() {
        receivedStorageJob?.cancel()
        receivedStorageJob = lifecycleScope.launch {
            val cached = receivedStorageState.value.totalBytes
            receivedStorageState.value = FlashReceivedStorageState(
                totalBytes = cached,
                isLoading = true,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { scanReceivedFilesBytes() }
            }
            receivedStorageState.value = result.fold(
                onSuccess = { total -> FlashReceivedStorageState(totalBytes = total) },
                onFailure = { FlashReceivedStorageState(totalBytes = cached, hasError = true) },
            )
        }
    }

    /**
     * Relaunches the app so a setting the engine reads once at start (the swarm switch) takes effect. The launch intent is
     * handed to the system first and the process ends afterwards; the short delay lets the setting that was just changed
     * reach its store.
     */
    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch == null) {
            Toast.makeText(this, "Close and reopen Flash to apply this", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500L)
            startActivity(launch)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Shares Flash's own rotating log (all files, oldest first) as one text file with a short device header. */
    private fun exportLogs() {
        lifecycleScope.launch {
            val shareUri = withContext(Dispatchers.IO) {
                runCatching {
                    val sink = (application as com.transfer.flash.di.FlashApplication).fileLog
                    sink.flush()
                    val dir = java.io.File(cacheDir, "log-export").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = java.io.File(dir, "flash-log-" + System.currentTimeMillis() + ".txt")
                    // Session header + live snapshot (docs/testing/PROBES.md): build, device, clock offset, switches, and every
                    // connected peer / group member with its features and paired / vouched state, as they are right now.
                    val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
                    val header = com.transfer.flash.debug.FlashLogContext.exportLines(appEngine, versionName, System.currentTimeMillis())
                    out.bufferedWriter().use { w ->
                        w.write(
                            "Flash log export; Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "); " +
                                Build.MANUFACTURER + " " + Build.MODEL + "\n",
                        )
                        header.forEach { w.write(it + "\n") }
                        sink.files().forEach { f -> f.bufferedReader().use { it.copyTo(w) } }
                    }
                    androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", out)
                }.getOrNull()
            }
            if (shareUri == null) {
                Toast.makeText(this@MainActivity, "Can't export the log", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(send, "Share Flash log")) }
                .onFailure { Toast.makeText(this@MainActivity, "No app to share with", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun clearReceivedFiles() {
        receivedStorageJob?.cancel()
        receivedStorageJob = lifecycleScope.launch {
            val cached = receivedStorageState.value.totalBytes
            receivedStorageState.value = FlashReceivedStorageState(
                totalBytes = cached,
                isClearing = true,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    deleteReceivedFilesInsideOwnedRoot()
                    scanReceivedFilesBytes()
                }
            }
            receivedStorageState.value = result.fold(
                onSuccess = { total -> FlashReceivedStorageState(totalBytes = total) },
                onFailure = {
                    val rescanned = withContext(Dispatchers.IO) {
                        runCatching { scanReceivedFilesBytes() }.getOrNull()
                    }
                    FlashReceivedStorageState(totalBytes = rescanned ?: cached, hasError = true)
                },
            )
        }
    }

    /** Scans only the exact root constructed and used by DiscoveryEngineHolder's receive pipeline. */
    private fun scanReceivedFilesBytes(): Long {
        val root = DiscoveryEngineHolder.receivedFilesRoot(this).canonicalFile
        if (!root.exists()) return 0L
        val rootPrefix = root.path + File.separator
        var total = 0L
        root.walkBottomUp().forEach { entry ->
            if (entry == root || !entry.isFile) return@forEach
            val canonical = entry.canonicalFile
            check(canonical.path.startsWith(rootPrefix)) { "Received file escaped the app-owned root" }
            val length = canonical.length().coerceAtLeast(0L)
            total = if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }
        return total
    }

    /** Deletes children only; the app-owned root itself and every path outside it are preserved. */
    private fun deleteReceivedFilesInsideOwnedRoot() {
        val root = DiscoveryEngineHolder.receivedFilesRoot(this).canonicalFile
        if (!root.exists()) return
        val rootPrefix = root.path + File.separator
        root.walkBottomUp().forEach { entry ->
            if (entry == root) return@forEach
            val canonical = entry.canonicalFile
            check(canonical.path.startsWith(rootPrefix)) { "Refusing to delete outside the received-files root" }
            if (!entry.delete()) {
                throw IOException("Could not clear received files")
            }
        }
    }

    /**
     * Bug 6 (OEM kill layer): asks the system to exempt Flash from AOSP battery
     * optimization (Doze/App Standby). Triggered by the existing Settings "Background
     * transfers" toggle so the request is always user-initiated — the standard pattern
     * for messengers/transfer apps. Once the AOSP exemption is held, the same entry points open
     * the OEM power manager (Xiaomi autostart, Transsion Phone Master, …) via
     * [OemBatteryOptimizationHelper], when one resolves on this device. See
     * docs/android-platform-notes.md.
     */
    private fun requestIgnoreBatteryOptimizations() {
        // Transsion: one guide lists the system prompt and the Phone Master screens together.
        if (TranssionSetupGuide.appliesTo(OemBatteryOptimizationHelper.currentBrand)) {
            transsionGuideVisible.value = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                return
            }
        }
        // AOSP exemption already held: the remaining kill layer is the OEM power manager.
        OemBatteryOptimizationHelper.resolveOemIntent(this)?.let { runCatching { startActivity(it) } }
    }

    /**
     * Publishes the current battery-optimisation exemption for the Settings row (ERROR-031 / D7).
     * Below API 23 there is no Doze to be exempt from, so report `true` rather than scaring the user
     * about a restriction that does not exist on their build.
     */
    private fun refreshBatteryOptimizationState() {
        ignoringBatteryOptimizations.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false
        } else {
            true
        }
    }

    private fun loadNotificationPermissionPrefs() {
        val prefs = getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE)
        notificationPromptedBefore.value = prefs.getBoolean(KEY_NOTIFICATIONS_PROMPTED, false)
        notificationBannerDismissed.value = prefs.getBoolean(KEY_NOTIFICATIONS_BANNER_DISMISSED, false)
    }

    private fun refreshNotificationPermissionState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission.value = true
            shouldShowNotificationRationale.value = false
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        hasNotificationPermission.value = granted
        shouldShowNotificationRationale.value = !granted &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            )
    }

    private fun requestNotificationPermissionFromBanner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAppNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            runCatching { startActivity(fallback) }
        }
    }

    private fun dismissNotificationBanner() {
        notificationBannerDismissed.value = true
        getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATIONS_BANNER_DISMISSED, true)
            .apply()
    }
}

private const val PERMISSION_PREFS_NAME = "flash_permission_prefs"
private const val KEY_NOTIFICATIONS_PROMPTED = "notifications_prompted"
private const val KEY_NOTIFICATIONS_BANNER_DISMISSED = "notifications_banner_dismissed"

/** UI-056: how long the system splash takes to fade into the Ink splash. */
/** Intent extra with a Hub route (`hub/announcement/<id>`, `hub/person/<deviceId>`); validated before use (UI-058 H9). */
internal const val EXTRA_HUB_ROUTE: String = FlashNotificationManager.EXTRA_HUB_ROUTE

private const val SYSTEM_SPLASH_FADE_MS: Long = 200L

/** UI-056: the longest the system splash waits for the "Launch animation" setting before skipping it. */
private const val LAUNCH_SETTING_READ_TIMEOUT_MS: Long = 1_000L

/** ADR-102: how long a screen-share start waits for the system consent dialog before it gives up. */
private const val SCREEN_CAPTURE_CONSENT_TIMEOUT_MS: Long = 5L * 60L * 1_000L

/** Toast when a group call could not be placed at all (ERROR-095); a call that starts but leaves someone out names them on its screen. */
private const val GROUP_CALL_NOT_STARTED: String =
    "Couldn't start the group call: none of its members can be called from this device " +
        "(you must be paired with them, or a call is already running)"

/** #14: maps the UI theme enum to/from the persisted DataStore string keys (pure, JVM-testable). */
object SettingsKeys {
    fun themeModeFromKey(key: String): FlashThemeMode = when (key) {
        FlashSettingsDataStore.THEME_MODE_LIGHT -> FlashThemeMode.Light
        FlashSettingsDataStore.THEME_MODE_DARK -> FlashThemeMode.Dark
        else -> FlashThemeMode.System
    }

    fun themeModeToKey(mode: FlashThemeMode): String = when (mode) {
        FlashThemeMode.Light -> FlashSettingsDataStore.THEME_MODE_LIGHT
        FlashThemeMode.Dark -> FlashSettingsDataStore.THEME_MODE_DARK
        FlashThemeMode.System -> FlashSettingsDataStore.THEME_MODE_SYSTEM
    }
}

typealias PendingSharePayload = FlashSharePayloadUi

@Composable
fun FlashApp(
    engine: AppEngine,
    pendingNotificationConversation: MutableStateFlow<String?> = MutableStateFlow(null),
    /** Notification answer-button: true when the user tapped "Answer" on the incoming-call notification. */
    pendingCallAnswer: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Deferred hardware PTT press (Phase 3): consumed by the session overlay once ready. */
    pendingPttPress: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Pending shared files/text from Android ACTION_SEND / ACTION_SEND_MULTIPLE. */
    pendingShare: MutableStateFlow<PendingSharePayload?> = MutableStateFlow(null),
    pendingWidgetSendPicker: MutableStateFlow<Boolean> = MutableStateFlow(false),
    onLaunchWidgetSendPicker: () -> Unit = {},
    /** Pending static app shortcut navigation target. */
    pendingShortcutTab: MutableStateFlow<FlashDestination?> = MutableStateFlow(null),
    pendingJoinLink: MutableStateFlow<String?> = MutableStateFlow(null),
    pendingEnrollLink: MutableStateFlow<String?> = MutableStateFlow(null),
    pendingHubRoute: MutableStateFlow<String?> = MutableStateFlow(null),
    /** Bug 6: fired when the user turns ON the Settings "Keep listening in the background" toggle (host-owned). */
    onEnableBackgroundTransfers: () -> Unit = {},
    /** ERROR-031 / D7: activity-published battery-optimisation exemption, refreshed on resume. */
    ignoringBatteryOptimizations: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** HIB-03: Transsion setup guide visibility; rendered inside FlashTheme so it inherits palette/accent. */
    transsionGuideVisible: MutableStateFlow<Boolean> = MutableStateFlow(false),
    onOpenTranssionTarget: (TranssionSetupGuide.Target) -> Unit = {},
    receivedStorageState: MutableStateFlow<FlashReceivedStorageState>,
    onRefreshStorageUsage: () -> Unit = {},
    onClearReceivedFiles: () -> Unit = {},
    onRestartApp: (() -> Unit)? = null,
    onExportLogs: (() -> Unit)? = null,
    /** UI-056: the process-wide launch splash gate; MainActivity decides it before the first frame. */
    launchSplash: FlashLaunchSplashGate = FlashLaunchSplashProcess.gate,
    hasNotificationPermission: MutableStateFlow<Boolean> = MutableStateFlow(true),
    notificationPromptedBefore: MutableStateFlow<Boolean> = MutableStateFlow(false),
    notificationBannerDismissed: MutableStateFlow<Boolean> = MutableStateFlow(false),
    shouldShowNotificationRationale: MutableStateFlow<Boolean> = MutableStateFlow(false),
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onDismissNotificationBanner: () -> Unit = {},
) {
    // #14 / UI-049 wiring: Appearance/Haptics are only real if the host applies them, so the settings
    // model lives above the theme. ONE theme scope owns the whole shell — a nested
    // FlashTheme { } with default arguments would reset darkTheme to the OS value and silently
    // undo the user's Light/Dark choice.
    //
    // Settings are now PERSISTED via the engine's Preferences DataStore (was in-memory `remember`,
    // audit #14). The model is DERIVED from the persisted flows plus live engine facts (real app
    // version, device id, trusted-peer count) — no more hardcoded identity/version/count. Writes go
    // straight back to the store; the flow re-emits and recomposes, so there is a single source of
    // truth and the choice survives process death.
    val store = engine.settingsStore
    val persistScope = rememberCoroutineScope()

    val themeModeKey by store.themeMode.collectAsState(initial = FlashSettingsDataStore.THEME_MODE_SYSTEM)
    // ADR-355: one app-wide chat wallpaper, chosen from the conversation menu.
    val chatWallpaper by store.chatWallpaper.collectAsState(initial = FlashChatWallpaper.Default)
    val chatWallpaperCustom by store.chatWallpaperCustom
        .collectAsState(initial = FlashChatWallpaper.DefaultCustomColour)
    val dynamicAccent by store.dynamicAccent.collectAsState(initial = false)
    val hapticsEnabled by store.hapticsEnabled.collectAsState(initial = true)
    val backgroundTransfers by store.backgroundTransfers.collectAsState(initial = false)
    val displayNamePref by store.displayName.collectAsState(initial = "")
    // Bug 3: per-MIME auto-download toggles
    val autoDownloadVoice by store.autoDownloadVoice.collectAsState(initial = true)
    val autoDownloadImage by store.autoDownloadImage.collectAsState(initial = true)
    val autoDownloadVideo by store.autoDownloadVideo.collectAsState(initial = false)
    val autoDownloadFile by store.autoDownloadFile.collectAsState(initial = false)
    val prioritiseVoiceQuality by store.prioritiseVoiceQuality.collectAsState(initial = true)
    val smallerVideoForMany by store.smallerVideoForMany.collectAsState(initial = false)
    // ERROR-033: the tier pin (null = Auto) and the tier actually in force. The pin drives the
    // picker; the resolved value drives the theme, so a device on Auto still gets the tier's
    // reduce-motion floor without the user having chosen anything.
    val pinnedPerformanceMode by store.performanceMode.collectAsState(initial = null)
    val effectivePerformanceMode by engine.performanceMode.collectAsState()
    val motionOverrideForcesReduce by store.reduceMotionOverrideForcesReduce.collectAsState(initial = null)
    val batteryExempt by ignoringBatteryOptimizations.collectAsState()
    val receivedStorage by receivedStorageState.collectAsState()
    val swarmHelpShare by store.swarmHelpShare.collectAsState(initial = true)
    val swarmKeepFinishedFiles by store.swarmKeepFinishedFiles.collectAsState(initial = true)
    val swarmEnabled by store.groupSwarmEnabled.collectAsState(initial = true)
    val launchAnimation by store.launchAnimation.collectAsState(initial = true)

    val ready by engine.ready.collectAsState()
    val trustedFallback = remember { MutableStateFlow(emptyList<NearbyTrustedPeerUi>()) }
    val trustedPeers by (engine.pairing?.trustedPeers ?: trustedFallback).collectAsState()
    val currentDiscoveryMode by DiscoveryEngineHolder.discoveryMode.collectAsState()
    val enterpriseCredsFallback = remember { MutableStateFlow<com.transfer.flash.core.security.enterprise.EnrollmentCredentials?>(null) }
    val enterpriseCreds by (engine.enterpriseTrustStore?.credentials ?: enterpriseCredsFallback).collectAsState()
    // The organisation hub (ADR-120): live status, the content opt-in and the groups an earlier enrolment had linked.
    val hubObject = remember(ready) { engine.enterpriseHub }
    val hubStatusFallback = remember { MutableStateFlow<com.transfer.flash.core.enterprise.HubStatus?>(null) }
    val hubStatusFlow = remember(hubObject) { hubObject?.status ?: hubStatusFallback }
    val hubStatus by hubStatusFlow.collectAsState(initial = null)
    val hubContentViaHub by engine.hubContentViaHub.collectAsState()
    val announcementNotifications by engine.announcementNotifications.collectAsState()
    // ADR-126: the sealing key state and the dropped-message counts for the connection details.
    val hubSealingKeyFallback = remember { MutableStateFlow(com.transfer.flash.core.enterprise.HubSealingKeyState.IDLE) }
    val hubSealingKeyState by (hubObject?.sealingKeys?.state ?: hubSealingKeyFallback).collectAsState()
    val hubDroppedFlow = remember(ready) { if (ready) engine.chats?.hubDropped else null }
    val hubDroppedFallback = remember { MutableStateFlow(com.transfer.flash.core.messaging.hub.HubDropCounts()) }
    val hubDropped by (hubDroppedFlow ?: hubDroppedFallback).collectAsState()
    val hubPreviousLinks by (hubObject?.trustStore?.previousHubLinks ?: remember { MutableStateFlow(null) }).collectAsState()
    val hubRelinkable = remember(hubObject, hubPreviousLinks, enterpriseCreds) { hubObject?.relinkableGroups()?.size ?: 0 }
    val orgResolver = remember(ready) { DiscoveryEngineHolder.currentOrgPeerResolver() }
    val orgLeaseFlow = remember(orgResolver) {
        orgResolver?.lease ?: MutableStateFlow(com.transfer.flash.core.enterprise.OrgLease.None)
    }
    val orgLease by orgLeaseFlow.collectAsState()
    val orgMismatchFlow = remember(orgResolver) {
        orgResolver?.identityMismatch ?: MutableStateFlow(false)
    }
    val orgIdentityMismatch by orgMismatchFlow.collectAsState()
    val orgTrustState = remember(orgLease, enterpriseCreds, orgIdentityMismatch) {
        if (enterpriseCreds == null) null
        else {
            val clockOk = (orgLease as? com.transfer.flash.core.enterprise.OrgLease.Expired)?.reason != com.transfer.flash.core.enterprise.ExpiredReason.CLOCK
            com.transfer.flash.ui.settings.FlashHubStatusMath.orgTrustState(
                lease = orgLease,
                clockOk = clockOk,
                hubSupports = true,
                identityMismatch = orgIdentityMismatch,
                nowMs = System.currentTimeMillis(),
            )
        }
    }

    val radioHostContext = LocalContext.current
    // The controller is process-wide (RadioGatewayHolder): it must outlive this Activity and its composition.
    val radioGatewayController = remember(radioHostContext) {
        com.transfer.flash.radio.RadioGatewayHolder.get(radioHostContext)
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        radioGatewayController.onNearbyDevicesPermissionResult(results.values.all { it })
    }
    // This Activity owns the launcher, so it (re)installs the requester and removes it when it leaves.
    DisposableEffect(radioGatewayController, nearbyPermissionLauncher) {
        radioGatewayController.permissionRequester = {
            if (Build.VERSION.SDK_INT >= 31) {
                nearbyPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                    )
                )
            }
        }
        onDispose { radioGatewayController.permissionRequester = null }
    }
    val radioMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> radioGatewayController.onMicrophonePermissionResult(granted) }
    DisposableEffect(radioGatewayController, radioMicLauncher) {
        radioGatewayController.microphonePermissionRequester = { radioMicLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        onDispose { radioGatewayController.microphonePermissionRequester = null }
    }
    val radioUiState by radioGatewayController.state.collectAsState()

    val settings = FlashSettingsModel(
        // ERROR-034: no `if (ready)` gate. Identity comes from a self-healing prefs store that
        // mints and persists an id and a "Flash <MODEL>" name on its first read, so it is real and
        // correct before the transport stack boots. Gating on `ready` substituted the literals
        // "Flash device" / "00000000" for the whole boot window — invented content, and on a slow
        // device visible for seconds before it swapped to the truth.
        displayName = displayNamePref.ifBlank { engine.localFriendlyName },
        themeMode = SettingsKeys.themeModeFromKey(themeModeKey),
        dynamicAccent = dynamicAccent,
        hapticsEnabled = hapticsEnabled,
        backgroundTransfers = backgroundTransfers,
        discoveryMode = currentDiscoveryMode.name,
        autoDownloadVoice = autoDownloadVoice,
        autoDownloadImage = autoDownloadImage,
        autoDownloadVideo = autoDownloadVideo,
        autoDownloadFile = autoDownloadFile,
        ignoringBatteryOptimizations = batteryExempt,
        prioritiseVoiceQuality = prioritiseVoiceQuality,
        smallerVideoForMany = smallerVideoForMany,
        performanceMode = pinnedPerformanceMode,
        detectedPerformanceMode = engine.detectedPerformance.mode,
        trustedPeerCount = trustedPeers.size,
        // ADR-356: Android writes completed receives into the app-owned FlashReceived root.
        saveLocationLabel = "App storage / FlashReceived",
        appVersion = engine.appVersionName,
        // ifBlank is a real guard, not the normal path: a UUID always survives take(8), but a
        // prefs entry that somehow holds "" would not be caught by the store's null check.
        deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
        receivedFilesBytes = receivedStorage.totalBytes,
        storageUsageLoading = receivedStorage.isLoading,
        storageUsageError = receivedStorage.hasError,
        clearingReceivedFiles = receivedStorage.isClearing,
        swarmHelpShare = swarmHelpShare,
        swarmKeepFinishedFiles = swarmKeepFinishedFiles,
        swarmEnabled = swarmEnabled,
        launchAnimation = launchAnimation,
        isEnterpriseEnrolled = enterpriseCreds != null,
        enterpriseHubUrl = enterpriseCreds?.hubUrl,
        enterpriseDepartment = enterpriseCreds?.department,
        enterpriseRole = enterpriseCreds?.role,
        enterpriseRootFingerprint = enterpriseCreds?.rootFingerprint,
        hubStatus = hubStatus,
        hubContentViaHub = hubContentViaHub,
        announcementNotifications = announcementNotifications,
        hubSealing = if (hubObject?.sealingKeys == null) null else com.transfer.flash.ui.settings.FlashHubSealingUi(
            keyState = hubSealingKeyState,
            unverified = hubDropped.unverified,
            readableOff = hubDropped.readableOff,
            stale = hubDropped.stale,
        ),
        hubRelinkableGroups = hubRelinkable,
        orgTrustState = orgTrustState,
        radioGatewayEnabled = radioUiState.enabled,
    )
    // Captured here (composable scope) so the non-composable lambda below can construct an
    // AndroidPreferencesIdentityStore when the display name changes.
    val settingsContext = LocalContext.current.applicationContext
    val onSettingsChange: (FlashSettingsModel) -> Unit = { updated ->
        persistScope.launch {
            if (updated.themeMode != settings.themeMode) {
                store.setThemeMode(SettingsKeys.themeModeToKey(updated.themeMode))
            }
            if (updated.dynamicAccent != settings.dynamicAccent) store.setDynamicAccent(updated.dynamicAccent)
            if (updated.hapticsEnabled != settings.hapticsEnabled) store.setHapticsEnabled(updated.hapticsEnabled)
            if (updated.backgroundTransfers != settings.backgroundTransfers) {
                store.setBackgroundTransfers(updated.backgroundTransfers)
            }
            if (updated.discoveryMode != settings.discoveryMode) {
                runCatching { FlashDiscoveryMode.valueOf(updated.discoveryMode) }.getOrNull()?.let { newMode ->
                    DiscoveryEngineHolder.setDiscoveryMode(newMode, settingsContext)
                }
            }
            if (updated.autoDownloadVoice != settings.autoDownloadVoice) store.setAutoDownloadVoice(updated.autoDownloadVoice)
            if (updated.autoDownloadImage != settings.autoDownloadImage) store.setAutoDownloadImage(updated.autoDownloadImage)
            if (updated.autoDownloadVideo != settings.autoDownloadVideo) store.setAutoDownloadVideo(updated.autoDownloadVideo)
            if (updated.autoDownloadFile != settings.autoDownloadFile) store.setAutoDownloadFile(updated.autoDownloadFile)
            if (updated.prioritiseVoiceQuality != settings.prioritiseVoiceQuality) {
                store.setPrioritiseVoiceQuality(updated.prioritiseVoiceQuality)
            }
            if (updated.smallerVideoForMany != settings.smallerVideoForMany) {
                store.setSmallerVideoForMany(updated.smallerVideoForMany)
            }
            if (updated.performanceMode != settings.performanceMode) {
                store.setPerformanceMode(updated.performanceMode)
            }
            if (updated.displayName != settings.displayName) {
                store.setDisplayName(updated.displayName)
                // Propagate to identity store (persisted) and discovery (live NSD re-advertisement).
                com.transfer.flash.core.security.identity.AndroidPreferencesIdentityStore(
                    settingsContext,
                ).updateFriendlyName(updated.displayName)
                DiscoveryEngineHolder.updateFriendlyName(updated.displayName)
            }
            if (updated.swarmHelpShare != settings.swarmHelpShare) store.setSwarmHelpShare(updated.swarmHelpShare)
            if (updated.swarmKeepFinishedFiles != settings.swarmKeepFinishedFiles) {
                store.setSwarmKeepFinishedFiles(updated.swarmKeepFinishedFiles)
            }
            if (updated.swarmEnabled != settings.swarmEnabled) store.setGroupSwarmEnabled(updated.swarmEnabled)
            if (updated.launchAnimation != settings.launchAnimation) store.setLaunchAnimation(updated.launchAnimation)
        }
    }

    val darkTheme = FlashSettingsMath.resolveDarkTheme(
        mode = settings.themeMode,
        systemDark = isSystemInDarkTheme(),
    )

    // ERROR-033: the performance tier is a *floor* under the motion decision, not another vote in
    // it. A handset classified LOW or MEDIUM does not animate even if the platform and the user both
    // say motion is fine — on the hardware that earns those tiers, the animation is the jank. One
    // resolved boolean here reaches every FlashTheme.motion call site in the app at once.
    val reduceMotionResolved = FlashMotionPolicy.resolveReduceMotion(
        mode = effectivePerformanceMode,
        overrideForcesReduce = motionOverrideForcesReduce,
        systemReduceMotion = rememberSystemReduceMotion(),
    )

    FlashMaterialTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicAccent) {
        FlashTheme(
            darkTheme = darkTheme,
            dynamicAccent = settings.dynamicAccent,
            hapticsEnabled = settings.hapticsEnabled,
            minimalChrome = effectivePerformanceMode.minimalChrome,
            ultraLowUi = effectivePerformanceMode.ultraLowUi,
            motion = rememberFlashMotion(reduceMotionResolved),
        ) {
            // UI-056 / ADR-080: the Ink launch splash. It plays only when the process-wide gate says so
            // (a cold start with the setting on), always to its end, and then leaves as soon as the
            // engine is ready or has failed — or at the 6 s ceiling (ERROR-034), so a stalled boot
            // never traps the user. The old looping bolt splash it replaces is gone.
            val startError by engine.startError.collectAsState()
            val splashSettled = launchSplash.phase == FlashLaunchSplashGate.Phase.Skipped ||
                launchSplash.phase == FlashLaunchSplashGate.Phase.Finished
            Box(modifier = Modifier.fillMaxSize()) {
                FlashShell(
                    engine = engine,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    chatWallpaper = chatWallpaper,
                    chatWallpaperCustom = chatWallpaperCustom,
                    onSetChatWallpaper = { wallpaper -> persistScope.launch { store.setChatWallpaper(wallpaper) } },
                    onSetChatWallpaperCustom = { hex -> persistScope.launch { store.setChatWallpaperCustom(hex) } },
                    pendingNotificationConversation = pendingNotificationConversation,
                    pendingCallAnswer = pendingCallAnswer,
                    pendingShare = pendingShare,
                    pendingWidgetSendPicker = pendingWidgetSendPicker,
                    onLaunchWidgetSendPicker = onLaunchWidgetSendPicker,
                    pendingShortcutTab = pendingShortcutTab,
                    pendingJoinLink = pendingJoinLink,
                    pendingEnrollLink = pendingEnrollLink,
                    pendingHubRoute = pendingHubRoute,
                    onEnableBackgroundTransfers = onEnableBackgroundTransfers,
                    onRefreshStorageUsage = onRefreshStorageUsage,
                    onClearReceivedFiles = onClearReceivedFiles,
                    onRestartApp = onRestartApp,
                    onExportLogs = onExportLogs,
                    radioGatewayController = radioGatewayController,
                    splashSettled = splashSettled,
                    hasNotificationPermission = hasNotificationPermission,
                    notificationPromptedBefore = notificationPromptedBefore,
                    notificationBannerDismissed = notificationBannerDismissed,
                    shouldShowNotificationRationale = shouldShowNotificationRationale,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onDismissNotificationBanner = onDismissNotificationBanner,
                )
                // Phase 3: PTT session overlay above every tab (below the boot splash).
                // State-driven, not nav-driven: back-navigation underneath never kills a
                // session. Tier-gated animation (never on LOW) + system reduce-motion
                // handled inside.
                val pressPending by pendingPttPress.collectAsState()
                val pttEngine = remember(ready) { DiscoveryEngineHolder.currentPttSession() }
                val pttToastContext = LocalContext.current
                PttSessionOverlay(
                    engine = pttEngine,
                    pressPending = pressPending,
                    ready = ready,
                    animateLevels = effectivePerformanceMode != FlashPerformanceMode.LOW,
                    onConsumePress = { pendingPttPress.value = false },
                    showToast = { message ->
                        Toast.makeText(pttToastContext, message, Toast.LENGTH_SHORT).show()
                    },
                )
                val guideVisible by transsionGuideVisible.collectAsState()
                if (guideVisible) {
                    TranssionGuideDialog(
                        ignoringBatteryOptimizations = batteryExempt,
                        onOpen = onOpenTranssionTarget,
                        onDismiss = { transsionGuideVisible.value = false },
                    )
                }
                FlashLaunchSplashOverlay(
                    gate = launchSplash,
                    engineSettled = ready || startError != null,
                )
            }
        }
    }
}

@Composable
private fun FlashShell(
    engine: AppEngine,
    settings: FlashSettingsModel,
    onSettingsChange: (FlashSettingsModel) -> Unit,
    chatWallpaper: FlashChatWallpaper = FlashChatWallpaper.Default,
    chatWallpaperCustom: String = FlashChatWallpaper.DefaultCustomColour,
    onSetChatWallpaper: ((FlashChatWallpaper) -> Unit)? = null,
    onSetChatWallpaperCustom: ((String) -> Unit)? = null,
    pendingNotificationConversation: MutableStateFlow<String?>,
    pendingCallAnswer: MutableStateFlow<Boolean>,
    pendingShare: MutableStateFlow<PendingSharePayload?>,
    pendingWidgetSendPicker: MutableStateFlow<Boolean> = MutableStateFlow(false),
    onLaunchWidgetSendPicker: () -> Unit = {},
    pendingShortcutTab: MutableStateFlow<FlashDestination?>,
    pendingJoinLink: MutableStateFlow<String?>,
    pendingEnrollLink: MutableStateFlow<String?>,
    pendingHubRoute: MutableStateFlow<String?>,
    onEnableBackgroundTransfers: () -> Unit,
    onRefreshStorageUsage: () -> Unit,
    onClearReceivedFiles: () -> Unit,
    onRestartApp: (() -> Unit)?,
    onExportLogs: (() -> Unit)?,
    radioGatewayController: com.transfer.flash.ui.radio.RadioGatewayController? = null,
    splashSettled: Boolean = true,
    hasNotificationPermission: MutableStateFlow<Boolean> = MutableStateFlow(true),
    notificationPromptedBefore: MutableStateFlow<Boolean> = MutableStateFlow(false),
    notificationBannerDismissed: MutableStateFlow<Boolean> = MutableStateFlow(false),
    shouldShowNotificationRationale: MutableStateFlow<Boolean> = MutableStateFlow(false),
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onDismissNotificationBanner: () -> Unit = {},
) {
    val nav = rememberFlashNavigationState()
    // Phase 3.1: Chats bind to the real Room-backed repository once the engine has booted.
    // Reading `ready` here is the recomposition trigger — engine.chats is a plain holder getter, so
    // without a state read the swap from empty → real would never recompose.
    //
    // ERROR-034: the pre-boot fallback used to be `SampleFlashChatRepository()`, "so the shell is
    // never empty". It made the tab render three fabricated threads (False School / Design Team /
    // Flash Transfer) that vanished the instant the real repository arrived. That is invisible on a
    // fast handset — the splash covers the whole boot — but the splash has a 6s ceiling, and on a
    // slow device (Belfone SCP810) boot outlasts it, so the user watches invented conversations
    // appear and disappear. The fallback is now an honest empty repository, and the tab reports its
    // real state through FlashChatListScreen's own isLoading/errorMessage inputs: skeleton while the
    // stack comes up (UI-026), first-run empty state once it is up with no threads (UI-025), error
    // state if boot failed (UI-027).
    val ready by engine.ready.collectAsState()
    // UI-058: the Hub tab replaces Transfers for an enrolled device. Enrolment is only trusted once the
    // engine is ready (before that the credentials flow holds its empty default); the saved stack
    // carries the last known value, so a restore does not flicker.
    LaunchedEffect(ready, settings.isEnterpriseEnrolled) {
        if (ready) nav.updateHubEnrolled(settings.isEnterpriseEnrolled)
    }
    LaunchedEffect(nav.current.destination) {
        if (nav.current.destination == FlashDestination.Hub) {
            DiscoveryEngineHolder.requestOrgSync("hub_tab_opened")
        }
    }
    // UI-058 H9: a route from a notification or a cold start. It waits until the engine is ready (enrolment unknown before
    // that), selects the Hub when enrolled, otherwise lands on Chats with a one-line notice. The Hub reports it consumed.
    val hubRoute by pendingHubRoute.collectAsState()
    val hubRouteContext = LocalContext.current
    LaunchedEffect(hubRoute, ready, settings.isEnterpriseEnrolled) {
        if (hubRoute == null) return@LaunchedEffect
        when (com.transfer.flash.ui.hub.FlashHubMath.routeDecision(hubRoute, enrolmentKnown = ready, enrolled = settings.isEnterpriseEnrolled)) {
            com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.Wait -> Unit
            com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.Drop -> pendingHubRoute.value = null
            is com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.OpenHub -> {
                nav.updateHubEnrolled(true)
                nav.selectTab(FlashDestination.Hub)
            }
            com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.NotEnrolled -> {
                nav.selectTab(FlashDestination.ChatList)
                Toast.makeText(hubRouteContext, com.transfer.flash.ui.hub.FlashHubCopy.ROUTE_NOT_ENROLLED, Toast.LENGTH_SHORT).show()
                pendingHubRoute.value = null
            }
        }
    }
    val chatStartError by engine.startError.collectAsState()
    val chatRepository: com.transfer.flash.core.messaging.FlashChatRepository =
        (if (ready) engine.chats else null) ?: EmptyFlashChatRepository
    val rawConversationState by chatRepository.conversationState.collectAsState()
    // Merge ongoing group calls into the conversation state so the banner composable can show it.
    val ongoingGroupCalls by (engine.calls?.ongoingGroupCalls
        ?: remember { MutableStateFlow(emptyMap<String, com.transfer.flash.core.calling.model.OngoingGroupCallUi>()) }
        ).collectAsState()
    val conversationState = remember(rawConversationState, ongoingGroupCalls) {
        val convId = rawConversationState.header.let { h ->
            if (h.isGroup) nav.current.conversationId else null
        }
        val ongoing = convId?.let { ongoingGroupCalls[it] }
        if (ongoing != null) {
            rawConversationState.copy(
                ongoingCall = com.transfer.flash.core.messaging.model.FlashActiveGroupCallBarUi(
                    callId = ongoing.callId,
                    callerName = ongoing.initiatorId,
                    video = ongoing.video,
                    participantCount = ongoing.participantCount,
                ),
            )
        } else {
            rawConversationState
        }
    }
    val chatListState by chatRepository.chatListState.collectAsState()
    // EXP-012: `chatListState` itself is only ever *read* inside the ChatList branch (the screen and
    // its isLoading), and `conversationState` only inside the Conversation branch — a `by` delegate
    // records its read where the property is read, not where collectAsState was called, so both are
    // already narrow. The one exception was the selection-mode BackHandler far below, which read the
    // whole state object at FlashShell scope; because a State has no per-field granularity, every
    // presence change, unread-count change, new preview and draft edit then re-executed this entire
    // ~750-line composable to re-evaluate one Boolean. Deriving the Boolean moves that read into the
    // derivation, which invalidates its reader only when the Boolean actually flips. Keyed on the
    // repository because it swaps from EmptyFlashChatRepository once the engine boots.
    val chatListSelectionMode by remember(chatRepository) {
        derivedStateOf { chatListState.selectionMode }
    }

    var selectedChatConversationId by remember { mutableStateOf<String?>(null) }

    // Bug 7: the shell mirrors the open conversation into the notification manager so it
    // can suppress notifications for the thread being read right now (and clear that
    // peer's notification). Derived from the live nav entry — the single source of truth —
    // so every path (tap, back, tab switch, process restore) stays in sync.
    val openConversationEntry = nav.current.destination.let { dest ->
        if (dest == FlashDestination.Conversation) nav.current.conversationId else null
    }
    FlashNotificationManager.openConversationId = openConversationEntry
    val notifyContext = LocalContext.current
    LaunchedEffect(openConversationEntry) {
        openConversationEntry?.let { FlashNotificationManager.clearConversation(notifyContext, it) }
    }

    // ERROR-087: any way off the Conversation destination - the system back gesture above all, which only pops the nav
    // stack - ends the chat's "open" state. Left open, the repository keeps marking later messages read and sends the
    // peer a Read receipt for a thread nobody is looking at. Only the transition from an open entry counts; a hop
    // straight from one conversation to another is handled by openConversation().
    var lastOpenConversationEntry by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openConversationEntry) {
        if (openConversationEntry == null && lastOpenConversationEntry != null) {
            chatRepository.closeConversation()
        }
        lastOpenConversationEntry = openConversationEntry
    }

    // Bug 7: consume a notification-tap navigation request once the engine is ready (the
    // real repository must exist to open the thread; navigating before it does would show an
    // empty conversation). Cleared after consuming so re-taps re-trigger.
    val pendingConversation by pendingNotificationConversation.collectAsState()
    LaunchedEffect(ready, pendingConversation) {
        if (!ready || pendingConversation == null) return@LaunchedEffect
        val conversationId = pendingConversation
        if (conversationId != null) {
            selectedChatConversationId = conversationId
            chatRepository.openConversation(conversationId)
            nav.navigate(FlashDestination.Conversation, conversationId = conversationId)
            pendingNotificationConversation.value = null
        }
    }

    val widgetSendRequested by pendingWidgetSendPicker.collectAsState()
    LaunchedEffect(widgetSendRequested, splashSettled) {
        if (widgetSendRequested && splashSettled) {
            pendingWidgetSendPicker.value = false
            onLaunchWidgetSendPicker()
        }
    }

    val shortcutTab by pendingShortcutTab.collectAsState()
    LaunchedEffect(shortcutTab) {
        val tab = shortcutTab ?: return@LaunchedEffect
        if (tab == FlashDestination.Transfers) nav.openTransfers() else nav.selectTab(tab)
        pendingShortcutTab.value = null
    }

    val activeShare by pendingShare.collectAsState()
    LaunchedEffect(activeShare) {
        if (activeShare != null) {
            if (nav.current.destination == FlashDestination.Transfers || nav.current.destination == FlashDestination.Hub || nav.current.destination == FlashDestination.Settings) {
                nav.selectTab(FlashDestination.NearbyDevices)
            }
        }
    }

    val initialJoinLink by pendingJoinLink.collectAsState()
    var showJoinGroupDialog by remember { mutableStateOf(false) }
    var activeJoinLink by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialJoinLink) {
        val link = initialJoinLink
        if (!link.isNullOrBlank()) {
            activeJoinLink = link
            showJoinGroupDialog = true
            pendingJoinLink.value = null
        }
    }

    val initialEnrollLink by pendingEnrollLink.collectAsState()
    var showDeepLinkEnrollDialog by remember { mutableStateOf(false) }
    var activeEnrollLink by remember { mutableStateOf("") }
    var isEnrollingDevice by remember { mutableStateOf(false) }
    var enrollDeviceError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialEnrollLink) {
        val link = initialEnrollLink
        if (!link.isNullOrBlank()) {
            activeEnrollLink = link
            enrollDeviceError = null
            showDeepLinkEnrollDialog = true
            pendingEnrollLink.value = null
        }
    }

    val toastContext = LocalContext.current
    val shellScope = rememberCoroutineScope()

    val onEnrollDeviceAction: (String) -> Unit = { link ->
        val invite = com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite.parseOrNull(link)
        val hub = engine.enterpriseHub
        if (invite == null) {
            enrollDeviceError = "That is not an organisation link. Ask your administrator for a new one."
        } else if (hub == null) {
            enrollDeviceError = "Flash is still starting. Try again in a moment."
        } else {
            isEnrollingDevice = true
            enrollDeviceError = null
            shellScope.launch {
                // One verified, atomic enrolment on the shared hub object (CLI-01): nothing is stored unless the hub proved it is the organisation's.
                val outcome = hub.enroll(invite)
                withContext(Dispatchers.Main) {
                    isEnrollingDevice = false
                    when (outcome) {
                        is com.transfer.flash.core.enterprise.EnrollmentOutcome.Enrolled -> {
                            enrollDeviceError = null
                            showDeepLinkEnrollDialog = false
                            Toast.makeText(
                                toastContext,
                                "You joined ${outcome.credentials.hubName.ifBlank { "your organisation" }}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        is com.transfer.flash.core.enterprise.EnrollmentOutcome.Failed -> {
                            enrollDeviceError = com.transfer.flash.ui.settings.FlashHubStatusMath.enrollFailureText(outcome.reason)
                        }
                    }
                }
            }
        }
    }

    if (showDeepLinkEnrollDialog) {
        com.transfer.flash.ui.settings.FlashEnrollDialog(
            initialLink = activeEnrollLink,
            onDismiss = {
                showDeepLinkEnrollDialog = false
                enrollDeviceError = null
            },
            onEnroll = onEnrollDeviceAction,
            isEnrolling = isEnrollingDevice,
            enrollError = enrollDeviceError,
        )
    }

    // #14: display-name edit sheet. The identity row's tap now opens a rename dialog whose result is
    // persisted through onSettingsChange (DataStore) instead of being a no-op.
    var showRenameDialog by remember { mutableStateOf(false) }
    var showEncryptionSheet by remember { mutableStateOf(false) }

    // UI-024: chat-list global search state. Hoisted at the shell so it survives the tab's
    // AnimatedContent disposal (a state remembered inside the page would reset on every switch).
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // UI-024 / #12: full-history search. Client-side title/preview filtering only matches the row's
    // visible text; a buried match needs Room. The repository now returns STRUCTURED hits (message
    // snippets + attachment file names) instead of a bare set of conversation ids, so the list can
    // say what matched and deep-link to it. Debounced so a fast typer issues one query per pause,
    // cleared when search closes or the box empties.
    var messageBodyMatches by remember { mutableStateOf<Set<String>>(emptySet()) }
    var colleagueMatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // UI-024 upgrade: structured hits + the active scope chip (All / Chats / Messages / Files /
    // Media / Links). Scoped host-side so it survives the tab's AnimatedContent disposal.
    var searchResults by remember { mutableStateOf(FlashChatSearchResultsUi.Empty) }
    var searchScope by remember { mutableStateOf(FlashSearchScope.All) }
    // UI-024 upgrade: persisted recent searches (the Room table existed but was never read). The
    // flow is remembered: `collectAsState` keys its effect on the flow instance, and a fresh flow on
    // every recomposition would restart the collection endlessly.
    val recentSearchesFlow = remember(chatRepository) { chatRepository.observeRecentSearches(8) }
    val recentSearches by recentSearchesFlow.collectAsState(initial = emptyList())
    LaunchedEffect(searchQuery, isSearching) {
        val q = searchQuery.trim()
        if (!isSearching || q.isEmpty()) {
            messageBodyMatches = emptySet()
            searchResults = FlashChatSearchResultsUi.Empty
            colleagueMatchIds = emptySet()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(200)
        val structured = chatRepository.searchChats(q)
        searchResults = structured
        messageBodyMatches = structured.conversationIds
        colleagueMatchIds = chatRepository.searchColleagues(q)
    }

    // UI-046 2.1: scroll position per tab lives HERE. AnimatedContent disposes the outgoing
    // page, so a state remembered inside a page would die on every tab switch and silently
    // jump back to the top.
    val chatListScroll = rememberLazyListState()
    val transfersScroll = rememberLazyListState()
    val nearbyScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()

    val isAnyListScrolling by remember {
        derivedStateOf {
            chatListScroll.isScrollInProgress ||
                transfersScroll.isScrollInProgress ||
                nearbyScroll.isScrollInProgress ||
                settingsScroll.isScrollInProgress
        }
    }
    LaunchedEffect(isAnyListScrolling) {
        UiScrollGovernor.onScrollChanged(isAnyListScrolling)
    }

    val scope = rememberCoroutineScope()
    val reduceMotion = FlashTheme.motion.reduceMotion
    // C7: CAMERA runtime permission launcher for video calls. webrtc-kmp's getUserMedia
    // throws CameraPermissionException if CAMERA is not granted, so we check before startCall.
    val callCtx = LocalContext.current
    // C7: an accept blocked on a runtime permission resumes itself from the grant callback.
    // Without this the user grants the mic and the ringing call just sits there until they
    // think to tap Accept a second time.
    var pendingCallAccept by remember { mutableStateOf(false) }
    // ADR-078: "Turn on camera" waiting on the CAMERA grant; the grant callback adds the camera to the live call.
    var pendingCameraUpgrade by remember { mutableStateOf(false) }
    var pendingOutgoingCall by remember { mutableStateOf<PendingOutgoingCall?>(null) }
    var pendingNearbySendPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingNearbySendPeerName by rememberSaveable { mutableStateOf<String?>(null) }
    // C7: platform audio mode/focus/routing for the duration of a call. Declared here — ahead of
    // the permission launchers — because a permission-resumed accept has to set the mode before
    // media starts, same as the direct accept path. Survives recomposition so the same instance
    // restores the mode it saved; onDispose is the safety net for the activity going away mid-call.
    val audioRouter = remember(callCtx) { FlashCallAudioRouter(callCtx) }
    DisposableEffect(audioRouter) {
        onDispose { audioRouter.detach() }
    }
    val executePendingOutgoingCall: (PendingOutgoingCall) -> Unit = { call ->
        scope.launch(Dispatchers.IO) {
            when (call) {
                is PendingOutgoingCall.Direct -> {
                    engine.calls?.startCall(call.peerId, call.peerName, video = call.video)
                }
                is PendingOutgoingCall.StartGroup -> {
                    val started = engine.calls?.startGroupCall(
                        groupId = call.groupId,
                        groupName = call.groupName,
                        memberIds = call.memberIds,
                        video = call.video,
                    )
                    if (started == false) {
                        // ERROR-095: surface refused group calls instead of acting like a dead button.
                        withContext(Dispatchers.Main) {
                            Toast.makeText(callCtx, GROUP_CALL_NOT_STARTED, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                is PendingOutgoingCall.JoinGroup -> {
                    val joined = engine.calls?.joinGroupCall(
                        groupId = call.groupId,
                        callId = call.callId,
                        memberIds = call.memberIds,
                        video = call.video,
                    )
                    if (joined == false) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(callCtx, GROUP_CALL_NOT_STARTED, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingCameraUpgrade) {
            pendingCameraUpgrade = false
            if (granted) {
                scope.launch { engine.calls?.upgradeToVideo() }
            } else {
                Toast.makeText(callCtx, "Camera permission is needed to turn on video", Toast.LENGTH_SHORT).show()
            }
            return@rememberLauncherForActivityResult
        }
        val queuedOutgoing = pendingOutgoingCall
        if (queuedOutgoing != null) {
            pendingOutgoingCall = null
            if (granted) {
                executePendingOutgoingCall(queuedOutgoing)
            } else if (queuedOutgoing is PendingOutgoingCall.JoinGroup) {
                Toast.makeText(callCtx, "Joining without camera", Toast.LENGTH_SHORT).show()
                executePendingOutgoingCall(queuedOutgoing.copy(video = false))
            } else {
                Toast.makeText(callCtx, "Camera permission is required for video calls", Toast.LENGTH_SHORT).show()
            }
            return@rememberLauncherForActivityResult
        }
        if (!granted && pendingCallAccept) {
            // ERROR-105: a refused camera used to leave the incoming call ringing with nothing to tap. Answer it
            // with the microphone only (the call screen says "Joined without camera"); the mic grant is already held.
            pendingCallAccept = false
            Toast.makeText(callCtx, "Joining without camera", Toast.LENGTH_SHORT).show()
            audioRouter.attach(engine.calls?.activeCall?.value?.speakerOn == true)
            scope.launch { engine.calls?.accept(audioOnly = true) }
        } else if (!granted) {
            Toast.makeText(callCtx, "Camera permission is required for video calls", Toast.LENGTH_SHORT).show()
        } else if (pendingCallAccept) {
            pendingCallAccept = false
            audioRouter.attach(engine.calls?.activeCall?.value?.speakerOn == true)
            scope.launch { engine.calls?.accept() }
        } else {
            Toast.makeText(callCtx, "Camera ready — start video call", Toast.LENGTH_SHORT).show()
        }
    }
    // C7: RECORD_AUDIO runtime permission launcher for voice/video calls.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            pendingCallAccept = false
            pendingOutgoingCall = null
            Toast.makeText(callCtx, "Microphone permission is required for calls", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (pendingCallAccept) {
            // A video call needs the camera too before accept can succeed — chain the prompts.
            val needsCamera = engine.calls?.activeCall?.value?.video == true &&
                ContextCompat.checkSelfPermission(callCtx, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            if (needsCamera) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                pendingCallAccept = false
                audioRouter.attach(engine.calls?.activeCall?.value?.speakerOn == true)
                scope.launch { engine.calls?.accept() }
            }
            return@rememberLauncherForActivityResult
        }
        val queuedOutgoing = pendingOutgoingCall ?: return@rememberLauncherForActivityResult
        val needsCamera = queuedOutgoing.video &&
            ContextCompat.checkSelfPermission(callCtx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        if (needsCamera) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            pendingOutgoingCall = null
            executePendingOutgoingCall(queuedOutgoing)
        }
    }
    val launchOrQueueOutgoingCall: (PendingOutgoingCall) -> Unit = { call ->
        val hasAudio = ContextCompat.checkSelfPermission(
            callCtx,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = !call.video || ContextCompat.checkSelfPermission(
            callCtx,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        when (CallPermissionActionPlan.decide(hasAudio = hasAudio, hasCamera = hasCamera)) {
            CallPermissionAction.EXECUTE_NOW -> {
                pendingOutgoingCall = null
                executePendingOutgoingCall(call)
            }
            CallPermissionAction.REQUEST_AUDIO -> {
                pendingOutgoingCall = call
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            CallPermissionAction.REQUEST_CAMERA -> {
                pendingOutgoingCall = call
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Notification answer-button: when the user taps "Answer" on the incoming-call notification
    // (FlashCallActionReceiver → EXTRA_ANSWER_CALL → pendingCallAnswer), auto-accept the ringing
    // call using the same permission/audioRouter pattern as the in-overlay accept button.
    val pendingAnswer by pendingCallAnswer.collectAsState()
    LaunchedEffect(pendingAnswer, ready) {
        if (!pendingAnswer || !ready) return@LaunchedEffect
        pendingCallAnswer.value = false
        val calls = engine.calls ?: return@LaunchedEffect
        val active = calls.activeCall.value ?: return@LaunchedEffect
        if (active.state != FlashCallState.RINGING) return@LaunchedEffect
        val hasAudio = ContextCompat.checkSelfPermission(
            callCtx, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudio) {
            pendingCallAccept = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        if (active.video) {
            val hasCamera = ContextCompat.checkSelfPermission(
                callCtx, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasCamera) {
                pendingCallAccept = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                return@LaunchedEffect
            }
        }
        audioRouter.attach(active.speakerOn)
        calls.accept()
    }

    // Phase 3.3: Transfers derives from the live transfer repository's activeTransfers. The fallback
    // flow is remembered UNCONDITIONALLY and swaps to the engine flow once booted (never remember
    // inside a `?:` — conditional remember desyncs the slot table). TransfersUiState.fromDomain does
    // the domain→UI mapping (Active/Failed/History bucketing, direction, ETA/verified normalisation).
    val fallbackTransfers = remember { MutableStateFlow(emptyList<FlashTransfer>()) }
    // The source is resolved OUTSIDE the remember below (the `?:` picks a flow, it does not decide
    // whether to remember), then paced. Unpaced this is the transfer layer's raw 10 ms watcher tick:
    // a hundred fresh lists a second for the whole duration of a transfer, collected here at the
    // shell, so it invalidated the shell and re-ran `fromDomain` (a map, four filters and a copy)
    // every frame — even with the Transfers tab off screen. See FlashTransfersMath.PROGRESS_THROTTLE_MS
    // for why the window is derived from the animation duration rather than picked, and why pacing
    // costs this screen nothing: speed is rounded to one decimal, ETA to whole seconds, and both the
    // row fill and the header throughput roll are animated against Compose's frame clock.
    val transfersSource = engine.transfers?.activeTransfers ?: fallbackTransfers
    val pacedTransfers = remember(transfersSource) {
        transfersSource.throttleLatest(FlashTransfersMath.PROGRESS_THROTTLE_MS)
    }
    // Seeded with the StateFlow's CURRENT value, not an empty list: the leading edge arrives on the
    // first dispatch, and seeding makes even that gap unobservable, so the tab never renders a frame
    // of "nothing" that it would have to take back (ERROR-034).
    @SuppressLint("StateFlowValueCalledInComposition") // `.value` is only the initial seed (see above).
    val domainTransfers by pacedTransfers.collectAsState(initial = transfersSource.value)
    // ERROR-034: the tab's Loading/Error branches existed but nothing ever reached them, so an
    // un-booted Transfers tab claimed "No transfers yet" — a statement about this device's history
    // from code that had no access to it yet. `engine.transfers == null` is exactly the pre-boot
    // window; once the repository exists an empty list is the truth (it is in-memory, not queried).
    val transfersReady = ready && engine.transfers != null
    // `derivedStateOf`, not `remember(domainTransfers, …)`. The difference is *where the read is
    // recorded* (EXP-012). With a plain `remember` keyed on `domainTransfers`, the key expression
    // reads the transfer State here, at FlashShell scope, so every paced tick invalidated this whole
    // ~750-line composable and re-ran `fromDomain` (a map, one item per transfer, four filter passes,
    // a state object and a copy) — with the Transfers tab off screen, because `transfersUi` has
    // exactly one consumer, the `FlashDestination.Transfers ->` branch. Inside `derivedStateOf` the
    // same read belongs to the derived state, so it invalidates only whoever reads `transfersUi`, and
    // the block itself is *lazy*: off-tab nobody reads it and the mapping never runs at all. Pacing
    // (above) is still what keeps the upstream flow cheap; this is what keeps the shell out of it.
    // Keys: `pacedTransfers` because the source flow swaps at boot and the block would otherwise keep
    // reading the pre-boot State forever; `transfersReady` because it is a plain Boolean, not a State,
    // so it would be captured. `chatStartError` needs no key — it is read as State inside the block.
    val transfersUi by remember(pacedTransfers, transfersReady) {
        derivedStateOf {
            TransfersUiState.fromDomain(
                transfers = domainTransfers,
                isLoading = !transfersReady && chatStartError == null,
                isError = chatStartError != null,
            )
        }
    }

    // Phase 3.2: Nearby derives from the live discovery engine. `peers` are the real NSD-discovered
    // endpoints; the identity card reflects this device's advertised id/name/port. Fallback flows are
    // remembered UNCONDITIONALLY (never call remember inside a `?:` — conditional remember desyncs the
    // slot table), and swap to the engine flows once booted. Trusted peers + the pairing dialog come
    // from the PairingCoordinator (C2/C4) below; a discovered peer that is also trusted shows Chat.
    val fallbackEndpoints = remember { MutableStateFlow(emptyList<FlashDiscoveredEndpoint>()) }
    val fallbackDiscoveryState = remember { MutableStateFlow(FlashDiscoveryState()) }
    val discoveredEndpoints by (engine.discovery?.discoveredEndpoints ?: fallbackEndpoints).collectAsState()
    val discoveryState by (engine.discovery?.state ?: fallbackDiscoveryState).collectAsState()
    // DR3: the manual "Scan network" outcome. Read by the `nearby` derivation below.
    val sweepState by DiscoveryEngineHolder.sweepState.collectAsState()
    // DR5: the quiet-network hint needs the live session count, so this is collected before the `nearby`
    // derivation that reads it (and again by the rows below).
    val activeSessions by remember(engine.network) {
        engine.network?.activeSessions ?: MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    }.collectAsState()
    // C2/C4: pairing dialog + trusted peers come from the PairingCoordinator once booted. Fallbacks
    // are remembered UNCONDITIONALLY and swap to the coordinator flows once ready (never remember
    // inside a `?:` — conditional remember desyncs the slot table).
    val fallbackPairing = remember { MutableStateFlow<PairingUiModel?>(null) }
    val fallbackTrusted = remember { MutableStateFlow(emptyList<NearbyTrustedPeerUi>()) }
    val pairingModel by (engine.pairing?.pairing ?: fallbackPairing).collectAsState()
    val trustedPeers by (engine.pairing?.trustedPeers ?: fallbackTrusted).collectAsState()
    // Group Phase 1A: the create-group sheet opens from the chat list's top bar; its roster comes
    // from the pairing coordinator's trusted peers only (fail-closed membership by construction).
    var showCreateGroup by remember { mutableStateOf(false) }
    var showBrowseOrgGroups by remember { mutableStateOf(false) }
    val trustedPeerRoster = trustedPeers.map { trusted ->
        // Initials mirror the repository's rule: first letters of the first two words, uppercased.
        val initials = trusted.name.trim().split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .take(2)
            .map { it.first().uppercaseChar().toString() }
            .joinToString("")
            .ifBlank { "?" }
        com.transfer.flash.ui.chat.FlashCreateGroupPeerUi(
            id = trusted.id,
            name = trusted.name,
            initials = initials,
        )
    }
    var lastPairingPeer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lastPairingFailureMessage by remember { mutableStateOf<String?>(null) }

    // Surface the coordinator's transient status lines (e.g. "Connecting…", "Couldn't reach …") as
    // toasts so tapping Pair always gives feedback instead of silently doing nothing. Keyed on the
    // coordinator instance so collection (re)starts once the engine boots.
    LaunchedEffect(engine.pairing) {
        android.util.Log.i(
            "FLASH_PAIRING",
            "shell.coordinator bound=${engine.pairing != null}",
        )
        engine.pairing?.messages?.collect { message ->
            if (message.startsWith("The other device is running") ||
                message.startsWith("Pairing stopped:") ||
                message.startsWith("Too many pairing attempts")
            ) {
                lastPairingFailureMessage = message
            }
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Diagnostic for ERROR-1010/1013: one line per visible phase, not per countdown second. Proves
    // whether the shell's consent-card model is null while the coordinator still holds an active
    // request (drawing/binding problem) or null together (protocol problem).
    LaunchedEffect(pairingModel?.phase) {
        if (pairingModel?.phase != FlashPairingPhase.Failed) {
            lastPairingFailureMessage = null
        }
        android.util.Log.i(
            "FLASH_PAIRING",
            "shell.model phase=${pairingModel?.phase?.name ?: "null"} " +
                "peer=${pairingModel?.request?.peerName ?: "-"}",
        )
    }

    var localLanAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    var wifiEnabled by remember { mutableStateOf(true) }
    var networkRefreshTick by remember { mutableStateOf(0) }
    DisposableEffect(toastContext) {
        val cm = toastContext.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkRefreshTick++ }
            override fun onLost(network: Network) { networkRefreshTick++ }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { networkRefreshTick++ }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { cm?.registerNetworkCallback(req, callback) }
        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                networkRefreshTick++
            }
        }
        runCatching {
            toastContext.registerReceiver(wifiReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        }
        onDispose {
            runCatching { cm?.unregisterNetworkCallback(callback) }
            runCatching { toastContext.unregisterReceiver(wifiReceiver) }
        }
    }
    LaunchedEffect(toastContext, ready, networkRefreshTick, discoveryState.advertisedPort) {
        val (ips, wifiOn) = withContext(Dispatchers.IO) {
            val addrs = runCatching {
                com.transfer.flash.core.network.util.LocalNetworkAddresses(toastContext).ipv4Addresses()
            }.getOrDefault(emptyList())
            val wm = toastContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val isWifiOn = wm?.isWifiEnabled ?: true
            addrs to isWifiOn
        }
        localLanAddresses = ips
        wifiEnabled = wifiOn
    }
    val radiosAvailable = !ready ||
        wifiEnabled ||
        localLanAddresses.isNotEmpty() ||
        discoveredEndpoints.isNotEmpty() ||
        activeSessions.isNotEmpty()

    var pendingShareRecipient by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isPairingForShare by remember { mutableStateOf(false) }

    val sendSharedPayloadToPeer: (targetDeviceId: String, targetDeviceName: String) -> Unit = { targetDeviceId, targetDeviceName ->
        val share = pendingShare.value
        val transfers = engine.transfers
        if (share != null && transfers != null) {
            val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == targetDeviceId }
            val targetDevice = FlashDevice(
                id = FlashDeviceId(targetDeviceId),
                friendlyName = targetDeviceName,
                transportType = endpoint?.transportType ?: FlashTransportType.LAN,
            )
            val isTrusted = trustedPeers.any { it.id == targetDeviceId }
            if (isTrusted) {
                pendingShare.value = null
                chatRepository.openConversation(targetDeviceId)
                selectedChatConversationId = targetDeviceId
                nav.navigate(FlashDestination.Conversation, conversationId = targetDeviceId)

                scope.launch(Dispatchers.IO) {
                    share.items.forEach { item ->
                        val transferResult = transfers.sendFile(
                            targetDevice = targetDevice,
                            fileUri = item.uri,
                            displayName = item.name,
                            fileSize = item.sizeBytes,
                        )
                        val transferId = transferResult.getOrNull()
                        if (transferId != null) {
                            chatRepository.sendAttachment(
                                conversationId = targetDeviceId,
                                transferId = transferId.value,
                                fileName = item.name,
                                mimeType = item.mimeType,
                                sizeBytes = item.sizeBytes,
                                localPath = item.uri,
                            )
                        }
                    }
                    val text = share.text
                    if (!text.isNullOrBlank()) {
                        chatRepository.sendText(text)
                    }
                }
                val summary = FlashShareTargetMath.formatItemSummary(share)
                Toast.makeText(toastContext, "Sharing $summary with $targetDeviceName", Toast.LENGTH_SHORT).show()
            } else {
                isPairingForShare = true
                pendingShareRecipient = Pair(targetDeviceId, targetDeviceName)
                scope.launch {
                    val net = engine.network
                    if (endpoint != null && net != null) {
                        net.connectManual(endpoint.hostAddress, endpoint.port)
                    }
                    lastPairingPeer = targetDeviceId to targetDeviceName
                    engine.pairing?.beginPair(targetDeviceId, targetDeviceName)
                }
                Toast.makeText(toastContext, "Initiating pairing with $targetDeviceName...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val groupFileSender = remember(engine, chatRepository, discoveredEndpoints) {
        GroupFileSender(
            localDeviceId = { engine.localDeviceId },
            groupMembers = { groupId -> chatRepository.groupMembers(groupId) },
            deviceFor = { memberId, memberName ->
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == memberId }
                FlashDevice(
                    id = FlashDeviceId(memberId),
                    friendlyName = memberName,
                    transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                )
            },
            announce = { groupId, recipientDeviceId, messageId, transferId, wireFileId, fileName, mimeType, sizeBytes, root, pieceSize, swarm, rootSig ->
                chatRepository.beginGroupAttachment(
                    groupId = groupId,
                    recipientDeviceId = recipientDeviceId,
                    messageId = messageId,
                    transferId = transferId,
                    wireFileId = wireFileId,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    root = root,
                    pieceSize = pieceSize,
                    swarm = swarm,
                    rootSig = rootSig,
                )
            },
            sendFile = { targetDevice, fileUri, fileName, sizeBytes, transferId, wireFileId ->
                engine.transfers?.sendFile(
                    targetDevice,
                    fileUri,
                    fileName,
                    sizeBytes,
                    transferId = transferId,
                    wireFileId = wireFileId,
                )
            },
            sendGroupAttachment = { groupId, messageId, transferId, fileName, mimeType, sizeBytes, localPath, dur, amps ->
                chatRepository.sendGroupAttachment(
                    conversationId = groupId,
                    messageId = messageId,
                    transferId = transferId,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    localPath = localPath,
                    voiceDurationMs = dur,
                    voiceAmplitudes = amps,
                )
            },
            idFactory = { java.util.UUID.randomUUID().toString() },
            isV2Group = { groupId -> chatRepository.isV2Group(groupId) },
            peerFeatures = { peerId -> engine.peerFeatures(peerId) },
            prepareSwarmOrigin = { groupId, messageId, fileName, mimeType, sizeBytes, uri ->
                engine.prepareSwarmOrigin(groupId, messageId, fileName, mimeType, sizeBytes, uri)
            },
            recordSwarmOffer = { messageId, root, pieceSize, rootSig ->
                chatRepository.recordSwarmOffer(messageId, root, pieceSize, rootSig)
            },
            hubGroupFile = { groupId, messageId, uri, fileName, _, sizeBytes, recipients ->
                engine.sendGroupFileThroughHub(groupId, messageId, uri, fileName, sizeBytes, recipients)
            },
        )
    }

    // Delivers [payload] into one chat by that chat's own id: a group goes through the group sender
    // (swarm / fan-out), a paired peer gets a direct transfer, and text is addressed to the target
    // rather than to whichever conversation happens to be open. It does not navigate, so a forward to
    // several chats reaches every one of them.
    val deliverPayloadToChat: (recipient: FlashShareRecipientUi, payload: FlashSharePayloadUi) -> Unit = { recipient, payload ->
        val transfers = engine.transfers
        if (transfers != null) {
            val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == recipient.id }
            val targetDevice = FlashDevice(
                id = FlashDeviceId(recipient.id),
                friendlyName = recipient.name,
                transportType = endpoint?.transportType ?: FlashTransportType.LAN,
            )
            scope.launch(Dispatchers.IO) {
                payload.items.forEach { item ->
                    val mime = item.mimeType.takeIf { it.isNotBlank() && it != "*/*" }
                        ?: guessMimeType(item.name, item.uri, toastContext)
                    if (recipient.isGroup) {
                        groupFileSender.send(
                            groupId = recipient.id,
                            uri = item.uri,
                            displayName = item.name,
                            sizeBytes = item.sizeBytes,
                            mimeType = mime,
                        )
                    } else {
                        val transferId = transfers.sendFile(
                            targetDevice = targetDevice,
                            fileUri = item.uri,
                            displayName = item.name,
                            fileSize = item.sizeBytes,
                        ).getOrNull()
                        if (transferId != null) {
                            chatRepository.sendAttachment(
                                conversationId = recipient.id,
                                transferId = transferId.value,
                                fileName = item.name,
                                mimeType = mime,
                                sizeBytes = item.sizeBytes,
                                localPath = item.uri,
                            )
                        }
                    }
                }
                val text = payload.text
                if (!text.isNullOrBlank()) {
                    chatRepository.sendTextTo(recipient.id, text)
                }
            }
        }
    }

    // Forward / share to one or more chats. Groups and paired peers are delivered directly. A lone
    // unpaired target keeps the pairing-then-send flow; with several targets an unpaired one is
    // skipped and named, because pairing is interactive and cannot be run once per target.
    val forwardPayloadToChats: (targets: List<FlashShareRecipientUi>, payload: FlashSharePayloadUi) -> Unit = { targets, payload ->
        val distinct = targets.distinctBy { it.id }
        val (ready, unpaired) = distinct.partition { target ->
            target.isGroup || trustedPeers.any { it.id == target.id }
        }
        if (ready.isEmpty() && unpaired.size == 1) {
            val only = unpaired.first()
            pendingShare.value = payload
            sendSharedPayloadToPeer(only.id, only.name)
        } else {
            ready.forEach { deliverPayloadToChat(it, payload) }
            if (ready.isNotEmpty()) pendingShare.value = null
            if (ready.size == 1 && unpaired.isEmpty()) {
                val only = ready.first()
                chatRepository.openConversation(only.id)
                selectedChatConversationId = only.id
                nav.navigate(FlashDestination.Conversation, conversationId = only.id)
            }
            if (unpaired.isNotEmpty()) {
                Toast.makeText(
                    toastContext,
                    "Not sent to ${unpaired.joinToString { it.name }}: pair with them first",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    LaunchedEffect(pairingModel?.phase, trustedPeers) {
        val target = pendingShareRecipient ?: return@LaunchedEffect
        val (targetDeviceId, targetDeviceName) = target
        val isNowTrusted = trustedPeers.any { it.id == targetDeviceId }
        val isPairedPhase = pairingModel?.phase == FlashPairingPhase.Paired

        if (isNowTrusted || isPairedPhase) {
            pendingShareRecipient = null
            isPairingForShare = false
            val share = pendingShare.value
            val transfers = engine.transfers
            if (share != null && transfers != null) {
                pendingShare.value = null
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == targetDeviceId }
                val targetDevice = FlashDevice(
                    id = FlashDeviceId(targetDeviceId),
                    friendlyName = targetDeviceName,
                    transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                )
                chatRepository.openConversation(targetDeviceId)
                selectedChatConversationId = targetDeviceId
                nav.navigate(FlashDestination.Conversation, conversationId = targetDeviceId)

                scope.launch(Dispatchers.IO) {
                    share.items.forEach { item ->
                        val transferResult = transfers.sendFile(
                            targetDevice = targetDevice,
                            fileUri = item.uri,
                            displayName = item.name,
                            fileSize = item.sizeBytes,
                        )
                        val transferId = transferResult.getOrNull()
                        if (transferId != null) {
                            chatRepository.sendAttachment(
                                conversationId = targetDeviceId,
                                transferId = transferId.value,
                                fileName = item.name,
                                mimeType = item.mimeType,
                                sizeBytes = item.sizeBytes,
                                localPath = item.uri,
                            )
                        }
                    }
                    val text = share.text
                    if (!text.isNullOrBlank()) {
                        chatRepository.sendText(text)
                    }
                }
                val summary = FlashShareTargetMath.formatItemSummary(share)
                Toast.makeText(toastContext, "Paired! Sharing $summary with $targetDeviceName", Toast.LENGTH_SHORT).show()
            }
        } else if (
            pairingModel?.phase == FlashPairingPhase.Declined ||
            pairingModel?.phase == FlashPairingPhase.Expired ||
            pairingModel?.phase == FlashPairingPhase.Failed
        ) {
            // Preserve pendingShare and pendingShareRecipient across Retry until pairing succeeds
            // or the user explicitly dismisses/declines the pairing dialog.
            Toast.makeText(
                toastContext,
                if (pairingModel?.phase == FlashPairingPhase.Failed) {
                    "Pairing failed. Files not sent."
                } else {
                    "Pairing was cancelled or timed out. Files not sent."
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // `ready` is read for `isLoading` below; inside `derivedStateOf` it is read as State rather than
    // as a key, which is narrower, not looser (EXP-012). Same reasoning as `transfersUi` above: as a
    // 5-key `remember` this recorded five State reads at FlashShell scope, so any discovery tick,
    // presence change or pairing-countdown second re-executed the entire shell body *and* rebuilt
    // NearbyUiState (a HashSet of trusted ids, a filter and a map over every endpoint) even with the
    // Nearby tab off screen. As a derived state the reads belong to the derivation, it recomputes
    // lazily only when something actually reads `nearby`, and because NearbyUiState is a data class
    // the default structural-equality policy drops an identical rebuild without invalidating anyone.
    // Keyed on the engine handles that own the four flows: they swap once at boot, and without the
    // keys this block would keep reading the pre-boot fallback States forever.
    val nearby by remember(engine, engine.discovery, engine.pairing, engine.network) {
        derivedStateOf {
            val trustedIds = trustedPeers.mapTo(HashSet()) { it.id }
            // Every discovered peer as a row, BEFORE the trusted filter: the badge join below looks
            // a trusted peer up here, and trusted peers are deliberately excluded from `peers`.
            val discoveredRows = discoveredEndpoints.map { ep ->
                val peerId = ep.deviceId.value
                val isColleague = engine.hasAuthenticatedOrgSession(peerId)
                NearbyPeerUi(
                    id = peerId,
                    name = ep.friendlyName,
                    transport = ep.transportType.toUiTransport(),
                    isTrusted = false,
                    deviceKind = ep.deviceKind,
                    isColleague = isColleague,
                    orgName = if (isColleague) engine.enterpriseTrustStore?.getCredentials()?.hubName else null,
                )
            }
            NearbyUiState(
                identity = NearbyIdentityUi(
                    // ERROR-034: real identity, not "Flash device" / "00000000". See the settings model
                    // above — the store resolves both before the stack boots, so there was never a
                    // window in which we had to invent them.
                    displayName = engine.localFriendlyName,
                    deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
                    port = discoveryState.advertisedPort,
                    localAddressLabel = localLanAddresses.firstOrNull(),
                ),
                isScanning = discoveryState.isDiscovering,
                radiosAvailable = radiosAvailable,
                // ERROR-034: the Nearby page has had a Loading branch all along and nothing reached it,
                // so for the whole boot the tab rendered the first-run "no devices" panel under the
                // header line "Scan paused" — attributing to the user a pause of a scan that had not
                // started. `ready` is the right gate here: discovery is what `ensureStarted` brings up.
                isLoading = !ready,
                // A trusted peer lives in the TRUSTED section (with its own Chat/Revoke), so exclude
                // it from DISCOVERED — otherwise the same device renders in both sections, which both
                // duplicates the row and (sharing a device-id key) crashed the Nearby LazyColumn.
                peers = discoveredRows.filter { it.id !in trustedIds },
                // The trusted row's PC/Phone badge is a JOIN against the current advertisement,
                // not a stored field: trust is persisted as id + name only, so a trusted peer that
                // is not on the network right now has no kind and shows no badge. Same helper the
                // desktop shell calls — a display rule that differed per host would be a bug.
                trustedPeers = FlashNearbyMath.withDeviceKinds(
                    trusted = trustedPeers,
                    discovered = discoveredRows,
                ),
                scan = sweepState.toNearbyScan(),
                discoveryQuiet = FlashNearbyMath.discoveryQuiet(
                    pairedPeers = trustedPeers.size,
                    discoveredPeers = discoveredEndpoints.size,
                    liveSessions = activeSessions.size,
                    isDiscovering = discoveryState.isDiscovering,
                ),
            )
        }
    }

    val activeSessionPeerIds = remember(activeSessions) {
        activeSessions.keys.map { it.value }.toSet()
    }

    val pairedRecipients = remember(trustedPeers, discoveredEndpoints, activeSessionPeerIds) {
        val discoveredMap = discoveredEndpoints.associateBy { it.deviceId.value }
        trustedPeers.map { trusted ->
            val endpoint = discoveredMap[trusted.id]
            val isOnline = endpoint != null || activeSessionPeerIds.contains(trusted.id)
            FlashShareRecipientUi(
                id = trusted.id,
                name = trusted.name,
                initials = FlashShareTargetMath.initialsFor(trusted.name),
                isOnline = isOnline,
                isGroup = false,
                isPaired = true,
                transport = endpoint?.transportType?.toUiTransport() ?: FlashNetworkTransport.Lan,
                deviceKind = trusted.deviceKind,
                subtitle = if (isOnline) null else "Offline",
            )
        }
    }

    val nearbyRecipients = remember(discoveredEndpoints, trustedPeers) {
        val trustedIds = trustedPeers.mapTo(HashSet()) { it.id }
        discoveredEndpoints
            .filterNot { ep -> trustedIds.contains(ep.deviceId.value) }
            .map { ep ->
                FlashShareRecipientUi(
                    id = ep.deviceId.value,
                    name = ep.friendlyName,
                    initials = FlashShareTargetMath.initialsFor(ep.friendlyName),
                    isOnline = true,
                    isGroup = false,
                    isPaired = false,
                    transport = ep.transportType.toUiTransport(),
                    deviceKind = ep.deviceKind,
                    subtitle = "Available nearby",
                )
            }
            .distinctBy { it.id }
    }

    val recentChatRecipients = remember(chatListState.items) {
        chatListState.items.take(5).map { chat ->
            FlashShareRecipientUi(
                id = chat.id,
                name = chat.title,
                initials = chat.avatarInitials,
                isOnline = chat.presence == FlashPeerPresence.Online,
                isGroup = chat.isGroup,
                isPaired = true,
                transport = FlashNetworkTransport.Lan,
                deviceKind = com.transfer.flash.core.common.model.FlashDeviceKind.UNKNOWN,
                subtitle = if (chat.isGroup) "Group chat" else null,
            )
        }
    }

    var showShareManualConnect by remember { mutableStateOf(false) }

    // UI-034 / AD-6: Adaptive layout for large displays (tablets, foldables unfolded, landscape, DeX).
    // When width >= 600dp (Medium & Expanded), transforms into a slim navigation rail on the left
    // (no stretched bottom navigation bar). When width >= 840dp, transforms into dual-pane layout
    // (matching modern WhatsApp & Telegram desktop implementations).
    val windowWidthDp = rememberFlashAdaptiveWindowWidthDp()
    val sizeClass = FlashAdaptiveMath.windowSizeForWidth(windowWidthDp)
    val useNavRail = sizeClass != FlashWindowSizeClass.Compact
    val twoPane = FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)

    var selectedTransferId by remember { mutableStateOf<String?>(null) }
    var selectedNearbyPeerId by remember { mutableStateOf<String?>(null) }

    val selectedTransferItem = selectedTransferId?.let { id ->
        transfersUi.offers.firstOrNull { it.id == id }
            ?: transfersUi.active.firstOrNull { it.id == id }
            ?: transfersUi.failed.firstOrNull { it.id == id }
            ?: transfersUi.history.firstOrNull { it.id == id }
    }
    val selectedNearbyPeer = selectedNearbyPeerId?.let { id ->
        nearby.peers.firstOrNull { it.id == id }
            ?: nearby.trustedPeers.firstOrNull { it.id == id }?.let { trusted ->
                val isColleague = engine.hasAuthenticatedOrgSession(trusted.id)
                NearbyPeerUi(
                    id = trusted.id,
                    name = trusted.name,
                    transport = FlashNetworkTransport.Lan,
                    isTrusted = true,
                    deviceKind = trusted.deviceKind,
                    isColleague = isColleague,
                    orgName = if (isColleague) engine.enterpriseTrustStore?.getCredentials()?.hubName else null,
                )
            }
    }

    LaunchedEffect(nav.current.destination) {
        if (nav.current.destination != FlashDestination.ChatList &&
            nav.current.destination != FlashDestination.Conversation
        ) {
            selectedChatConversationId = null
        }
        if (nav.current.destination != FlashDestination.Transfers) {
            selectedTransferId = null
        }
        if (nav.current.destination != FlashDestination.NearbyDevices) {
            selectedNearbyPeerId = null
        }
    }

    val closeActiveConversation: () -> Unit = {
        val backToHub = nav.conversationOpenedFromHub
        chatRepository.closeConversation()
        selectedChatConversationId = null
        if (twoPane) {
            if (backToHub) nav.selectTab(FlashDestination.Hub) else nav.selectTab(FlashDestination.ChatList)
        } else if (nav.current.destination == FlashDestination.Conversation) {
            nav.back()
        }
    }

    // In two-pane mode, back clears active detail selection on the visible tab without popping the app.
    // Registered BEFORE search and selection BackHandlers so inner overlays win when active.
    val hasTwoPaneDetail = twoPane && when (nav.current.destination) {
        FlashDestination.Transfers -> selectedTransferItem != null
        FlashDestination.NearbyDevices -> selectedNearbyPeer != null
        FlashDestination.Conversation -> true
        FlashDestination.ChatList -> selectedChatConversationId != null
        else -> false
    }
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    BackHandler(enabled = hasTwoPaneDetail) {
        when (nav.current.destination) {
            FlashDestination.Transfers -> selectedTransferId = null
            FlashDestination.NearbyDevices -> selectedNearbyPeerId = null
            FlashDestination.Conversation, FlashDestination.ChatList -> closeActiveConversation()
            else -> Unit
        }
    }
    // UI-024: while chat-list search is open, Back closes search first (registered after
    // stack-pop and two-pane detail so it wins when search is active).
    BackHandler(enabled = isSearching) {
        isSearching = false
        searchQuery = ""
        searchScope = FlashSearchScope.All
    }
    // UI-013: while chat-list selection mode is active, Back exits selection first (registered
    // last so it wins over search-close, two-pane detail, and stack-pop handlers).
    // Reads the derived Boolean, not `chatListState` — see its declaration for why (EXP-012).
    BackHandler(enabled = chatListSelectionMode) {
        chatRepository.clearListSelection()
    }

    val nearbyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val peerId = pendingNearbySendPeerId
        val peerName = pendingNearbySendPeerName
        pendingNearbySendPeerId = null
        pendingNearbySendPeerName = null
        if (uris.isEmpty() || peerId == null || peerName == null) return@rememberLauncherForActivityResult
        val transfers = engine.transfers ?: return@rememberLauncherForActivityResult
        val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
        val targetDevice = FlashDevice(
            id = FlashDeviceId(peerId),
            friendlyName = peerName,
            transportType = endpoint?.transportType ?: FlashTransportType.LAN,
        )
        selectedChatConversationId = peerId
        chatRepository.openConversation(peerId)
        nav.navigate(FlashDestination.Conversation, conversationId = peerId)
        scope.launch(Dispatchers.IO) {
            var lastSentName: String? = null
            uris.forEach { uri ->
                runCatching {
                    toastContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val (name, size) = resolveContentUriNameAndSize(toastContext, uri)
                val mime = guessMimeType(name, uri.toString(), toastContext)
                val transferId = transfers.sendFile(targetDevice, uri.toString(), name, size).getOrNull()
                if (transferId != null) {
                    chatRepository.sendAttachment(
                        conversationId = peerId,
                        transferId = transferId.value,
                        fileName = name,
                        mimeType = mime,
                        sizeBytes = size,
                        localPath = uri.toString(),
                    )
                }
                lastSentName = name
            }
            val summary = if (uris.size == 1) lastSentName ?: "1 file" else "${uris.size} files"
            withContext(Dispatchers.Main) {
                Toast.makeText(toastContext, "Sending $summary to $peerName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val openPeerChat: (NearbyPeerUi) -> Unit = { peer ->
        if (peer.isColleague && !peer.isTrusted) {
            scope.launch {
                val outcome = chatRepository.startColleagueConversation(peer.id)
                when (outcome) {
                    is com.transfer.flash.core.common.result.FlashResult.Success -> {
                        selectedChatConversationId = peer.id
                        chatRepository.openConversation(peer.id)
                        nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
                    }
                    is com.transfer.flash.core.common.result.FlashResult.Failure -> {
                        val msg = (outcome.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                            ?: com.transfer.flash.ui.chat.FlashOrgStrings.ORG_START_NOT_MEMBER
                        Toast.makeText(
                            toastContext,
                            msg,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        } else {
            selectedChatConversationId = peer.id
            chatRepository.openConversation(peer.id)
            nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
        }
    }

    val totalUnreadCount = remember(chatListState.items) {
        chatListState.items.sumOf { it.unreadCount }
    }

    // UI-058: ONE tab source for the bottom bar and the rail (FlashShellTabs), so they cannot drift
    // from FlashNavigationMath.tabOrder. Hub carries no badge until announcements exist (A3).
    val hubUnread by chatRepository.hubUnreadAnnouncementCount.collectAsState(initial = 0)
    val shellTabs = remember(totalUnreadCount, nav.hubEnrolled, hubUnread) {
        FlashShellTabs.of(nav.hubEnrolled, chatsUnread = totalUnreadCount, hubBadge = hubUnread)
    }
    val liveBottomNavTabs = remember(shellTabs) {
        shellTabs.map { FlashBottomNavItem(it.destination, it.icon, it.label, it.badgeCount) }
    }
    val navigationRailTabs = remember(shellTabs) {
        shellTabs.map { FlashNavigationRailTab(it.destination, it.icon, it.label, it.badgeCount ?: 0) }
    }

    // UI-046 v2: the shell bar HANGS over the page instead of docking under it. Tab roots
    // hand `contentInset + system nav inset` to the page, which folds it into its own
    // contentPadding so rows scroll UNDER the capsule; pushed screens (Conversation) drop the
    // bar entirely and manage their own bottom insets. In rail mode, there is no bottom nav.
    val showBar = FlashNavigationMath.showsBottomBar(nav.current.destination, nav.hubEnrolled, useNavRail)
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBottomInset = if (useNavRail) 0.dp else (FlashBottomNavDefaults.contentInset + systemBottomInset)

    val conversationScreenBody: @Composable () -> Unit = {
        val conversationId = nav.current.conversationId
        // The chat this composition belongs to: the draft is saved under it when the screen leaves, after Back has already
        // closed the repository's active conversation (or, in two-pane, after the next chat was opened).
        val draftOwnerId = conversationId ?: selectedChatConversationId
        FlashConversationScreen(
            state = conversationState,
            chatWallpaper = chatWallpaper,
            chatWallpaperCustom = chatWallpaperCustom,
            onSetChatWallpaper = onSetChatWallpaper,
            onSetChatWallpaperCustom = onSetChatWallpaperCustom,
            onBack = closeActiveConversation,
            // UI-032: tapping the header avatar opens the in-screen peer-details sheet.
            // Feed it the peer's live trust status + a revoke action keyed by the
            // conversationId (== peer device id, the pairing/trust key).
            isPeerTrusted = conversationId?.let { pid ->
                trustedPeers.any { it.id == pid }
            } ?: false,
            onRevokePeerTrust = conversationId?.let { pid ->
                { engine.pairing?.revoke(pid); Unit }
            },
            onVerifySecurityCodes = conversationId?.let { pid ->
                { scope.launch { engine.pairing?.beginPair(pid, conversationState.header.title) }; Unit }
            },
            localFingerprint = engine.pairing?.localFingerprintHex,
            peerFingerprint = conversationId?.let { pid -> engine.pairing?.getFingerprint(pid) },
            onSendText = chatRepository::sendText,
            onSendReply = { text, replyToId, replyToPreview ->
                chatRepository.sendReply(text, replyToId, replyToPreview)
            },
            onPersistDraft = { text -> draftOwnerId?.let { chatRepository.saveDraft(it, text) } },
            onSetMessagePinned = chatRepository::setMessagePinned,
            onToggleReaction = { messageId, emoji ->
                chatRepository.toggleReaction(messageId, emoji)
            },
            onTypingChanged = chatRepository::setTyping,
            onSetRadioPolicy = { policy -> conversationId?.let { chatRepository.setRadioPolicy(it, policy) } },
            onAttachmentClick = chatRepository::openAttachmentPicker,
            onSendFile = { uri, displayName, size ->
                val peerId = conversationId
                val transfers = engine.transfers
                if (peerId != null && transfers != null) {
                    val mime = guessMimeType(displayName, uri, toastContext)
                    if (conversationState.header.isGroup) {
                        scope.launch(Dispatchers.IO) {
                            groupFileSender.send(
                                groupId = peerId,
                                uri = uri,
                                displayName = displayName,
                                sizeBytes = size,
                                mimeType = mime,
                            )
                        }
                    } else {
                        val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
                        val targetDevice = FlashDevice(
                            id = FlashDeviceId(peerId),
                            friendlyName = conversationState.header.title,
                            transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                        )
                        scope.launch(Dispatchers.IO) {
                            // Start the P2P transfer, then (B4) drop a local chat row keyed by
                            // the returned transferId so the attachment shows inline in the
                            // conversation — image thumbnail / video play button / file card —
                            // with progress joined from activeTransfers, alongside Transfers.
                            val transferId = transfers.sendFile(targetDevice, uri, displayName, size).getOrNull()
                            if (transferId != null) {
                                chatRepository.sendAttachment(
                                    conversationId = peerId,
                                    transferId = transferId.value,
                                    fileName = displayName,
                                    mimeType = mime,
                                    sizeBytes = size,
                                    localPath = uri,
                                )
                            }
                        }
                    }
                }
            },
            onDeleteMessage = { ids -> chatRepository.deleteMessages(ids) },
            onDeleteMessageForEveryone = chatRepository::deleteMessageForEveryone,
            onOpenAttachment = { path, mime, _ -> openAttachment(toastContext, path, mime) },
            onSaveImage = { uri, mime -> saveMediaToGallery(toastContext, uri, mime) },
            onShareImage = { uri, mime -> shareImageUri(toastContext, uri, mime) },
            onVoiceRecordingStarting = DiscoveryEngineHolder::beginVoiceNote,
            onVoiceRecordingStopped = DiscoveryEngineHolder::endVoiceNote,
            onSendVoiceMessage = { localPath, durationMs, amplitudes ->
                val peerId = conversationId
                val transfers = engine.transfers
                if (peerId != null && transfers != null) {
                    val fileName = "Voice message.m4a"
                    val isGroup = conversationState.header.isGroup
                    val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
                    val target = FlashDevice(
                        id = FlashDeviceId(peerId),
                        friendlyName = conversationState.header.title,
                        transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                    )
                    scope.launch(Dispatchers.IO) {
                        val file = runCatching {
                            val source = requireNotNull(android.net.Uri.parse(localPath).path)
                            val root = java.io.File(toastContext.filesDir, "sent")
                            java.io.File(com.transfer.flash.core.engine.ReceivedFilePlacement.publish(
                                source, root.path, fileName, "audio/mp4",
                            ))
                        }.getOrNull()
                        if (file == null) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(toastContext,
                                    "Couldn’t save the voice message. Try recording again.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        val uri = android.net.Uri.fromFile(file).toString()
                        val size = file.length()
                        if (isGroup) {
                            groupFileSender.send(
                                groupId = peerId, uri = uri, displayName = fileName, sizeBytes = size, mimeType = "audio/mp4",
                                voiceDurationMs = durationMs, voiceAmplitudes = amplitudes,
                            )
                        } else {
                            val transferId = transfers.sendFile(target, uri, fileName, size).getOrNull()
                            if (transferId != null) {
                                chatRepository.sendAttachment(
                                    conversationId = peerId, transferId = transferId.value, fileName = fileName,
                                    mimeType = "audio/mp4", sizeBytes = size, localPath = uri,
                                    voiceDurationMs = durationMs, voiceAmplitudes = amplitudes,
                                )
                            }
                        }
                    }
                }
            },
            // Bug 3: accept/decline a pending inbound video/file offer directly from the
            // chat bubble. file.id == the wire transferId (set in applyAttachment).
            onAcceptOffer = { transferId ->
                engine.transfers?.let { repo ->
                    scope.launch { repo.acceptIncoming(FlashTransferId(transferId)) }
                }
            },
            onDeclineOffer = { transferId ->
                engine.transfers?.let { repo ->
                    scope.launch { repo.declineIncoming(FlashTransferId(transferId)) }
                }
            },
            // Tapping a failed card retries it, matching its "Tap to retry" label and
            // Retry badge. Same entry point as the Transfers tab's Retry button.
            onRetryTransfer = { transferId ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(transferId)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.resumeTransfer(FlashTransferId(subId))
                            }
                        } else {
                            repo.resumeTransfer(FlashTransferId(transferId))
                        }
                    }
                }
            },
            onRetryMessage = { messageId ->
                chatRepository.retryMessage(messageId)
            },
            onStopRadioRetry = { messageId ->
                chatRepository.stopRadioRetry(messageId)
            },
            onPauseTransfer = { transferId ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(transferId)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.pauseTransfer(FlashTransferId(subId))
                            }
                        } else {
                            repo.pauseTransfer(FlashTransferId(transferId))
                        }
                    }
                }
            },
            onResumeTransfer = { transferId ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(transferId)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.resumeTransfer(FlashTransferId(subId))
                            }
                        } else {
                            repo.resumeTransfer(FlashTransferId(transferId))
                        }
                    }
                }
            },
            onCancelTransfer = { transferId ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(transferId)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.cancelTransfer(FlashTransferId(subId))
                            }
                        } else {
                            repo.cancelTransfer(FlashTransferId(transferId))
                        }
                    }
                }
            },
            onStartCall = {
                val peerId = conversationId
                if (peerId != null) {
                    if (conversationState.header.isGroup) {
                        scope.launch {
                            val memberIds = if (conversationState.members.isNotEmpty()) {
                                conversationState.members.map { it.id }
                            } else {
                                withContext(Dispatchers.IO) { chatRepository.groupMembers(peerId).map { it.id } }
                            }
                            launchOrQueueOutgoingCall(
                                PendingOutgoingCall.StartGroup(
                                    groupId = peerId,
                                    groupName = conversationState.header.title,
                                    memberIds = memberIds,
                                    video = false,
                                ),
                            )
                        }
                    } else {
                        launchOrQueueOutgoingCall(
                            PendingOutgoingCall.Direct(
                                peerId = peerId,
                                peerName = conversationState.header.title,
                                video = false,
                            ),
                        )
                    }
                }
            },
            onStartVideoCall = {
                val peerId = conversationId
                if (peerId != null) {
                    if (conversationState.header.isGroup) {
                        scope.launch {
                            val memberIds = if (conversationState.members.isNotEmpty()) {
                                conversationState.members.map { it.id }
                            } else {
                                withContext(Dispatchers.IO) { chatRepository.groupMembers(peerId).map { it.id } }
                            }
                            launchOrQueueOutgoingCall(
                                PendingOutgoingCall.StartGroup(
                                    groupId = peerId,
                                    groupName = conversationState.header.title,
                                    memberIds = memberIds,
                                    video = true,
                                ),
                            )
                        }
                    } else {
                        launchOrQueueOutgoingCall(
                            PendingOutgoingCall.Direct(
                                peerId = peerId,
                                peerName = conversationState.header.title,
                                video = true,
                            ),
                        )
                    }
                }
            },
            // Connection-banner Retry: re-arm discovery browsing and force an immediate
            // auto-connect sweep. Returns false only before the engine has booted, which
            // is the one case where the banner should say "try again in a moment".
            onRetryConnection = { engine.reconnectNow() },
            onShareText = { text -> shareText(toastContext, text) },
            forwardRecipients = (recentChatRecipients + pairedRecipients).distinctBy { it.id },
            forwardRecentChats = recentChatRecipients,
            forwardPairedDevices = pairedRecipients,
            onForwardToRecipients = { targets, payload -> forwardPayloadToChats(targets, payload) },
            // Group Phase D: conversationId + menu actions.
            conversationId = conversationId,
            // UI-024 upgrade: open at the message a global search result pointed to.
            targetMessageId = nav.current.targetMessageId,
            targetNonce = nav.current.targetNonce,
            addablePeers = trustedPeerRoster.filter { candidate ->
                conversationState.members.none { it.id == candidate.id }
            },
            onAddGroupMembers = { groupId, memberIds ->
                scope.launch {
                    // The result used to be dropped, so a refused add looked like success.
                    val note = when (val added = chatRepository.addGroupMembers(groupId, memberIds)) {
                        is com.transfer.flash.core.common.result.FlashResult.Success ->
                            chatRepository.addGroupMembersAdvice(groupId, memberIds)
                        is com.transfer.flash.core.common.result.FlashResult.Failure ->
                            (added.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                                ?: "Couldn't add members"
                    }
                    if (note != null) {
                        Toast.makeText(toastContext, note, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onLeaveGroup = { groupId ->
                scope.launch {
                    val left = chatRepository.leaveGroup(groupId)
                    if (left is com.transfer.flash.core.common.result.FlashResult.Success) {
                        closeActiveConversation()
                    } else {
                        Toast.makeText(
                            toastContext,
                            "Couldn't leave the group — try again",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onLeaveGroupWithSuccessor = { groupId, successorId ->
                scope.launch {
                    val left = chatRepository.leaveGroup(groupId, successorId)
                    if (left is com.transfer.flash.core.common.result.FlashResult.Success) {
                        closeActiveConversation()
                    } else {
                        Toast.makeText(
                            toastContext,
                            "Couldn't leave the group — try again",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onContinueInNewGroup = { groupId ->
                scope.launch {
                    val result = chatRepository.continueInNewGroup(groupId)
                    when (result) {
                        is com.transfer.flash.core.common.result.FlashResult.Success -> {
                            chatRepository.openConversation(result.value)
                            nav.navigate(FlashDestination.Conversation, conversationId = result.value)
                        }
                        is com.transfer.flash.core.common.result.FlashResult.Failure -> {
                            val errMessage = (result.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                                ?: "Couldn't continue in new group"
                            Toast.makeText(
                                toastContext,
                                errMessage,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            // ADR-044 V2: a member the group owner introduced is verified by ordinary pairing, the same flow as
            // Nearby's "Verify" for a peer paired under the old code.
            onVerifyGroupMember = { memberId, memberName ->
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == memberId }
                scope.launch {
                    val net = engine.network
                    if (endpoint != null && net != null) net.connectManual(endpoint.hostAddress, endpoint.port)
                    engine.pairing?.beginPair(memberId, memberName)
                }
            },
            // ADR-044 V2 (E5): only offered to the owner of a v2 group (conversation state canRemoveMembers).
            onRemoveGroupMember = { groupId, memberId, memberName ->
                scope.launch {
                    val removed = chatRepository.removeGroupMember(groupId, memberId)
                    if (removed !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(
                            toastContext,
                            "Couldn't remove $memberName — try again",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            // ADR-063: Promote a member to admin in a v2 group (owner only).
            onPromoteGroupAdmin = { groupId, memberId ->
                scope.launch {
                    val res = chatRepository.promoteAdmin(groupId, memberId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(
                            toastContext,
                            "Couldn't promote to admin — try again",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            // ADR-063: Demote an admin to regular member in a v2 group (owner only).
            onDemoteGroupAdmin = { groupId, memberId ->
                scope.launch {
                    val res = chatRepository.demoteAdmin(groupId, memberId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(
                            toastContext,
                            "Couldn't demote admin — try again",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            // UI-051: Message Info for a group message this device sent (who read / got / has not yet got it).
            observeMessageInfo = chatRepository::observeMessageInfo,
            onClearConversation = { id ->
                chatRepository.deleteConversations(setOf(id))
                closeActiveConversation()
            },
            onMarkUnread = chatRepository::markConversationUnread,
            onJoinGroupCall = { callId, video ->
                val peerId = conversationId
                if (peerId != null) {
                    scope.launch {
                        val memberIds = if (conversationState.members.isNotEmpty()) {
                            conversationState.members.map { it.id }
                        } else {
                            withContext(Dispatchers.IO) { chatRepository.groupMembers(peerId).map { it.id } }
                        }
                        launchOrQueueOutgoingCall(
                            PendingOutgoingCall.JoinGroup(
                                groupId = peerId,
                                callId = callId,
                                memberIds = memberIds,
                                video = video,
                            ),
                        )
                    }
                }
            },
            onApproveJoinRequest = { gid, subjectId ->
                scope.launch {
                    val res = chatRepository.approveJoinRequest(gid, subjectId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't approve join request", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRefuseJoinRequest = { gid, subjectId ->
                scope.launch {
                    val res = chatRepository.refuseJoinRequest(gid, subjectId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't refuse join request", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onUpdateGroupSettings = { gid, joinPolicy, inviteSharers, maxMembers, swarmServing, membersMayAdd ->
                scope.launch {
                    val res = chatRepository.updateGroupSettings(
                        groupId = gid,
                        joinPolicy = joinPolicy,
                        inviteSharers = inviteSharers,
                        maxMembers = maxMembers,
                        swarmServing = swarmServing,
                        membersMayAdd = membersMayAdd,
                    )
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't update settings", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onUpdateGroupPreferences = { gid, serveToGroup, serveWifiOnly, batteryThreshold, keepDays ->
                scope.launch {
                    val res = chatRepository.updateGroupLocalPreferences(
                        groupId = gid,
                        serveToGroup = serveToGroup,
                        serveWifiOnly = serveWifiOnly,
                        batteryThresholdPercent = batteryThreshold,
                        keepAvailableDays = keepDays,
                    )
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't update preferences", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onChangeGroupCode = { gid ->
                scope.launch {
                    val res = chatRepository.changeGroupCode(gid)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't change group code", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onChooseGroupHistory = { gid, windowMs, includeFiles ->
                scope.launch {
                    val res = chatRepository.chooseGroupHistory(gid, windowMs, includeFiles)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't load earlier history", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onConfirmHubKeyChange = { peerId -> scope.launch { chatRepository.confirmHubKeyChange(peerId) } },
            onSkipGroupHistory = { gid ->
                scope.launch {
                    val res = chatRepository.skipGroupHistory(gid)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't save your choice", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLoadOlderGroupHistory = { gid, windowMs ->
                scope.launch {
                    val res = chatRepository.loadOlderGroupHistory(gid, windowMs)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't load older messages", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSetGroupHistoryCeiling = { gid, ceiling ->
                scope.launch {
                    val res = chatRepository.updateGroupSettings(groupId = gid, historyCeiling = ceiling)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        Toast.makeText(toastContext, "Couldn't update the history limit", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onRequestInviteLink = { gid ->
                (chatRepository.inviteFor(gid) as? com.transfer.flash.core.common.result.FlashResult.Success)?.value
            },
            onJoinInviteGroup = { inviteUrl ->
                activeJoinLink = inviteUrl
                showJoinGroupDialog = true
            },
            isEnterpriseEnrolled = settings.isEnterpriseEnrolled,
            isLinkedToHub = conversationId?.let { engine.enterpriseTrustStore?.isGroupLinkedToHub(it) } ?: false,
            onToggleLinkToHub = { link ->
                conversationId?.let { gid ->
                    scope.launch {
                        if (link) {
                            val res = chatRepository.linkGroupToHub(gid)
                            withContext(Dispatchers.Main) {
                                if (res is com.transfer.flash.core.common.result.FlashResult.Success) {
                                    Toast.makeText(toastContext, "Linked to corporate hub (Zero Cloud Dump: past messages remain local)", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(toastContext, "Failed to link group to hub", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            chatRepository.unlinkGroupFromHub(gid)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(toastContext, "Unlinked from corporate hub", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
            isPeerVerified = conversationId?.let { pid ->
                trustedPeers.firstOrNull { it.id == pid }?.verified
            } ?: false,
            onVerifyPeerTrust = conversationId?.let { pid ->
                { scope.launch { engine.pairing?.beginPair(pid, conversationState.header.title) }; Unit }
            },
            connectionTransport = conversationId?.let { pid ->
                discoveredEndpoints.firstOrNull { it.deviceId.value == pid }?.transportType?.toUiTransport()
            } ?: conversationState.header.transport,
            onFindDevicesClick = { nav.selectTab(FlashDestination.NearbyDevices) },
            localDeviceId = engine.localDeviceId.takeIf { it.isNotBlank() },
        )
    }

    // One screen instance per conversation. In two-pane the detail pane is the same call site for every chat, so without a key
    // the composer text, reply target, selection, scroll position and "N new messages" count of the previous chat carried over
    // to the next one (and a typed note was then sent to the wrong person).
    val conversationScreenContent: @Composable () -> Unit = {
        key(nav.current.conversationId ?: selectedChatConversationId) { conversationScreenBody() }
    }

    val transfersScreenContent: @Composable () -> Unit = {
        FlashTransfersScreen(
            state = transfersUi,
            onPauseResumeClick = { item ->
                engine.transfers?.let { repo ->
                    val id = FlashTransferId(item.id)
                    scope.launch {
                        if (item.state == FlashTransferState.Paused) repo.resumeTransfer(id)
                        else repo.pauseTransfer(id)
                    }
                }
            },
            onCancelClick = { item ->
                engine.transfers?.let { repo ->
                    scope.launch { repo.cancelTransfer(FlashTransferId(item.id)) }
                }
            },
            onRetryClick = { item ->
                // Retry == resume the failed transfer from its last checkpoint.
                engine.transfers?.let { repo ->
                    scope.launch { repo.resumeTransfer(FlashTransferId(item.id)) }
                }
            },
            onAcceptOffer = { item ->
                // #5: user accepted an inbound offer — host resolves the sink and
                // RESUMEs the parked sender (see DiscoveryEngineHolder.acceptOffer).
                engine.transfers?.let { repo ->
                    scope.launch { repo.acceptIncoming(FlashTransferId(item.id)) }
                }
            },
            onDeclineOffer = { item ->
                engine.transfers?.let { repo ->
                    scope.launch { repo.declineIncoming(FlashTransferId(item.id)) }
                }
            },
            onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
            onHistoryOpen = { item ->
                if (twoPane) {
                    selectedTransferId = item.id
                } else {
                    openAttachment(toastContext, item.localPath, guessMimeType(item.fileName))
                }
            },
            onHistoryShare = { item -> shareTransferredFile(toastContext, item) },
            // Plan 1.2: the screen only draws "Clear history" when a host passes this.
            onClearHistory = {
                selectedTransferId = null
                engine.transfers?.clearFinishedHistory()
                FlashNotificationManager.clearAllTransfers(toastContext)
            },
            // C-10: a Failed or Cancelled row can be dismissed one by one or all at once.
            onDismissItem = { item ->
                if (selectedTransferId == item.id) selectedTransferId = null
                engine.transfers?.dismissTransfer(FlashTransferId(item.id))
            },
            onClearFailed = {
                val failedIds = transfersUi.failed.filter { it.retryable }.map { it.id }
                if (selectedTransferId in failedIds) selectedTransferId = null
                engine.transfers?.let { repo ->
                    failedIds.forEach { id -> repo.dismissTransfer(FlashTransferId(id)) }
                }
            },
            onClearCancelled = {
                val cancelledIds = transfersUi.failed.filterNot { it.retryable }.map { it.id }
                if (selectedTransferId in cancelledIds) selectedTransferId = null
                engine.transfers?.let { repo ->
                    cancelledIds.forEach { id -> repo.dismissTransfer(FlashTransferId(id)) }
                }
            },
            onItemClick = { item -> selectedTransferId = item.id },
            onRetryLoad = { engine.start() },
            modifier = Modifier.fillMaxSize(),
            listState = transfersScroll,
            bottomInset = tabBottomInset,
        )
    }

    val nearbyScreenContent: @Composable () -> Unit = {
        // PC5: ECO dials unpaired peers only while Nearby is on screen, so they can be paired.
        DisposableEffect(Unit) {
            DiscoveryEngineHolder.setNearbyVisible(true)
            onDispose { DiscoveryEngineHolder.setNearbyVisible(false) }
        }
        FlashNearbyScreen(
            state = nearby,
            onPairClick = { peer ->
                if (twoPane) selectedNearbyPeerId = peer.id
                if (pendingShare.value != null) {
                    sendSharedPayloadToPeer(peer.id, peer.name)
                } else {
                    // Ensure a session exists (dial is idempotent/coalesced), then start the
                    // handshake. onSessionUp exchanges fingerprints so beginPair can derive
                    // the shared 6-digit code; the responder sees the Accept/Decline dialog.
                    val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peer.id }
                    lastPairingPeer = peer.id to peer.name
                    scope.launch {
                        val net = engine.network
                        if (endpoint != null && net != null) {
                            net.connectManual(endpoint.hostAddress, endpoint.port)
                        }
                        engine.pairing?.beginPair(peer.id, peer.name)
                    }
                }
            },
            onChatClick = { peer ->
                if (pendingShare.value != null) {
                    sendSharedPayloadToPeer(peer.id, peer.name)
                }
                openPeerChat(peer)
            },
            onRevokeClick = { trusted ->
                if (selectedNearbyPeerId == trusted.id && nearby.peers.none { it.id == trusted.id }) {
                    selectedNearbyPeerId = null
                }
                engine.pairing?.revoke(trusted.id)
            },
            // ADR-042: re-run pairing (v2) with a peer paired under the forceable v1 code.
            onVerifyTrustedClick = { trusted ->
                if (twoPane) selectedNearbyPeerId = trusted.id
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == trusted.id }
                lastPairingPeer = trusted.id to trusted.name
                scope.launch {
                    val net = engine.network
                    if (endpoint != null && net != null) net.connectManual(endpoint.hostAddress, endpoint.port)
                    engine.pairing?.beginPair(trusted.id, trusted.name)
                }
            },
            onChatTrustedClick = { trusted ->
                if (pendingShare.value != null) {
                    sendSharedPayloadToPeer(trusted.id, trusted.name)
                }
                selectedChatConversationId = trusted.id
                chatRepository.openConversation(trusted.id)
                nav.navigate(FlashDestination.Conversation, conversationId = trusted.id)
            },
            onManualConnectSuspend = { host, port ->
                val net = engine.network
                if (net == null) {
                    "Flash is still starting up. Try again in a moment."
                } else {
                    val result = net.connectManual(host, port)
                    if (result is com.transfer.flash.core.common.result.FlashResult.Success) {
                        val peerDevice = result.value.peer
                        lastPairingPeer = peerDevice.id.value to peerDevice.friendlyName
                        if (twoPane) selectedNearbyPeerId = peerDevice.id.value
                        engine.pairing?.beginPair(peerDevice.id.value, peerDevice.friendlyName)
                        null
                    } else {
                        "Couldn't connect to $host:$port. Check that Flash is open on the same network."
                    }
                }
            },
            onScanNetwork = { DiscoveryEngineHolder.scanNetwork() },
            onPeerClick = { peer -> selectedNearbyPeerId = peer.id },
            onTrustedPeerClick = { trusted -> selectedNearbyPeerId = trusted.id },
            onOpenWifiSettings = {
                runCatching {
                    toastContext.startActivity(
                        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            listState = nearbyScroll,
            bottomInset = tabBottomInset,
        )
    }

    val settingsScreenContent: @Composable () -> Unit = {
        FlashSettingsScreen(
            model = settings,
            onThemeModeSelected = { mode ->
                onSettingsChange(settings.copy(themeMode = mode))
            },
            onDynamicAccentChanged = {
                onSettingsChange(settings.copy(dynamicAccent = it))
            },
            onHapticsChanged = {
                onSettingsChange(settings.copy(hapticsEnabled = it))
            },
            onLaunchAnimationChanged = {
                onSettingsChange(settings.copy(launchAnimation = it))
            },
            onBackgroundTransfersChanged = {
                // Bug 6: opting into background mesh = ask the system (AOSP Doze/
                // App Standby) to leave Flash alone. User-initiated by design.
                if (it) onEnableBackgroundTransfers()
                onSettingsChange(settings.copy(backgroundTransfers = it))
            },
            onAutoDownloadVoiceChanged = {
                onSettingsChange(settings.copy(autoDownloadVoice = it))
            },
            onAutoDownloadImageChanged = {
                onSettingsChange(settings.copy(autoDownloadImage = it))
            },
            onAutoDownloadVideoChanged = {
                onSettingsChange(settings.copy(autoDownloadVideo = it))
            },
            onAutoDownloadFileChanged = {
                onSettingsChange(settings.copy(autoDownloadFile = it))
            },
            onPrioritiseVoiceQualityChanged = {
                onSettingsChange(settings.copy(prioritiseVoiceQuality = it))
            },
            onSmallerVideoForManyChanged = {
                onSettingsChange(settings.copy(smallerVideoForMany = it))
            },
            onPerformanceModeSelected = {
                onSettingsChange(settings.copy(performanceMode = it))
            },
            onDiscoveryModeChanged = { mode ->
                onSettingsChange(settings.copy(discoveryMode = mode))
            },
            onEditDisplayName = { showRenameDialog = true },
            onOpenEncryption = { showEncryptionSheet = true },
            onOpenTrustedPeers = { nav.selectTab(FlashDestination.NearbyDevices) },
            // ADR-356: Android writes received files into the app-owned FlashReceived directory.
            onPickSaveLocation = null,
            // ERROR-031 / D7: same system prompt the Background-transfers toggle fires,
            // reachable on its own so a user who already flipped that toggle (or who
            // revoked the exemption later) can still get to it.
            onOpenBatterySettings = onEnableBackgroundTransfers,
            onRefreshStorageUsage = onRefreshStorageUsage,
            onClearReceivedFiles = onClearReceivedFiles,
            onRestartApp = onRestartApp,
            onExportLogs = onExportLogs,
            onSwarmHelpShareChanged = {
                onSettingsChange(settings.copy(swarmHelpShare = it))
            },
            onSwarmKeepFinishedFilesChanged = {
                onSettingsChange(settings.copy(swarmKeepFinishedFiles = it))
            },
            onSwarmEnabledChanged = {
                onSettingsChange(settings.copy(swarmEnabled = it))
            },
            onEnrollDevice = onEnrollDeviceAction,
            onDisconnectEnterprise = {
                scope.launch {
                    // CLI-09: tells the hub (or remembers to), clears everything in one atomic write, keeps the group links for a later re-enrolment.
                    val result = engine.enterpriseHub?.unenrol()
                    withContext(Dispatchers.Main) {
                        val text = if (result == null) {
                            "Flash is still starting. Try again in a moment."
                        } else {
                            com.transfer.flash.ui.settings.FlashHubStatusMath.unenrolText(result)
                        }
                        Toast.makeText(toastContext, text, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLoadApprovals = {
                (engine.enterpriseHub?.pendingApprovals() as? com.transfer.flash.core.common.result.FlashResult.Success)?.value
            },
            onApprove = { approval ->
                engine.enterpriseHub?.approve(approval) is com.transfer.flash.core.common.result.FlashResult.Success
            },
            onRetryHub = { engine.enterpriseHub?.retryNow() },
            onDismissHubRevoked = { engine.enterpriseHub?.dismissRevokedNotice() },
            onSetHubContentViaHub = { engine.setHubContentViaHub(it) },
            onAnnouncementNotificationsChanged = { engine.setAnnouncementNotifications(it) },
            onRestoreHubGroups = {
                scope.launch {
                    val restored = engine.enterpriseHub?.relinkPreviousGroups() ?: 0
                    withContext(Dispatchers.Main) {
                        Toast.makeText(toastContext, if (restored == 1) "Restored 1 group" else "Restored $restored groups", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onForgetHubGroups = { scope.launch { engine.enterpriseHub?.forgetPreviousGroups() } },
            isEnrollingDevice = isEnrollingDevice,
            enrollDeviceError = enrollDeviceError,
            radioGatewayController = radioGatewayController,
            modifier = Modifier.fillMaxSize(),
            listState = settingsScroll,
            bottomInset = tabBottomInset,
        )
    }

    val hubPendingJoins by chatRepository.pendingEnterpriseJoins.collectAsState()
    // UI-058: the Hub tab (H7/H8/H9). Everything it reads and writes goes through the repository.
    val hubHostContext = LocalContext.current
    val hubPrefs = remember { HubUiPrefs(hubHostContext) }
    // Unsent announcement drafts belong to the organisation: forget them once enrolment is known to have ended (never on the cold-start default).
    LaunchedEffect(ready, settings.isEnterpriseEnrolled) {
        if (ready && !settings.isEnterpriseEnrolled) hubPrefs.clearDrafts()
    }
    val hubCall: (String, String) -> Unit = { id, name ->
        launchOrQueueOutgoingCall(PendingOutgoingCall.Direct(peerId = id, peerName = name, video = false))
    }
    // Bumped when the Hub tab is tapped while it is already showing; the Hub scrolls to the top (or jumps to News).
    var hubReselectTick by remember { mutableStateOf(0) }
    val hubScreenContent: @Composable () -> Unit = {
        val hubHost = remember(chatRepository, settings.hubStatus?.hubHost, hubRoute) {
            com.transfer.flash.ui.hub.FlashHubHost(
                onOpenConversation = { id ->
                    selectedChatConversationId = id
                    chatRepository.openConversation(id)
                    nav.navigate(FlashDestination.Conversation, conversationId = id)
                },
                onOpenGroup = { groupId ->
                    selectedChatConversationId = groupId
                    chatRepository.openConversation(groupId)
                    nav.navigate(FlashDestination.Conversation, conversationId = groupId)
                },
                onOpenLink = { url ->
                    runCatching {
                        hubHostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                onSignOut = {
                    scope.launch {
                        val result = engine.enterpriseHub?.unenrol()
                        withContext(Dispatchers.Main) {
                            val text = if (result == null) "Flash is still starting. Try again in a moment."
                            else com.transfer.flash.ui.settings.FlashHubStatusMath.unenrolText(result)
                            Toast.makeText(toastContext, text, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCall = hubCall.takeIf { engine.calls != null },
                onPairColleague = { id ->
                    scope.launch {
                        val result = chatRepository.pairColleagueFromDirectory(id)
                        withContext(Dispatchers.Main) {
                            val text = when (result) {
                                is com.transfer.flash.core.common.result.FlashResult.Success -> "Paired."
                                is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                    (result.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message ?: "Pairing failed."
                            }
                            Toast.makeText(toastContext, text, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                orgHost = settings.hubStatus?.hubHost?.takeIf { it.isNotBlank() },
                onManageGroupRequests = { showBrowseOrgGroups = true },
                pendingRoute = hubRoute,
                onRouteConsumed = { pendingHubRoute.value = null },
                prefs = hubPrefs,
                drafts = hubPrefs,
            )
        }
        com.transfer.flash.ui.hub.FlashHubRoute(
            repository = chatRepository,
            link = settings.hubStatus?.state ?: com.transfer.flash.core.enterprise.HubLinkState.NOT_ENROLLED,
            host = hubHost,
            modifier = Modifier.fillMaxSize(),
            reselectSignal = hubReselectTick,
            bottomInset = tabBottomInset,
        )
    }
    // UI-024 upgrade: a structured hit opens the conversation AT that message. Search is closed
    // first so the back gesture from the chat returns to the chat list, not to a stale overlay.
    val openSearchHit: (String, String) -> Unit = { conversationId, messageId ->
        val q = searchQuery.trim()
        if (q.isNotEmpty()) scope.launch { chatRepository.recordRecentSearch(q) }
        selectedChatConversationId = conversationId
        chatRepository.openConversation(conversationId)
        chatRepository.clearListSelection()
        isSearching = false
        searchQuery = ""
        nav.navigate(
            FlashDestination.Conversation,
            conversationId = conversationId,
            targetMessageId = messageId,
        )
    }
    val chatListScreenContent: @Composable () -> Unit = {
        FlashChatListScreen(
            state = chatListState,
            activeConversationId = if (twoPane) (nav.current.conversationId ?: selectedChatConversationId) else null,
            onConversationClick = { id ->
                selectedChatConversationId = id
                chatRepository.openConversation(id)
                chatRepository.clearListSelection()
                nav.navigate(FlashDestination.Conversation, conversationId = id)
            },
            onSearchClick = { isSearching = true },
            // #24: the first-run empty-state CTA and the top-bar LAN glyph both jump to the
            // Nearby tab (the P2P next action) instead of being inert.
            onFindDevicesClick = { nav.selectTab(FlashDestination.NearbyDevices) },
            onLanClick = { nav.selectTab(FlashDestination.NearbyDevices) },
            onNewChatClick = { nav.selectTab(FlashDestination.NearbyDevices) },
            isSearching = isSearching,
            searchQuery = searchQuery,
            onSearchQueryChanged = { searchQuery = it },
            onCloseSearch = {
                isSearching = false
                searchQuery = ""
                // A scope chosen for one search ("Files") must not hide the chat rows of the next one.
                searchScope = FlashSearchScope.All
            },
            messageBodyMatches = messageBodyMatches,
            colleagueMatchIds = colleagueMatchIds,
            searchResults = searchResults,
            searchScope = searchScope,
            onSearchScopeChanged = { searchScope = it },
            onSearchMessageClick = openSearchHit,
            onSearchFileClick = openSearchHit,
            onSearchSubmit = {
                val q = searchQuery.trim()
                if (q.isNotEmpty()) scope.launch { chatRepository.recordRecentSearch(q) }
            },
            recentSearches = recentSearches,
            onRecentSearchClick = { recent ->
                isSearching = true
                searchQuery = recent
                scope.launch { chatRepository.recordRecentSearch(recent) }
            },
            onClearRecentSearches = { scope.launch { chatRepository.clearRecentSearches() } },
            onConversationLongClick = chatRepository::enterListSelectionMode,
            onToggleSelection = chatRepository::toggleListSelection,
            onArchiveConversation = chatRepository::archiveConversation,
            onUnarchiveConversation = chatRepository::unarchiveConversation,
            onCloseSelection = chatRepository::clearListSelection,
            onPinSelected = {
                val selectedItems = (chatListState.items + chatListState.archivedItems)
                    .filter { it.id in chatListState.selectedIds }
                val nextPinned = selectedItems.isEmpty() || !selectedItems.all { it.isPinned }
                chatRepository.setConversationsPinned(chatListState.selectedIds, nextPinned)
            },
            onMuteSelected = {
                val selectedItems = (chatListState.items + chatListState.archivedItems)
                    .filter { it.id in chatListState.selectedIds }
                val nextMuted = selectedItems.isEmpty() || !selectedItems.all { it.isMuted }
                chatRepository.setConversationsMuted(chatListState.selectedIds, nextMuted)
            },
            onMarkSelectedRead = {
                chatRepository.markConversationsRead(chatListState.selectedIds)
            },
            onArchiveSelected = {
                chatRepository.archiveConversations(chatListState.selectedIds)
            },
            onUnarchiveSelected = {
                chatRepository.unarchiveConversations(chatListState.selectedIds)
            },
            onDeleteSelected = {
                val deletedIds = chatListState.selectedIds
                chatRepository.deleteConversations(deletedIds)
                if (nav.current.conversationId in deletedIds || selectedChatConversationId in deletedIds) {
                    closeActiveConversation()
                }
            },
            // ERROR-034: the tab's real boot state, replacing the fabricated-content
            // fallback. Skeleton while the transport stack comes up, error state (with a
            // working retry — AppEngine.start() is idempotent and re-armable after a
            // failure) if it never did. `isErrorEnvironmental` stays false: a boot
            // failure is ours, not the network's.
            //
            // Both terms are needed. `ready` covers the pre-boot window, in which the
            // repository is EmptyFlashChatRepository and has no rows to give. `hasLoaded`
            // covers the window *after* it: `ready` flips when the stack finishes
            // booting, which is earlier than the real repository's first Room emission,
            // so gating on `ready` alone showed the first-run "No conversations yet"
            // panel to a device that has conversations, then crossfaded to real rows.
            isLoading = (!ready || !chatListState.hasLoaded) && chatStartError == null,
            errorMessage = chatStartError?.let { error ->
                error.message?.takeIf { it.isNotBlank() }
                    ?: error::class.simpleName
                    ?: "Unknown startup failure"
            },
            onRetryLoad = { engine.start() },
            onNewGroupClick = { showCreateGroup = true },
            onJoinWithLinkClick = {
                activeJoinLink = null
                showJoinGroupDialog = true
            },
            // UI-058: enrolled devices browse department groups from the Hub tab and open Transfers
            // from this bar; unenrolled devices keep the bar they always had.
            onBrowseOrgGroupsClick = null,
            onTransfersClick = if (nav.hubEnrolled) { { nav.openTransfers() } } else null,
            transfersActiveCount = if (nav.hubEnrolled) {
                domainTransfers.count { com.transfer.flash.ui.chat.FlashChatListTopBarMath.countsAsActive(it.state) }
            } else 0,
            pendingApprovalGroupNames = hubPendingJoins.values.toList(),
            modifier = Modifier.fillMaxSize(),
            listState = chatListScroll,
            bottomInset = tabBottomInset,
        )
    }

    val callStateFlow = engine.calls?.activeCall ?: remember { MutableStateFlow(null) }

    val transferDetailPaneContent: @Composable (FlashTransferItemUi, Modifier) -> Unit = { item, paneModifier ->
        FlashTransferDetailPane(
            item = item,
            onClose = { selectedTransferId = null },
            modifier = paneModifier,
            onPauseResume = {
                engine.transfers?.let { repo ->
                    val id = FlashTransferId(item.id)
                    scope.launch {
                        if (item.state == FlashTransferState.Paused) repo.resumeTransfer(id)
                        else repo.pauseTransfer(id)
                    }
                }
            },
            onCancel = {
                engine.transfers?.let { repo ->
                    scope.launch { repo.cancelTransfer(FlashTransferId(item.id)) }
                }
            },
            onRetry = {
                engine.transfers?.let { repo ->
                    scope.launch { repo.resumeTransfer(FlashTransferId(item.id)) }
                }
            },
            onOpen = {
                if (!twoPane) selectedTransferId = null
                openAttachment(toastContext, item.localPath, guessMimeType(item.fileName))
            },
            onReveal = {
                if (!twoPane) selectedTransferId = null
                shareTransferredFile(toastContext, item)
            },
        )
    }

    val nearbyDetailPaneContent: @Composable (NearbyPeerUi, Modifier) -> Unit = { peer, paneModifier ->
        FlashNearbyDetailPane(
            peer = peer,
            onClose = { selectedNearbyPeerId = null },
            modifier = paneModifier,
            onPair = {
                if (!twoPane) selectedNearbyPeerId = null
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peer.id }
                lastPairingPeer = peer.id to peer.name
                scope.launch {
                    val net = engine.network
                    if (endpoint != null && net != null) {
                        net.connectManual(endpoint.hostAddress, endpoint.port)
                    }
                    engine.pairing?.beginPair(peer.id, peer.name)
                }
            },
            onChat = {
                if (!twoPane) selectedNearbyPeerId = null
                openPeerChat(peer)
            },
            onVoiceCall = {
                if (!twoPane) selectedNearbyPeerId = null
                launchOrQueueOutgoingCall(
                    PendingOutgoingCall.Direct(peer.id, peer.name, video = false),
                )
            },
            onVideoCall = {
                if (!twoPane) selectedNearbyPeerId = null
                launchOrQueueOutgoingCall(
                    PendingOutgoingCall.Direct(peer.id, peer.name, video = true),
                )
            },
            onSendFile = {
                if (!twoPane) selectedNearbyPeerId = null
                pendingNearbySendPeerId = peer.id
                pendingNearbySendPeerName = peer.name
                nearbyFilePickerLauncher.launch(arrayOf("*/*"))
            },
        )
    }

    NotificationPermissionBannerHost(
        modifier = Modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundApp),
        splashSettled = splashSettled,
        destination = nav.current.destination,
        hasNotificationPermissionFlow = hasNotificationPermission,
        notificationPromptedBeforeFlow = notificationPromptedBefore,
        notificationBannerDismissedFlow = notificationBannerDismissed,
        shouldShowNotificationRationaleFlow = shouldShowNotificationRationale,
        pendingShareFlow = pendingShare,
        callStateFlow = callStateFlow,
        onRequestPermission = onRequestNotificationPermission,
        onOpenSettings = onOpenNotificationSettings,
        onDismiss = onDismissNotificationBanner,
    ) {
        if (useNavRail) {
            Row(Modifier.fillMaxSize()) {
                FlashNavigationRail(
                    tabs = navigationRailTabs,
                    selectedTab = nav.shellTab,
                    onTabSelected = { destination ->
                        if (destination == FlashDestination.ChatList) {
                            if (twoPane && nav.conversationOpenedFromHub && selectedChatConversationId != null) {
                                chatRepository.openConversation(selectedChatConversationId!!)
                                // Replace the stack: a push from the Hub tab would keep the Hub as the list pane.
                                nav.selectChatsTab(selectedChatConversationId)
                            } else if (nav.current.destination == FlashDestination.Conversation) {
                                chatRepository.closeConversation()
                                selectedChatConversationId = null
                                nav.selectTab(FlashDestination.ChatList)
                            } else {
                                if (!twoPane) selectedChatConversationId = null
                                nav.selectTab(FlashDestination.ChatList)
                            }
                        } else {
                            selectedChatConversationId = null
                            nav.selectTab(destination)
                        }
                    },
                    localDisplayName = settings.displayName,
                    onProfileClick = {
                        selectedChatConversationId = null
                        nav.selectTab(FlashDestination.Settings)
                    },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (twoPane) {
                        val availableWidthDp = (windowWidthDp - 68f).coerceAtLeast(0f)
                        when (nav.current.destination) {
                            FlashDestination.ChatList, FlashDestination.Conversation -> {
                                FlashAdaptiveTwoPane(
                                    // H-16: a chat opened from the Hub keeps the Hub beside it instead of swapping in the chat list.
                                    listPane = if (nav.conversationOpenedFromHub) hubScreenContent else chatListScreenContent,
                                    detailPane = {
                                        val activeConvId = nav.current.conversationId ?: selectedChatConversationId
                                        if (activeConvId != null) {
                                            conversationScreenContent()
                                        } else {
                                            FlashPlaceholderDetailPane(
                                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                                destination = FlashDestination.ChatList,
                                            )
                                        }
                                    },
                                    windowWidthDp = availableWidthDp,
                                    allowTwoPane = twoPane,
                                )
                            }
                            FlashDestination.Transfers -> {
                                FlashAdaptiveTwoPane(
                                    listPane = transfersScreenContent,
                                    detailPane = {
                                        val item = selectedTransferItem
                                        if (item != null) {
                                            transferDetailPaneContent(item, Modifier)
                                        } else {
                                            FlashPlaceholderDetailPane(
                                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                                destination = FlashDestination.Transfers,
                                            )
                                        }
                                    },
                                    windowWidthDp = availableWidthDp,
                                    allowTwoPane = twoPane,
                                )
                            }
                            FlashDestination.NearbyDevices -> {
                                FlashAdaptiveTwoPane(
                                    listPane = nearbyScreenContent,
                                    detailPane = {
                                        val peer = selectedNearbyPeer
                                        if (peer != null) {
                                            nearbyDetailPaneContent(peer, Modifier)
                                        } else {
                                            FlashPlaceholderDetailPane(
                                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                                destination = FlashDestination.NearbyDevices,
                                            )
                                        }
                                    },
                                    windowWidthDp = availableWidthDp,
                                    allowTwoPane = twoPane,
                                )
                            }
                            // Expanded Settings uses full width rather than squeezing beside "Select a chat".
                            FlashDestination.Settings -> settingsScreenContent()
                            // UI-058: shell-only Hub (H3) uses the full width; list + detail panes arrive with H7.
                            FlashDestination.Hub -> hubScreenContent()
                        }
                    } else {
                        // Medium (600..839dp) - rail on left, single pane in content area
                        Box(Modifier.fillMaxSize()) {
                            FlashAnimatedScreen(targetState = nav.current, hubEnrolled = nav.hubEnrolled) { entry ->
                                when (entry.destination) {
                                    FlashDestination.Conversation -> conversationScreenContent()
                                    FlashDestination.Transfers -> transfersScreenContent()
                                    FlashDestination.Hub -> hubScreenContent()
                                    FlashDestination.NearbyDevices -> nearbyScreenContent()
                                    FlashDestination.Settings -> settingsScreenContent()
                                    FlashDestination.ChatList -> chatListScreenContent()
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Compact (< 600dp) - standard phone single-pane layout
            Box(Modifier.fillMaxSize()) {
                FlashAnimatedScreen(targetState = nav.current, hubEnrolled = nav.hubEnrolled) { entry ->
                    when (entry.destination) {
                        FlashDestination.Conversation -> conversationScreenContent()
                        FlashDestination.Transfers -> transfersScreenContent()
                        FlashDestination.Hub -> hubScreenContent()
                        FlashDestination.NearbyDevices -> nearbyScreenContent()
                        FlashDestination.Settings -> settingsScreenContent()
                        FlashDestination.ChatList -> chatListScreenContent()
                    }
                }
            }
        }

        if (!twoPane && nav.current.destination == FlashDestination.Transfers && selectedTransferItem != null) {
            FlashSheetHost(
                onDismiss = { selectedTransferId = null },
                containerColor = FlashTheme.colors.backgroundApp,
            ) {
                transferDetailPaneContent(
                    selectedTransferItem,
                    Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                )
            }
        }

        if (!twoPane && nav.current.destination == FlashDestination.NearbyDevices && selectedNearbyPeer != null) {
            FlashSheetHost(
                onDismiss = { selectedNearbyPeerId = null },
                containerColor = FlashTheme.colors.backgroundApp,
            ) {
                nearbyDetailPaneContent(
                    selectedNearbyPeer,
                    Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                )
            }
        }

        // Group Phase 1A: trusted-only creation. On success the new group opens like any
        // other conversation; a failure is surfaced as a toast rather than silently dropped.
        if (showCreateGroup) {
            com.transfer.flash.ui.chat.FlashCreateGroupSheet(
                peers = trustedPeerRoster,
                onDismiss = { showCreateGroup = false },
                isEnterpriseEnrolled = settings.isEnterpriseEnrolled,
                onFindDevicesClick = {
                    showCreateGroup = false
                    nav.selectTab(FlashDestination.NearbyDevices)
                },
                onCreate = { title, memberIds ->
                    scope.launch {
                        val result = chatRepository.createGroup(title, memberIds)
                        val groupId = result.getOrNull()
                        if (groupId != null) {
                            showCreateGroup = false
                            selectedChatConversationId = groupId
                            chatRepository.openConversation(groupId)
                            nav.navigate(FlashDestination.Conversation, conversationId = groupId)
                        } else {
                            // The repository's own reason when it has one (ERROR-095: a member that is offline).
                            val reason = ((result as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error
                                as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                            Toast.makeText(
                                toastContext,
                                reason ?: "Couldn't create the group — check that every member is paired",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onCreateWithHub = { title, memberIds, linkToHub ->
                    scope.launch {
                        val result = chatRepository.createGroup(title, memberIds)
                        val groupId = result.getOrNull()
                        if (groupId != null) {
                            if (linkToHub) {
                                chatRepository.linkGroupToHub(groupId)
                            }
                            showCreateGroup = false
                            selectedChatConversationId = groupId
                            chatRepository.openConversation(groupId)
                            nav.navigate(FlashDestination.Conversation, conversationId = groupId)
                        } else {
                            val reason = ((result as? com.transfer.flash.core.common.result.FlashResult.Failure)?.error
                                as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                            Toast.makeText(
                                toastContext,
                                reason ?: "Couldn't create the group — check that every member is paired",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
        }

        if (showBrowseOrgGroups) {
            val pendingJoins by chatRepository.pendingEnterpriseJoins.collectAsState()
            com.transfer.flash.ui.chat.FlashBrowseOrgGroupsSheet(
                departmentName = settings.enterpriseDepartment,
                onDismiss = { showBrowseOrgGroups = false },
                onLoadGroups = { chatRepository.fetchEnterpriseDirectoryGroups(settings.enterpriseDepartment) },
                onJoinGroup = { group -> chatRepository.joinEnterpriseGroup(group.groupId, group.name) },
                onJoined = { group ->
                    showBrowseOrgGroups = false
                    selectedChatConversationId = group.groupId
                    chatRepository.openConversation(group.groupId)
                    nav.navigate(FlashDestination.Conversation, conversationId = group.groupId)
                },
                joinedGroupIds = chatListState.items.map { it.id }.toSet(),
                pendingGroupIds = pendingJoins.keys,
                onLoadJoinRequests = { groupId -> chatRepository.fetchEnterpriseJoinRequests(groupId) },
                onDecideJoinRequest = { request, approve -> chatRepository.decideEnterpriseJoinRequest(request.requestId, approve) },
            )
        }

        if (showJoinGroupDialog) {
            com.transfer.flash.ui.chat.FlashJoinGroupDialog(
                initialInviteUrl = activeJoinLink,
                resolveInviterName = { invite -> chatRepository.inviterDisplayName(invite.inviterDeviceId) },
                onDismiss = {
                    showJoinGroupDialog = false
                    activeJoinLink = null
                },
                onJoin = { inviteUrl ->
                    scope.launch {
                        val result = chatRepository.acceptInvite(inviteUrl)
                        when (result) {
                            is com.transfer.flash.core.common.result.FlashResult.Success -> {
                                showJoinGroupDialog = false
                                activeJoinLink = null
                                // A group this device has not joined yet has no conversation row: opening it would show
                                // an empty chat titled with the raw group id. The group appears in the list once the
                                // admin approves; until then say where the request stands.
                                val status = chatRepository.inviteStatusSentence(result.value)
                                if (status == "Joined") {
                                    chatRepository.openConversation(result.value)
                                    selectedChatConversationId = result.value
                                    nav.navigate(FlashDestination.Conversation, conversationId = result.value)
                                } else {
                                    val message = status ?: "Join request sent"
                                    Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
                                }
                            }
                            is com.transfer.flash.core.common.result.FlashResult.Failure -> {
                                val err = (result.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                                    ?: "Couldn't join group"
                                Toast.makeText(toastContext, err, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
        }

        if (!twoPane) {
            val activeListScroll = when (nav.current.destination) {
                FlashDestination.ChatList -> chatListScroll
                FlashDestination.Transfers -> transfersScroll
                FlashDestination.NearbyDevices -> nearbyScroll
                FlashDestination.Settings -> settingsScroll
                else -> null
            }
            val autoNavVisible by rememberAutoNavVisible(activeListScroll)

            Box(Modifier.align(Alignment.BottomCenter)) {
                AnimatedVisibility(
                    visible = showBar,
                    enter = FlashTheme.motion.shellBarEnter(),
                    exit = FlashTheme.motion.shellBarExit(),
                ) {
                    FlashBottomNav(
                        items = liveBottomNavTabs,
                        selectedTab = nav.shellTab,
                        onTabSelected = { destination ->
                            selectedChatConversationId = null
                            nav.selectTab(destination)
                        },
                        visible = autoNavVisible,
                        // Telegram behaviour: tapping the tab you are already on returns it to the top.
                        onTabReselected = { destination ->
                            // A pushed Transfers (enrolled device) keeps the bar; the highlighted tab under it is a way back, not a scroll.
                            if (!com.transfer.flash.ui.navigation.FlashNavigationMath.isTabRoot(nav.current.destination, nav.hubEnrolled)) {
                                selectedChatConversationId = null
                                nav.selectTab(destination)
                                return@FlashBottomNav
                            }
                            if (destination == FlashDestination.Hub) {
                                hubReselectTick++
                                return@FlashBottomNav
                            }
                            val listState = when (destination) {
                                FlashDestination.ChatList -> chatListScroll
                                FlashDestination.Transfers -> transfersScroll
                                FlashDestination.NearbyDevices -> nearbyScroll
                                FlashDestination.Settings -> settingsScroll
                                else -> null
                            }
                            listState?.let { state ->
                                scope.launch {
                                    if (reduceMotion) state.scrollToItem(0) else state.animateScrollToItem(0)
                                }
                            }
                        },
                    )
                }
            }
        }

        if (showRenameDialog) {
            FlashDisplayNameDialog(
                initial = settings.displayName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    onSettingsChange(settings.copy(displayName = newName))
                    showRenameDialog = false
                },
            )
        }

        if (showEncryptionSheet) {
            FlashEncryptionSheet(
                state = if (trustedPeers.any { it.verified }) {
                    FlashEncryptionBadgeState.EncryptedTrusted
                } else {
                    FlashEncryptionBadgeState.EncryptedUnverified
                },
                onDismiss = { showEncryptionSheet = false },
                onVerifySecurityCodes = {
                    showEncryptionSheet = false
                    nav.selectTab(FlashDestination.NearbyDevices)
                },
            )
        }

        if (activeShare != null && !isPairingForShare) {
            FlashShareTargetSheet(
                payload = activeShare!!,
                pairedDevices = pairedRecipients,
                nearbyDevices = nearbyRecipients,
                recentChats = recentChatRecipients,
                isScanning = nearby.isScanning,
                onSelectRecipient = { recipient ->
                    if (recipient.isGroup) {
                        // A group is not a pairable peer: deliver into it, never start pairing.
                        forwardPayloadToChats(listOf(recipient), activeShare!!)
                    } else {
                        sendSharedPayloadToPeer(recipient.id, recipient.name)
                    }
                },
                onDismiss = {
                    pendingShare.value = null
                },
                onManualConnect = {
                    showShareManualConnect = true
                },
            )
        }

        var shareManualConnecting by remember { mutableStateOf(false) }
        var shareManualError by remember { mutableStateOf<String?>(null) }
        if (showShareManualConnect) {
            FlashManualConnectDialog(
                onDismiss = {
                    if (!shareManualConnecting) {
                        shareManualError = null
                        showShareManualConnect = false
                    }
                },
                onConnect = { host, port ->
                    shareManualConnecting = true
                    shareManualError = null
                    scope.launch {
                        val net = engine.network
                        if (net == null) {
                            shareManualConnecting = false
                            shareManualError = "Flash is still starting up. Try again in a moment."
                            return@launch
                        }
                        val result = net.connectManual(host, port)
                        shareManualConnecting = false
                        if (result is com.transfer.flash.core.common.result.FlashResult.Success) {
                            showShareManualConnect = false
                            val peerDevice = result.value.peer
                            val peerId = peerDevice.id.value
                            val peerName = peerDevice.friendlyName
                            val isTrusted = trustedPeers.any { it.id == peerId }
                            if (isTrusted) {
                                sendSharedPayloadToPeer(peerId, peerName)
                            } else {
                                isPairingForShare = true
                                pendingShareRecipient = Pair(peerId, peerName)
                                lastPairingPeer = peerId to peerName
                                engine.pairing?.beginPair(peerId, peerName)
                                Toast.makeText(toastContext, "Connected to $host:$port. Initiating pairing with $peerName...", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            shareManualError = "Couldn't connect to $host:$port. Check that Flash is open on the same network."
                        }
                    }
                },
                isConnecting = shareManualConnecting,
                errorMessage = shareManualError,
                onClearError = { shareManualError = null },
            )
        }

        // ERROR-1010: one shell-level card serves every tab, including Nearby. It reads the live
        // coordinator directly so a tab transition cannot hide or duplicate an active request.
        pairingModel?.let { model ->
            val request = model.request
            val peerIdHint = model.peerDeviceId ?: request.peerDeviceId
            val resolvedTransport = (
                peerIdHint?.let { pid -> discoveredEndpoints.firstOrNull { it.deviceId.value == pid } }
                    ?: discoveredEndpoints.firstOrNull { it.friendlyName == request.peerName }
                )?.transportType
                ?.toUiTransport()
                ?: FlashNetworkTransport.Unknown
            val retryTarget = peerIdHint?.let { pid ->
                val resolvedName = discoveredEndpoints.firstOrNull { it.deviceId.value == pid }?.friendlyName
                    ?: trustedPeers.firstOrNull { it.id == pid }?.name
                    ?: lastPairingPeer?.takeIf { it.first == pid }?.second
                    ?: request.peerName
                pid to resolvedName
            }
                ?: lastPairingPeer?.takeIf { it.second == request.peerName }
                ?: discoveredEndpoints.firstOrNull { it.friendlyName == request.peerName }
                    ?.let { it.deviceId.value to it.friendlyName }
                ?: trustedPeers.firstOrNull { it.name == request.peerName }
                    ?.let { it.id to it.name }
            val dismissPairing: () -> Unit = {
                if (isPairingForShare) {
                    isPairingForShare = false
                    pendingShareRecipient = null
                    pendingShare.value = null
                }
                engine.pairing?.declineLocal()
            }
            FlashPairingDialog(
                request = request.copy(
                    transport = resolvedTransport,
                    failureReason = lastPairingFailureMessage,
                ),
                phase = model.phase,
                secondsLeft = model.secondsLeft,
                onAccept = { engine.pairing?.acceptLocal() },
                onDecline = dismissPairing,
                onDismiss = dismissPairing,
                onRetry = retryTarget?.let { (peerId, peerName) ->
                    {
                        scope.launch {
                            engine.pairing?.declineLocal()
                            withTimeoutOrNull(500L) {
                                (engine.pairing?.pairing ?: fallbackPairing).firstOrNull { it == null }
                            }
                            val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
                            if (endpoint != null) {
                                engine.network?.connectManual(endpoint.hostAddress, endpoint.port)
                            }
                            lastPairingPeer = peerId to peerName
                            engine.pairing?.beginPair(peerId, peerName)
                        }
                    }
                },
            )
        }

        // C7 (calling): full-screen call overlay. Topmost sibling so it renders above
        // everything (nav bar, console, rename dialog). Driven by the engine's FlashCalling.
        // Dismissing ENDED hides the overlay immediately; live-call Back enters PiP when
        // available or prompts before hanging up.
        val callState by callStateFlow.collectAsState()
        val callMedia = engine.calls?.media
        val activeCall = callState
        var dismissedEndedCallId by remember { mutableStateOf<String?>(null) }
        var showEndCallConfirm by remember { mutableStateOf(false) }
        LaunchedEffect(activeCall?.callId, activeCall?.state) {
            if (activeCall == null ||
                activeCall.callId != dismissedEndedCallId ||
                activeCall.state != FlashCallState.ENDED
            ) {
                dismissedEndedCallId = null
            }
            if (activeCall == null ||
                activeCall.state == FlashCallState.ENDED ||
                activeCall.state == FlashCallState.RINGING
            ) {
                showEndCallConfirm = false
            }
        }
        val visibleCall = activeCall?.takeUnless {
            it.state == FlashCallState.ENDED && it.callId == dismissedEndedCallId
        }
        val callContext = LocalContext.current
        val audioRoutes by audioRouter.routes.collectAsState()

        // ADR-067 / UI-050f: picture-in-picture, keep-screen-on for video, screen-off against the ear for voice.
        val callActivity = remember(callContext) { callContext.findActivity() }
        val pipSupported = remember(callContext) { FlashCallPictureInPicture.supported(callContext) }
        var inPictureInPicture by remember { mutableStateOf(false) }
        DisposableEffect(callActivity) {
            val componentActivity = callActivity as? ComponentActivity
            val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
                inPictureInPicture = it.isInPictureInPictureMode
            }
            inPictureInPicture = callActivity?.isInPictureInPictureMode == true
            componentActivity?.addOnPictureInPictureModeChangedListener(listener)
            onDispose { componentActivity?.removeOnPictureInPictureModeChangedListener(listener) }
        }
        val callLive = activeCall != null &&
            (activeCall.state == FlashCallState.CONNECTING || activeCall.state == FlashCallState.ACTIVE)
        val videoLive = callLive && activeCall?.video == true
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view, videoLive) {
            view.keepScreenOn = videoLive
            onDispose { view.keepScreenOn = false }
        }
        LaunchedEffect(callActivity, videoLive, activeCall?.isGroup) {
            val autoEnter = videoLive && activeCall?.isGroup == false
            callActivity?.let { FlashCallPictureInPicture.setAutoEnter(it, autoEnter) }
        }
        val proximity = remember(callContext) { FlashCallProximity(callContext) }
        val holdToEar = FlashCallAudioRouting.screenOffAgainstEar(
            video = activeCall?.video == true,
            live = callLive,
            active = audioRoutes.active,
            speakerOn = activeCall?.speakerOn == true,
        )
        LaunchedEffect(holdToEar) { proximity.hold(holdToEar) }
        DisposableEffect(proximity) { onDispose { proximity.hold(false) } }
        LaunchedEffect(activeCall) {
            when (activeCall?.state) {
                FlashCallState.DIALING, FlashCallState.RINGING -> {
                    FlashCallService.start(callContext)
                }
                null -> FlashCallService.stop(callContext)
                else -> { /* keep FGS running */ }
            }
            // Deliberately not attached while RINGING: FlashCallRinger holds transient ring focus
            // for the ringtone, and exclusive voice-communication focus would silence it. The
            // handover is the focus edge itself — attach()'s EXCLUSIVE request arrives at the ringer
            // as AUDIOFOCUS_LOSS and stops the ring before media starts. attach() is idempotent and
            // re-applies the speaker flag, so every state emission re-syncs the platform route with
            // the UI toggle. (DIALING deliberately DOES attach: the ringback is call audio and has
            // to follow the earpiece/speaker route.)
            val audioState = activeCall
            if (audioState == null ||
                audioState.state == FlashCallState.RINGING ||
                audioState.state == FlashCallState.ENDED
            ) {
                audioRouter.detach()
            } else {
                audioRouter.attach(audioState.speakerOn)
            }
        }
        // ADR-102 / UI-050g: presenting the screen from Android. The engine reports "cannot present" until
        // [installScreenCaptureSupport] has run, so the Share row appears only on a device that really can.
        val callingEngine = engine.calls
        val shareHost = remember(callingEngine) {
            callingEngine?.let { calling ->
                FlashCallShareHost(
                    listSources = { calling.listShareSources() },
                    usesSystemPicker = calling.shareUsesSystemPicker,
                    onStart = { source, quality, takeOver ->
                        scope.launch { calling.startScreenShare(source, quality, takeOver) }
                    },
                    onStop = { scope.launch { calling.stopScreenShare() } },
                    onSetQuality = { calling.setShareQuality(it) },
                    onDismissNotice = { calling.dismissShareNotice() },
                )
            }
        }
        if (visibleCall != null) {
            FlashCallScreen(
                state = visibleCall,
                session = callMedia,
                onAccept = {
                    val hasAudio = ContextCompat.checkSelfPermission(
                        callCtx, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasAudio) {
                        pendingCallAccept = true
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@FlashCallScreen
                    }
                    if (visibleCall.video) {
                        val hasCamera = ContextCompat.checkSelfPermission(
                            callCtx, Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasCamera) {
                            pendingCallAccept = true
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            return@FlashCallScreen
                        }
                    }
                    // Mode before media: accept() starts capture/playout, and a HAL that opened
                    // the mic outside MODE_IN_COMMUNICATION never engages the platform AEC.
                    audioRouter.attach(visibleCall.speakerOn)
                    scope.launch { engine.calls?.accept() }
                },
                onDecline = { scope.launch { engine.calls?.decline() } },
                onHangUp = { scope.launch { engine.calls?.hangUp() } },
                onToggleMute = { engine.calls?.toggleMute() },
                onToggleSpeaker = {
                    val next = !visibleCall.speakerOn
                    engine.calls?.setSpeaker(next)
                    audioRouter.setSpeaker(next)
                },
                onToggleCamera = { engine.calls?.toggleCamera() },
                onSwitchCamera = { scope.launch { engine.calls?.switchCamera() } },
                onDismiss = {
                    when {
                        visibleCall.state == FlashCallState.ENDED -> {
                            dismissedEndedCallId = visibleCall.callId
                        }
                        pipSupported && callLive && visibleCall.video && !visibleCall.isGroup && callActivity != null -> {
                            FlashCallPictureInPicture.enter(callActivity)
                        }
                        else -> {
                            showEndCallConfirm = true
                        }
                    }
                },
                onVideoFocus = { peerId -> engine.calls?.setVideoFocus(peerId) },
                onShowFewerVideos = { on -> engine.calls?.setShowFewerVideos(on) },
                onSendSmallerVideo = { scope.launch { engine.settingsStore.setSmallerVideoForMany(true) } },
                audioRoutes = audioRoutes,
                onSelectRoute = { route ->
                    // The pick pins the platform output; the flag keeps the UI state and the ringback in step.
                    audioRouter.setRoute(route)
                    engine.calls?.setSpeaker(route == FlashCallAudioRoute.SPEAKER)
                },
                peerVerified = !visibleCall.isGroup && trustedPeers.any { it.id == visibleCall.peerId },
                inPictureInPicture = inPictureInPicture,
                onEnterPictureInPicture = if (pipSupported && callActivity != null) {
                    { FlashCallPictureInPicture.enter(callActivity) }
                } else {
                    null
                },
                onSetHandRaised = { raised -> engine.calls?.setHandRaised(raised) },
                onSendReaction = { kind -> engine.calls?.sendReaction(kind) ?: false },
                onSetDataSaver = { on -> engine.calls?.setDataSaver(on) },
                onUpgradeToVideo = {
                    // ADR-078: the camera is opened in the foreground, on this tap, after the grant.
                    val hasCamera = ContextCompat.checkSelfPermission(
                        callCtx, Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasCamera) {
                        scope.launch { engine.calls?.upgradeToVideo() }
                    } else {
                        pendingCameraUpgrade = true
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                share = shareHost,
            )
        }
        if (showEndCallConfirm) {
            val colors = FlashTheme.colors
            FlashConfirmHost(
                onDismiss = { showEndCallConfirm = false },
                containerColor = colors.backgroundSurface,
                title = {
                    FlashText(
                        text = "End call?",
                        style = FlashTheme.typography.headingMedium,
                        color = colors.textPrimary,
                    )
                },
                text = {
                    FlashText(
                        text = "You'll leave the current call.",
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textSecondary,
                    )
                },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .heightIn(min = FlashDimensions.minTouchTarget)
                            .clip(FlashShapes.bubbleGrouped)
                            .clickable {
                                showEndCallConfirm = false
                                scope.launch { engine.calls?.hangUp() }
                            }
                            .semantics { role = Role.Button }
                            .padding(horizontal = FlashSpacing.space12),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(
                            text = "End call",
                            style = FlashTheme.typography.captionEmphasis,
                            color = colors.textError,
                        )
                    }
                },
                dismissButton = {
                    Box(
                        modifier = Modifier
                            .heightIn(min = FlashDimensions.minTouchTarget)
                            .clip(FlashShapes.bubbleGrouped)
                            .clickable { showEndCallConfirm = false }
                            .semantics { role = Role.Button }
                            .padding(horizontal = FlashSpacing.space12),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(
                            text = "Stay",
                            style = FlashTheme.typography.captionEmphasis,
                            color = colors.textSecondary,
                        )
                    }
                },
            )
        }
    }
}

private fun FlashTransportType.toUiTransport(): FlashNetworkTransport = when (this) {
    FlashTransportType.WIFI_DIRECT -> FlashNetworkTransport.WifiDirect
    FlashTransportType.RELAY, FlashTransportType.MESH -> FlashNetworkTransport.Relay
    FlashTransportType.UNKNOWN, FlashTransportType.BLUETOOTH -> FlashNetworkTransport.Unknown
    // NSD-discovered peers report LAN; the WS mesh path maps to the same LAN-class UI badge.
    FlashTransportType.LAN, FlashTransportType.WEBSOCKET -> FlashNetworkTransport.Lan
}

/**
 * Shares a completed transfer's file via a system chooser. Received files live on internal/external
 * storage as absolute paths and must be exposed through our FileProvider (a raw file:// URI would
 * throw FileUriExposedException on modern Android); sent files came in as content:// SAF URIs and
 * can be re-shared directly. No path → nothing to share (transfer still in flight).
 */
private fun shareTransferredFile(
    context: android.content.Context,
    item: com.transfer.flash.ui.transfers.FlashTransferItemUi,
) {
    val path = item.localPath
    if (path.isNullOrBlank()) {
        Toast.makeText(context, "File not available yet", Toast.LENGTH_SHORT).show()
        return
    }
    val uri: android.net.Uri = try {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            android.net.Uri.parse(path)
        } else {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                java.io.File(path),
            )
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Can't share this file", Toast.LENGTH_SHORT).show()
        return
    }

    val mime = guessMimeType(item.fileName)
    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(share, "Share ${item.fileName}")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Resolves a MIME type from a URI and filename, with explicit mappings for media formats
 * (MKV, MP4, WebM, MOV, AVI, TS, FLV, JPEG, PNG, etc.) that MimeTypeMap often misses or reports null for.
 */
private fun guessMimeType(
    fileName: String,
    uriString: String? = null,
    context: android.content.Context? = null,
): String {
    // 1. Try contentResolver.getType if a content URI is provided
    if (context != null && !uriString.isNullOrBlank() && uriString.startsWith("content://")) {
        val resolverType = runCatching {
            context.contentResolver.getType(android.net.Uri.parse(uriString))
        }.getOrNull()
        if (!resolverType.isNullOrBlank() && resolverType != "*/*" && resolverType != "application/octet-stream") {
            return resolverType
        }
    }

    // 2. Resolve by extension
    val ext = (fileName.substringAfterLast('.', "")
        .ifBlank { uriString?.substringAfterLast('.', "") ?: "" })
        .lowercase(java.util.Locale.ROOT)
    return when (ext) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp", "3gpp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}

/**
 * Resolve a chat-image reference (content:// / file:// URI, or an absolute received-file path) to a
 * URI that other apps can read. Absolute paths go through our FileProvider so a raw file:// URI never
 * escapes (FileUriExposedException). Returns null when there is nothing to open yet.
 */
private fun resolveShareableUri(
    context: android.content.Context,
    ref: String?,
): android.net.Uri? {
    if (ref.isNullOrBlank()) return null
    return try {
        if (ref.startsWith("content://") || ref.startsWith("file://")) {
            android.net.Uri.parse(ref)
        } else {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                java.io.File(ref),
            )
        }
    } catch (e: Exception) {
        null
    }
}

/** UI-018: share a viewed image or video out via the system chooser (ACTION_SEND). */
private fun shareImageUri(
    context: android.content.Context,
    ref: String?,
    mimeType: String,
) {
    val isVideo = mimeType.startsWith("video/")
    val uri = resolveShareableUri(context, ref)
    if (uri == null) {
        Toast.makeText(context, if (isVideo) "Can't share this video" else "Can't share this image", Toast.LENGTH_SHORT).show()
        return
    }
    val defaultMime = if (isVideo) "video/*" else "image/*"
    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { defaultMime }
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(share, if (isVideo) "Share video" else "Share image")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Forwards message text out through the system chooser (ACTION_SEND, text/plain).
 *
 * Backs the chat's Forward actions, which used to only raise a "Forwarding message" toast and drop
 * the text on the floor. Flash has no in-app conversation picker, so the system chooser (which can
 * target Flash itself, plus any other messenger) is the honest destination.
 */
private fun shareText(
    context: android.content.Context,
    text: String,
) {
    if (text.isBlank()) {
        Toast.makeText(context, "Nothing to forward", Toast.LENGTH_SHORT).show()
        return
    }
    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(share, "Forward message")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "No app to forward with", Toast.LENGTH_SHORT).show()
    }
}

/**
 * UI-018: copy a viewed photo *or video* into the shared gallery via MediaStore. Reads the source
 * through the content resolver so both content:// URIs and FileProvider-backed paths work, and never
 * needs WRITE_EXTERNAL_STORAGE on API 29+ (scoped storage / RELATIVE_PATH).
 *
 * The collection follows the MIME type. Saving a clip used to insert it into `MediaStore.Images`
 * under Pictures/Flash — the scanner trusts the collection it was filed under rather than the bytes,
 * so the video showed up as a broken photo. Every ContentValues key here lives on the shared
 * [android.provider.MediaStore.MediaColumns], so only the target collection, the default directory
 * and the confirmation copy differ between the two cases.
 */
private fun saveMediaToGallery(
    context: android.content.Context,
    ref: String?,
    mimeType: String,
) {
    val isVideo = mimeType.startsWith("video/")
    val source = resolveShareableUri(context, ref)
    if (source == null) {
        Toast.makeText(
            context,
            if (isVideo) "Video not available yet" else "Image not available yet",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val failureLabel = if (isVideo) "Couldn't save video" else "Couldn't save image"
    val resolver = context.contentResolver
    val mime = mimeType.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" }
    val ext = when (mime) {
        "video/x-matroska" -> "mkv"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (isVideo) "mp4" else "jpg"
    }
    val name = "flash_${System.currentTimeMillis()}.$ext"
    val folder = if (isVideo) {
        android.os.Environment.DIRECTORY_MOVIES
    } else {
        android.os.Environment.DIRECTORY_PICTURES
    }
    val scoped = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
        if (scoped) {
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Flash")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val collection = if (isVideo) {
        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val target = runCatching { resolver.insert(collection, values) }.getOrNull()
    if (target == null) {
        Toast.makeText(context, failureLabel, Toast.LENGTH_SHORT).show()
        return
    }
    val ok = runCatching {
        resolver.openInputStream(source).use { input ->
            requireNotNull(input) { "no input stream" }
            resolver.openOutputStream(target).use { output ->
                requireNotNull(output) { "no output stream" }
                input.copyTo(output)
            }
        }
        true
    }.getOrElse {
        runCatching { resolver.delete(target, null, null) }
        false
    }
    if (ok && scoped) {
        values.clear()
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        runCatching { resolver.update(target, values, null, null) }
    }
    // Pre-Q has no RELATIVE_PATH, so the folder promise only holds on the scoped-storage path.
    val successLabel = if (scoped) "Saved to $folder/Flash" else "Saved to gallery"
    Toast.makeText(
        context,
        if (ok) successLabel else failureLabel,
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * Opens a chat attachment (video / generic file) in the system viewer via ACTION_VIEW (B4). Content
 * URIs open directly; absolute received-file paths are exposed through our FileProvider (a raw
 * file:// URI would throw FileUriExposedException). Images use the in-app viewer, so they never hit
 * this path. No path → nothing to open yet (transfer still in flight).
 */
private fun openAttachment(
    context: android.content.Context,
    path: String?,
    mimeType: String,
) {
    if (path.isNullOrBlank()) {
        Toast.makeText(context, "File not available yet", Toast.LENGTH_SHORT).show()
        return
    }
    val uri: android.net.Uri = try {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            android.net.Uri.parse(path)
        } else {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                java.io.File(path),
            )
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
        return
    }
    val effectiveMime = if (mimeType.isNotBlank() && mimeType != "*/*" && mimeType != "application/octet-stream") {
        mimeType
    } else {
        guessMimeType(path, path, context)
    }
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, effectiveMime.ifBlank { "*/*" })
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(view) }
        .onFailure {
            val fallbackMime = when {
                effectiveMime.startsWith("video/") -> "video/*"
                effectiveMime.startsWith("image/") -> "image/*"
                effectiveMime.startsWith("audio/") -> "audio/*"
                else -> "*/*"
            }
            if (fallbackMime != effectiveMime) {
                val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, fallbackMime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallbackIntent) }
                    .onFailure {
                        Toast.makeText(context, "No app to open this file", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(context, "No app to open this file", Toast.LENGTH_SHORT).show()
            }
        }
}

/** Resolves the human-readable display name and size in bytes for a shared content URI. */
private fun resolveContentUriNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "shared_file"
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val n = cursor.getString(nameIndex)
                    if (!n.isNullOrBlank()) name = n
                }
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Throwable) {}
    if (size <= 0L) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                size = pfd.statSize
            }
        } catch (_: Throwable) {}
    }
    return Pair(name, size)
}

/**
 * The sweep's state as the Nearby page shows it (DR3). Twin of `SweepState.toNearbyScan` in the desktop shell,
 * kept identical on purpose: `ui:chat` cannot see `core:network`, and a display rule that differed per host
 * would be a bug. An automatic sweep is shown while it runs (the action must not start a second one) but
 * its outcome is not, because the user did not ask for it.
 */
private fun SweepState.toNearbyScan(): NearbyNetworkScan = when (this) {
    SweepState.Idle -> NearbyNetworkScan.Idle
    is SweepState.Scanning -> NearbyNetworkScan.Running(if (total == 0) 0 else scanned * 100 / total)
    is SweepState.Finished ->
        if (automatic) NearbyNetworkScan.Idle else NearbyNetworkScan.Done(answered, narrowed)
    is SweepState.Refused -> NearbyNetworkScan.Unavailable(
        when (refusal) {
            SweepRefusal.NO_LAN -> NearbyScanBlock.NO_NETWORK
            // TOO_LARGE is only ever an automatic refusal, which is never shown; a manual sweep narrows instead.
            SweepRefusal.NOT_PRIVATE, SweepRefusal.TOO_LARGE -> NearbyScanBlock.NOT_LOCAL
            SweepRefusal.TOO_SMALL -> NearbyScanBlock.TOO_SMALL
            SweepRefusal.RATE_LIMITED -> NearbyScanBlock.TOO_SOON
        },
    )
}

internal object LaunchEntryPolicy {
    private val DIRECT_ENTRY_ACTIONS = setOf(
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        "com.transfer.flash.action.SHORTCUT_SEND",
        "com.transfer.flash.action.SHORTCUT_NEARBY",
        "com.transfer.flash.action.SHORTCUT_CHATS",
        "android.service.quicksettings.action.QS_TILE_PREFERENCES",
        FlashNotificationManager.ACTION_VIEW_NEARBY,
        FlashNotificationManager.ACTION_VIEW_TRANSFERS,
        FlashNotificationManager.ACTION_OPEN_FILE,
    )

    fun shouldSkipLaunchSplash(
        action: String?,
        dataScheme: String?,
        hasAnswerCallExtra: Boolean,
        hasPttPressExtra: Boolean,
        hasConversationExtra: Boolean,
        hasHubRouteExtra: Boolean,
    ): Boolean {
        if (hasAnswerCallExtra || hasPttPressExtra) return true
        if (hasConversationExtra || hasHubRouteExtra) return true
        if (action in DIRECT_ENTRY_ACTIONS) return true
        if (action == Intent.ACTION_VIEW && dataScheme.equals("flash", ignoreCase = true)) return true
        return false
    }
}

internal data class ShareUriPartition(
    val accepted: List<Uri>,
    val rejected: List<Uri>,
)

internal object ShareUriFeedback {
    fun rejectedMessage(
        hasFileUri: Boolean,
        acceptedCount: Int,
        hasTextFallback: Boolean,
    ): String = when {
        acceptedCount > 0 ->
            "Some items couldn't be shared directly and were skipped."
        hasTextFallback && hasFileUri ->
            "Attached file links couldn't be read directly. Sharing text only."
        hasFileUri ->
            "This app shared a file link Flash can't read. Try sharing from Files or Gallery."
        else ->
            "Flash couldn't read the shared item. Try sharing it from Files or Gallery."
    }
}

internal object TranssionTargetFeedback {
    fun failureMessage(target: TranssionSetupGuide.Target): String = when (target) {
        TranssionSetupGuide.Target.WIDGET_PIN ->
            "Your home screen didn't add the widget automatically. Long-press your home screen to add Flash."
        else ->
            "Couldn't open that setting on this phone. Open Phone settings manually."
    }
}

internal enum class CallPermissionAction {
    EXECUTE_NOW,
    REQUEST_AUDIO,
    REQUEST_CAMERA,
}

internal object CallPermissionActionPlan {
    fun decide(hasAudio: Boolean, hasCamera: Boolean): CallPermissionAction = when {
        !hasAudio -> CallPermissionAction.REQUEST_AUDIO
        !hasCamera -> CallPermissionAction.REQUEST_CAMERA
        else -> CallPermissionAction.EXECUTE_NOW
    }
}

private sealed interface PendingOutgoingCall {
    val video: Boolean

    data class Direct(
        val peerId: String,
        val peerName: String,
        override val video: Boolean,
    ) : PendingOutgoingCall

    data class StartGroup(
        val groupId: String,
        val groupName: String,
        val memberIds: List<String>,
        override val video: Boolean,
    ) : PendingOutgoingCall

    data class JoinGroup(
        val groupId: String,
        val callId: String,
        val memberIds: List<String>,
        override val video: Boolean,
    ) : PendingOutgoingCall
}

internal enum class NotificationPermissionUiState {
    Hidden,
    Rationale,
    PermanentlyDenied,
}

internal object NotificationPermissionPolicy {
    fun resolveUiState(
        sdkInt: Int,
        hasPermission: Boolean,
        splashSettled: Boolean,
        dismissed: Boolean,
        hasPromptedBefore: Boolean,
        shouldShowRationale: Boolean,
        hasActiveCall: Boolean,
        hasActiveShare: Boolean,
        inConversation: Boolean,
    ): NotificationPermissionUiState {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU || hasPermission || !splashSettled || dismissed) {
            return NotificationPermissionUiState.Hidden
        }
        if (hasActiveCall || hasActiveShare || inConversation) {
            return NotificationPermissionUiState.Hidden
        }
        return if (hasPromptedBefore && !shouldShowRationale) {
            NotificationPermissionUiState.PermanentlyDenied
        } else {
            NotificationPermissionUiState.Rationale
        }
    }
}

@Composable
private fun NotificationPermissionBannerHost(
    modifier: Modifier,
    splashSettled: Boolean,
    destination: FlashDestination,
    hasNotificationPermissionFlow: MutableStateFlow<Boolean>,
    notificationPromptedBeforeFlow: MutableStateFlow<Boolean>,
    notificationBannerDismissedFlow: MutableStateFlow<Boolean>,
    shouldShowNotificationRationaleFlow: MutableStateFlow<Boolean>,
    pendingShareFlow: MutableStateFlow<PendingSharePayload?>,
    callStateFlow: kotlinx.coroutines.flow.StateFlow<FlashCallUiState?>,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val hasPermission by hasNotificationPermissionFlow.collectAsState()
    val promptedBefore by notificationPromptedBeforeFlow.collectAsState()
    val dismissed by notificationBannerDismissedFlow.collectAsState()
    val showRationale by shouldShowNotificationRationaleFlow.collectAsState()
    val activeShare by pendingShareFlow.collectAsState()
    val activeCall by callStateFlow.collectAsState()

    val uiState = NotificationPermissionPolicy.resolveUiState(
        sdkInt = Build.VERSION.SDK_INT,
        hasPermission = hasPermission,
        splashSettled = splashSettled,
        dismissed = dismissed,
        hasPromptedBefore = promptedBefore,
        shouldShowRationale = showRationale,
        hasActiveCall = activeCall != null,
        hasActiveShare = activeShare != null,
        inConversation = destination == FlashDestination.Conversation,
    )

    val showBanner = uiState != NotificationPermissionUiState.Hidden
    Column(modifier = modifier) {
        if (showBanner) {
            NotificationPermissionBanner(
                uiState = uiState,
                onPrimaryAction = {
                    if (uiState == NotificationPermissionUiState.PermanentlyDenied) {
                        onOpenSettings()
                    } else {
                        onRequestPermission()
                    }
                },
                onDismiss = onDismiss,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (showBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            content = content,
        )
    }
}

@Composable
private fun NotificationPermissionBanner(
    uiState: NotificationPermissionUiState,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val isSettingsFallback = uiState == NotificationPermissionUiState.PermanentlyDenied
    val title = if (isSettingsFallback) {
        "Notifications are turned off"
    } else {
        "Allow notifications"
    }
    val body = if (isSettingsFallback) {
        "Open system settings so Flash can alert you for incoming calls, messages, and transfers."
    } else {
        "Get alerts when someone messages, calls, or sends you a file."
    }
    val primaryLabel = if (isSettingsFallback) "Open Settings" else "Allow"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.backgroundSurfaceSubtle)
            .statusBarsPadding()
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .clip(FlashShapes.attachment)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            text = title,
            style = typography.bodyEmphasis,
            color = colors.textPrimary,
        )
        FlashText(
            text = body,
            style = typography.captionDefault,
            color = colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.button)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = FlashSpacing.space12)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Not now",
                    style = typography.captionEmphasis,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.width(FlashSpacing.space8))
            Box(
                modifier = Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.button)
                    .background(colors.accentFill)
                    .clickable(onClick = onPrimaryAction)
                    .padding(horizontal = FlashSpacing.space16)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = primaryLabel,
                    style = typography.captionEmphasis,
                    color = colors.onAccentFill,
                )
            }
        }
    }
}
