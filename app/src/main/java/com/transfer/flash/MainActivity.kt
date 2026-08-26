package com.transfer.flash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.SampleFlashChatRepository
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.getOrNull
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.di.AppEngine
import com.transfer.flash.debug.DevConsoleChip
import com.transfer.flash.debug.FlashDevConsoleScreen
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.navigation.FlashAnimatedScreen
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath
import com.transfer.flash.ui.navigation.rememberFlashNavigationState
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.nearby.NearbyIdentityUi
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.nearby.NearbyTrustedPeerUi
import com.transfer.flash.ui.nearby.NearbyUiState
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.pairing.PairingUiModel
import com.transfer.flash.ui.settings.FlashSettingsModel
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.settings.FlashThemeMode
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.ui.shell.FlashBottomNav
import com.transfer.flash.ui.shell.FlashBottomNavDefaults
import com.transfer.flash.ui.shell.FlashBottomNavItem
import com.transfer.flash.ui.transfers.FlashTransfersScreen
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.TransfersUiState
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Showcase host (Phase 8 app shell, docs/ui-page-plan.md): four-tab FlashBottomNav +
 * FlashNavigationState-driven content. Tab switches reset the stack (selectTab); pushes
 * are reserved for Conversation and overlays. Demo tab states below are shaped exactly
 * like the future C5/C3/C1.4 engine mappings so wiring is substitution, not rewrite.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appEngine: AppEngine

    // #13: POST_NOTIFICATIONS is a runtime permission on Android 13+ (TIRAMISU). Without it the
    // foreground-transfer / message notifications are silently suppressed. Registered here (not
    // deep in Compose) so the launcher survives config changes and only fires once per launch.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort; no-op on denial */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        // Boot the real WS mesh stack once, idempotently. The holder de-dupes against the Dev
        // Console / background service, so this never spins up a second server. Failures are
        // captured into appEngine.startError (permission gating lands in Phase 4).
        appEngine.start()
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        setContent {
            FlashApp(engine = appEngine, showDevConsoleEntry = isDebuggable)
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

@Composable
fun FlashApp(engine: AppEngine, showDevConsoleEntry: Boolean = false) {
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

    val ready by engine.ready.collectAsState()
    val trustedFallback = remember { MutableStateFlow(emptyList<NearbyTrustedPeerUi>()) }
    val trustedPeers by (engine.pairing?.trustedPeers ?: trustedFallback).collectAsState()

    val settings = FlashSettingsModel(
        displayName = displayNamePref.ifBlank { if (ready) engine.localFriendlyName else "Flash device" },
        themeMode = SettingsKeys.themeModeFromKey(themeModeKey),
        dynamicAccent = dynamicAccent,
        hapticsEnabled = hapticsEnabled,
        backgroundTransfers = backgroundTransfers,
        trustedPeerCount = trustedPeers.size,
        appVersion = engine.appVersionName,
        deviceIdShort = (if (ready) engine.localDeviceId else "").take(8).ifBlank { "00000000" },
    )
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
            if (updated.displayName != settings.displayName) store.setDisplayName(updated.displayName)
        }
    }

    val darkTheme = FlashSettingsMath.resolveDarkTheme(
        mode = settings.themeMode,
        systemDark = isSystemInDarkTheme(),
    )

    FlashMaterialTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicAccent) {
        FlashTheme(
            darkTheme = darkTheme,
            dynamicAccent = settings.dynamicAccent,
            hapticsEnabled = settings.hapticsEnabled,
        ) {
            FlashShell(
                engine = engine,
                showDevConsoleEntry = showDevConsoleEntry,
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
        }
    }
}

@Composable
private fun FlashShell(
    engine: AppEngine,
    showDevConsoleEntry: Boolean,
    settings: FlashSettingsModel,
    onSettingsChange: (FlashSettingsModel) -> Unit,
) {
    val nav = rememberFlashNavigationState()
    // Phase 3.1: Chats now bind to the real Room-backed repository once the engine has booted.
    // Reading `ready` here is the recomposition trigger — engine.chats is a plain holder getter, so
    // without a state read the swap from Sample → real would never recompose. Until the stack is up
    // (or if boot failed), the Sample repo keeps the tab populated so the shell is never empty.
    val ready by engine.ready.collectAsState()
    val sampleChatRepository = remember { SampleFlashChatRepository() }
    val chatRepository: com.transfer.flash.core.messaging.FlashChatRepository =
        (if (ready) engine.chats else null) ?: sampleChatRepository
    val conversationState by chatRepository.conversationState.collectAsState()
    val chatListState by chatRepository.chatListState.collectAsState()

    var showDevConsole by remember { mutableStateOf(false) }

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

    // Phase 3.3: Transfers derives from the live transfer repository's activeTransfers. The fallback
    // flow is remembered UNCONDITIONALLY and swaps to the engine flow once booted (never remember
    // inside a `?:` — conditional remember desyncs the slot table). TransfersUiState.fromDomain does
    // the domain→UI mapping (Active/Failed/History bucketing, direction, ETA/verified normalisation).
    val fallbackTransfers = remember { MutableStateFlow(emptyList<FlashTransfer>()) }
    val domainTransfers by (engine.transfers?.activeTransfers ?: fallbackTransfers).collectAsState()
    val transfersUi = remember(domainTransfers) { TransfersUiState.fromDomain(domainTransfers) }

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
    // Surface the coordinator's transient status lines (e.g. "Connecting…", "Couldn't reach …") as
    // toasts so tapping Pair always gives feedback instead of silently doing nothing. Keyed on the
    // coordinator instance so collection (re)starts once the engine boots.
    val toastContext = LocalContext.current
    LaunchedEffect(engine.pairing) {
        engine.pairing?.messages?.collect { message ->
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }
    val nearby = remember(discoveredEndpoints, discoveryState, ready, pairingModel, trustedPeers) {
        val trustedIds = trustedPeers.mapTo(HashSet()) { it.id }
        NearbyUiState(
            identity = NearbyIdentityUi(
                displayName = if (ready) engine.localFriendlyName else "Flash device",
                deviceIdShort = (if (ready) engine.localDeviceId else "").take(8).ifBlank { "00000000" },
                port = discoveryState.advertisedPort,
            ),
            isScanning = discoveryState.isDiscovering,
            // A trusted peer lives in the TRUSTED section (with its own Chat/Revoke), so exclude
            // it from DISCOVERED — otherwise the same device renders in both sections, which both
            // duplicates the row and (sharing a device-id key) crashed the Nearby LazyColumn.
            peers = discoveredEndpoints
                .filter { it.deviceId.value !in trustedIds }
                .map { ep ->
                    NearbyPeerUi(
                        id = ep.deviceId.value,
                        name = ep.friendlyName,
                        transport = ep.transportType.toUiTransport(),
                        isTrusted = false,
                    )
                },
            trustedPeers = trustedPeers,
            pairingRequest = pairingModel?.request,
            pairingPhase = pairingModel?.phase ?: FlashPairingPhase.Idle,
            pairingSecondsLeft = pairingModel?.secondsLeft ?: 0,
        )
    }

    // Console first: while it is up it owns back, and the shell keeps its own handler disabled
    // so one press never both closes the console and pops the stack.
    BackHandler(enabled = showDevConsole) { showDevConsole = false }
    BackHandler(enabled = nav.canGoBack && !showDevConsole) { nav.back() }
    // UI-024: while chat-list search is open, Back closes search first (registered last so it
    // takes priority over the stack pop when both are eligible).
    BackHandler(enabled = isSearching && !showDevConsole) {
        isSearching = false
        searchQuery = ""
    }
    // UI-013: while chat-list selection mode is active, Back exits selection first (registered
    // last so it wins over the search-close and stack-pop handlers when several are eligible).
    BackHandler(enabled = chatListState.selectionMode && !showDevConsole) {
        chatRepository.clearListSelection()
    }

    // UI-046 v2: the shell bar HANGS over the page instead of docking under it. Tab roots
    // hand `contentInset + system nav inset` to the page, which folds it into its own
    // contentPadding so rows scroll UNDER the capsule; pushed screens (Conversation) drop the
    // bar entirely and manage their own bottom insets.
    val showBar = FlashNavigationMath.isTabRoot(nav.current.destination)
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBottomInset = FlashBottomNavDefaults.contentInset + systemBottomInset
    // Only the floating Dev Console chip still needs the animated inset — page content is
    // padded from the inside, so its inset must stay constant to avoid a relayout per frame.
    val chipBottomInset by animateDpAsState(
        targetValue = if (showBar) tabBottomInset else 0.dp,
        animationSpec = FlashTheme.motion.tweenNormalSpec(),
        label = "flashShellChipInset",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundApp),
    ) {
        Box(Modifier.fillMaxSize()) {
            FlashAnimatedScreen(targetState = nav.current) { entry ->
                when (entry.destination) {
                    FlashDestination.Conversation -> FlashConversationScreen(
                        state = conversationState,
                        onBack = {
                            chatRepository.closeConversation()
                            nav.back()
                        },
                        // UI-032: tapping the header avatar opens the in-screen peer-details sheet.
                        // Feed it the peer's live trust status + a revoke action keyed by the
                        // conversationId (== peer device id, the pairing/trust key).
                        isPeerTrusted = entry.conversationId?.let { pid ->
                            trustedPeers.any { it.id == pid }
                        } ?: false,
                        onRevokePeerTrust = entry.conversationId?.let { pid ->
                            { engine.pairing?.revoke(pid); Unit }
                        },
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
                            // conversationId doubles as the peer's device id (the transport routing
                            // key). Reuse the live discovered transport when known so the transfer
                            // rides the right medium; fall back to LAN (the WS-mesh default).
                            val peerId = entry.conversationId
                            val transfers = engine.transfers
                            if (peerId != null && transfers != null) {
                                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
                                val targetDevice = FlashDevice(
                                    id = FlashDeviceId(peerId),
                                    friendlyName = conversationState.header.title,
                                    transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                                )
                                val mime = guessMimeType(displayName)
                                scope.launch {
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
                        },
                        onDeleteMessage = { ids -> chatRepository.deleteMessages(ids) },
                        onOpenAttachment = { path, mime, _ -> openAttachment(toastContext, path, mime) },
                        onSaveImage = { uri, mime -> saveImageToGallery(toastContext, uri, mime) },
                        onShareImage = { uri, mime -> shareImageUri(toastContext, uri, mime) },
                        onSendVoiceMessage = { localPath, durationMs, amplitudes ->
                            // B9: a captured voice note rides the same P2P transfer pipeline as any
                            // file, then lands as an inline playback card via sendAttachment (audio/*).
                            val peerId = entry.conversationId
                            val transfers = engine.transfers
                            if (peerId != null && transfers != null) {
                                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
                                val targetDevice = FlashDevice(
                                    id = FlashDeviceId(peerId),
                                    friendlyName = conversationState.header.title,
                                    transportType = endpoint?.transportType ?: FlashTransportType.LAN,
                                )
                                val fileName = "Voice message.m4a"
                                val size = runCatching {
                                    android.net.Uri.parse(localPath).path?.let { java.io.File(it).length() } ?: 0L
                                }.getOrDefault(0L)
                                scope.launch {
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
                        },
                    )
                    FlashDestination.Transfers -> FlashTransfersScreen(
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
                            openAttachment(toastContext, item.localPath, guessMimeType(item.fileName))
                        },
                        onHistoryShare = { item -> shareTransferredFile(toastContext, item) },
                        modifier = Modifier.fillMaxSize(),
                        listState = transfersScroll,
                        bottomInset = tabBottomInset,
                    )
                    FlashDestination.NearbyDevices -> FlashNearbyScreen(
                        state = nearby,
                        onPairClick = { peer ->
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
                        },
                        onChatClick = { peer ->
                            chatRepository.openConversation(peer.id)
                            nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
                        },
                        onRevokeClick = { trusted -> engine.pairing?.revoke(trusted.id) },
                        onChatTrustedClick = { trusted ->
                            chatRepository.openConversation(trusted.id)
                            nav.navigate(FlashDestination.Conversation, conversationId = trusted.id)
                        },
                        onAcceptPairing = { engine.pairing?.acceptLocal() },
                        onDeclinePairing = { engine.pairing?.declineLocal() },
                        modifier = Modifier.fillMaxSize(),
                        listState = nearbyScroll,
                        bottomInset = tabBottomInset,
                    )
                    FlashDestination.Settings -> FlashSettingsScreen(
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
                            onSettingsChange(settings.copy(backgroundTransfers = it))
                        },
                        onEditDisplayName = { showRenameDialog = true },
                        modifier = Modifier.fillMaxSize(),
                        listState = settingsScroll,
                        bottomInset = tabBottomInset,
                    )
                    FlashDestination.ChatList -> FlashChatListScreen(
                        state = chatListState,
                        onConversationClick = { id ->
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
                        onDeleteSelected = {
                            chatRepository.deleteConversations(chatListState.selectedIds)
                        },
                        modifier = Modifier.fillMaxSize(),
                        listState = chatListScroll,
                        bottomInset = tabBottomInset,
                    )
                }
            }

            // P3.5/E: debug-only Dev Console entry sits just above the hanging bar. Release never sees this.
            if (showDevConsoleEntry) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp + chipBottomInset),
                ) {
                    DevConsoleChip(onClick = { showDevConsole = true })
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = showBar,
                enter = FlashTheme.motion.shellBarEnter(),
                exit = FlashTheme.motion.shellBarExit(),
            ) {
                FlashBottomNav(
                    items = bottomNavTabs,
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

        // The console is a sibling LAYER, not an early return: replacing the shell wholesale
        // discarded every remember slot below it (demo state, scroll positions), which is why
        // closing it used to dump you onto whatever screen had been tapped meanwhile.
        AnimatedVisibility(
            visible = showDevConsole,
            enter = FlashTheme.motion.sheetEnter(),
            exit = FlashTheme.motion.sheetExit(),
        ) {
            FlashDevConsoleScreen(
                context = LocalContext.current,
                onClose = { showDevConsole = false },
            )
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

/** Coarse MIME from a filename extension; falls back to a permissive wildcard for the chooser. */
private fun guessMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.getDefault())
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
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

/** UI-018: share a viewed image out via the system chooser (ACTION_SEND). */
private fun shareImageUri(
    context: android.content.Context,
    ref: String?,
    mimeType: String,
) {
    val uri = resolveShareableUri(context, ref)
    if (uri == null) {
        Toast.makeText(context, "Can't share this image", Toast.LENGTH_SHORT).show()
        return
    }
    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { "image/*" }
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(share, "Share image")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}

/**
 * UI-018: copy a viewed image into the shared gallery via MediaStore (Pictures/Flash). Reads the
 * source through the content resolver so both content:// URIs and FileProvider-backed paths work,
 * and never needs WRITE_EXTERNAL_STORAGE on API 29+ (scoped storage / RELATIVE_PATH).
 */
private fun saveImageToGallery(
    context: android.content.Context,
    ref: String?,
    mimeType: String,
) {
    val source = resolveShareableUri(context, ref)
    if (source == null) {
        Toast.makeText(context, "Image not available yet", Toast.LENGTH_SHORT).show()
        return
    }
    val resolver = context.contentResolver
    val mime = mimeType.ifBlank { "image/jpeg" }
    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
    val name = "flash_${System.currentTimeMillis()}.$ext"
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_PICTURES}/Flash",
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val target = runCatching { resolver.insert(collection, values) }.getOrNull()
    if (target == null) {
        Toast.makeText(context, "Couldn't save image", Toast.LENGTH_SHORT).show()
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
    if (ok && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        runCatching { resolver.update(target, values, null, null) }
    }
    Toast.makeText(
        context,
        if (ok) "Saved to Pictures/Flash" else "Couldn't save image",
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
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "*/*" })
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(view) }
        .onFailure { Toast.makeText(context, "No app to open this file", Toast.LENGTH_SHORT).show() }
}

