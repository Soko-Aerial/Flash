package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection as DomainDirection
import com.transfer.flash.core.transfer.model.FlashTransferState as DomainState
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.navigation.FlashAnimatedScreen
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath
import com.transfer.flash.ui.navigation.rememberFlashNavigationState
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.nearby.NearbyIdentityUi
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.nearby.NearbyTrustedPeerUi
import com.transfer.flash.ui.nearby.NearbyUiState
import com.transfer.flash.ui.settings.FlashSettingsModel
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.shell.FlashBottomNav
import com.transfer.flash.ui.shell.FlashBottomNavItem
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.transfers.FlashTransferDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.FlashTransfersScreen
import com.transfer.flash.ui.transfers.TransfersUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Thin desktop shell (Phase 21 21-3 + Phase 22 22-5) — Option B per the phase file's Step 6
 * analysis: `:app`'s `FlashShell` stays where it is, and this shell composes the four shared
 * `:ui:chat` tab screens directly against `DesktopEngine`'s flows.
 *
 * Mirrors FlashShell's data-shaping discipline (fallback flows remembered UNCONDITIONALLY and
 * swapped after boot; `derivedStateOf` so off-tab mapping never re-executes the shell) but
 * without `:app`-scoped pieces: no pairing coordinator (C2/C4 pairing is Android-wired until a
 * desktop coordinator exists), no calling, no PTT, no notification flows. The conversation
 * screen is reachable only once a chat repository exists (it does not until 09B-2 — see
 * [DesktopEngine.chats]); the Chats tab therefore renders its honest empty/loading state.
 *
 * Phase 22 adds the adaptive arrangement: at Expanded widths (≥840dp) a `DesktopSideBar` takes
 * the left edge and the content area becomes list+detail via [DesktopTwoPane] (transfer / peer
 * selection drives the detail pane); at Compact/Medium the Phase 21 bottom-nav single-pane
 * layout is kept.
 */
@Composable
public fun DesktopShell(engine: DesktopEngine) {
    val nav = rememberFlashNavigationState()
    val scope = rememberCoroutineScope()

    val ready by engine.ready.collectAsState()
    val startError by engine.startError.collectAsState()

    // ── Phase 22: window size + selection state for the two-pane layout ──
    val sizeClass = rememberFlashDesktopWindowSize()
    val twoPane = FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)
    var selectedTransferItem by remember { mutableStateOf<FlashTransferItemUi?>(null) }
    var selectedNearbyPeer by remember { mutableStateOf<NearbyPeerUi?>(null) }

    // ── Chats: EmptyFlashChatRepository until 09B-2 (honest empty per ERROR-034) ──
    val chatRepository = remember(engine) { engine.chats }
    val chatListState by chatRepository.chatListState.collectAsState()

    // ── Transfers: fallback remembered unconditionally, swapped after boot; paced ──
    val fallbackTransfers = remember { MutableStateFlow(emptyList<FlashTransfer>()) }
    val transfersSource = engine.transfers?.activeTransfers ?: fallbackTransfers
    val pacedTransfers = remember(transfersSource) {
        transfersSource.throttleLatestDesktop(FLASH_TRANSFERS_THROTTLE_MS)
    }
    val domainTransfers by pacedTransfers.collectAsState(initial = transfersSource.value)
    val transfersReady = ready && engine.transfers != null
    val transfersUi by remember(pacedTransfers, transfersReady) {
        derivedStateOf {
            TransfersUiState.fromDomain(
                transfers = domainTransfers,
                isLoading = !transfersReady && startError == null,
                isError = startError != null,
            )
        }
    }

    // ── Nearby: real discovery endpoints + trust store; no pairing dialog ──
    val fallbackEndpoints = remember { MutableStateFlow(emptyList<FlashDiscoveredEndpoint>()) }
    val fallbackDiscoveryState = remember { MutableStateFlow(FlashDiscoveryState()) }
    val discoveredEndpoints by (engine.discovery?.discoveredEndpoints ?: fallbackEndpoints).collectAsState()
    val discoveryState by (engine.discovery?.state ?: fallbackDiscoveryState).collectAsState()
    val trustedPeers by remember(engine) {
        derivedStateOf {
            engine.trust.getTrustedPeers().map { (id, name) -> NearbyTrustedPeerUi(id = id.value, name = name) }
        }
    }
    val nearby by remember(engine, engine.discovery) {
        derivedStateOf {
            val trustedIds = trustedPeers.mapTo(HashSet()) { it.id }
            NearbyUiState(
                identity = NearbyIdentityUi(
                    displayName = engine.localFriendlyName,
                    deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
                    port = discoveryState.advertisedPort,
                ),
                isScanning = discoveryState.isDiscovering,
                isLoading = !ready,
                peers = discoveredEndpoints
                    .filter { it.deviceId.value !in trustedIds }
                    .map { ep ->
                        NearbyPeerUi(
                            id = ep.deviceId.value,
                            name = ep.friendlyName,
                            transport = ep.transportType.toDesktopTransport(),
                            isTrusted = false,
                        )
                    },
                trustedPeers = trustedPeers,
                pairingRequest = null,
                pairingPhase = FlashPairingPhase.Idle,
                pairingSecondsLeft = 0,
            )
        }
    }

    // ── Settings: no persisted DataStore on desktop until 09B-3; honest defaults ──
    val settings = remember(engine, ready) {
        FlashSettingsModel(
            displayName = engine.localFriendlyName,
            deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
            appVersion = engine.appVersionName,
        )
    }

    // UI-046: per-tab scroll state, like the app shell.
    val chatListScroll = rememberLazyListState()
    val transfersScroll = rememberLazyListState()
    val nearbyScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()

    val tabBottomInset = if (twoPane) 0.dp else FLASH_BOTTOM_NAV_INSET

    // The tab content, shared by both window layouts. In two-pane mode the transfers/nearby
    // screens additionally drive the detail pane through the selection state.
    val listPaneContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            FlashAnimatedScreen(targetState = nav.current) { entry ->
                when (entry.destination) {
                    FlashDestination.ChatList -> FlashChatListScreen(
                        state = chatListState,
                        onConversationClick = { id ->
                            chatRepository.openConversation(id)
                            nav.navigate(FlashDestination.Conversation, conversationId = id)
                        },
                        onSearchClick = { /* desktop v1: no search chrome */ },
                        onFindDevicesClick = { nav.selectTab(FlashDestination.NearbyDevices) },
                        onLanClick = { nav.selectTab(FlashDestination.NearbyDevices) },
                        isLoading = (!ready || !chatListState.hasLoaded) && startError == null,
                        errorMessage = startError?.let { error ->
                            error.message?.takeIf { it.isNotBlank() }
                                ?: error::class.simpleName
                                ?: "Unknown startup failure"
                        },
                        onRetryLoad = { engine.start() },
                        modifier = Modifier.fillMaxSize(),
                        listState = chatListScroll,
                        bottomInset = tabBottomInset,
                    )
                    FlashDestination.Conversation -> {
                        // Desktop v1 has no conversation screen until a chat repository exists
                        // (09B-2). Render the chat list rather than a dead tab — the shell
                        // never routes here because openConversation() on the empty repository
                        // is a no-op, but the branch keeps the exhaustive `when` honest.
                        FlashChatListScreen(
                            state = chatListState,
                            onConversationClick = { },
                            onSearchClick = { },
                            modifier = Modifier.fillMaxSize(),
                            listState = chatListScroll,
                            bottomInset = tabBottomInset,
                        )
                    }
                    FlashDestination.Transfers -> FlashTransfersScreen(
                        state = transfersUi,
                        onPauseResumeClick = { item ->
                            engine.transfers?.let { repo ->
                                val id = com.transfer.flash.core.transfer.model.FlashTransferId(item.id)
                                scope.launch {
                                    if (item.state == FlashTransferState.Paused) repo.resumeTransfer(id)
                                    else repo.pauseTransfer(id)
                                }
                            }
                        },
                        onCancelClick = { item ->
                            engine.transfers?.let { repo ->
                                scope.launch {
                                    repo.cancelTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(item.id))
                                }
                            }
                        },
                        onRetryClick = { item ->
                            engine.transfers?.let { repo ->
                                scope.launch {
                                    repo.resumeTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(item.id))
                                }
                            }
                        },
                        onAcceptOffer = { item ->
                            engine.transfers?.let { repo ->
                                scope.launch {
                                    repo.acceptIncoming(com.transfer.flash.core.transfer.model.FlashTransferId(item.id))
                                }
                            }
                        },
                        onDeclineOffer = { item ->
                            engine.transfers?.let { repo ->
                                scope.launch {
                                    repo.declineIncoming(com.transfer.flash.core.transfer.model.FlashTransferId(item.id))
                                }
                            }
                        },
                        onHistoryOpen = { item ->
                            // Two-pane: also show the details alongside. Single-pane: open it.
                            selectedTransferItem = item
                            if (!twoPane) {
                                DesktopHelpers.openAttachment(item.localPath, DesktopHelpers.guessMimeType(item.fileName))
                            }
                        },
                        onHistoryShare = { item -> DesktopHelpers.shareTransferredFile(item) },
                        modifier = Modifier.fillMaxSize(),
                        listState = transfersScroll,
                        bottomInset = tabBottomInset,
                    )
                    FlashDestination.NearbyDevices -> FlashNearbyScreen(
                        state = nearby,
                        onPairClick = { peer ->
                            // Ensure a session exists (dial is idempotent), like the app — minus
                            // beginPair, which needs a PairingCoordinator (Android-only, C2/C4).
                            val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peer.id }
                            val net = engine.network
                            if (endpoint != null && net != null) {
                                scope.launch { net.connectManual(endpoint.hostAddress, endpoint.port) }
                            }
                            // Two-pane: show what we know about the peer while it connects.
                            if (twoPane) selectedNearbyPeer = peer
                        },
                        onChatClick = { peer ->
                            chatRepository.openConversation(peer.id)
                            nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
                        },
                        onRevokeClick = { trusted ->
                            scope.launch { engine.trust.revokeTrust(com.transfer.flash.core.common.model.FlashDeviceId(trusted.id)) }
                        },
                        onChatTrustedClick = { trusted ->
                            chatRepository.openConversation(trusted.id)
                            nav.navigate(FlashDestination.Conversation, conversationId = trusted.id)
                        },
                        modifier = Modifier.fillMaxSize(),
                        listState = nearbyScroll,
                        bottomInset = tabBottomInset,
                    )
                    FlashDestination.Settings -> FlashSettingsScreen(
                        model = settings,
                        onThemeModeSelected = { /* no persisted settings tier on desktop (09B-3) */ },
                        onDynamicAccentChanged = { },
                        onHapticsChanged = { },
                        onBackgroundTransfersChanged = { },
                        onRefreshStorageUsage = { },
                        onClearReceivedFiles = {
                            scope.launch {
                                DesktopHelpers.clearReceivedFiles(engine)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        listState = settingsScroll,
                        bottomInset = tabBottomInset,
                    )
                }
            }
        }
    }

    // The detail pane for two-pane mode: transfer info, peer info, or the placeholder (C3:
    // no conversation pane in v1 — the empty repository cannot supply conversation state).
    val detailPaneContent: @Composable () -> Unit = {
        when {
            selectedTransferItem != null -> TransferDetailPane(
                item = selectedTransferItem!!,
                onClose = { selectedTransferItem = null },
            )
            selectedNearbyPeer != null -> NearbyDetailPane(
                peer = selectedNearbyPeer!!,
                onClose = { selectedNearbyPeer = null },
            )
            else -> PlaceholderDetailPane()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundApp),
    ) {
        if (twoPane) {
            // Expanded: sidebar on the left edge, list+detail in the remainder.
            Row(Modifier.fillMaxSize()) {
                DesktopSideBar(
                    tabs = DESKTOP_SIDE_TABS,
                    selectedTab = nav.current.destination,
                    onTabSelected = nav::selectTab,
                )
                DesktopTwoPane(
                    listPane = listPaneContent,
                    detailPane = detailPaneContent,
                    sizeClass = sizeClass,
                )
            }
        } else {
            // Compact/Medium: the Phase 21 single-pane layout with the hanging bottom nav.
            listPaneContent()
            if (FlashNavigationMath.isTabRoot(nav.current.destination)) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    FlashBottomNav(
                        items = DESKTOP_TABS,
                        selectedTab = nav.current.destination,
                        onTabSelected = nav::selectTab,
                        onTabReselected = { destination ->
                            val listState = when (destination) {
                                FlashDestination.ChatList -> chatListScroll
                                FlashDestination.Transfers -> transfersScroll
                                FlashDestination.NearbyDevices -> nearbyScroll
                                FlashDestination.Settings -> settingsScroll
                                else -> null
                            }
                            listState?.let { state ->
                                scope.launch { state.animateScrollToItem(0) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Desktop local equivalent of `:app`'s `FlashTransportType.toUiTransport()`. */
private fun FlashTransportType.toDesktopTransport(): FlashNetworkTransport = when (this) {
    FlashTransportType.WIFI_DIRECT -> FlashNetworkTransport.WifiDirect
    FlashTransportType.RELAY, FlashTransportType.MESH -> FlashNetworkTransport.Relay
    FlashTransportType.UNKNOWN -> FlashNetworkTransport.Unknown
    FlashTransportType.LAN, FlashTransportType.WEBSOCKET -> FlashNetworkTransport.Lan
}

/** Desktop inline mapper — replaces `:app`'s `TransfersUiMapper.toUiItem` (Option B). */
private fun FlashTransfer.toDesktopTransferItemUi(): FlashTransferItemUi = FlashTransferItemUi(
    id = id.value,
    fileName = fileName,
    direction = when (direction) {
        DomainDirection.Sending -> FlashTransferDirection.Send
        DomainDirection.Receiving -> FlashTransferDirection.Receive
    },
    peerName = peerName,
    bytesTotal = bytesTotal,
    bytesDone = bytesDone,
    state = when (state) {
        DomainState.Offered -> FlashTransferState.Offered
        DomainState.Queued -> FlashTransferState.Queued
        DomainState.Transferring, DomainState.Verifying -> FlashTransferState.Active
        DomainState.Paused -> FlashTransferState.Paused
        DomainState.Completed -> FlashTransferState.Completed
        DomainState.Failed, DomainState.Cancelled -> FlashTransferState.Failed
    },
    speedBytesPerSec = speedBytesPerSec,
    etaSeconds = etaSeconds.takeIf { it > 0L },
    errorMessage = errorMessage ?: if (state == DomainState.Cancelled) "Cancelled" else null,
    verified = state == DomainState.Completed,
    localPath = localPath ?: sourceUri,
    retryable = state != DomainState.Cancelled,
)

/** Desktop twin of `:app`'s `TransfersUiState.fromDomain` (mandatory boot flags, ERROR-034). */
private fun TransfersUiState.Companion.fromDomain(
    transfers: List<FlashTransfer>,
    isLoading: Boolean,
    isError: Boolean,
): TransfersUiState = TransfersUiState.fromItems(transfers.map { it.toDesktopTransferItemUi() })
    .copy(isLoading = isLoading, isError = isError)

private val DESKTOP_TABS = listOf(
    FlashBottomNavItem(FlashDestination.ChatList, FlashIcons.Chat, "Chats"),
    FlashBottomNavItem(FlashDestination.Transfers, FlashIcons.Transfer, "Transfers"),
    FlashBottomNavItem(FlashDestination.NearbyDevices, FlashIcons.Nearby, "Nearby"),
    FlashBottomNavItem(FlashDestination.Settings, FlashIcons.Settings, "Settings"),
)

/** Same pacing window the app shell derives from the theme's animation duration. */
private const val FLASH_TRANSFERS_THROTTLE_MS = 75L

/** UI-046: the shell bar's content inset. No system navigation bar on desktop, so no inset add. */
private val FLASH_BOTTOM_NAV_INSET = 72.dp

/**
 * Local copy of `:app`'s `UiPacing.throttleLatest` — leading edge first, upstream suspended
 * for the window, newest value always eventually arrives. `:app`'s copy is `internal` to that
 * module and `:core:messaging`'s is `internal` to that one; both KDocs explain why no shared
 * home exists (a published ABI for an app-internal operator). `:desktop` is a third consumer
 * with the same reasoning, so it takes the same deal: a third copy, semantics identical to the
 * other two (change one, change all).
 */
private fun <T> Flow<T>.throttleLatestDesktop(windowMs: Long): Flow<T> = flow {
    if (windowMs <= 0L) {
        collect { emit(it) }
        return@flow
    }
    collect { value ->
        emit(value)
        delay(windowMs)
    }
}
