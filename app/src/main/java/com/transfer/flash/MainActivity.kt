package com.transfer.flash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.transfer.flash.ui.chat.FlashPairingDialog
import com.transfer.flash.ui.nearby.FlashManualConnectDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.transfer.flash.core.common.perf.FlashMotionPolicy
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.result.getOrNull
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.di.AppEngine
import com.transfer.flash.debug.DiscoveryEngineHolder
import com.transfer.flash.debug.FlashBackgroundService
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.navigation.FlashAnimatedScreen
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath
import com.transfer.flash.ui.navigation.rememberFlashNavigationState
import com.transfer.flash.ui.nearby.FlashNearbyMath
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.nearby.NearbyIdentityUi
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.nearby.NearbyTrustedPeerUi
import com.transfer.flash.ui.nearby.NearbyUiState
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.calling.FlashCallAudioRouter
import com.transfer.flash.calling.FlashCallService
import com.transfer.flash.core.ptt.PttSessionEngine
import com.transfer.flash.ptt.PttSessionOverlay
import com.transfer.flash.pairing.PairingUiModel
import com.transfer.flash.ui.calling.FlashCallScreen
import com.transfer.flash.ui.settings.FlashSettingsModel
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.settings.FlashThemeMode
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.ui.shell.FlashBottomNav
import com.transfer.flash.ui.shell.FlashBottomNavDefaults
import com.transfer.flash.ui.shell.FlashBottomNavItem
import com.transfer.flash.ui.splash.FlashSplashScreen
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
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import com.transfer.flash.ui.theme.rememberSystemReduceMotion
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // #13: POST_NOTIFICATIONS is a runtime permission on Android 13+ (TIRAMISU). Without it the
    // foreground-transfer / message notifications are silently suppressed. Registered here (not
    // deep in Compose) so the launcher survives config changes and only fires once per launch.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort; no-op on denial */ }

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

    private val pendingShare = MutableStateFlow<FlashSharePayloadUi?>(null)
    private val pendingShortcutTab = MutableStateFlow<FlashDestination?>(null)

    private val receivedStorageState = MutableStateFlow(FlashReceivedStorageState())
    private var receivedStorageJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate to take over the theme's splash window.
        // Keep the cold-start splash up for exactly as long as the engine takes to boot:
        // a fast phone dismisses it almost immediately, a slow phone holds it — no fixed
        // minimum. Also release on start failure so a boot error never traps the user.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            !appEngine.ready.value && appEngine.startError.value == null
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        // Bug 7: a notification tap can cold-start the activity (no onNewIntent on cold
        // start) — consume the launch intent here too.
        pendingNotificationConversation.value =
            intent?.getStringExtra(FlashNotificationManager.EXTRA_CONVERSATION_ID)
        if (intent?.getBooleanExtra(com.transfer.flash.calling.FlashCallActionReceiver.EXTRA_ANSWER_CALL, false) == true) {
            pendingCallAnswer.value = true
        }
        if (intent?.getBooleanExtra(PttSessionEngine.EXTRA_PTT_PRESS, false) == true) {
            pendingPttPress.value = true
        }
        handleIncomingIntent(intent)
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
                pendingShortcutTab = pendingShortcutTab,
                onEnableBackgroundTransfers = ::requestIgnoreBatteryOptimizations,
                ignoringBatteryOptimizations = ignoringBatteryOptimizations,
                receivedStorageState = receivedStorageState,
                onRefreshStorageUsage = ::refreshReceivedStorageUsage,
                onClearReceivedFiles = ::clearReceivedFiles,
            )
        }
        refreshReceivedStorageUsage()
    }

    override fun onResume() {
        super.onResume()
        // The exemption can only have changed while we were away in the system prompt (or in the
        // OEM battery screen), so re-read it here — this is what makes the Settings row reflect
        // reality instead of whatever was true at launch.
        refreshBatteryOptimizationState()
    }

    override fun onStart() {
        super.onStart()
        // Bug 6: launch while this activity is user-visible. Android 12+ rejects most foreground-
        // service starts from the background, so engine startup must not launch this asynchronously.
        // Do not stop in onStop: the service is what keeps discovery and mesh sessions alive there.
        FlashBackgroundService.start(this)
        // Bug 7: notifications must be suppressed only while we're genuinely on screen — not
        // merely while the process lives (the service keeps the process alive across onStop).
        FlashNotificationManager.appForeground = true
    }

    override fun onStop() {
        super.onStop()
        FlashNotificationManager.appForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Bug 7: notification tap while the activity was alive (CLEAR_TOP|SINGLE_TOP resume).
        pendingNotificationConversation.value =
            intent.getStringExtra(FlashNotificationManager.EXTRA_CONVERSATION_ID)
        if (intent.getBooleanExtra(com.transfer.flash.calling.FlashCallActionReceiver.EXTRA_ANSWER_CALL, false)) {
            pendingCallAnswer.value = true
        }
        if (intent.getBooleanExtra(PttSessionEngine.EXTRA_PTT_PRESS, false)) {
            pendingPttPress.value = true
        }
        handleIncomingIntent(intent)
    }

    private fun extractUrisFromIntent(intent: Intent): List<Uri> {
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
        // 4. Intent data fallback
        intent.data?.let { result.add(it) }
        return result.toList()
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
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val uris = extractUrisFromIntent(intent)
                if (uris.isNotEmpty() || !text.isNullOrBlank()) {
                    resolvePendingShare(uris, text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = extractUrisFromIntent(intent)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uris.isNotEmpty() || !text.isNullOrBlank()) {
                    resolvePendingShare(uris, text)
                }
            }
            "com.transfer.flash.action.SHORTCUT_SEND",
            "com.transfer.flash.action.SHORTCUT_NEARBY",
            "android.service.quicksettings.action.QS_TILE_PREFERENCES" -> {
                pendingShortcutTab.value = FlashDestination.NearbyDevices
            }
            "com.transfer.flash.action.SHORTCUT_CHATS" -> {
                pendingShortcutTab.value = FlashDestination.ChatList
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
     * for messengers/transfer apps. Note this covers AOSP only; Transsion/Infinix power
     * managers ("Phone Master"/"Phoenix") apply their own auto-kill that may need a manual
     * exemption (Settings → Battery → Flash → Allow background activity). See
     * docs/android-platform-notes.md.
     */
    private fun requestIgnoreBatteryOptimizations() {
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
            }
        }
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

    /** Requests POST_NOTIFICATIONS on Android 13+ when not yet granted. Below API 33 the
     *  permission is install-time, so nothing to do. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private val bottomNavTabs = listOf(
    FlashBottomNavItem(FlashDestination.ChatList, FlashIcons.Chat, "Chats"),
    FlashBottomNavItem(FlashDestination.Transfers, FlashIcons.Transfer, "Transfers"),
    FlashBottomNavItem(FlashDestination.NearbyDevices, FlashIcons.Nearby, "Nearby"),
    FlashBottomNavItem(FlashDestination.Settings, FlashIcons.Settings, "Settings"),
)

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
    /** Pending static app shortcut navigation target. */
    pendingShortcutTab: MutableStateFlow<FlashDestination?> = MutableStateFlow(null),
    /** Bug 6: fired when the user turns ON the Settings "Background transfers" toggle (host-owned). */
    onEnableBackgroundTransfers: () -> Unit = {},
    /** ERROR-031 / D7: activity-published battery-optimisation exemption, refreshed on resume. */
    ignoringBatteryOptimizations: MutableStateFlow<Boolean> = MutableStateFlow(false),
    receivedStorageState: MutableStateFlow<FlashReceivedStorageState>,
    onRefreshStorageUsage: () -> Unit = {},
    onClearReceivedFiles: () -> Unit = {},
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
    // ERROR-033: the tier pin (null = Auto) and the tier actually in force. The pin drives the
    // picker; the resolved value drives the theme, so a device on Auto still gets the tier's
    // reduce-motion floor without the user having chosen anything.
    val pinnedPerformanceMode by store.performanceMode.collectAsState(initial = null)
    val effectivePerformanceMode by engine.performanceMode.collectAsState()
    val motionOverrideForcesReduce by store.reduceMotionOverrideForcesReduce.collectAsState(initial = null)
    val batteryExempt by ignoringBatteryOptimizations.collectAsState()
    val receivedStorage by receivedStorageState.collectAsState()

    val ready by engine.ready.collectAsState()
    val trustedFallback = remember { MutableStateFlow(emptyList<NearbyTrustedPeerUi>()) }
    val trustedPeers by (engine.pairing?.trustedPeers ?: trustedFallback).collectAsState()
    val currentDiscoveryMode by DiscoveryEngineHolder.discoveryMode.collectAsState()

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
        performanceMode = pinnedPerformanceMode,
        detectedPerformanceMode = engine.detectedPerformance.mode,
        trustedPeerCount = trustedPeers.size,
        appVersion = engine.appVersionName,
        // ifBlank is a real guard, not the normal path: a UUID always survives take(8), but a
        // prefs entry that somehow holds "" would not be caught by the store's null check.
        deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
        receivedFilesBytes = receivedStorage.totalBytes,
        storageUsageLoading = receivedStorage.isLoading,
        storageUsageError = receivedStorage.hasError,
        clearingReceivedFiles = receivedStorage.isClearing,
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
            motion = rememberFlashMotion(reduceMotionResolved),
        ) {
            // Launch animation: the looping splash stays up until the engine is ready (or
            // boot fails), then fades out. No minimum display time — a fast boot dismisses
            // it almost immediately; a slow one keeps it looping. The 6s ceiling only
            // guards against a stalled boot trapping the user, never adds latency.
            val startError by engine.startError.collectAsState()
            var dismissSplash by remember { mutableStateOf(false) }
            LaunchedEffect(ready, startError) {
                if (ready || startError != null) dismissSplash = true
            }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(6_000)
                dismissSplash = true
            }
            Box(modifier = Modifier.fillMaxSize()) {
                FlashShell(
                    engine = engine,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    pendingNotificationConversation = pendingNotificationConversation,
                    pendingCallAnswer = pendingCallAnswer,
                    pendingShare = pendingShare,
                    pendingShortcutTab = pendingShortcutTab,
                    onEnableBackgroundTransfers = onEnableBackgroundTransfers,
                    onRefreshStorageUsage = onRefreshStorageUsage,
                    onClearReceivedFiles = onClearReceivedFiles,
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
                AnimatedVisibility(
                    visible = !dismissSplash,
                    enter = EnterTransition.None,
                    exit = fadeOut(animationSpec = tween(250)),
                ) {
                    FlashSplashScreen()
                }
            }
        }
    }
}

@Composable
private fun FlashShell(
    engine: AppEngine,
    settings: FlashSettingsModel,
    onSettingsChange: (FlashSettingsModel) -> Unit,
    pendingNotificationConversation: MutableStateFlow<String?>,
    pendingCallAnswer: MutableStateFlow<Boolean>,
    pendingShare: MutableStateFlow<PendingSharePayload?>,
    pendingShortcutTab: MutableStateFlow<FlashDestination?>,
    onEnableBackgroundTransfers: () -> Unit,
    onRefreshStorageUsage: () -> Unit,
    onClearReceivedFiles: () -> Unit,
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

    // Bug 7: consume a notification-tap navigation request once the engine is ready (the
    // real repository must exist to open the thread; navigating before it does would show an
    // empty conversation). Cleared after consuming so re-taps re-trigger.
    val pendingConversation by pendingNotificationConversation.collectAsState()
    LaunchedEffect(ready, pendingConversation) {
        if (!ready || pendingConversation == null) return@LaunchedEffect
        val conversationId = pendingConversation
        if (conversationId != null) {
            chatRepository.openConversation(conversationId)
            nav.navigate(FlashDestination.Conversation, conversationId = conversationId)
            pendingNotificationConversation.value = null
        }
    }

    val shortcutTab by pendingShortcutTab.collectAsState()
    LaunchedEffect(shortcutTab) {
        val tab = shortcutTab ?: return@LaunchedEffect
        nav.selectTab(tab)
        pendingShortcutTab.value = null
    }

    val activeShare by pendingShare.collectAsState()
    LaunchedEffect(activeShare) {
        if (activeShare != null) {
            if (nav.current.destination == FlashDestination.Transfers || nav.current.destination == FlashDestination.Settings) {
                nav.selectTab(FlashDestination.NearbyDevices)
            }
        }
    }


    // #14: display-name edit sheet. The identity row's tap now opens a rename dialog whose result is
    // persisted through onSettingsChange (DataStore) instead of being a no-op.
    var showRenameDialog by remember { mutableStateOf(false) }

    // UI-024: chat-list global search state. Hoisted at the shell so it survives the tab's
    // AnimatedContent disposal (a state remembered inside the page would reset on every switch).
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // UI-024 / #12: full-history search. Client-side title/preview filtering only matches the row's
    // visible text; to also surface a thread whose match is buried deep in its history we ask the
    // repository (Room LIKE over all message bodies) for the matching conversation ids. Debounced so
    // a fast typer issues one query per pause, cleared when search closes or the box empties.
    var messageBodyMatches by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(searchQuery, isSearching) {
        val q = searchQuery.trim()
        if (!isSearching || q.isEmpty()) {
            messageBodyMatches = emptySet()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(200)
        messageBodyMatches = chatRepository.searchMessageBodies(q)
    }

    // UI-046 2.1: scroll position per tab lives HERE. AnimatedContent disposes the outgoing
    // page, so a state remembered inside a page would die on every tab switch and silently
    // jump back to the top.
    val chatListScroll = rememberLazyListState()
    val transfersScroll = rememberLazyListState()
    val nearbyScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = FlashTheme.motion.reduceMotion
    // C7: CAMERA runtime permission launcher for video calls. webrtc-kmp's getUserMedia
    // throws CameraPermissionException if CAMERA is not granted, so we check before startCall.
    val callCtx = LocalContext.current
    // C7: an accept blocked on a runtime permission resumes itself from the grant callback.
    // Without this the user grants the mic and the ringing call just sits there until they
    // think to tap Accept a second time.
    var pendingCallAccept by remember { mutableStateOf(false) }
    // C7: platform audio mode/focus/routing for the duration of a call. Declared here — ahead of
    // the permission launchers — because a permission-resumed accept has to set the mode before
    // media starts, same as the direct accept path. Survives recomposition so the same instance
    // restores the mode it saved; onDispose is the safety net for the activity going away mid-call.
    val audioRouter = remember(callCtx) { FlashCallAudioRouter(callCtx) }
    DisposableEffect(audioRouter) {
        onDispose { audioRouter.detach() }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            pendingCallAccept = false
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
            Toast.makeText(callCtx, "Microphone permission is required for calls", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (!pendingCallAccept) return@rememberLauncherForActivityResult
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
    // Surface the coordinator's transient status lines (e.g. "Connecting…", "Couldn't reach …") as
    // toasts so tapping Pair always gives feedback instead of silently doing nothing. Keyed on the
    // coordinator instance so collection (re)starts once the engine boots.
    val toastContext = LocalContext.current
    LaunchedEffect(engine.pairing) {
        engine.pairing?.messages?.collect { message ->
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }

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
                    engine.pairing?.beginPair(targetDeviceId, targetDeviceName)
                }
                Toast.makeText(toastContext, "Initiating pairing with $targetDeviceName...", Toast.LENGTH_SHORT).show()
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
        } else if (pairingModel?.phase == FlashPairingPhase.Declined || pairingModel?.phase == FlashPairingPhase.Expired) {
            pendingShareRecipient = null
            isPairingForShare = false
            pendingShare.value = null
            Toast.makeText(toastContext, "Pairing was cancelled or timed out. Files not sent.", Toast.LENGTH_SHORT).show()
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
    val nearby by remember(engine, engine.discovery, engine.pairing) {
        derivedStateOf {
            val trustedIds = trustedPeers.mapTo(HashSet()) { it.id }
            // Every discovered peer as a row, BEFORE the trusted filter: the badge join below looks
            // a trusted peer up here, and trusted peers are deliberately excluded from `peers`.
            val discoveredRows = discoveredEndpoints.map { ep ->
                NearbyPeerUi(
                    id = ep.deviceId.value,
                    name = ep.friendlyName,
                    transport = ep.transportType.toUiTransport(),
                    isTrusted = false,
                    deviceKind = ep.deviceKind,
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
                ),
                isScanning = discoveryState.isDiscovering,
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
                pairingRequest = pairingModel?.request,
                pairingPhase = pairingModel?.phase ?: FlashPairingPhase.Idle,
                pairingSecondsLeft = pairingModel?.secondsLeft ?: 0,
            )
        }
    }

    val activeSessions by remember(engine.network) {
        engine.network?.activeSessions ?: MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    }.collectAsState()
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

    BackHandler(enabled = nav.canGoBack) { nav.back() }
    // UI-024: while chat-list search is open, Back closes search first (registered last so it
    // takes priority over the stack pop when both are eligible).
    BackHandler(enabled = isSearching) {
        isSearching = false
        searchQuery = ""
    }
    // UI-013: while chat-list selection mode is active, Back exits selection first (registered
    // last so it wins over the search-close and stack-pop handlers when several are eligible).
    // Reads the derived Boolean, not `chatListState` — see its declaration for why (EXP-012).
    BackHandler(enabled = chatListSelectionMode) {
        chatRepository.clearListSelection()
    }

    // UI-034 / AD-6: Adaptive layout for large displays (tablets, foldables unfolded, landscape, DeX).
    // UI-034 / AD-6: Adaptive layout for large displays (tablets, foldables unfolded, landscape, DeX).
    // When width >= 600dp (Medium & Expanded), transforms into a slim navigation rail on the left
    // (no stretched bottom navigation bar). When width >= 840dp, transforms into dual-pane layout
    // (matching modern WhatsApp & Telegram desktop implementations).
    val windowWidthDp = rememberFlashAdaptiveWindowWidthDp()
    val sizeClass = FlashAdaptiveMath.windowSizeForWidth(windowWidthDp)
    val useNavRail = sizeClass != FlashWindowSizeClass.Compact
    val twoPane = FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)

    var selectedTransferItem by remember { mutableStateOf<FlashTransferItemUi?>(null) }
    var selectedNearbyPeer by remember { mutableStateOf<NearbyPeerUi?>(null) }

    val totalUnreadCount = remember(chatListState.items) {
        chatListState.items.sumOf { it.unreadCount }
    }

    val liveBottomNavTabs = remember(totalUnreadCount) {
        listOf(
            FlashBottomNavItem(FlashDestination.ChatList, FlashIcons.Chat, "Chats", badgeCount = if (totalUnreadCount > 0) totalUnreadCount else null),
            FlashBottomNavItem(FlashDestination.Transfers, FlashIcons.Transfer, "Transfers"),
            FlashBottomNavItem(FlashDestination.NearbyDevices, FlashIcons.Nearby, "Nearby"),
            FlashBottomNavItem(FlashDestination.Settings, FlashIcons.Settings, "Settings"),
        )
    }

    val navigationRailTabs = remember(totalUnreadCount) {
        listOf(
            FlashNavigationRailTab(
                destination = FlashDestination.ChatList,
                icon = FlashIcons.Chat,
                label = "Chats",
                badgeCount = totalUnreadCount,
            ),
            FlashNavigationRailTab(
                destination = FlashDestination.Transfers,
                icon = FlashIcons.Transfer,
                label = "Transfers",
            ),
            FlashNavigationRailTab(
                destination = FlashDestination.NearbyDevices,
                icon = FlashIcons.Nearby,
                label = "Nearby",
            ),
            FlashNavigationRailTab(
                destination = FlashDestination.Settings,
                icon = FlashIcons.Settings,
                label = "Settings",
            ),
        )
    }

    // In two-pane mode, back clears active detail selection without popping the tab or exiting the app
    BackHandler(enabled = twoPane && (nav.current.destination == FlashDestination.Conversation || selectedTransferItem != null || selectedNearbyPeer != null)) {
        if (selectedTransferItem != null) {
            selectedTransferItem = null
        } else if (selectedNearbyPeer != null) {
            selectedNearbyPeer = null
        } else if (nav.current.destination == FlashDestination.Conversation) {
            chatRepository.closeConversation()
            selectedChatConversationId = null
            nav.navigate(FlashDestination.ChatList)
        }
    }

    // UI-046 v2: the shell bar HANGS over the page instead of docking under it. Tab roots
    // hand `contentInset + system nav inset` to the page, which folds it into its own
    // contentPadding so rows scroll UNDER the capsule; pushed screens (Conversation) drop the
    // bar entirely and manage their own bottom insets. In rail mode, there is no bottom nav.
    val showBar = !useNavRail && FlashNavigationMath.isTabRoot(nav.current.destination)
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBottomInset = if (useNavRail) 0.dp else (FlashBottomNavDefaults.contentInset + systemBottomInset)

    val conversationScreenContent: @Composable () -> Unit = {
        val conversationId = nav.current.conversationId
        FlashConversationScreen(
            state = conversationState,
            onBack = {
                chatRepository.closeConversation()
                if (twoPane) {
                    nav.navigate(FlashDestination.ChatList)
                } else {
                    nav.back()
                }
            },
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
            onPersistDraft = chatRepository::saveDraft,
            onToggleReaction = { messageId, emoji ->
                chatRepository.toggleReaction(messageId, emoji)
            },
            onTypingChanged = chatRepository::setTyping,
            onAttachmentClick = chatRepository::openAttachmentPicker,
            onSendFile = { uri, displayName, size ->
                val peerId = conversationId
                val transfers = engine.transfers
                if (peerId != null && transfers != null) {
                    val mime = guessMimeType(displayName, uri, toastContext)
                    if (conversationState.header.isGroup) {
                        // F4: one shared chat identity/file identity, plus a distinct
                        // recipient transfer identity carried by both intro and FILE_START.
                        val sharedMessageId = java.util.UUID.randomUUID().toString()
                        val sharedWireFileId = java.util.UUID.randomUUID().toString()
                        scope.launch(Dispatchers.IO) {
                            chatRepository.groupMembers(peerId)
                                .filter { it.id != engine.localDeviceId }
                                .forEach { member ->
                                    val recipientTransferId = java.util.UUID.randomUUID().toString()
                                    val announced = chatRepository.beginGroupAttachment(
                                        groupId = peerId,
                                        recipientDeviceId = member.id,
                                        messageId = sharedMessageId,
                                        transferId = recipientTransferId,
                                        wireFileId = sharedWireFileId,
                                        fileName = displayName,
                                        mimeType = mime,
                                        sizeBytes = size,
                                    )
                                    if (!announced) return@forEach
                                    val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == member.id }
                                    val targetDevice = FlashDevice(
                                        id = FlashDeviceId(member.id),
                                        friendlyName = member.name,
                                        transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                                    )
                                    transfers.sendFile(
                                        targetDevice, uri, displayName, size,
                                        transferId = recipientTransferId,
                                        wireFileId = sharedWireFileId,
                                    )
                                }
                            // One sender bubble for the group, keyed by its shared message id.
                            chatRepository.sendGroupAttachment(
                                conversationId = peerId,
                                messageId = sharedMessageId,
                                transferId = sharedMessageId,
                                fileName = displayName,
                                mimeType = mime,
                                sizeBytes = size,
                                localPath = uri,
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
                // B9: a captured voice note rides the same P2P transfer pipeline as any
                // file, then lands as an inline playback card via sendAttachment (audio/*).
                val peerId = conversationId
                val transfers = engine.transfers
                if (peerId != null && transfers != null) {
                    val fileName = "Voice message.m4a"
                    val size = runCatching {
                        android.net.Uri.parse(localPath).path?.let { java.io.File(it).length() } ?: 0L
                    }.getOrDefault(0L)
                    if (conversationState.header.isGroup) {
                        // F4: identical fan-out to onSendFile's group path, with voice meta.
                        val sharedMessageId = java.util.UUID.randomUUID().toString()
                        val sharedWireFileId = java.util.UUID.randomUUID().toString()
                        scope.launch(Dispatchers.IO) {
                            chatRepository.groupMembers(peerId)
                                .filter { it.id != engine.localDeviceId }
                                .forEach { member ->
                                    val recipientTransferId = java.util.UUID.randomUUID().toString()
                                    val announced = chatRepository.beginGroupAttachment(
                                        groupId = peerId,
                                        recipientDeviceId = member.id,
                                        messageId = sharedMessageId,
                                        transferId = recipientTransferId,
                                        wireFileId = sharedWireFileId,
                                        fileName = fileName,
                                        mimeType = "audio/mp4",
                                        sizeBytes = size,
                                    )
                                    if (!announced) return@forEach
                                    val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == member.id }
                                    val targetDevice = FlashDevice(
                                        id = FlashDeviceId(member.id),
                                        friendlyName = member.name,
                                        transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                                    )
                                    transfers.sendFile(
                                        targetDevice, localPath, fileName, size,
                                        transferId = recipientTransferId,
                                        wireFileId = sharedWireFileId,
                                    )
                                }
                            chatRepository.sendGroupAttachment(
                                conversationId = peerId,
                                messageId = sharedMessageId,
                                transferId = sharedMessageId,
                                fileName = fileName,
                                mimeType = "audio/mp4",
                                sizeBytes = size,
                                localPath = localPath,
                                voiceDurationMs = durationMs,
                                voiceAmplitudes = amplitudes,
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
                            val transferId = transfers.sendFile(targetDevice, localPath, fileName, size).getOrNull()
                            if (transferId != null) {
                                chatRepository.sendAttachment(
                                    conversationId = peerId,
                                    transferId = transferId.value,
                                    fileName = fileName,
                                    mimeType = "audio/mp4",
                                    sizeBytes = size,
                                    localPath = localPath,
                                    voiceDurationMs = durationMs,
                                    voiceAmplitudes = amplitudes,
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
                    val hasAudio = ContextCompat.checkSelfPermission(
                        callCtx,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasAudio) {
                        scope.launch(Dispatchers.IO) {
                            if (conversationState.header.isGroup) {
                                val memberIds = if (conversationState.members.isNotEmpty()) {
                                    conversationState.members.map { it.id }
                                } else {
                                    chatRepository.groupMembers(peerId).map { it.id }
                                }
                                engine.calls?.startGroupCall(
                                    groupId = peerId,
                                    groupName = conversationState.header.title,
                                    memberIds = memberIds,
                                    video = false,
                                )
                            } else {
                                engine.calls?.startCall(peerId, conversationState.header.title, video = false)
                            }
                        }
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            onStartVideoCall = {
                val peerId = conversationId
                if (peerId != null) {
                    val hasAudio = ContextCompat.checkSelfPermission(
                        callCtx,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCamera = ContextCompat.checkSelfPermission(
                        callCtx,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasAudio && hasCamera) {
                        scope.launch(Dispatchers.IO) {
                            if (conversationState.header.isGroup) {
                                val memberIds = if (conversationState.members.isNotEmpty()) {
                                    conversationState.members.map { it.id }
                                } else {
                                    chatRepository.groupMembers(peerId).map { it.id }
                                }
                                engine.calls?.startGroupCall(
                                    groupId = peerId,
                                    groupName = conversationState.header.title,
                                    memberIds = memberIds,
                                    video = true,
                                )
                            } else {
                                engine.calls?.startCall(peerId, conversationState.header.title, video = true)
                            }
                        }
                    } else {
                        // Request whichever is missing. The user taps the video button
                        // again once permissions are granted.
                        if (!hasAudio) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            },
            // Connection-banner Retry: re-arm discovery browsing and force an immediate
            // auto-connect sweep. Returns false only before the engine has booted, which
            // is the one case where the banner should say "try again in a moment".
            onRetryConnection = { engine.reconnectNow() },
            onShareText = { text -> shareText(toastContext, text) },
            // Group Phase D: conversationId + menu actions.
            conversationId = conversationId,
            addablePeers = trustedPeerRoster.filter { candidate ->
                conversationState.members.none { it.id == candidate.id }
            },
            onAddGroupMembers = { groupId, memberIds ->
                scope.launch {
                    chatRepository.addGroupMembers(groupId, memberIds)
                }
            },
            onLeaveGroup = { groupId ->
                scope.launch {
                    val left = chatRepository.leaveGroup(groupId)
                    if (left is com.transfer.flash.core.common.result.FlashResult.Success) {
                        chatRepository.closeConversation()
                        if (twoPane) {
                            nav.navigate(FlashDestination.ChatList)
                        } else {
                            nav.back()
                        }
                    } else {
                        Toast.makeText(
                            toastContext,
                            "Couldn't leave the group — try again",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onClearConversation = { id ->
                chatRepository.deleteConversations(setOf(id))
                chatRepository.closeConversation()
                if (twoPane) {
                    nav.navigate(FlashDestination.ChatList)
                } else {
                    nav.back()
                }
            },
            onMarkUnread = chatRepository::markConversationUnread,
            onJoinGroupCall = { callId, video ->
                val peerId = conversationId
                if (peerId != null) {
                    scope.launch(Dispatchers.IO) {
                        val memberIds = if (conversationState.members.isNotEmpty()) {
                            conversationState.members.map { it.id }
                        } else {
                            chatRepository.groupMembers(peerId).map { it.id }
                        }
                        val hasAudio = ContextCompat.checkSelfPermission(
                            callCtx, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasAudio) {
                            engine.calls?.joinGroupCall(
                                groupId = peerId,
                                callId = callId,
                                memberIds = memberIds,
                                video = video,
                            )
                        }
                    }
                }
            },
        )
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
            onHistoryOpen = { item ->
                selectedTransferItem = item
                if (!twoPane) {
                    openAttachment(toastContext, item.localPath, guessMimeType(item.fileName))
                }
            },
            onHistoryShare = { item -> shareTransferredFile(toastContext, item) },
            modifier = Modifier.fillMaxSize(),
            listState = transfersScroll,
            bottomInset = tabBottomInset,
        )
    }

    val nearbyScreenContent: @Composable () -> Unit = {
        FlashNearbyScreen(
            state = nearby,
            onPairClick = { peer ->
                if (pendingShare.value != null) {
                    sendSharedPayloadToPeer(peer.id, peer.name)
                }
                // Ensure a session exists (dial is idempotent/coalesced), then start the
                // handshake. onSessionUp exchanges fingerprints so beginPair can derive
                // the shared 6-digit code; the responder sees the Accept/Decline dialog.
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peer.id }
                scope.launch {
                    val net = engine.network
                    if (endpoint != null && net != null) {
                        net.connectManual(endpoint.hostAddress, endpoint.port)
                    }
                    engine.pairing?.beginPair(peer.id, peer.name)
                }
                if (twoPane) selectedNearbyPeer = peer
            },
            onChatClick = { peer ->
                if (pendingShare.value != null) {
                    sendSharedPayloadToPeer(peer.id, peer.name)
                }
                selectedChatConversationId = peer.id
                chatRepository.openConversation(peer.id)
                nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
            },
            onRevokeClick = { trusted -> engine.pairing?.revoke(trusted.id) },
            onChatTrustedClick = { trusted ->
                if (pendingShare.value != null) {
                    sendSharedPayloadToPeer(trusted.id, trusted.name)
                }
                selectedChatConversationId = trusted.id
                chatRepository.openConversation(trusted.id)
                nav.navigate(FlashDestination.Conversation, conversationId = trusted.id)
            },
            onAcceptPairing = { engine.pairing?.acceptLocal() },
            onDeclinePairing = { engine.pairing?.declineLocal() },
            onManualConnect = { host, port ->
                scope.launch {
                    val net = engine.network
                    if (net != null) {
                        val result = net.connectManual(host, port)
                        if (result is com.transfer.flash.core.common.result.FlashResult.Success) {
                            val peerDevice = result.value.peer
                            engine.pairing?.beginPair(peerDevice.id.value, peerDevice.friendlyName)
                        } else {
                            android.widget.Toast.makeText(
                                toastContext,
                                "Couldn't connect to $host:$port",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
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
            onPerformanceModeSelected = {
                onSettingsChange(settings.copy(performanceMode = it))
            },
            onDiscoveryModeChanged = { mode ->
                onSettingsChange(settings.copy(discoveryMode = mode))
            },
            onEditDisplayName = { showRenameDialog = true },
            // ERROR-031 / D7: same system prompt the Background-transfers toggle fires,
            // reachable on its own so a user who already flipped that toggle (or who
            // revoked the exemption later) can still get to it.
            onOpenBatterySettings = onEnableBackgroundTransfers,
            onRefreshStorageUsage = onRefreshStorageUsage,
            onClearReceivedFiles = onClearReceivedFiles,
            modifier = Modifier.fillMaxSize(),
            listState = settingsScroll,
            bottomInset = tabBottomInset,
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
            isSearching = isSearching,
            searchQuery = searchQuery,
            onSearchQueryChanged = { searchQuery = it },
            onCloseSearch = {
                isSearching = false
                searchQuery = ""
            },
            messageBodyMatches = messageBodyMatches,
            onConversationLongClick = chatRepository::enterListSelectionMode,
            onToggleSelection = chatRepository::toggleListSelection,
            onArchiveConversation = chatRepository::archiveConversation,
            onUnarchiveConversation = chatRepository::unarchiveConversation,
            onCloseSelection = chatRepository::clearListSelection,
            onPinSelected = {
                chatRepository.setConversationsPinned(chatListState.selectedIds, true)
            },
            onMuteSelected = {
                chatRepository.setConversationsMuted(chatListState.selectedIds, true)
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
                chatRepository.deleteConversations(chatListState.selectedIds)
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
            modifier = Modifier.fillMaxSize(),
            listState = chatListScroll,
            bottomInset = tabBottomInset,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundApp),
    ) {
        if (useNavRail) {
            Row(Modifier.fillMaxSize()) {
                FlashNavigationRail(
                    tabs = navigationRailTabs,
                    selectedTab = if (nav.current.destination == FlashDestination.Conversation) FlashDestination.ChatList else nav.current.destination,
                    onTabSelected = { destination ->
                        if (destination == FlashDestination.ChatList) {
                            if (selectedChatConversationId != null && twoPane) {
                                chatRepository.openConversation(selectedChatConversationId!!)
                                nav.navigate(FlashDestination.Conversation, conversationId = selectedChatConversationId)
                            } else if (nav.current.destination == FlashDestination.Conversation) {
                                chatRepository.closeConversation()
                                selectedChatConversationId = null
                                nav.navigate(FlashDestination.ChatList)
                            } else {
                                nav.selectTab(FlashDestination.ChatList)
                            }
                        } else {
                            nav.selectTab(destination)
                        }
                    },
                    localDisplayName = settings.displayName,
                    onProfileClick = {
                        nav.selectTab(FlashDestination.Settings)
                    },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (twoPane) {
                        val availableWidthDp = (windowWidthDp - 68f).coerceAtLeast(0f)
                        when (nav.current.destination) {
                            FlashDestination.ChatList, FlashDestination.Conversation -> {
                                FlashAdaptiveTwoPane(
                                    listPane = chatListScreenContent,
                                    detailPane = {
                                        val activeConvId = nav.current.conversationId ?: selectedChatConversationId
                                        if (activeConvId != null) {
                                            conversationScreenContent()
                                        } else {
                                            FlashPlaceholderDetailPane(
                                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                            )
                                        }
                                    },
                                    windowWidthDp = availableWidthDp,
                                )
                            }
                            FlashDestination.Transfers -> {
                                FlashAdaptiveTwoPane(
                                    listPane = transfersScreenContent,
                                    detailPane = {
                                        val item = selectedTransferItem
                                        if (item != null) {
                                            FlashTransferDetailPane(
                                                item = item,
                                                onClose = { selectedTransferItem = null },
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
                                                    openAttachment(toastContext, item.localPath, guessMimeType(item.fileName))
                                                },
                                                onReveal = {
                                                    shareTransferredFile(toastContext, item)
                                                },
                                            )
                                        } else {
                                            FlashPlaceholderDetailPane(
                                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                            )
                                        }
                                    },
                                    windowWidthDp = availableWidthDp,
                                )
                            }
                            FlashDestination.NearbyDevices -> {
                                FlashAdaptiveTwoPane(
                                    listPane = nearbyScreenContent,
                                    detailPane = {
                                        val peer = selectedNearbyPeer
                                        if (peer != null) {
                                            FlashNearbyDetailPane(
                                                peer = peer,
                                                onClose = { selectedNearbyPeer = null },
                                                onPair = {
                                                    val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peer.id }
                                                    scope.launch {
                                                        val net = engine.network
                                                        if (endpoint != null && net != null) {
                                                            net.connectManual(endpoint.hostAddress, endpoint.port)
                                                        }
                                                        engine.pairing?.beginPair(peer.id, peer.name)
                                                    }
                                                },
                                                onChat = {
                                                    selectedChatConversationId = peer.id
                                                    chatRepository.openConversation(peer.id)
                                                    nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
                                                },
                                            )
                                        } else {
                                            FlashPlaceholderDetailPane(
                                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                            )
                                        }
                                    },
                                    windowWidthDp = availableWidthDp,
                                )
                            }
                            FlashDestination.Settings -> {
                                FlashAdaptiveTwoPane(
                                    listPane = settingsScreenContent,
                                    detailPane = {
                                        FlashPlaceholderDetailPane(
                                            onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                        )
                                    },
                                    windowWidthDp = availableWidthDp,
                                )
                            }
                        }
                    } else {
                        // Medium (600..839dp) - rail on left, single pane in content area
                        Box(Modifier.fillMaxSize()) {
                            FlashAnimatedScreen(targetState = nav.current) { entry ->
                                when (entry.destination) {
                                    FlashDestination.Conversation -> conversationScreenContent()
                                    FlashDestination.Transfers -> transfersScreenContent()
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
                FlashAnimatedScreen(targetState = nav.current) { entry ->
                    when (entry.destination) {
                        FlashDestination.Conversation -> conversationScreenContent()
                        FlashDestination.Transfers -> transfersScreenContent()
                        FlashDestination.NearbyDevices -> nearbyScreenContent()
                        FlashDestination.Settings -> settingsScreenContent()
                        FlashDestination.ChatList -> chatListScreenContent()
                    }
                }
            }
        }

        // Group Phase 1A: trusted-only creation. On success the new group opens like any
        // other conversation; a failure is surfaced as a toast rather than silently dropped.
        if (showCreateGroup) {
            com.transfer.flash.ui.chat.FlashCreateGroupSheet(
                peers = trustedPeerRoster,
                onDismiss = { showCreateGroup = false },
                onCreate = { title, memberIds ->
                    scope.launch {
                        val result = chatRepository.createGroup(title, memberIds)
                        val groupId = result.getOrNull()
                        if (groupId != null) {
                            showCreateGroup = false
                            chatRepository.openConversation(groupId)
                            nav.navigate(FlashDestination.Conversation, conversationId = groupId)
                        } else {
                            Toast.makeText(
                                toastContext,
                                "Couldn't create the group — check that every member is paired",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }

        if (!twoPane) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                AnimatedVisibility(
                    visible = showBar,
                    enter = FlashTheme.motion.shellBarEnter(),
                    exit = FlashTheme.motion.shellBarExit(),
                ) {
                    FlashBottomNav(
                        items = liveBottomNavTabs,
                        selectedTab = nav.current.destination,
                        onTabSelected = nav::selectTab,
                        // Telegram behaviour: tapping the tab you are already on returns it to the top.
                        onTabReselected = { destination ->
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
            DisplayNameDialog(
                initial = settings.displayName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    onSettingsChange(settings.copy(displayName = newName))
                    showRenameDialog = false
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
                    sendSharedPayloadToPeer(recipient.id, recipient.name)
                },
                onDismiss = {
                    pendingShare.value = null
                },
                onManualConnect = {
                    showShareManualConnect = true
                },
            )
        }

        if (showShareManualConnect) {
            FlashManualConnectDialog(
                onDismiss = { showShareManualConnect = false },
                onConnect = { host, port ->
                    showShareManualConnect = false
                    scope.launch {
                        val net = engine.network
                        if (net != null) {
                            val result = net.connectManual(host, port)
                            if (result is com.transfer.flash.core.common.result.FlashResult.Success) {
                                val peerDevice = result.value.peer
                                val peerId = peerDevice.id.value
                                val peerName = peerDevice.friendlyName
                                val isTrusted = trustedPeers.any { it.id == peerId }
                                if (isTrusted) {
                                    sendSharedPayloadToPeer(peerId, peerName)
                                } else {
                                    isPairingForShare = true
                                    pendingShareRecipient = Pair(peerId, peerName)
                                    engine.pairing?.beginPair(peerId, peerName)
                                    Toast.makeText(toastContext, "Connected to $host:$port. Initiating pairing with $peerName...", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(toastContext, "Couldn't connect to $host:$port", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
        }

        if (nav.current.destination != FlashDestination.NearbyDevices) {
            pairingModel?.request?.let { request ->
                FlashPairingDialog(
                    request = request,
                    phase = pairingModel?.phase ?: FlashPairingPhase.Idle,
                    secondsLeft = pairingModel?.secondsLeft ?: 0,
                    onAccept = { engine.pairing?.acceptLocal() },
                    onDecline = { engine.pairing?.declineLocal() },
                    onDismiss = { engine.pairing?.declineLocal() },
                )
            }
        }

        // C7 (calling): full-screen call overlay. Topmost sibling so it renders above
        // everything (nav bar, console, rename dialog). Driven by the engine's FlashCalling.
        // v1 has no minimize — the overlay stays until the coordinator clears the call
        // (ENDED shows for 2s, then _activeCall nulls and this overlay disappears).
        val callStateFlow = engine.calls?.activeCall ?: remember { MutableStateFlow(null) }
        val callState by callStateFlow.collectAsState()
        val callMedia = engine.calls?.media
        val activeCall = callState
        val callContext = LocalContext.current
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
        if (activeCall != null) {
            FlashCallScreen(
                state = activeCall,
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
                    if (activeCall.video) {
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
                    audioRouter.attach(activeCall.speakerOn)
                    scope.launch { engine.calls?.accept() }
                },
                onDecline = { scope.launch { engine.calls?.decline() } },
                onHangUp = { scope.launch { engine.calls?.hangUp() } },
                onToggleMute = { engine.calls?.toggleMute() },
                onToggleSpeaker = {
                    val next = !activeCall.speakerOn
                    engine.calls?.setSpeaker(next)
                    audioRouter.setSpeaker(next)
                },
                onToggleCamera = { engine.calls?.toggleCamera() },
                onSwitchCamera = { scope.launch { engine.calls?.switchCamera() } },
                onDismiss = { /* v1: no minimize — call always ends before dismiss. */ },
            )
        }
    }
}

/** #14: minimal display-name editor. Trims and ignores blanks so identity never persists empty. */
@Composable
private fun DisplayNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Display name") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { androidx.compose.material3.Text("Visible to nearby devices") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { androidx.compose.material3.Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel")
            }
        },
    )
}

private fun FlashTransportType.toUiTransport(): FlashNetworkTransport = when (this) {
    FlashTransportType.WIFI_DIRECT -> FlashNetworkTransport.WifiDirect
    FlashTransportType.RELAY, FlashTransportType.MESH -> FlashNetworkTransport.Relay
    FlashTransportType.UNKNOWN -> FlashNetworkTransport.Unknown
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

