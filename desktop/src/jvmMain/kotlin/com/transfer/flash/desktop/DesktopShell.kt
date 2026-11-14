@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.ui.shims.rememberFlashFilePickerLauncher
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.dnd.DropTargetListener
import java.io.File
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatSearchResultsUi
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.FlashTrustedPeer
import com.transfer.flash.core.engine.group.GroupFileSender
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection as DomainDirection
import com.transfer.flash.core.transfer.model.FlashTransferState as DomainState
import com.transfer.flash.core.common.result.getOrNull
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashWindowSizeClass
import com.transfer.flash.core.ptt.floor.PttFloorState
import com.transfer.flash.ui.calling.FlashCallScreen
import com.transfer.flash.ui.calling.FlashCallShareHost
import com.transfer.flash.ui.calling.PttSessionOverlayContent
import com.transfer.flash.ui.calling.pttPressOutcomeMessage
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.chat.FlashSearchScope
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.ui.chat.FlashBrowseOrgGroupsSheet
import com.transfer.flash.ui.chat.FlashCreateGroupSheet
import com.transfer.flash.ui.chat.FlashCreateGroupPeerUi
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.chat.FlashSharePayloadUi
import com.transfer.flash.ui.chat.FlashShareItemUi
import com.transfer.flash.ui.chat.FlashShareRecipientUi
import com.transfer.flash.ui.chat.FlashShareTargetSheet
import com.transfer.flash.ui.chat.FlashShareTargetMath
import com.transfer.flash.ui.chat.FlashEncryptionBadgeState
import com.transfer.flash.ui.chat.FlashEncryptionSheet
import com.transfer.flash.ui.chat.FlashPairingDialog
import com.transfer.flash.ui.chat.FlashSheetHost
import com.transfer.flash.ui.nearby.FlashManualConnectDialog
import com.transfer.flash.ui.settings.FlashThemeMode
import com.transfer.flash.ui.settings.FlashDisplayNameDialog
import com.transfer.flash.ui.chat.FlashPairingRequestUi
import com.transfer.flash.ui.navigation.FlashAnimatedScreen
import com.transfer.flash.ui.navigation.FlashBackStackState
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath
import com.transfer.flash.ui.navigation.FlashNavigationState
import com.transfer.flash.ui.navigation.rememberFlashNavigationState
import com.transfer.flash.ui.nearby.FlashNearbyMath
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.nearby.NearbyIdentityUi
import com.transfer.flash.ui.nearby.NearbyNetworkScan
import com.transfer.flash.ui.nearby.NearbyScanBlock
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.nearby.NearbyTrustedPeerUi
import com.transfer.flash.ui.nearby.NearbyUiState
import com.transfer.flash.core.network.sweep.SweepRefusal
import com.transfer.flash.core.network.sweep.SweepState
import com.transfer.flash.ui.settings.FlashHubStatusMath
import com.transfer.flash.ui.settings.FlashSettingsModel
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.shell.FlashBottomNav
import com.transfer.flash.ui.shell.FlashShellTabs
import com.transfer.flash.ui.shell.FlashBottomNavItem
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.transfers.FlashTransferDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.FlashTransfersScreen
import com.transfer.flash.ui.transfers.TransfersUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
public fun DesktopShell(
    engine: DesktopEngine,
    /**
     * The Appearance selection, owned by the caller because `FlashTheme` wraps this composable — the
     * state has to live above the theme it selects, so it cannot be held here.
     */
    themeMode: FlashThemeMode,
    onThemeModeSelected: (FlashThemeMode) -> Unit,
    window: java.awt.Window? = null,
    nav: FlashNavigationState = rememberFlashNavigationState(),
    trayNavTick: Int = 0,
    externalShareFiles: List<java.io.File> = emptyList(),
    onClearExternalShareFiles: () -> Unit = {},
    /** UI-058 H9: a Hub route (`hub/announcement/<id>`, `hub/person/<deviceId>`) to open, set at launch or by a notification. */
    pendingHubRoute: kotlinx.coroutines.flow.MutableStateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null),
    /** Application-owned in DesktopMain: a tab or tray transition must not recreate a stored-ON radio. */
    radioGatewayController: com.transfer.flash.ui.radio.RadioGatewayController = remember(engine) {
        com.transfer.flash.desktop.radio.DesktopRadioGatewayController(
            scope = engine.scope,
            onManagerChanged = com.transfer.flash.desktop.radio.DesktopRadioManagers::publish,
        )
    },
) {
    val scope = rememberCoroutineScope()

    val ready by engine.ready.collectAsState()
    val startError by engine.startError.collectAsState()
    // The display name is observed, not read: a rename has to reach Settings, the sidebar and Nearby at once.
    val localFriendlyName by engine.localFriendlyNameState.collectAsState()

    // One snackbar surface for the whole window. Declared here, above the collectors that raise
    // messages, so nothing depends on declaration order.
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Phase 22: window size + selection state for the two-pane layout ──
    val sizeClass = rememberFlashDesktopWindowSize(window)
    val useNavRail = sizeClass != FlashWindowSizeClass.Compact
    val twoPane = FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)
    var selectedTransferId by remember { mutableStateOf<String?>(null) }
    var selectedNearbyPeerId by remember { mutableStateOf<String?>(null) }
    var selectedChatConversationId by remember { mutableStateOf<String?>(null) }
    var showRadioGateway by remember { mutableStateOf(false) }

    LaunchedEffect(nav.current.conversationId) {
        val cid = nav.current.conversationId
        if (cid != null) {
            selectedChatConversationId = cid
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
        if (nav.current.destination != FlashDestination.Settings) {
            showRadioGateway = false
        }
        if (nav.current.destination == FlashDestination.Hub) {
            engine.requestOrgSync("hub_tab_opened")
        }
    }
    LaunchedEffect(trayNavTick) {
        if (trayNavTick > 0) {
            selectedChatConversationId = null
            selectedTransferId = null
            selectedNearbyPeerId = null
            showRadioGateway = false
        }
    }

    // ── Chats: the real repository once boot builds it, honest-empty before that ──
    // Keyed on `ready` (not just `engine`): `engine.chats` swaps from the empty stand-in
    // to the durable repository during `assemble()`, and a `remember(engine)` alone would
    // pin whichever one was current at first composition — the empty one — forever.
    val chatRepository = remember(engine, ready) { engine.chats }
    val openConversationEntry = when {
        nav.current.destination == FlashDestination.Conversation -> nav.current.conversationId
        twoPane && nav.current.destination == FlashDestination.ChatList -> selectedChatConversationId
        else -> null
    }
    var lastOpenConversationEntry by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openConversationEntry) {
        if (openConversationEntry == null && lastOpenConversationEntry != null) {
            chatRepository.closeConversation()
        }
        lastOpenConversationEntry = openConversationEntry
    }
    val chatListState by chatRepository.chatListState.collectAsState()
    val totalUnreadCount = remember(chatListState.items) {
        chatListState.items.sumOf { it.unreadCount }
    }
    // The conversation screen's whole state. Before boot the repository is still the honest
    // empty stand-in, so the screen renders its empty state rather than nothing.
    val repositoryConversation by chatRepository.conversationState.collectAsState()

    // ── Search state (Item 1) ──
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var messageBodyMatches by remember { mutableStateOf<Set<String>>(emptySet()) }
    var colleagueMatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // UI-024 upgrade: structured hits + active scope, plus the persisted recent-search chips. The
    // flow is remembered so the collection is not restarted on every recomposition.
    var searchResults by remember { mutableStateOf(FlashChatSearchResultsUi.Empty) }
    var searchScope by remember { mutableStateOf(FlashSearchScope.All) }
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
        delay(200)
        val structured = chatRepository.searchChats(q)
        searchResults = structured
        messageBodyMatches = structured.conversationIds
        colleagueMatchIds = chatRepository.searchColleagues(q)
    }

    // ── Group creation (Item 3) ──
    var showCreateGroup by remember { mutableStateOf(false) }
    var showBrowseOrgGroups by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf(false) }
    var activeJoinLink by remember { mutableStateOf<String?>(null) }

    // ── Calls, 33a: the shared coordinator behind the shared overlay ──
    // `calls` is null until assemble builds it; the empty flow keeps this collect
    // unconditional (same slot-table reasoning as the chats line above).
    val calls = remember(engine, ready) { engine.calls }
    val activeCall by remember(calls) {
        calls?.activeCall ?: MutableStateFlow(null)
    }.collectAsState()
    var dismissedEndedCallId by remember { mutableStateOf<String?>(null) }
    val currentCall = activeCall
    LaunchedEffect(currentCall?.callId, currentCall?.state) {
        if (currentCall == null ||
            currentCall.callId != dismissedEndedCallId ||
            currentCall.state != com.transfer.flash.core.calling.model.FlashCallState.ENDED
        ) {
            dismissedEndedCallId = null
        }
    }
    val ringingCall = currentCall?.takeUnless {
        it.state == com.transfer.flash.core.calling.model.FlashCallState.ENDED &&
            it.callId == dismissedEndedCallId
    }
    // ── Push-to-talk (UI-051 addendum A, ADR-058) ──
    // `engine.ptt` is null until assemble builds it, so it is keyed on `ready` like `calls`. The state
    // flow is read here only for "is a session live"; the card reads it again for role and name.
    val ptt = remember(engine, ready) { engine.ptt }
    val pttState by remember(ptt) {
        ptt?.state ?: MutableStateFlow<PttFloorState>(PttFloorState.Idle)
    }.collectAsState()
    val pttActive = pttState !is PttFloorState.Idle
    LaunchedEffect(ptt, snackbarHostState) {
        // Same `collectLatest` reasoning as the pairing messages below: newest notice wins.
        ptt?.notices?.collectLatest { notice ->
            snackbarHostState.showSnackbar(message = notice, duration = SnackbarDuration.Short)
        }
    }
    // One entry for the rail button, the compact floating button and Ctrl+Shift+T: toggle the floor and
    // say why when the engine refuses.
    val onPttToggle: () -> Unit = {
        val message = if (ptt == null) {
            "Flash is starting — try again"
        } else {
            pttPressOutcomeMessage(ptt.onPttButton())
        }
        if (message != null) {
            scope.launch { snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short) }
        }
    }
    val ongoingGroupCalls by (calls?.ongoingGroupCalls
        ?: remember { MutableStateFlow(emptyMap<String, com.transfer.flash.core.calling.model.OngoingGroupCallUi>()) }
        ).collectAsState()
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
    val selectedTransferItem = resolveSelectedTransferItem(selectedTransferId, transfersUi)
    LaunchedEffect(transfersUi.isLoading, selectedTransferId, selectedTransferItem) {
        if (!transfersUi.isLoading && selectedTransferId != null && selectedTransferItem == null) {
            selectedTransferId = null
        }
    }

    // ── Nearby: discovery endpoints + trust store + the Phase 26-3 pairing dialog ──
    // Never touch a synchronized lazy from composition before boot has warmed it.
    val pairing = remember(engine, ready) { engine.readyPairing }
    val pairingUi by remember(pairing) {
        pairing?.pairing ?: MutableStateFlow<FlashPairingCoordinator.PairingUi?>(null)
    }.collectAsState()
    var lastPairingPeer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lastPairingFailureMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairing, snackbarHostState) {
        pairing?.messages?.collectLatest { message ->
            FlashLog.i(TAG_PAIRING, message)
            if (message.startsWith("The other device is running") ||
                message.startsWith("Pairing stopped:") ||
                message.startsWith("Too many pairing attempts")
            ) {
                lastPairingFailureMessage = message
            }
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(pairingUi?.phase) {
        val mapped = pairingPhaseOf(pairingUi?.phase)
        if (mapped != FlashPairingPhase.Failed) {
            lastPairingFailureMessage = null
        }
    }
    LaunchedEffect(engine, snackbarHostState) {
        engine.onJoinRequestNotification = { _, groupTitle, requesterName ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "$requesterName wants to join $groupTitle",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    // The pairing DIALOG is driven entirely by this one value, so when it fails to appear there is
    // otherwise no way to tell "the engine never published a phase" from "the shell never re-read
    // it" — and that ambiguity is the whole of the 2026-09-14 report ("the request shows on the
    // phone, no dialog on the PC"). Printed on every change, like the dial lines: the next report
    // is a log line instead of an absence.
    LaunchedEffect(pairing) {
        pairing?.pairing?.collect { ui ->
            // FlashLog, not println: this must land in `~/.flash/desktop.log`, which is the artifact
            // that survives a Gradle console rewrite.
            com.transfer.flash.core.common.logging.FlashLog.i(
                "PAIRING",
                "[shell] engine pairing state: phase=${ui?.phase} code=${ui?.numericCode} " +
                    "secondsLeft=${ui?.secondsLeft}",
            )
        }
    }

    // Pairing UI phase mapping (the app's PairingUiMapper, re-stated here because :app is not a
    // dependency of :desktop). It is a FUNCTION, called INSIDE the `derivedStateOf` below, and that
    // is load-bearing rather than stylistic — see the comment at its call site.
    val fallbackEndpoints = remember { MutableStateFlow(emptyList<FlashDiscoveredEndpoint>()) }
    val fallbackDiscoveryState = remember { MutableStateFlow(FlashDiscoveryState()) }
    val discoveredEndpoints by (engine.discovery?.discoveredEndpoints ?: fallbackEndpoints).collectAsState()
    val discoveryState by (engine.discovery?.state ?: fallbackDiscoveryState).collectAsState()
    val fallbackActiveSessions = remember { MutableStateFlow(emptyMap<com.transfer.flash.core.common.model.FlashDeviceId, com.transfer.flash.core.network.FlashSession>()) }
    val activeSessions by (engine.network?.activeSessions ?: fallbackActiveSessions).collectAsState()
    // DR3: the manual "Scan network" outcome, read by the `nearby` derivation below.
    val sweepState by engine.sweepState.collectAsState()
    // From the pairing coordinator's flow, NOT a `derivedStateOf` over the trust store: that store
    // is a plain ConcurrentHashMap with no snapshot state, so a derivation read none of it — it
    // computed once at first composition and never invalidated, leaving a just-paired peer showing
    // Pair (and a revoked one showing Chat) for the life of the window.
    // Mapped at this edge: the coordinator is now shared (`:core:security`) and publishes its own
    // `FlashTrustedPeer`, so a Compose-era UI type must not be part of its published API. A plain
    // map rather than a `derivedStateOf` — it is a short list built from an already-collected state,
    // and the extra state object would only add a way to go stale.
    val trustedPeersByCoordinator by remember(pairing) {
        pairing?.trustedPeers ?: MutableStateFlow<List<FlashTrustedPeer>>(emptyList())
    }.collectAsState()

    // ── Every input is read HERE, in the lambda, and passed in ────────────────────────────────────
    // The body is a call to a pure function so that this cannot go wrong a third time. It has gone
    // wrong twice on this screen, the same way both times:
    //
    //  - `pairingPhase` was a plain `val` computed above this `derivedStateOf` and captured by value,
    //    so it froze at the composition that CREATED the remembered derived state. The dialog was
    //    handed a fresh request and a permanently `Idle` phase, and rendered nothing. Measured:
    //    `[pairing-diag] dialog body: request=true phase=Idle visible=false` next to
    //    `[shell] engine pairing state: phase=RequestReceived code=856950`.
    //  - the trusted ROW list was the same mistake, and it hid a peer outright. `trustedIds` was read
    //    inside (tracked, live) while the rows were captured (frozen, empty) — so a peer whose id had
    //    just entered `trustedIds` was filtered OUT of `peers` and was simultaneously absent from
    //    `trustedPeers`. The device left both lists at once, which is the "trusted peer doesn't
    //    appear after accepting" report.
    //
    // The rule: a `derivedStateOf` tracks snapshot state, and a plain `val` computed above it is a
    // CONSTANT from the derived state's point of view. Passing every argument at the call site makes
    // that structural rather than remembered — there is no enclosing `val` left to capture. The body
    // lives in `nearbyUiStateOf` below; being pure, its invariant is unit-tested
    // (DesktopNearbyStateTest) instead of being checked by eye in a live run.
    var localLanAddresses by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(ready, discoveredEndpoints.size, activeSessions.size, sweepState, nav.current.destination) {
        while (true) {
            val addrs = withContext(Dispatchers.IO) {
                com.transfer.flash.core.network.sweep.JvmLocalSubnets.lanSubnets()
                    .map { it.address }
                    .distinct()
            }
            localLanAddresses = addrs
            if (nav.current.destination != FlashDestination.NearbyDevices) break
            delay(4_000L)
        }
    }
    val radiosAvailable = !ready ||
        localLanAddresses.isNotEmpty() ||
        discoveredEndpoints.isNotEmpty() ||
        activeSessions.isNotEmpty()

    val nearby by remember(engine, engine.discovery, activeSessions) {
        derivedStateOf {
            nearbyUiStateOf(
                trusted = trustedPeersByCoordinator,
                discovered = discoveredEndpoints,
                discoveryState = discoveryState,
                ui = pairingUi,
                ready = ready,
                localFriendlyName = localFriendlyName,
                localDeviceId = engine.localDeviceId,
                liveSessions = activeSessions.size,
                scan = sweepState.toNearbyScan(),
                isOrgPeer = { peerId -> engine.hasAuthenticatedOrgSession(peerId) },
                orgName = engine.enterpriseTrustStore.getCredentials()?.hubName,
                localAddressLabel = localLanAddresses.firstOrNull(),
                radiosAvailable = radiosAvailable,
                failureReason = lastPairingFailureMessage,
            )
        }
    }

    // ── Conversation header: derived from what the desktop actually knows about the peer ──
    // Declared HERE, after the Nearby section, because it reads the trust list and the discovery
    // roster. The repository's own header cannot know the desktop's trust names, so opening
    // a chat from Nearby would show a blank name and no online dot for a peer whose name the
    // desktop is holding in its trust store the whole time. See `desktopConversationHeader` for exactly what is filled in and why the call
    // actions stay hidden.
    // Trust is a plain lookup against the list the Nearby rows use, so the two screens cannot
    // disagree about whether a peer is paired.
    val trustedPeerRoster = remember(trustedPeersByCoordinator) {
        trustedPeersByCoordinator.map { trusted ->
            val initials = trusted.name.trim().split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
                .take(2)
                .map { it.first().uppercaseChar().toString() }
                .joinToString("")
                .ifBlank { "?" }
            FlashCreateGroupPeerUi(
                id = trusted.id,
                name = trusted.name,
                initials = initials,
            )
        }
    }

    var pendingDesktopShare by remember { mutableStateOf<FlashSharePayloadUi?>(null) }
    var pendingDesktopShareRecipient by remember { mutableStateOf<Pair<String, String>?>(null) }
    var isPairingForDesktopShare by remember { mutableStateOf(false) }
    var showDesktopShareManualConnect by remember { mutableStateOf(false) }

    LaunchedEffect(externalShareFiles) {
        if (externalShareFiles.isNotEmpty()) {
            val shareItems = externalShareFiles.flatMap { file ->
                if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile }.map { f ->
                        FlashShareItemUi(
                            uri = f.toURI().toString(),
                            name = file.name + "/" + f.relativeTo(file).path.replace('\\', '/'),
                            sizeBytes = f.length(),
                            mimeType = DesktopHelpers.guessMimeType(f.name),
                        )
                    }.toList()
                } else {
                    listOf(
                        FlashShareItemUi(
                            uri = file.toURI().toString(),
                            name = file.name,
                            sizeBytes = file.length(),
                            mimeType = DesktopHelpers.guessMimeType(file.name),
                        )
                    )
                }
            }
            if (shareItems.isNotEmpty()) {
                val existing = pendingDesktopShare?.items ?: emptyList()
                pendingDesktopShare = FlashSharePayloadUi(
                    items = (existing + shareItems).distinctBy { it.uri },
                    text = null,
                )
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = EMPTY_FOLDER_SHARE_MESSAGE,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
            onClearExternalShareFiles()
        }
    }

    val desktopPairedRecipients = remember(trustedPeersByCoordinator, discoveredEndpoints, activeSessions) {
        val discoveredMap = discoveredEndpoints.associateBy { it.deviceId.value }
        val sessionPeerIds = activeSessions.keys.map { it.value }.toSet()
        trustedPeersByCoordinator.map { trusted ->
            val endpoint = discoveredMap[trusted.id]
            val isOnline = endpoint != null || sessionPeerIds.contains(trusted.id)
            FlashShareRecipientUi(
                id = trusted.id,
                name = trusted.name,
                initials = FlashShareTargetMath.initialsFor(trusted.name),
                isOnline = isOnline,
                isGroup = false,
                isPaired = true,
                transport = endpoint?.transportType?.toDesktopTransport() ?: FlashNetworkTransport.Lan,
                deviceKind = FlashDeviceKind.UNKNOWN,
                subtitle = if (isOnline) null else "Offline",
            )
        }
    }

    val desktopNearbyRecipients = remember(discoveredEndpoints, trustedPeersByCoordinator) {
        val trustedIds = trustedPeersByCoordinator.mapTo(HashSet()) { it.id }
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
                    transport = ep.transportType.toDesktopTransport(),
                    deviceKind = ep.deviceKind,
                    subtitle = "Available nearby",
                )
            }
            .distinctBy { it.id }
    }

    val desktopRecentChatRecipients = remember(chatListState.items) {
        chatListState.items.take(5).map { chat ->
            FlashShareRecipientUi(
                id = chat.id,
                name = chat.title,
                initials = chat.avatarInitials,
                isOnline = chat.presence == FlashPeerPresence.Online,
                isGroup = chat.isGroup,
                isPaired = true,
                transport = FlashNetworkTransport.Lan,
                deviceKind = FlashDeviceKind.UNKNOWN,
                subtitle = if (chat.isGroup) "Group chat" else null,
            )
        }
    }

    // ── Conversation header: derived from what the desktop actually knows about the peer ──
    // Declared HERE, after the Nearby section, because it reads the trust list and the discovery
    // roster. The repository's own header cannot know the desktop's trust names, so opening
    // a chat from Nearby would show a blank name and no online dot for a peer whose name the
    // desktop is holding in its trust store the whole time. See `desktopConversationHeader` for exactly what is filled in and why the call
    // actions stay hidden.
    // Trust is a plain lookup against the list the Nearby rows use, so the two screens cannot
    // disagree about whether a peer is paired.
    val activeConversationId = nav.current.conversationId
        ?: if (twoPane && nav.current.destination == FlashDestination.ChatList) selectedChatConversationId else null
    val trustedConversationPeer = trustedPeersByCoordinator.firstOrNull { it.id == activeConversationId }
    val conversationIdIsTrusted = trustedConversationPeer != null
    val conversationIdIsVerified = trustedConversationPeer?.verified == true

    val conversationState = remember(
        repositoryConversation,
        activeConversationId,
        trustedPeersByCoordinator,
        discoveredEndpoints,
        ongoingGroupCalls,
        activeSessions,
    ) {
        val convId = activeConversationId
        val base = repositoryConversation
        if (convId == null) {
            base
        } else if (base.header.isGroup) {
            val ongoing = ongoingGroupCalls[convId]
            if (ongoing != null) {
                base.copy(
                    ongoingCall = com.transfer.flash.core.messaging.model.FlashActiveGroupCallBarUi(
                        callId = ongoing.callId,
                        callerName = ongoing.initiatorId,
                        video = ongoing.video,
                        participantCount = ongoing.participantCount,
                    ),
                )
            } else {
                base
            }
        } else {
            // ERROR-035: Direct chat presence & transport MUST come from the repository's live session
            // tracking (RealFlashChatRepository.displayedPresence), NOT from whether an mDNS beacon exists
            // in discovery. A peer with an mDNS endpoint but no active WebSocket session is offline in chat.
            // When an active session is established with the peer, presence resolves to Online.
            val isDirectPeerOnline = activeSessions.containsKey(com.transfer.flash.core.common.model.FlashDeviceId(convId))
            val isEncrypted = base.header.isEncrypted || (engine.trust.getSessionKey(convId) != null)
            val isColleague = engine.isKnownColleague(convId) || engine.isOrgPeer(convId)
            val fallback = desktopConversationHeader(
                conversationId = convId,
                trusted = trustedPeersByCoordinator,
                discovered = discoveredEndpoints,
                isEncrypted = isEncrypted,
                presence = base.header.presence,
                transport = base.header.transport,
                hasActiveSession = isDirectPeerOnline,
                isColleague = isColleague,
                orgName = engine.enterpriseTrustStore.getCredentials()?.hubName,
            )
            val resolvedTitle = if (base.header.title.isNotBlank() && base.header.title != convId && base.header.title != "Select a conversation") {
                base.header.title
            } else {
                fallback?.title ?: base.header.title
            }
            val resolvedInitials = if (base.header.avatarInitials.isNotBlank() && base.header.avatarInitials != "—") {
                base.header.avatarInitials
            } else {
                fallback?.avatarInitials ?: base.header.avatarInitials
            }
            val resolvedPresence = when {
                base.header.presence == FlashPeerPresence.Typing -> FlashPeerPresence.Typing
                base.header.presence == FlashPeerPresence.Online -> FlashPeerPresence.Online
                isDirectPeerOnline -> FlashPeerPresence.Online
                base.header.presence == FlashPeerPresence.Connecting -> FlashPeerPresence.Connecting
                else -> fallback?.presence ?: base.header.presence
            }
            val resolvedTransport = if (isDirectPeerOnline && base.header.transport == FlashNetworkTransport.Unknown) {
                FlashNetworkTransport.Lan
            } else if (base.header.transport != FlashNetworkTransport.Unknown) {
                base.header.transport
            } else {
                fallback?.transport ?: base.header.transport
            }
            val isColleaguePeer = base.header.isColleague || (fallback?.isColleague == true)
            val offLanCallReady = engine.enterpriseHub?.let { hub ->
                val cfg = hub.validCallIceConfig()
                hub.relay.state.value.canSend &&
                    cfg != null &&
                    cfg.offLanCallingSupported &&
                    cfg.iceServers.isNotEmpty()
            } == true
            val canShowCallActions = base.header.showCallActions &&
                (isDirectPeerOnline || (!isColleaguePeer && resolvedTransport != FlashNetworkTransport.Relay) || offLanCallReady)
            val header = base.header.copy(
                title = resolvedTitle,
                avatarInitials = resolvedInitials,
                avatarSeed = resolvedTitle,
                // Truthful presence & transport from RealFlashChatRepository joined with live active sessions
                presence = resolvedPresence,
                transport = resolvedTransport,
                typingMemberNames = base.header.typingMemberNames,
                isEncrypted = isEncrypted,
                showCallActions = canShowCallActions,
                isColleague = isColleaguePeer,
                orgName = base.header.orgName ?: fallback?.orgName,
            )
            base.copy(header = header)
        }
    }

    fun placeVoiceCall(
        peerId: String,
        peerName: String,
        isGroup: Boolean = conversationState.header.isGroup,
    ) {
        scope.launch {
            val ok = if (isGroup) {
                val memberIds = if (conversationState.members.isNotEmpty()) {
                    conversationState.members.map { it.id }
                } else {
                    chatRepository.groupMembers(peerId).map { it.id }
                }
                calls?.startGroupCall(
                    groupId = peerId,
                    groupName = peerName,
                    memberIds = memberIds,
                    video = false,
                ) == true
            } else {
                calls?.startCall(peerId, peerName, video = false) == true
            }
            if (!ok) {
                snackbarHostState.showSnackbar(
                    message = "Couldn't start the call. Make sure the peer is reachable, then try again.",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    fun placeVideoCall(
        peerId: String,
        peerName: String,
        isGroup: Boolean = conversationState.header.isGroup,
    ) {
        scope.launch {
            val ok = if (isGroup) {
                val memberIds = if (conversationState.members.isNotEmpty()) {
                    conversationState.members.map { it.id }
                } else {
                    chatRepository.groupMembers(peerId).map { it.id }
                }
                calls?.startGroupCall(
                    groupId = peerId,
                    groupName = peerName,
                    memberIds = memberIds,
                    video = true,
                ) == true
            } else {
                calls?.startCall(peerId, peerName, video = true) == true
            }
            if (!ok) {
                snackbarHostState.showSnackbar(
                    message = "Couldn't start the video call. Make sure the peer is reachable, then try again.",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    fun sendFileToPeer(
        peerId: String,
        peerName: String,
        isGroup: Boolean,
        uri: String,
        displayName: String,
        size: Long,
        mimeType: String = DesktopHelpers.guessMimeType(displayName),
        voiceDurationMs: Long = 0L,
        voiceAmplitudes: List<Int> = emptyList(),
    ) {
        val transfers = engine.transfers ?: return
        if (isGroup) {
            val groupFileSender = GroupFileSender(
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
                announce = { groupId, recipientDeviceId, messageId, transferId, wireFileId, fileName, mime, sizeBytes, root, pieceSize, swarm, rootSig ->
                    chatRepository.beginGroupAttachment(
                        groupId = groupId,
                        recipientDeviceId = recipientDeviceId,
                        messageId = messageId,
                        transferId = transferId,
                        wireFileId = wireFileId,
                        fileName = fileName,
                        mimeType = mime,
                        sizeBytes = sizeBytes,
                        root = root,
                        pieceSize = pieceSize,
                        swarm = swarm,
                        rootSig = rootSig,
                    )
                },
                sendFile = { targetDevice, fileUri, fileName, sizeBytes, transferId, wireFileId ->
                    transfers.sendFile(
                        targetDevice,
                        fileUri,
                        fileName,
                        sizeBytes,
                        transferId = transferId,
                        wireFileId = wireFileId,
                    )
                },
                sendGroupAttachment = { groupId, messageId, transferId, fileName, mime, sizeBytes, localPath, dur, amps ->
                    chatRepository.sendGroupAttachment(
                        conversationId = groupId,
                        messageId = messageId,
                        transferId = transferId,
                        fileName = fileName,
                        mimeType = mime,
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
            scope.launch(Dispatchers.IO) {
                groupFileSender.send(
                    groupId = peerId,
                    uri = uri,
                    displayName = displayName,
                    sizeBytes = size,
                    mimeType = mimeType,
                    voiceDurationMs = voiceDurationMs,
                    voiceAmplitudes = voiceAmplitudes,
                )
            }
        } else {
            val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
            val targetDevice = FlashDevice(
                id = FlashDeviceId(peerId),
                friendlyName = peerName,
                transportType = endpoint?.transportType ?: FlashTransportType.LAN,
            )
            scope.launch(Dispatchers.IO) {
                val sendResult = transfers.sendFile(targetDevice, uri, displayName, size)
                if (sendResult is com.transfer.flash.core.common.result.FlashResult.Success) {
                    chatRepository.sendAttachment(
                        conversationId = peerId,
                        transferId = sendResult.value.value,
                        fileName = displayName,
                        mimeType = mimeType,
                        sizeBytes = size,
                        localPath = uri,
                        voiceDurationMs = voiceDurationMs,
                        voiceAmplitudes = voiceAmplitudes,
                    )
                }
            }
        }
    }

    // Forward a message to one or more chats. Every target is addressed by its own id (groups go through
    // the group sender, paired peers get a direct transfer, text goes to the target chat, not to the open
    // one) and nothing navigates, so several targets all receive it. Pairing is interactive and cannot be
    // run once per target, so an unpaired one is skipped and named.
    fun forwardToChats(targets: List<FlashShareRecipientUi>, payload: FlashSharePayloadUi) {
        val (ready, unpaired) = targets.distinctBy { it.id }.partition { target ->
            target.isGroup || trustedPeersByCoordinator.any { it.id == target.id }
        }
        ready.forEach { target ->
            payload.items.forEach { item ->
                sendFileToPeer(
                    peerId = target.id,
                    peerName = target.name,
                    isGroup = target.isGroup,
                    uri = item.uri,
                    displayName = item.name,
                    size = item.sizeBytes,
                    mimeType = item.mimeType.takeIf { it.isNotBlank() && it != "*/*" }
                        ?: DesktopHelpers.guessMimeType(item.name),
                )
            }
            payload.text?.takeIf { it.isNotBlank() }?.let { chatRepository.sendTextTo(target.id, it) }
        }
        if (unpaired.isNotEmpty()) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Not sent to ${unpaired.joinToString { it.name }}: pair with them first",
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    fun clearPendingDesktopSharePairing() {
        if (isPairingForDesktopShare || pendingDesktopShareRecipient != null) {
            pendingDesktopShareRecipient = null
            isPairingForDesktopShare = false
            pendingDesktopShare = null
        }
    }

    LaunchedEffect(nearby.pairingPhase, trustedPeersByCoordinator) {
        val target = pendingDesktopShareRecipient ?: return@LaunchedEffect
        val (targetDeviceId, targetDeviceName) = target
        val isNowTrusted = trustedPeersByCoordinator.any { it.id == targetDeviceId }
        val isPairedPhase = nearby.pairingPhase == FlashPairingPhase.Paired

        if (isNowTrusted || isPairedPhase) {
            pendingDesktopShareRecipient = null
            isPairingForDesktopShare = false
            val share = pendingDesktopShare
            if (share != null) {
                pendingDesktopShare = null
                selectedChatConversationId = targetDeviceId
                chatRepository.openConversation(targetDeviceId)
                nav.navigate(FlashDestination.Conversation, conversationId = targetDeviceId)
                share.items.forEach { item ->
                    sendFileToPeer(
                        peerId = targetDeviceId,
                        peerName = targetDeviceName,
                        isGroup = false,
                        uri = item.uri,
                        displayName = item.name,
                        size = item.sizeBytes,
                        mimeType = item.mimeType,
                    )
                }
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = if (share.items.size == 1) "Paired! Sending ${share.items[0].name} to $targetDeviceName" else "Paired! Sending ${share.items.size} files to $targetDeviceName",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
        // Preserve pendingDesktopShare and pendingDesktopShareRecipient across Declined/Expired/Failed
        // so tapping "Try again" in FlashPairingDialog delivers the share if retry succeeds. They are
        // cleared only when pairing succeeds or the user explicitly closes/declines the pairing dialog.
    }

    val generalFilePicker = rememberFlashFilePickerLauncher { picked ->
        val peerId = activeConversationId
        if (peerId != null) {
            sendFileToPeer(
                peerId = peerId,
                peerName = conversationState.header.title,
                isGroup = conversationState.header.isGroup,
                uri = picked.uri,
                displayName = picked.name,
                size = picked.size,
            )
        }
    }

    var nearbySendFileTarget by remember { mutableStateOf<NearbyPeerUi?>(null) }
    val nearbyFilePicker = rememberFlashFilePickerLauncher { picked ->
        val target = nearbySendFileTarget ?: return@rememberFlashFilePickerLauncher
        sendFileToPeer(
            peerId = target.id,
            peerName = target.name,
            isGroup = false,
            uri = picked.uri,
            displayName = picked.name,
            size = picked.size,
        )
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "Sending ${picked.name} to ${target.name}",
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Drag-and-drop: dropping files or folders onto the window sends them to the active peer only
    // when that conversation pane is actually visible; otherwise it opens FlashShareTargetSheet.
    // OPT-O19-01: Key DropTarget registration strictly on window lifecycle, decoupling dynamic nav and
    // header state via rememberUpdatedState to avoid recursive teardown/re-registration on every tab switch or typing tick.
    val currentNav by rememberUpdatedState(nav.current)
    val currentConversationHeader by rememberUpdatedState(conversationState.header)
    val currentTwoPane by rememberUpdatedState(twoPane)
    val currentSelectedChatId by rememberUpdatedState(selectedChatConversationId)
    // The sender captures the repository selected at this composition (empty before boot).
    // Update the whole callback, not only its navigation/header arguments.
    val currentDropSender by rememberUpdatedState(::sendFileToPeer)

    DisposableEffect(window) {
        val comp = window ?: return@DisposableEffect onDispose {}
        val dropListener = object : DropTargetListener {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dropActionChanged(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragExit(dte: DropTargetEvent) {}

            override fun drop(dtde: DropTargetDropEvent) {
                try {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                        val transferable = dtde.transferable
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                        val fileList = files?.filterIsInstance<File>() ?: emptyList()
                        if (fileList.isNotEmpty()) {
                            val activePeerId = visibleChatTargetForDrop(
                                destination = currentNav.destination,
                                navConversationId = currentNav.conversationId,
                                selectedChatConversationId = currentSelectedChatId,
                                twoPane = currentTwoPane,
                            )
                            val allFiles = fileList.flatMap { file ->
                                if (file.isDirectory) {
                                    file.walkTopDown().filter { it.isFile }.map { subFile ->
                                        val relPath = "${file.name}/${subFile.relativeTo(file).path.replace('\\', '/')}"
                                        Pair(subFile, relPath)
                                    }.toList()
                                } else {
                                    listOf(Pair(file, file.name))
                                }
                            }
                            if (allFiles.isEmpty()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = EMPTY_FOLDER_SHARE_MESSAGE,
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                                dtde.dropComplete(true)
                                return
                            }
                            if (activePeerId != null) {
                                allFiles.forEach { (file, relPath) ->
                                    currentDropSender(
                                        activePeerId,
                                        currentConversationHeader.title,
                                        currentConversationHeader.isGroup,
                                        file.toURI().toString(),
                                        relPath,
                                        file.length(),
                                        DesktopHelpers.guessMimeType(relPath),
                                        0L,
                                        emptyList(),
                                    )
                                }
                                val targetName = currentConversationHeader.title.takeIf { it.isNotBlank() }
                                scope.launch {
                                    val fileLabel = if (allFiles.size == 1) allFiles[0].first.name else "${allFiles.size} files"
                                    val msg = if (targetName != null) "Sending $fileLabel to $targetName" else "Sending $fileLabel"
                                    snackbarHostState.showSnackbar(
                                        message = msg,
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                                dtde.dropComplete(true)
                                return
                            } else {
                                val shareItems = allFiles.map { (file, relPath) ->
                                    FlashShareItemUi(
                                        uri = file.toURI().toString(),
                                        name = relPath,
                                        sizeBytes = file.length(),
                                        mimeType = DesktopHelpers.guessMimeType(file.name),
                                    )
                                }
                                pendingDesktopShare = mergeDesktopSharePayload(
                                    existing = pendingDesktopShare,
                                    incomingItems = shareItems,
                                )
                                dtde.dropComplete(true)
                                return
                            }
                        }
                        dtde.dropComplete(true)
                    } else {
                        dtde.rejectDrop()
                    }
                } catch (e: Exception) {
                    FlashLog.w("DND", "Drop error: ${e.message}")
                    dtde.dropComplete(false)
                }
            }
        }

        val attached = mutableListOf<java.awt.Component>()
        fun attachRecursively(c: java.awt.Component) {
            try {
                c.dropTarget = DropTarget(c, DnDConstants.ACTION_COPY, dropListener, true)
                attached.add(c)
            } catch (e: Exception) {
                FlashLog.w("DND", "Attach drop target failed for $c: ${e.message}")
            }
            if (c is java.awt.Container) {
                for (child in c.components) {
                    attachRecursively(child)
                }
            }
        }

        attachRecursively(comp)
        if (comp is javax.swing.RootPaneContainer) {
            comp.contentPane?.let { attachRecursively(it) }
            comp.layeredPane?.let { attachRecursively(it) }
            comp.glassPane?.let { attachRecursively(it) }
        }

        onDispose {
            attached.forEach { c ->
                try {
                    c.dropTarget = null
                } catch (_: Exception) {}
            }
            attached.clear()
        }
    }

    // ── Settings: no persisted DataStore on desktop until 09B-3; honest defaults ──
    //
    // `receivedFilesBytes` IS live, unlike the settings tier: it is a filesystem fact, not a
    // preference, so it needs no DataStore. It is load-bearing rather than cosmetic — the storage
    // card enables its Clear control only for a scan that returned a positive total
    // (`FlashStorageMath.canClearReceivedFiles`), so leaving this null left that control
    // permanently disabled and the confirmation dialog unreachable on desktop. Null is still the
    // honest initial value (the scan has not run yet) and resolves to a real 0 on an empty folder.
    val desktopSettings by engine.settings.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showEncryptionSheet by remember { mutableStateOf(false) }
    val enterpriseCredentials by engine.enterpriseTrustStore.credentials.collectAsState()
    // The organisation hub (ADR-120): live status for the hub destination.
    val hubObject = remember(ready) { engine.enterpriseHub }
    val hubStatusFlow = remember(hubObject) {
        hubObject?.status ?: kotlinx.coroutines.flow.MutableStateFlow<com.transfer.flash.core.enterprise.HubStatus?>(null)
    }
    val hubStatus by hubStatusFlow.collectAsState(initial = null)

    // UI-046: per-tab scroll state, like the app shell.
    val chatListScroll = rememberLazyListState()
    val transfersScroll = rememberLazyListState()
    val nearbyScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()

    // UI-058: the Hub tab replaces Transfers for an enrolled device. Enrolment is trusted only once the
    // engine is ready; the saved navigation state carries the last known value across a restart.
    LaunchedEffect(ready, enterpriseCredentials != null) {
        if (ready) nav.updateHubEnrolled(enterpriseCredentials != null)
    }
    val showBar = FlashNavigationMath.showsBottomBar(nav.current.destination, nav.hubEnrolled, railMode = useNavRail)
    val tabBottomInset = if (useNavRail) 0.dp else FLASH_BOTTOM_NAV_INSET

    // UI-058 H9: a route waits until enrolment is known (engine ready), opens the Hub when enrolled, else lands on Chats
    // with a one-line notice. The Hub reports it consumed.
    val hubRoute by pendingHubRoute.collectAsState()
    LaunchedEffect(hubRoute, ready, enterpriseCredentials != null) {
        if (hubRoute == null) return@LaunchedEffect
        when (com.transfer.flash.ui.hub.FlashHubMath.routeDecision(hubRoute, enrolmentKnown = ready, enrolled = enterpriseCredentials != null)) {
            com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.Wait -> Unit
            com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.Drop -> pendingHubRoute.value = null
            is com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.OpenHub -> {
                nav.updateHubEnrolled(true)
                nav.selectTab(FlashDestination.Hub)
            }
            com.transfer.flash.ui.hub.FlashHubMath.RouteDecision.NotEnrolled -> {
                nav.selectTab(FlashDestination.ChatList)
                pendingHubRoute.value = null
                snackbarHostState.showSnackbar(com.transfer.flash.ui.hub.FlashHubCopy.ROUTE_NOT_ENROLLED, duration = SnackbarDuration.Short)
            }
        }
    }
    val hubUnread by chatRepository.hubUnreadAnnouncementCount.collectAsState(initial = 0)
    val hubPrefs = remember { DesktopHubUiPrefs() }
    // Unsent announcement drafts belong to the organisation: forget them once enrolment is known to have ended (never on the cold-start default).
    LaunchedEffect(ready, enterpriseCredentials != null) {
        if (ready && enterpriseCredentials == null) hubPrefs.clearDrafts()
    }
    val hubUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val hubCall: (String, String) -> Unit = { id, name ->
        scope.launch {
            if (calls?.startCall(id, name, video = false) != true) {
                snackbarHostState.showSnackbar(
                    message = "Couldn't start the call. Make sure the peer is reachable, then try again.",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    val hubPendingJoins by chatRepository.pendingEnterpriseJoins.collectAsState()
    // UI-024 upgrade: a structured hit opens the conversation AT that message. Search closes first
    // so Escape/back from the chat returns to the list rather than a stale overlay.
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
    val closeDeletedConversationIfActive: (Set<String>) -> Unit = { deletedIds ->
        if (nav.current.conversationId in deletedIds || selectedChatConversationId in deletedIds) {
            val backToHub = nav.conversationOpenedFromHub
            selectedChatConversationId = null
            chatRepository.closeConversation()
            if (twoPane) {
                nav.selectTab(if (backToHub) FlashDestination.Hub else FlashDestination.ChatList)
            } else if (nav.current.destination == FlashDestination.Conversation) {
                nav.back()
            }
        }
    }
    // Chat list content shared by listPaneContent in both single-pane and two-pane modes.
    val chatListPaneContent: @Composable () -> Unit = {
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
            onPinConversation = { id, pinned ->
                chatRepository.setConversationsPinned(setOf(id), pinned)
            },
            onMuteConversation = { id, muted ->
                chatRepository.setConversationsMuted(setOf(id), muted)
            },
            onMarkConversationRead = { id ->
                chatRepository.markConversationsRead(setOf(id))
            },
            onDeleteConversation = { id ->
                chatRepository.deleteConversations(setOf(id))
                closeDeletedConversationIfActive(setOf(id))
            },
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
                closeDeletedConversationIfActive(deletedIds)
            },
            isLoading = (!ready || !chatListState.hasLoaded) && startError == null,
            errorMessage = startError?.let { error ->
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
            // UI-058: an enrolled device browses department groups from the Hub tab and opens Transfers
            // from this bar; an unenrolled device keeps the bar it always had.
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

    // Active conversation content. In two-pane mode, onBack/leave/clear returns to Hub or ChatList without pushing duplicates.
    // Bumped when the Hub tab is tapped while it is already showing; the Hub scrolls to the top (or jumps to News).
    var hubReselectTick by remember { mutableStateOf(0) }

    fun closeActiveConversation() {
        val backToHub = nav.conversationOpenedFromHub
        selectedChatConversationId = null
        chatRepository.closeConversation()
        if (twoPane) {
            nav.selectTab(if (backToHub) FlashDestination.Hub else FlashDestination.ChatList)
        } else {
            nav.back()
        }
    }

    fun selectDesktopShellTab(destination: FlashDestination) {
        selectDesktopShellTab(
            destination = destination,
            nav = nav,
            twoPane = twoPane,
            selectedChatConversationId = selectedChatConversationId,
            onOpenConversation = { id -> chatRepository.openConversation(id) },
            onCloseConversation = { chatRepository.closeConversation() },
            onClearSelections = {
                selectedChatConversationId = null
                selectedTransferId = null
                selectedNearbyPeerId = null
                showRadioGateway = false
            },
            onHubReselect = { hubReselectTick++ },
        )
    }

    fun startPeerPairing(peerId: String, peerName: String) {
        lastPairingPeer = peerId to peerName
        val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peerId }
        val net = engine.network
        if (endpoint != null && net != null) {
            scope.launch {
                net.connectManual(endpoint.hostAddress, endpoint.port)
                engine.pairing.beginPair(peerId, peerName)
            }
        } else {
            engine.pairing.beginPair(peerId, peerName)
        }
    }

    fun retryPairingWithTarget(target: Pair<String, String>) {
        val (peerId, peerName) = target
        scope.launch {
            engine.pairing.declineLocal()
            withTimeoutOrNull(500L) {
                (pairing?.pairing ?: MutableStateFlow(null)).firstOrNull { it == null }
            }
            startPeerPairing(peerId, peerName)
        }
    }

    fun openPeerChat(peerId: String, isColleague: Boolean, isTrusted: Boolean) {
        if (isColleague && !isTrusted) {
            scope.launch {
                val outcome = chatRepository.startColleagueConversation(peerId)
                when (outcome) {
                    is com.transfer.flash.core.common.result.FlashResult.Success -> {
                        selectedChatConversationId = peerId
                        chatRepository.openConversation(peerId)
                        nav.navigate(FlashDestination.Conversation, conversationId = peerId)
                    }
                    is com.transfer.flash.core.common.result.FlashResult.Failure -> {
                        val msg = (outcome.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                            ?: com.transfer.flash.ui.chat.FlashOrgStrings.ORG_START_NOT_MEMBER
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        } else {
            selectedChatConversationId = peerId
            chatRepository.openConversation(peerId)
            nav.navigate(FlashDestination.Conversation, conversationId = peerId)
        }
    }

    val conversationPaneBody: @Composable () -> Unit = {
        // The chat this composition belongs to: its draft is saved under this id when the screen leaves, after Back has
        // already closed the repository's active conversation (or the next chat was opened).
        val convId = activeConversationId
        val draftOwnerId = convId
        FlashConversationScreen(
            state = conversationState,
            chatWallpaper = desktopSettings.chatWallpaper,
            chatWallpaperCustom = desktopSettings.chatWallpaperCustom,
            onSetChatWallpaper = { wallpaper ->
                engine.updateSettings { it.copy(chatWallpaper = wallpaper) }
            },
            onSetChatWallpaperCustom = { hex ->
                engine.updateSettings { it.copy(chatWallpaperCustom = hex) }
            },
            onBack = { closeActiveConversation() },
            onRetryConnection = { engine.reconnectNow() },
            // Task 3.1: the in-app Forward sheet. Without these the conversation screen had no
            // recipients and Forward did nothing on desktop.
            forwardRecipients = (desktopRecentChatRecipients + desktopPairedRecipients).distinctBy { it.id },
            forwardRecentChats = desktopRecentChatRecipients,
            forwardPairedDevices = desktopPairedRecipients,
            onForwardToRecipients = { targets, payload -> forwardToChats(targets, payload) },
            // Whether this peer is in the trust store, from the same list the Nearby
            // screen's rows and this screen's header already read.
            isPeerTrusted = conversationIdIsTrusted,
            onRevokePeerTrust = if (conversationIdIsTrusted) {
                {
                    convId?.let { id ->
                        scope.launch {
                            engine.trust.revokeTrust(
                                com.transfer.flash.core.common.model.FlashDeviceId(id),
                            )
                        }
                    }
                }
            } else {
                null
            },
            onVerifySecurityCodes = {
                convId?.let { id ->
                    startPeerPairing(id, conversationState.header.title)
                }
            },
            localFingerprint = pairing?.localFingerprintHex,
            peerFingerprint = convId?.let { id ->
                pairing?.getPeerFingerprint(id)
            },
            onSendText = { chatRepository.sendText(it) },
            onSendReply = { text, replyToId, replyToPreview ->
                chatRepository.sendReply(text, replyToId, replyToPreview)
            },
            onPersistDraft = { text -> draftOwnerId?.let { chatRepository.saveDraft(it, text) } },
            onSetMessagePinned = chatRepository::setMessagePinned,
            onToggleReaction = { messageId, emoji ->
                chatRepository.toggleReaction(messageId, emoji)
            },
            onTypingChanged = chatRepository::setTyping,
            onSetRadioPolicy = { policy -> convId?.let { chatRepository.setRadioPolicy(it, policy) } },
            // Voice & video calls from the conversation header (Phase 33a/33c).
            onStartCall = {
                convId?.let { id ->
                    placeVoiceCall(id, conversationState.header.title)
                }
            },
            onStartVideoCall = {
                convId?.let { id ->
                    placeVideoCall(id, conversationState.header.title)
                }
            },
            showVideoCallAction = true,
            // Offer accept/decline/retry/open, parity with the Transfers tab (which
            // calls the same repository methods): the chat bubble params default to
            // no-ops, and leaving them unwired is exactly the "accept in chat does
            // nothing" report (ERROR-062 follow-up). acceptIncoming emits ACTION_ACCEPT,
            // which the engine collector turns into sink-then-RESUME like a tab accept.
            onAcceptOffer = { tid ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        repo.acceptIncoming(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
                    }
                }
            },
            onDeclineOffer = { tid ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        repo.declineIncoming(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
                    }
                }
            },
            onRetryTransfer = { tid ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(tid)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.resumeTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(subId))
                            }
                        } else {
                            repo.resumeTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
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
            onPauseTransfer = { tid ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(tid)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.pauseTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(subId))
                            }
                        } else {
                            repo.pauseTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
                        }
                    }
                }
            },
            onResumeTransfer = { tid ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(tid)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.resumeTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(subId))
                            }
                        } else {
                            repo.resumeTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
                        }
                    }
                }
            },
            onCancelTransfer = { tid ->
                engine.transfers?.let { repo ->
                    scope.launch {
                        val recipientIds = chatRepository.getRecipientTransferIds(tid)
                        if (recipientIds.isNotEmpty()) {
                            recipientIds.forEach { subId ->
                                repo.cancelTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(subId))
                            }
                        } else {
                            repo.cancelTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
                        }
                    }
                }
            },
            onOpenAttachment = { path, mime, _ ->
                DesktopHelpers.openAttachment(path, mime)
            },
            onAttachmentClick = {
                generalFilePicker.launch(listOf("*/*"))
            },
            onSendFile = { uri, displayName, size ->
                val peerId = convId
                if (peerId != null) {
                    sendFileToPeer(
                        peerId = peerId,
                        peerName = conversationState.header.title,
                        isGroup = conversationState.header.isGroup,
                        uri = uri,
                        displayName = displayName,
                        size = size,
                        mimeType = DesktopHelpers.guessMimeType(displayName),
                    )
                }
            },
            onDeleteMessage = { ids -> chatRepository.deleteMessages(ids) },
            onDeleteMessageForEveryone = chatRepository::deleteMessageForEveryone,
            // Both helpers already existed and were never called, so the media viewer's
            // Save and Share were silently inert. Save writes a copy next to the
            // original under the received root; Share hands the file to the OS.
            onSaveImage = { uri, mime -> DesktopHelpers.saveImageToGallery(uri, mime) },
            onShareImage = { uri, mime -> DesktopHelpers.shareImageUri(uri, mime) },
            onVoiceRecordingStarting = {
                if (calls?.activeCall?.value != null) null else java.util.UUID.randomUUID().toString()
            },
            onVoiceRecordingStopped = { _ -> },
            onSendVoiceMessage = { localPath, durationMs, amplitudes ->
                val peerId = convId
                if (peerId != null && engine.transfers != null) {
                    val fileName = "Voice message.wav"
                    val peerName = conversationState.header.title
                    val isGroup = conversationState.header.isGroup
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching { engine.persistVoiceRecording(localPath, fileName) }.getOrNull()
                        }
                        if (file == null) {
                            snackbarHostState.showSnackbar("Couldn’t save the voice message. Try recording again.")
                            return@launch
                        }
                        sendFileToPeer(
                            peerId = peerId,
                            peerName = peerName,
                            isGroup = isGroup,
                            uri = file.toURI().toString(),
                            displayName = fileName,
                            size = file.length(),
                            mimeType = "audio/wav",
                            voiceDurationMs = durationMs,
                            voiceAmplitudes = amplitudes,
                        )
                    }
                }
            },
            conversationId = convId,
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
                        snackbarHostState.showSnackbar(message = note, duration = SnackbarDuration.Long)
                    }
                }
            },
            onLeaveGroup = { groupId ->
                scope.launch {
                    val left = chatRepository.leaveGroup(groupId)
                    if (left is com.transfer.flash.core.common.result.FlashResult.Success) {
                        closeActiveConversation()
                    } else {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't leave the group — try again",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            },
            onLeaveGroupWithSuccessor = { groupId, successorId ->
                scope.launch {
                    val left = chatRepository.leaveGroup(groupId, successorId)
                    if (left is com.transfer.flash.core.common.result.FlashResult.Success) {
                        closeActiveConversation()
                    } else {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't leave the group — try again",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            },
            onContinueInNewGroup = { groupId ->
                scope.launch {
                    val result = chatRepository.continueInNewGroup(groupId)
                    when (result) {
                        is com.transfer.flash.core.common.result.FlashResult.Success -> {
                            selectedChatConversationId = result.value
                            chatRepository.openConversation(result.value)
                            nav.navigate(FlashDestination.Conversation, conversationId = result.value)
                        }
                        is com.transfer.flash.core.common.result.FlashResult.Failure -> {
                            val errMessage = (result.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                                ?: "Couldn't continue in new group"
                            snackbarHostState.showSnackbar(
                                message = errMessage,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                }
            },
            // ADR-044 V2: a member the group owner introduced is verified by ordinary pairing, like Nearby's Pair.
            onVerifyGroupMember = { memberId, memberName ->
                val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == memberId }
                val net = engine.network
                if (endpoint != null && net != null) {
                    scope.launch {
                        net.connectManual(endpoint.hostAddress, endpoint.port)
                        engine.pairing.beginPair(memberId, memberName)
                    }
                } else {
                    engine.pairing.beginPair(memberId, memberName)
                }
            },
            // ADR-044 V2 (E5): only offered to the owner of a v2 group (conversation state canRemoveMembers).
            onRemoveGroupMember = { groupId, memberId, memberName ->
                scope.launch {
                    val removed = chatRepository.removeGroupMember(groupId, memberId)
                    if (removed !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't remove $memberName — try again",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            },
            // ADR-063: Promote a member to admin in a v2 group (owner only).
            onPromoteGroupAdmin = { groupId, memberId ->
                scope.launch {
                    val res = chatRepository.promoteAdmin(groupId, memberId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't promote to admin — try again",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            },
            // ADR-063: Demote an admin to regular member in a v2 group (owner only).
            onDemoteGroupAdmin = { groupId, memberId ->
                scope.launch {
                    val res = chatRepository.demoteAdmin(groupId, memberId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't demote admin — try again",
                            duration = SnackbarDuration.Short,
                        )
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
                val peerId = convId
                if (peerId != null) {
                    scope.launch(Dispatchers.IO) {
                        val memberIds = if (conversationState.members.isNotEmpty()) {
                            conversationState.members.map { it.id }
                        } else {
                            chatRepository.groupMembers(peerId).map { it.id }
                        }
                        calls?.joinGroupCall(
                            groupId = peerId,
                            callId = callId,
                            memberIds = memberIds,
                            video = video,
                        )
                    }
                }
            },
            onApproveJoinRequest = { gid, subjectId ->
                scope.launch {
                    val res = chatRepository.approveJoinRequest(gid, subjectId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't approve join request", duration = SnackbarDuration.Short)
                    }
                }
            },
            onRefuseJoinRequest = { gid, subjectId ->
                scope.launch {
                    val res = chatRepository.refuseJoinRequest(gid, subjectId)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't refuse join request", duration = SnackbarDuration.Short)
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
                        snackbarHostState.showSnackbar("Couldn't update settings", duration = SnackbarDuration.Short)
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
                        snackbarHostState.showSnackbar("Couldn't update preferences", duration = SnackbarDuration.Short)
                    }
                }
            },
            onChangeGroupCode = { gid ->
                scope.launch {
                    val res = chatRepository.changeGroupCode(gid)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't change group code", duration = SnackbarDuration.Short)
                    }
                }
            },
            onChooseGroupHistory = { gid, windowMs, includeFiles ->
                scope.launch {
                    val res = chatRepository.chooseGroupHistory(gid, windowMs, includeFiles)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't load earlier history", duration = SnackbarDuration.Short)
                    }
                }
            },
            onConfirmHubKeyChange = { peerId -> scope.launch { chatRepository.confirmHubKeyChange(peerId) } },
            onSkipGroupHistory = { gid ->
                scope.launch {
                    val res = chatRepository.skipGroupHistory(gid)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't save your choice", duration = SnackbarDuration.Short)
                    }
                }
            },
            onLoadOlderGroupHistory = { gid, windowMs ->
                scope.launch {
                    val res = chatRepository.loadOlderGroupHistory(gid, windowMs)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't load older messages", duration = SnackbarDuration.Short)
                    }
                }
            },
            onSetGroupHistoryCeiling = { gid, ceiling ->
                scope.launch {
                    val res = chatRepository.updateGroupSettings(groupId = gid, historyCeiling = ceiling)
                    if (res !is com.transfer.flash.core.common.result.FlashResult.Success) {
                        snackbarHostState.showSnackbar("Couldn't update the history limit", duration = SnackbarDuration.Short)
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
            isEnterpriseEnrolled = enterpriseCredentials != null,
            isLinkedToHub = convId?.let { chatRepository.isGroupLinkedToHub(it) } ?: false,
            onToggleLinkToHub = { link ->
                convId?.let { gid ->
                    scope.launch {
                        if (link) {
                            chatRepository.linkGroupToHub(gid)
                        } else {
                            chatRepository.unlinkGroupFromHub(gid)
                        }
                    }
                }
            },
            isPeerVerified = conversationIdIsVerified,
            onVerifyPeerTrust = convId?.let { id ->
                { startPeerPairing(id, conversationState.header.title) }
            },
            connectionTransport = conversationState.header.transport,
            onFindDevicesClick = { selectDesktopShellTab(FlashDestination.NearbyDevices) },
            localDeviceId = engine.localDeviceId,
        )
    }

    // One screen instance per conversation (see MainActivity.conversationScreenContent): no state may leak between chats.
    val conversationPaneContent: @Composable () -> Unit = {
        key(activeConversationId) { conversationPaneBody() }
    }

    // The tab content, shared by both window layouts. In two-pane mode the transfers/nearby
    // screens additionally drive the detail pane through the selection state. When twoPane is
    // active and the user is in a conversation, the list pane remains on ChatList.
    val listPaneContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            val chatFromHub = twoPane && nav.current.destination == FlashDestination.Conversation && nav.conversationOpenedFromHub
            if (twoPane && nav.current.destination == FlashDestination.Conversation && !chatFromHub) {
                chatListPaneContent()
            } else {
                // H-16: a chat opened from the Hub keeps the Hub as the list pane (a constant Hub entry, so no transition plays
                // while the chat beside it changes); anything else shows the current entry as before.
                val listEntry = if (chatFromHub) FlashBackStackState(FlashDestination.Hub) else nav.current
                FlashAnimatedScreen(targetState = listEntry, hubEnrolled = nav.hubEnrolled) { entry ->
                    when (entry.destination) {
                        FlashDestination.ChatList -> chatListPaneContent()
                        // UI-058 (H7/H8/H9): the Hub tab. Everything it reads and writes goes through the repository.
                        FlashDestination.Hub -> {
                            val hubHost = remember(chatRepository, hubStatus?.hubHost, calls, hubRoute) {
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
                                    onOpenLink = { url -> runCatching { hubUriHandler.openUri(url) } },
                                    onSignOut = {
                                        scope.launch {
                                            val result = engine.enterpriseHub?.unenrol()
                                            snackbarHostState.showSnackbar(
                                                if (result == null) "Flash is still starting. Try again in a moment."
                                                else com.transfer.flash.ui.settings.FlashHubStatusMath.unenrolText(result),
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    onCall = hubCall.takeIf { calls != null },
                                    onPairColleague = { id ->
                                        scope.launch {
                                            val result = chatRepository.pairColleagueFromDirectory(id)
                                            snackbarHostState.showSnackbar(
                                                when (result) {
                                                    is com.transfer.flash.core.common.result.FlashResult.Success -> "Paired."
                                                    is com.transfer.flash.core.common.result.FlashResult.Failure ->
                                                        (result.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message ?: "Pairing failed."
                                                },
                                                duration = SnackbarDuration.Short,
                                            )
                                        }
                                    },
                                    orgHost = hubStatus?.hubHost?.takeIf { it.isNotBlank() },
                                    onManageGroupRequests = { showBrowseOrgGroups = true },
                                    pendingRoute = hubRoute,
                                    onRouteConsumed = { pendingHubRoute.value = null },
                                    prefs = hubPrefs,
                                    drafts = hubPrefs,
                                )
                            }
                            com.transfer.flash.ui.hub.FlashHubRoute(
                                repository = chatRepository,
                                link = hubStatus?.state ?: com.transfer.flash.core.enterprise.HubLinkState.NOT_ENROLLED,
                                host = hubHost,
                                modifier = Modifier.fillMaxSize(),
                                reselectSignal = hubReselectTick,
                                bottomInset = tabBottomInset,
                            )
                        }
                        FlashDestination.Conversation -> conversationPaneContent()
                        FlashDestination.Transfers -> Column(Modifier.fillMaxSize()) {
                            if (nav.hubEnrolled && nav.stackSize > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .heightIn(min = FlashDimensions.minTouchTarget)
                                            .clip(RoundedCornerShape(FlashShapes.radius8))
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable(role = Role.Button, onClickLabel = "Back") {
                                                selectedTransferId = null
                                                nav.back()
                                            }
                                            .padding(horizontal = FlashSpacing.space8),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        FlashIcon(
                                            icon = FlashIcons.Back,
                                            contentDescription = null,
                                            tint = FlashTheme.colors.accentFill,
                                            size = FlashDimensions.iconSm,
                                        )
                                        Spacer(Modifier.width(FlashSpacing.space4))
                                        FlashText(
                                            text = "Back",
                                            style = FlashTheme.typography.bodyEmphasis,
                                            color = FlashTheme.colors.accentFill,
                                        )
                                    }
                                }
                            }
                            FlashTransfersScreen(
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
                                    // Two-pane: show details alongside. Single-pane: open the completed file.
                                    if (twoPane) {
                                        selectedTransferId = item.id
                                    } else {
                                        DesktopHelpers.openAttachment(item.localPath, DesktopHelpers.guessMimeType(item.fileName))
                                    }
                                },
                                onHistoryShare = { item -> DesktopHelpers.shareTransferredFile(item) },
                                onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) },
                                // Plan 1.2: the screen only draws "Clear history" when a host passes this.
                                onClearHistory = {
                                    selectedTransferId = null
                                    engine.transfers?.clearFinishedHistory()
                                },
                                // C-10: a Failed or Cancelled row can be dismissed one by one or all at once.
                                onDismissItem = { item ->
                                    if (selectedTransferId == item.id) selectedTransferId = null
                                    engine.transfers?.dismissTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(item.id))
                                },
                                onClearFailed = {
                                    val failedIds = transfersUi.failed.filter { it.retryable }.map { it.id }
                                    if (selectedTransferId in failedIds) selectedTransferId = null
                                    engine.transfers?.let { repo ->
                                        failedIds.forEach { id ->
                                            repo.dismissTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(id))
                                        }
                                    }
                                },
                                onClearCancelled = {
                                    val cancelledIds = transfersUi.failed.filterNot { it.retryable }.map { it.id }
                                    if (selectedTransferId in cancelledIds) selectedTransferId = null
                                    engine.transfers?.let { repo ->
                                        cancelledIds.forEach { id ->
                                            repo.dismissTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(id))
                                        }
                                    }
                                },
                                onItemClick = { item -> selectedTransferId = item.id },
                                onRetryLoad = { engine.start() },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                listState = transfersScroll,
                                bottomInset = tabBottomInset,
                            )
                        }
                    FlashDestination.NearbyDevices -> {
                    // PC5: ECO dials unpaired peers only while this screen is shown, so they can be paired.
                    DisposableEffect(engine) {
                        engine.setNearbyVisible(true)
                        onDispose { engine.setNearbyVisible(false) }
                    }
                    // The one boundary that is otherwise invisible: the engine's flow is proven to
                    // carry a pairing state (the collector above logs it), and the dialog renders iff
                    // `state.pairingRequest != null`. This logs what the SCREEN is actually handed, so
                    // "the engine published nothing" and "the screen was handed nothing" stop looking
                    // identical — which is the whole of the 2026-09-14 "no dialog on the PC" report.
                    val requestForLog = nearby.pairingRequest
                    val phaseForLog = nearby.pairingPhase
                    // The peer counts are logged alongside, because the OTHER failure on this screen
                    // was a disappearing device, not a disappearing dialog: a trusted peer used to be
                    // filtered out of `peers` while the trusted rows lagged, so it left both lists at
                    // once. With these numbers a live run answers "where did it go" directly —
                    // `trusted=1 peers=0` either way, and the ids say which list holds it.
                    val trustedForLog = nearby.trustedPeers.map { it.id }
                    val peersForLog = nearby.peers.map { it.id }
                    androidx.compose.runtime.LaunchedEffect(
                        requestForLog,
                        phaseForLog,
                        trustedForLog,
                        peersForLog,
                    ) {
                        com.transfer.flash.core.common.logging.FlashLog.i(
                            "PAIRING",
                            "[shell] Nearby screen state: requestPresent=${requestForLog != null} " +
                                "phase=$phaseForLog reqCode=${requestForLog?.numericCode} " +
                                "trusted=$trustedForLog peers=$peersForLog",
                        )
                    }
                    val activeNearbyRetryTarget = nearby.pairingRequest?.let { req ->
                        resolveDesktopPairingRetryTarget(
                            peerDeviceId = req.peerDeviceId ?: pairingUi?.peerDeviceId,
                            peerName = req.peerName,
                            lastPairingPeer = lastPairingPeer,
                            discoveredEndpoints = discoveredEndpoints,
                            trustedPeers = trustedPeersByCoordinator,
                        )
                    }
                    FlashNearbyScreen(
                        state = nearby,
                        onPairClick = { peer ->
                            startPeerPairing(peer.id, peer.name)
                            // Two-pane: show live details for this peer while pairing progresses.
                            if (twoPane) selectedNearbyPeerId = peer.id
                        },
                        onAcceptPairing = { engine.pairing.acceptLocal() },
                        onDeclinePairing = {
                            clearPendingDesktopSharePairing()
                            engine.pairing.declineLocal()
                        },
                        onChatClick = { peer ->
                            openPeerChat(peer.id, isColleague = peer.isColleague, isTrusted = peer.isTrusted)
                        },
                        onRevokeClick = { trusted ->
                            if (selectedNearbyPeerId == trusted.id && nearby.peers.none { it.id == trusted.id }) {
                                selectedNearbyPeerId = null
                            }
                            engine.pairing.revoke(trusted.id)
                        },
                        onChatTrustedClick = { trusted ->
                            openPeerChat(trusted.id, isColleague = false, isTrusted = true)
                        },
                        // ADR-042: re-run pairing (v2) with a peer paired under the forceable v1 code.
                        onVerifyTrustedClick = { trusted ->
                            startPeerPairing(trusted.id, trusted.name)
                            if (twoPane) selectedNearbyPeerId = trusted.id
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
                                    engine.pairing.beginPair(peerDevice.id.value, peerDevice.friendlyName)
                                    if (twoPane) selectedNearbyPeerId = peerDevice.id.value
                                    null
                                } else {
                                    "Couldn't connect to $host:$port. Check that Flash is open on the same network."
                                }
                            }
                        },
                        onScanNetwork = { engine.scanNetwork() },
                        onRetryPairing = activeNearbyRetryTarget?.let { target ->
                            { _ -> retryPairingWithTarget(target) }
                        },
                        onPeerClick = { peer -> selectedNearbyPeerId = peer.id },
                        onTrustedPeerClick = { trusted -> selectedNearbyPeerId = trusted.id },
                        modifier = Modifier.fillMaxSize(),
                        listState = nearbyScroll,
                        bottomInset = tabBottomInset,
                    )
                    }
                    FlashDestination.Settings -> DesktopSettingsDestination(
                        engine = engine,
                        ready = ready,
                        themeMode = themeMode,
                        onThemeModeSelected = onThemeModeSelected,
                        localFriendlyName = localFriendlyName,
                        trustedPeersCount = trustedPeersByCoordinator.size,
                        enterpriseCredentials = enterpriseCredentials,
                        desktopSettings = desktopSettings,
                        hubStatus = hubStatus,
                        onEditDisplayName = { showRenameDialog = true },
                        onOpenEncryption = { showEncryptionSheet = true },
                        onOpenTrustedPeers = { nav.selectTab(FlashDestination.NearbyDevices) },
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        settingsScroll = settingsScroll,
                        tabBottomInset = tabBottomInset,
                        window = window,
                        radioGatewayController = radioGatewayController,
                        showRadioGateway = showRadioGateway,
                        onOpenRadioGateway = { showRadioGateway = true },
                        onCloseRadioGateway = { showRadioGateway = false },
                    )
                    }
                }
            }
        }
    }

    val selectedNearbyPeer = remember(selectedNearbyPeerId, nearby, discoveredEndpoints, enterpriseCredentials) {
        resolveSelectedNearbyPeer(
            peerId = selectedNearbyPeerId,
            nearby = nearby,
            discovered = discoveredEndpoints,
            orgName = enterpriseCredentials?.hubName,
            isOrgPeer = { peerId -> engine.hasAuthenticatedOrgSession(peerId) },
        )
    }

    val renderTransferDetail: @Composable (FlashTransferItemUi) -> Unit = { item ->
        TransferDetailPane(
            item = item,
            onClose = { selectedTransferId = null },
            onPauseResume = {
                engine.transfers?.let { repo ->
                    val id = com.transfer.flash.core.transfer.model.FlashTransferId(item.id)
                    scope.launch {
                        if (item.state == FlashTransferState.Paused) repo.resumeTransfer(id)
                        else if (item.state == FlashTransferState.Active) repo.pauseTransfer(id)
                    }
                }
            },
            onCancel = {
                engine.transfers?.let { repo ->
                    val id = com.transfer.flash.core.transfer.model.FlashTransferId(item.id)
                    scope.launch { repo.cancelTransfer(id) }
                }
            },
            onRetry = {
                engine.transfers?.let { repo ->
                    val id = com.transfer.flash.core.transfer.model.FlashTransferId(item.id)
                    scope.launch { repo.resumeTransfer(id) }
                }
            },
            onOpen = {
                DesktopHelpers.openAttachment(item.localPath, DesktopHelpers.guessMimeType(item.fileName))
            },
            onReveal = {
                DesktopHelpers.shareTransferredFile(item)
            },
        )
    }

    val renderNearbyDetail: @Composable (NearbyPeerUi, Boolean) -> Unit = { peer, closeSheetOnAction ->
        NearbyDetailPane(
            peer = peer,
            onClose = { selectedNearbyPeerId = null },
            onPair = if (!peer.isTrusted) {
                {
                    if (closeSheetOnAction) selectedNearbyPeerId = null
                    startPeerPairing(peer.id, peer.name)
                }
            } else null,
            onChat = if (peer.isTrusted || peer.isColleague) {
                {
                    if (closeSheetOnAction) selectedNearbyPeerId = null
                    openPeerChat(peer.id, isColleague = peer.isColleague, isTrusted = peer.isTrusted)
                }
            } else null,
            onVoiceCall = if (peer.isTrusted && calls != null) {
                {
                    if (closeSheetOnAction) selectedNearbyPeerId = null
                    placeVoiceCall(peer.id, peer.name, isGroup = false)
                }
            } else null,
            onVideoCall = if (peer.isTrusted && calls != null) {
                {
                    if (closeSheetOnAction) selectedNearbyPeerId = null
                    placeVideoCall(peer.id, peer.name, isGroup = false)
                }
            } else null,
            onSendFile = if (peer.isTrusted && engine.transfers != null) {
                {
                    if (closeSheetOnAction) selectedNearbyPeerId = null
                    nearbySendFileTarget = peer
                    try {
                        nearbyFilePicker.launch(listOf("*/*"))
                    } finally {
                        nearbySendFileTarget = null
                    }
                }
            } else null,
        )
    }

    // The detail pane for two-pane mode: active conversation, transfer info, peer info, or placeholder.
    val detailPaneContent: @Composable () -> Unit = {
        val detailConvId = activeConversationId
        when {
            (nav.current.destination == FlashDestination.Conversation || (twoPane && nav.current.destination == FlashDestination.ChatList && detailConvId != null)) && detailConvId != null -> {
                conversationPaneContent()
            }
            nav.current.destination == FlashDestination.Transfers && selectedTransferItem != null -> {
                renderTransferDetail(selectedTransferItem)
            }
            nav.current.destination == FlashDestination.NearbyDevices && selectedNearbyPeer != null -> {
                renderNearbyDetail(selectedNearbyPeer, false)
            }
            else -> PlaceholderDetailPane(
                onFindDevices = if (nav.current.destination != FlashDestination.NearbyDevices) {
                    { nav.selectTab(FlashDestination.NearbyDevices) }
                } else null,
                destination = nav.current.destination,
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundApp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val isModifierPressed = event.isCtrlPressed || event.isMetaPressed
                    if (event.key == Key.Escape) {
                        when (
                            resolveDesktopEscapeAction(
                                pttActive = pttActive,
                                isSearching = isSearching,
                                destination = nav.current.destination,
                                hubEnrolled = nav.hubEnrolled,
                                stackSize = nav.stackSize,
                                twoPane = twoPane,
                                selectedChatConversationId = selectedChatConversationId,
                                hasSelectedTransferItem = selectedTransferItem != null,
                                hasSelectedNearbyPeer = selectedNearbyPeer != null,
                                hasEndedCallOverlay = ringingCall?.state == com.transfer.flash.core.calling.model.FlashCallState.ENDED,
                                hasActiveCallOverlay = ringingCall != null && ringingCall.state != com.transfer.flash.core.calling.model.FlashCallState.ENDED,
                                showRadioGateway = showRadioGateway,
                            )
                        ) {
                            DesktopEscapeAction.DismissEndedCall -> {
                                ringingCall?.let { dismissedEndedCallId = it.callId }
                                true
                            }
                            DesktopEscapeAction.ConsumeInCall -> true
                            DesktopEscapeAction.CloseRadioGateway -> {
                                showRadioGateway = false
                                true
                            }
                            DesktopEscapeAction.StopPtt -> {
                                ptt?.stopLocal()
                                true
                            }
                            DesktopEscapeAction.CloseSearch -> {
                                isSearching = false
                                searchQuery = ""
                                searchScope = FlashSearchScope.All
                                true
                            }
                            DesktopEscapeAction.ClearTransferDetail -> {
                                selectedTransferId = null
                                true
                            }
                            DesktopEscapeAction.PopTransfersBack -> {
                                selectedTransferId = null
                                nav.back()
                                true
                            }
                            DesktopEscapeAction.ClearNearbyDetail -> {
                                selectedNearbyPeerId = null
                                true
                            }
                            DesktopEscapeAction.CloseConversation -> {
                                closeActiveConversation()
                                true
                            }
                            DesktopEscapeAction.None -> false
                        }
                    } else if (isModifierPressed) {
                        val action = resolveDesktopShortcut(
                            key = event.key,
                            shiftPressed = event.isShiftPressed,
                            hubEnrolled = nav.hubEnrolled,
                            hasActiveCall = ringingCall != null,
                            hasPendingShare = pendingDesktopShare != null,
                        )
                        when (action) {
                            DesktopShortcutAction.OpenSearch -> {
                                if (nav.current.destination != FlashDestination.ChatList &&
                                    nav.current.destination != FlashDestination.Conversation
                                ) {
                                    nav.selectChatsTab(if (twoPane) selectedChatConversationId else null)
                                }
                                isSearching = true
                                true
                            }
                            is DesktopShortcutAction.SelectTab -> {
                                selectDesktopShellTab(action.destination)
                                true
                            }
                            DesktopShortcutAction.OpenTransfers -> {
                                nav.openTransfers()
                                true
                            }
                            DesktopShortcutAction.TogglePtt -> {
                                onPttToggle()
                                true
                            }
                            null -> false
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
    ) {

        // Rename this device. `DesktopIdentityStore.updateFriendlyName` has always worked and
        // persisted; the row that would call it was wired to nothing, so every desktop install was
        // named "Flash Desktop" forever.
        if (showRenameDialog) {
            FlashDisplayNameDialog(
                initial = localFriendlyName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { name ->
                    showRenameDialog = false
                    scope.launch { engine.renameLocalDevice(name) }
                },
            )
        }
        if (showEncryptionSheet) {
            FlashEncryptionSheet(
                state = if (trustedPeersByCoordinator.any { it.verified }) {
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
        if (showCreateGroup) {
            FlashCreateGroupSheet(
                peers = trustedPeerRoster,
                onDismiss = { showCreateGroup = false },
                isEnterpriseEnrolled = enterpriseCredentials != null,
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
                            snackbarHostState.showSnackbar(
                                message = reason ?: "Couldn't create the group — check that every member is paired",
                                duration = SnackbarDuration.Long,
                            )
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
                            snackbarHostState.showSnackbar(
                                message = reason ?: "Couldn't create the group — check that every member is paired",
                                duration = SnackbarDuration.Long,
                            )
                        }
                    }
                },
            )
        }
        if (showBrowseOrgGroups) {
            val pendingJoins by chatRepository.pendingEnterpriseJoins.collectAsState()
            FlashBrowseOrgGroupsSheet(
                departmentName = enterpriseCredentials?.department.orEmpty(),
                onDismiss = { showBrowseOrgGroups = false },
                onLoadGroups = { chatRepository.fetchEnterpriseDirectoryGroups(enterpriseCredentials?.department.orEmpty()) },
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
                                    snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
                                }
                            }
                            is com.transfer.flash.core.common.result.FlashResult.Failure -> {
                                val err = (result.error as? com.transfer.flash.core.common.result.FlashError.Unknown)?.message
                                    ?: "Couldn't join group"
                                snackbarHostState.showSnackbar(err, duration = SnackbarDuration.Short)
                            }
                        }
                    }
                },
            )
        }
        val currentDesktopShare = pendingDesktopShare
        if (currentDesktopShare != null && !isPairingForDesktopShare) {
            FlashShareTargetSheet(
                payload = currentDesktopShare,
                pairedDevices = desktopPairedRecipients,
                nearbyDevices = desktopNearbyRecipients,
                recentChats = desktopRecentChatRecipients,
                isScanning = nearby.isScanning,
                onSelectRecipient = { recipient ->
                    val trustedIds = trustedPeersByCoordinator.mapTo(HashSet()) { it.id }
                    if (canDeliverDesktopShareDirectly(recipient, trustedIds)) {
                        pendingDesktopShare = null
                        selectedChatConversationId = recipient.id
                        chatRepository.openConversation(recipient.id)
                        nav.navigate(FlashDestination.Conversation, conversationId = recipient.id)
                        currentDesktopShare.items.forEach { item ->
                            sendFileToPeer(
                                peerId = recipient.id,
                                peerName = recipient.name,
                                isGroup = recipient.isGroup,
                                uri = item.uri,
                                displayName = item.name,
                                size = item.sizeBytes,
                                mimeType = item.mimeType.takeIf { it.isNotBlank() && it != "*/*" }
                                    ?: DesktopHelpers.guessMimeType(item.name),
                            )
                        }
                        currentDesktopShare.text?.takeIf { it.isNotBlank() }?.let {
                            chatRepository.sendTextTo(recipient.id, it)
                        }
                        val summary = FlashShareTargetMath.formatItemSummary(currentDesktopShare)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Sending $summary to ${recipient.name}",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    } else {
                        isPairingForDesktopShare = true
                        pendingDesktopShareRecipient = Pair(recipient.id, recipient.name)
                        lastPairingPeer = recipient.id to recipient.name
                        val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == recipient.id }
                        val net = engine.network
                        if (endpoint != null && net != null) {
                            scope.launch {
                                net.connectManual(endpoint.hostAddress, endpoint.port)
                                engine.pairing.beginPair(recipient.id, recipient.name)
                            }
                        } else {
                            engine.pairing.beginPair(recipient.id, recipient.name)
                        }
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Initiating pairing with ${recipient.name}...",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                },
                onDismiss = {
                    pendingDesktopShare = null
                    pendingDesktopShareRecipient = null
                    isPairingForDesktopShare = false
                },
                onManualConnect = {
                    showDesktopShareManualConnect = true
                },
            )
        }

        var shareManualConnecting by remember { mutableStateOf(false) }
        var shareManualError by remember { mutableStateOf<String?>(null) }
        if (showDesktopShareManualConnect) {
            FlashManualConnectDialog(
                onDismiss = {
                    if (!shareManualConnecting) {
                        shareManualError = null
                        showDesktopShareManualConnect = false
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
                            showDesktopShareManualConnect = false
                            val peerDevice = result.value.peer
                            val peerId = peerDevice.id.value
                            val peerName = peerDevice.friendlyName
                            val isTrusted = trustedPeersByCoordinator.any { it.id == peerId }
                            if (isTrusted) {
                                val share = pendingDesktopShare
                                pendingDesktopShare = null
                                selectedChatConversationId = peerId
                                chatRepository.openConversation(peerId)
                                nav.navigate(FlashDestination.Conversation, conversationId = peerId)
                                share?.items?.forEach { item ->
                                    sendFileToPeer(
                                        peerId = peerId,
                                        peerName = peerName,
                                        isGroup = false,
                                        uri = item.uri,
                                        displayName = item.name,
                                        size = item.sizeBytes,
                                        mimeType = item.mimeType,
                                    )
                                }
                            } else {
                                isPairingForDesktopShare = true
                                pendingDesktopShareRecipient = Pair(peerId, peerName)
                                lastPairingPeer = peerId to peerName
                                engine.pairing.beginPair(peerId, peerName)
                                snackbarHostState.showSnackbar(
                                    message = "Connected to $host:$port. Initiating pairing with $peerName...",
                                    duration = SnackbarDuration.Short,
                                )
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

        if (useNavRail) {
            // Medium (600..839dp) & Expanded (>= 840dp): sidebar on the left edge, matching Android.
            Row(Modifier.fillMaxSize()) {
                DesktopSideBar(
                    tabs = desktopSideTabs(nav.hubEnrolled),
                    hubBadge = hubUnread,
                    selectedTab = nav.shellTab,
                    onTabSelected = ::selectDesktopShellTab,
                    unreadCount = totalUnreadCount,
                    localDisplayName = localFriendlyName,
                    onProfileClick = {
                        selectedChatConversationId = null
                        selectedTransferId = null
                        selectedNearbyPeerId = null
                        showRadioGateway = false
                        nav.selectTab(FlashDestination.Settings)
                    },
                    footer = { DesktopPttButton(active = pttActive, onClick = onPttToggle) },
                )
                val paneLayoutPrefs = remember { DesktopPaneLayoutPrefs() }
                if (twoPane && !isFullWidthDesktopDestination(nav.current.destination)) {
                    DesktopTwoPane(
                        listPane = listPaneContent,
                        detailPane = detailPaneContent,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        window = window,
                        sizeClass = sizeClass,
                        prefs = paneLayoutPrefs,
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        listPaneContent()
                    }
                }
            }
        } else {
            // Compact (< 600dp): single-pane layout with the hanging bottom nav.
            listPaneContent()
            if (showBar) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    FlashBottomNav(
                        items = desktopBottomTabs(nav.hubEnrolled, hubUnread),
                        selectedTab = nav.shellTab,
                        onTabSelected = ::selectDesktopShellTab,
                        onTabReselected = { destination ->
                            if (!FlashNavigationMath.isTabRoot(nav.current.destination, nav.hubEnrolled)) {
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
                                scope.launch { state.animateScrollToItem(0) }
                            }
                        },
                    )
                }
                // No rail in this layout, so the push-to-talk button floats above the hanging nav on the
                // tab roots (and pushed Transfers for enrolled devices).
                DesktopPttButton(
                    active = pttActive,
                    onClick = onPttToggle,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = tabBottomInset + 12.dp),
                )
            }
        }

        // Push-to-talk session card (UI-051): derived from the floor state, so it cannot outlive the
        // session. Before the call overlay in z-order, so an incoming call paints above it (a call
        // tears the session down anyway). The scrim swallows taps so nothing behind the card is hit.
        if (ptt != null && pttActive) {
            PttSessionOverlayContent(
                engine = ptt,
                state = pttState,
                animateLevels = (desktopSettings.performanceMode ?: FlashPerformanceMode.HIGH) != FlashPerformanceMode.LOW,
                scrimModifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            )
        }

        // Voice/video calls, 33a: the shared screen as a topmost overlay, mirroring Android's
        // `if (activeCall != null) FlashCallScreen(...)`. Last sibling so it paints above the
        // layout (same z-order rule as the snackbar host above). No permission gates (desktop
        // has no runtime grants) and no audio router (no AudioManager; the webrtc-java ADM
        // default output stands). Camera toggles pass through; the screen gates them on the
        // call's video flag, and 33a only ever places audio calls. Dismiss minimizes — the
        // call continues, the coordinator keeps state (the screen's own contract).
        if (ringingCall != null) {
            FlashCallScreen(
                state = ringingCall,
                session = calls?.media,
                onAccept = { scope.launch { calls?.accept() } },
                onDecline = { scope.launch { calls?.decline() } },
                onHangUp = { scope.launch { calls?.hangUp() } },
                onToggleMute = { calls?.toggleMute() },
                onToggleSpeaker = {
                    val next = !(calls?.activeCall?.value?.speakerOn ?: false)
                    calls?.setSpeaker(next)
                },
                onToggleCamera = { calls?.toggleCamera() },
                onSwitchCamera = { scope.launch { calls?.switchCamera() } },
                onDismiss = {
                    if (ringingCall.state == com.transfer.flash.core.calling.model.FlashCallState.ENDED) {
                        dismissedEndedCallId = ringingCall.callId
                    }
                },
                onVideoFocus = { peerId -> calls?.setVideoFocus(peerId) },
                onShowFewerVideos = { on -> calls?.setShowFewerVideos(on) },
                onSendSmallerVideo = {
                    scope.launch { engine.updateSettings { it.copy(smallerVideoForMany = true) } }
                },
                // ADR-067 / UI-050f. No audio routes (the desktop has no router: the speaker toggle stays) and no
                // picture-in-picture; the rest is the same as the phone.
                peerVerified = !ringingCall.isGroup && trustedPeersByCoordinator.any { it.id == ringingCall.peerId },
                onSetHandRaised = { raised -> calls?.setHandRaised(raised) },
                onSendReaction = { kind -> calls?.sendReaction(kind) ?: false },
                onSetDataSaver = { on -> calls?.setDataSaver(on) },
                // ADR-078: no permission gate on the desktop; a camera that will not open shows its own banner.
                onUpgradeToVideo = { scope.launch { calls?.upgradeToVideo() } },
                // ADR-102: the desktop can present a screen or a window; the phones can only watch.
                share = calls?.let { c ->
                    FlashCallShareHost(
                        listSources = { c.listShareSources() },
                        usesSystemPicker = c.shareUsesSystemPicker,
                        onStart = { source, quality, takeOver -> scope.launch { c.startScreenShare(source, quality, takeOver) } },
                        onStop = { scope.launch { c.stopScreenShare() } },
                        onSetQuality = { quality -> c.setShareQuality(quality) },
                        onDismissNotice = { c.dismissShareNotice() },
                    )
                },
            )
        }

        // Single-pane (!twoPane) modal inspection for Transfers and Nearby Devices.
        if (!twoPane && nav.current.destination == FlashDestination.Transfers && selectedTransferItem != null) {
            FlashSheetHost(
                onDismiss = { selectedTransferId = null },
                containerColor = FlashTheme.colors.backgroundSurface,
            ) {
                renderTransferDetail(selectedTransferItem)
            }
        }
        if (!twoPane && nav.current.destination == FlashDestination.NearbyDevices && selectedNearbyPeer != null) {
            FlashSheetHost(
                onDismiss = { selectedNearbyPeerId = null },
                containerColor = FlashTheme.colors.backgroundSurface,
            ) {
                renderNearbyDetail(selectedNearbyPeer, true)
            }
        }

        // RVW22 (owner report, desktop): these two used to be emitted BEFORE the layout, and Compose paints siblings in
        // emission order, so an error message and the "verify this device" pairing card were painted behind the sidebar
        // and the chat panes, out of reach (the pairing card is not shown on the Nearby tab, where it is part of the
        // screen). They are the LAST siblings now: above the layout, the push-to-talk card and the call screen.
        // The pairing card sits below the snackbar so a message can still be read while it is open.
        if (nav.current.destination != FlashDestination.NearbyDevices) {
            nearby.pairingRequest?.let { request ->
                val retryTarget = resolveDesktopPairingRetryTarget(
                    peerDeviceId = request.peerDeviceId ?: pairingUi?.peerDeviceId,
                    peerName = request.peerName,
                    lastPairingPeer = lastPairingPeer,
                    discoveredEndpoints = discoveredEndpoints,
                    trustedPeers = trustedPeersByCoordinator,
                )
                FlashPairingDialog(
                    request = request,
                    phase = nearby.pairingPhase,
                    secondsLeft = nearby.pairingSecondsLeft,
                    onAccept = { engine.pairing.acceptLocal() },
                    onDecline = {
                        clearPendingDesktopSharePairing()
                        engine.pairing.declineLocal()
                    },
                    onDismiss = {
                        clearPendingDesktopSharePairing()
                        engine.pairing.declineLocal()
                    },
                    onRetry = retryTarget?.let { target ->
                        { retryPairingWithTarget(target) }
                    },
                )
            }
        }
        // `tabBottomInset` lifts the snackbar clear of the hanging bottom nav in the compact layout; in two-pane there
        // is no nav and the inset is zero.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = tabBottomInset),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}

/** Desktop local equivalent of `:app`'s `FlashTransportType.toUiTransport()`. */
private fun FlashTransportType.toDesktopTransport(): FlashNetworkTransport = when (this) {
    FlashTransportType.WIFI_DIRECT -> FlashNetworkTransport.WifiDirect
    FlashTransportType.RELAY, FlashTransportType.MESH -> FlashNetworkTransport.Relay
    FlashTransportType.UNKNOWN, FlashTransportType.BLUETOOTH -> FlashNetworkTransport.Unknown
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
    state = if (isAwaitingReceiverAcceptance) FlashTransferState.Queued else when (state) {
        DomainState.Offered -> FlashTransferState.Offered
        DomainState.Queued -> FlashTransferState.Queued
        DomainState.Transferring, DomainState.Verifying -> FlashTransferState.Active
        DomainState.Paused -> FlashTransferState.Paused
        DomainState.Completed -> FlashTransferState.Completed
        DomainState.Failed, DomainState.Cancelled -> FlashTransferState.Failed
    },
    speedBytesPerSec = if (state == DomainState.Verifying) 0L else speedBytesPerSec,
    etaSeconds = if (state == DomainState.Verifying) null else etaSeconds.takeIf { it > 0L },
    errorMessage = com.transfer.flash.core.transfer.TransferFailureText.displayReason(errorMessage)
        ?: if (state == DomainState.Cancelled) "Cancelled" else null,
    verified = state == DomainState.Completed,
    localPath = localPath ?: sourceUri,
    // The one predicate lives on the model so the app, the desktop and the notifications agree.
    retryable = canRetry,
    transportLabel = if (viaHub) com.transfer.flash.core.enterprise.files.HubFileText.VIA_HUB else null,
    waitReason = waitReason,
    canGoOffline = canGoOffline,
    holdersOnline = holdersOnline,
    pieceBlocks = pieceBlocks,
    isGroupFile = groupOrigin,
    isVerifying = state == DomainState.Verifying,
)

/** Desktop twin of `:app`'s `TransfersUiState.fromDomain` (mandatory boot flags, ERROR-034). */
private fun TransfersUiState.Companion.fromDomain(
    transfers: List<FlashTransfer>,
    isLoading: Boolean,
    isError: Boolean,
): TransfersUiState = TransfersUiState.fromItems(transfers.map { it.toDesktopTransferItemUi() })
    .copy(isLoading = isLoading, isError = isError)

/** UI-058: the compact bottom bar's tabs, from the one shared source (see [desktopSideTabs]). */
private fun desktopBottomTabs(hubEnrolled: Boolean, hubBadge: Int = 0): List<FlashBottomNavItem> =
    FlashShellTabs.of(hubEnrolled, hubBadge = hubBadge).map { FlashBottomNavItem(it.destination, it.icon, it.label, it.badgeCount) }

/** Same pacing window the app shell derives from the theme's animation duration. */
private const val FLASH_TRANSFERS_THROTTLE_MS = 75L

/** AGENTS.md §24 tag for pairing; matches `DesktopEngine`'s `TAG_WS` convention. */
private const val TAG_PAIRING = "PAIR"

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

/**
 * Engine pairing phase → the shared UI's phase (the app's `PairingUiMapper`, restated here because
 * `:app` is not a dependency of `:desktop`).
 */
internal fun pairingPhaseOf(
    phase: com.transfer.flash.core.security.pairing.PairingPhase?,
): FlashPairingPhase = when (phase) {
    null, com.transfer.flash.core.security.pairing.PairingPhase.Idle -> FlashPairingPhase.Idle
    // v2 (ADR-042), same mapping as the app's PairingUiMapper: neither side has a code before
    // both nonces are known, so AwaitingPeerReveal and AwaitingPeerNonce stay Idle (never fake 000 000).
    com.transfer.flash.core.security.pairing.PairingPhase.AwaitingPeerReveal,
    com.transfer.flash.core.security.pairing.PairingPhase.AwaitingPeerNonce -> FlashPairingPhase.Idle
    com.transfer.flash.core.security.pairing.PairingPhase.RequestReceived,
    com.transfer.flash.core.security.pairing.PairingPhase.AwaitingLocalDecision ->
        FlashPairingPhase.RequestReceived
    com.transfer.flash.core.security.pairing.PairingPhase.AwaitingPeerConfirmation ->
        FlashPairingPhase.AwaitingPeerConfirmation
    com.transfer.flash.core.security.pairing.PairingPhase.Confirmed -> FlashPairingPhase.Paired
    com.transfer.flash.core.security.pairing.PairingPhase.DeclinedByPeer -> FlashPairingPhase.Declined
    com.transfer.flash.core.security.pairing.PairingPhase.Expired -> FlashPairingPhase.Expired
    com.transfer.flash.core.security.pairing.PairingPhase.Failed -> FlashPairingPhase.Failed
}

/**
 * The conversation header, filled from what the DESKTOP already knows about the peer.
 *
 * The repository's header cannot know the desktop's trust names, so this derives the
 * header from that real data instead of inventing any:
 *
 *  - **title** is the peer's trusted name, falling back to the name discovery is currently reporting;
 *  - **presence** is `Online` (Connected) when a live session exists, and `Reachable` (Online, ring
 *    dot; UI-030b) when discovery can see the peer right now without one. Discovery is the same fact
 *    the Nearby dot is drawn from, not a guess and not a heartbeat;
 *  - **showCallActions is true since 33a.** The voice and video buttons start calls via
 *    the shared coordinator (video live since 33c).
 *
 * Returns null when the conversation is not a known peer, so the caller falls back to the
 * repository's own state rather than to a fabricated one.
 */
internal fun desktopConversationHeader(
    conversationId: String?,
    trusted: List<FlashTrustedPeer>,
    discovered: List<FlashDiscoveredEndpoint>,
    isEncrypted: Boolean = false,
    presence: FlashPeerPresence? = null,
    transport: FlashNetworkTransport? = null,
    hasActiveSession: Boolean = false,
    isColleague: Boolean = false,
    orgName: String? = null,
): FlashChatHeaderUiState? {
    if (conversationId == null) return null
    val trustedName = trusted.firstOrNull { it.id == conversationId }?.name
    val endpoint = discovered.firstOrNull { it.deviceId.value == conversationId }
    val name = trustedName ?: endpoint?.friendlyName ?: return null
    val resolvedPresence = presence ?: when {
        hasActiveSession -> FlashPeerPresence.Online
        endpoint != null -> FlashPeerPresence.Reachable
        else -> FlashPeerPresence.Offline
    }
    val resolvedTransport = transport ?: if (resolvedPresence == FlashPeerPresence.Online) FlashNetworkTransport.Lan else FlashNetworkTransport.Unknown
    val isColleaguePeer = isColleague && trustedName == null
    return FlashChatHeaderUiState(
        title = name,
        avatarInitials = name.trim().split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" },
        avatarSeed = name,
        presence = resolvedPresence,
        transport = resolvedTransport,
        isGroup = false,
        isEncrypted = isEncrypted,
        showCallActions = true,
        isColleague = isColleaguePeer,
        orgName = if (isColleaguePeer) orgName else null,
    )
}

/**
 * The Nearby screen's whole state, from its inputs and nothing else.
 *
 * Top-level and **pure** on purpose. This body used to be inline in a `remember`ed `derivedStateOf`
 * that closed over values computed in the composable body, and two of those were plain `val`s — which
 * a `derivedStateOf` does not track, so both froze at the composition that created it. See the call
 * site for the two measured failures. With every input a parameter there is no enclosing scope left
 * to capture from, and being pure is what lets [DesktopNearbyStateTest] assert it.
 *
 * The invariant the two peer lists hold together: **every peer appears as a trusted row or as a
 * discovered row, never neither.** `trustedIds` filters `peers`, so a trusted list that lags that set
 * by one frame removes a peer from both lists at once — indistinguishable, on screen, from the peer
 * having gone away.
 */
internal fun nearbyUiStateOf(
    trusted: List<FlashTrustedPeer>,
    discovered: List<FlashDiscoveredEndpoint>,
    discoveryState: FlashDiscoveryState,
    ui: FlashPairingCoordinator.PairingUi?,
    ready: Boolean,
    localFriendlyName: String,
    localDeviceId: String,
    liveSessions: Int,
    scan: NearbyNetworkScan = NearbyNetworkScan.Idle,
    isOrgPeer: (String) -> Boolean = { false },
    orgName: String? = null,
    localAddressLabel: String? = null,
    radiosAvailable: Boolean = true,
    failureReason: String? = null,
): NearbyUiState {
    val trustedIds = trusted.mapTo(HashSet()) { it.id }
    // Every discovered peer as a row, BEFORE the trusted filter — the join in `withDeviceKinds`
    // below looks a trusted peer up here, and a trusted peer is deliberately excluded from `peers`.
    val discoveredRows = discovered.map { ep ->
        val peerId = ep.deviceId.value
        val isColleague = isOrgPeer(peerId)
        NearbyPeerUi(
            id = peerId,
            name = ep.friendlyName,
            transport = ep.transportType.toDesktopTransport(),
            isTrusted = false,
            deviceKind = ep.deviceKind,
            isColleague = isColleague,
            orgName = if (isColleague) orgName else null,
        )
    }
    return NearbyUiState(
        identity = NearbyIdentityUi(
            displayName = localFriendlyName,
            deviceIdShort = localDeviceId.take(8).ifBlank { "00000000" },
            port = discoveryState.advertisedPort,
            localAddressLabel = localAddressLabel,
        ),
        isScanning = discoveryState.isDiscovering,
        radiosAvailable = radiosAvailable,
        isLoading = !ready,
        peers = discoveredRows.filter { it.id !in trustedIds },
        trustedPeers = FlashNearbyMath.withDeviceKinds(
            trusted = trusted.map { NearbyTrustedPeerUi(id = it.id, name = it.name, verified = it.verified) },
            discovered = discoveredRows,
        ),
        pairingRequest = ui?.let { u ->
            val resolvedTransport = (
                u.peerDeviceId?.let { pid -> discoveredRows.firstOrNull { it.id == pid } }
                    ?: discoveredRows.firstOrNull { it.name == u.peerName }
                )?.transport
                ?: FlashNetworkTransport.Unknown
            FlashPairingRequestUi(
                peerName = u.peerName,
                peerInitials = u.peerName.trim().split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercase() }
                    .ifBlank { "?" },
                numericCode = u.numericCode,
                transport = resolvedTransport,
                expiresInSeconds = u.secondsLeft,
                failureReason = failureReason,
                peerDeviceId = u.peerDeviceId,
            )
        },
        pairingPhase = pairingPhaseOf(ui?.phase),
        pairingSecondsLeft = ui?.secondsLeft ?: 0,
        scan = scan,
        discoveryQuiet = FlashNearbyMath.discoveryQuiet(
            pairedPeers = trusted.size,
            discoveredPeers = discovered.size,
            liveSessions = liveSessions,
            isDiscovering = discoveryState.isDiscovering,
        ),
    )
}

/**
 * The sweep's state as the Nearby page shows it (DR3). Twin of `SweepState.toNearbyScan` in the Android host
 * (`MainActivity`), kept identical on purpose: `ui:chat` cannot see `core:network`, and a display rule that
 * differed per host would be a bug. An automatic sweep is shown while it runs (the action must not start a
 * second one) but its outcome is not, because the user did not ask for it.
 */
internal fun SweepState.toNearbyScan(): NearbyNetworkScan = when (this) {
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

internal const val EMPTY_FOLDER_SHARE_MESSAGE = "That folder doesn't contain any files to send."

internal fun visibleChatTargetForDrop(
    destination: FlashDestination,
    navConversationId: String?,
    selectedChatConversationId: String?,
    twoPane: Boolean,
): String? = when (destination) {
    FlashDestination.Conversation -> navConversationId?.takeIf { it.isNotBlank() }
    FlashDestination.ChatList -> if (twoPane) selectedChatConversationId?.takeIf { it.isNotBlank() } else null
    else -> null
}

internal fun canDeliverDesktopShareDirectly(
    recipient: FlashShareRecipientUi,
    trustedPeerIds: Set<String>,
): Boolean = recipient.isGroup || recipient.id in trustedPeerIds

internal fun resolveSelectedNearbyPeer(
    peerId: String?,
    nearby: NearbyUiState,
    discovered: List<FlashDiscoveredEndpoint>,
    orgName: String? = null,
    isOrgPeer: (String) -> Boolean = { false },
): NearbyPeerUi? {
    val targetId = peerId?.takeIf { it.isNotBlank() } ?: return null
    nearby.peers.firstOrNull { it.id == targetId }?.let { return it }
    val trusted = nearby.trustedPeers.firstOrNull { it.id == targetId } ?: return null
    val endpoint = discovered.firstOrNull { it.deviceId.value == targetId }
    val isColleague = isOrgPeer(targetId)
    return NearbyPeerUi(
        id = trusted.id,
        name = trusted.name,
        transport = endpoint?.transportType?.toDesktopTransport() ?: FlashNetworkTransport.Lan,
        isTrusted = true,
        deviceKind = endpoint?.deviceKind ?: trusted.deviceKind,
        isColleague = isColleague,
        orgName = if (isColleague) orgName else null,
    )
}

internal fun resolveSelectedTransferItem(
    transferId: String?,
    transfersUi: TransfersUiState,
): FlashTransferItemUi? {
    val id = transferId?.takeIf { it.isNotBlank() } ?: return null
    return transfersUi.offers.firstOrNull { it.id == id }
        ?: transfersUi.active.firstOrNull { it.id == id }
        ?: transfersUi.failed.firstOrNull { it.id == id }
        ?: transfersUi.history.firstOrNull { it.id == id }
}

internal fun resolveDesktopPairingRetryTarget(
    peerDeviceId: String?,
    peerName: String,
    lastPairingPeer: Pair<String, String>?,
    discoveredEndpoints: List<FlashDiscoveredEndpoint>,
    trustedPeers: List<FlashTrustedPeer>,
): Pair<String, String>? {
    val cleanId = peerDeviceId?.takeIf { it.isNotBlank() }
    if (cleanId != null) {
        val resolvedName = lastPairingPeer?.takeIf { it.first == cleanId }?.second
            ?: discoveredEndpoints.firstOrNull { it.deviceId.value == cleanId }?.friendlyName
            ?: trustedPeers.firstOrNull { it.id == cleanId }?.name
            ?: peerName
        return cleanId to resolvedName
    }
    return lastPairingPeer?.takeIf { it.second == peerName }
        ?: discoveredEndpoints.firstOrNull { it.friendlyName == peerName }
            ?.let { it.deviceId.value to it.friendlyName }
        ?: trustedPeers.firstOrNull { it.name == peerName }
            ?.let { it.id to it.name }
}

internal fun mergeDesktopSharePayload(
    existing: FlashSharePayloadUi?,
    incomingItems: List<FlashShareItemUi>,
): FlashSharePayloadUi = FlashSharePayloadUi(
    items = ((existing?.items ?: emptyList()) + incomingItems).distinctBy { it.uri },
    text = existing?.text,
)

internal fun selectDesktopShellTab(
    destination: FlashDestination,
    nav: FlashNavigationState,
    twoPane: Boolean,
    selectedChatConversationId: String?,
    onOpenConversation: (String) -> Unit,
    onCloseConversation: () -> Unit,
    onClearSelections: () -> Unit,
    onHubReselect: () -> Unit = {},
) {
    if (destination == FlashDestination.ChatList) {
        if (twoPane && nav.conversationOpenedFromHub && selectedChatConversationId != null) {
            onOpenConversation(selectedChatConversationId)
            nav.selectChatsTab(selectedChatConversationId)
        } else if (nav.current.destination == FlashDestination.Conversation) {
            onCloseConversation()
            onClearSelections()
            nav.selectTab(FlashDestination.ChatList)
        } else {
            onClearSelections()
            nav.selectTab(FlashDestination.ChatList)
        }
    } else {
        if (destination == FlashDestination.Hub && nav.current.destination == FlashDestination.Hub) {
            onHubReselect()
        } else {
            onClearSelections()
        }
        if (destination == FlashDestination.Transfers) {
            nav.openTransfers()
        } else {
            nav.selectTab(destination)
        }
    }
}

internal enum class DesktopEscapeAction {
    DismissEndedCall,
    ConsumeInCall,
    CloseRadioGateway,
    StopPtt,
    CloseSearch,
    ClearTransferDetail,
    PopTransfersBack,
    ClearNearbyDetail,
    CloseConversation,
    None,
}

internal fun resolveDesktopEscapeAction(
    pttActive: Boolean,
    isSearching: Boolean,
    destination: FlashDestination,
    hubEnrolled: Boolean,
    stackSize: Int,
    twoPane: Boolean,
    selectedChatConversationId: String?,
    hasSelectedTransferItem: Boolean,
    hasSelectedNearbyPeer: Boolean,
    hasEndedCallOverlay: Boolean = false,
    hasActiveCallOverlay: Boolean = false,
    showRadioGateway: Boolean = false,
): DesktopEscapeAction = when {
    hasEndedCallOverlay -> DesktopEscapeAction.DismissEndedCall
    hasActiveCallOverlay -> DesktopEscapeAction.ConsumeInCall
    pttActive -> DesktopEscapeAction.StopPtt
    isSearching -> DesktopEscapeAction.CloseSearch
    destination == FlashDestination.Settings && showRadioGateway ->
        DesktopEscapeAction.CloseRadioGateway
    destination == FlashDestination.Transfers && hasSelectedTransferItem ->
        DesktopEscapeAction.ClearTransferDetail
    destination == FlashDestination.Transfers && hubEnrolled && stackSize > 1 ->
        DesktopEscapeAction.PopTransfersBack
    destination == FlashDestination.NearbyDevices && hasSelectedNearbyPeer ->
        DesktopEscapeAction.ClearNearbyDetail
    destination == FlashDestination.Conversation ||
        (twoPane && destination == FlashDestination.ChatList && !selectedChatConversationId.isNullOrBlank()) ->
        DesktopEscapeAction.CloseConversation
    else -> DesktopEscapeAction.None
}

internal sealed interface DesktopShortcutAction {
    data object OpenSearch : DesktopShortcutAction
    data class SelectTab(val destination: FlashDestination) : DesktopShortcutAction
    data object OpenTransfers : DesktopShortcutAction
    data object TogglePtt : DesktopShortcutAction
}

internal fun resolveDesktopShortcut(
    key: Key,
    shiftPressed: Boolean,
    hubEnrolled: Boolean,
    hasActiveCall: Boolean = false,
    hasPendingShare: Boolean = false,
): DesktopShortcutAction? {
    if (hasActiveCall || hasPendingShare) return null
    val tabs = FlashShellTabs.of(hubEnrolled)
    return when {
        key == Key.F && !shiftPressed -> DesktopShortcutAction.OpenSearch
        key == Key.T && shiftPressed -> DesktopShortcutAction.TogglePtt
        key == Key.T && !shiftPressed -> DesktopShortcutAction.OpenTransfers
        (key == Key.One || key == Key.NumPad1) && !shiftPressed ->
            tabs.getOrNull(0)?.let { DesktopShortcutAction.SelectTab(it.destination) }
        (key == Key.Two || key == Key.NumPad2) && !shiftPressed ->
            tabs.getOrNull(1)?.let { DesktopShortcutAction.SelectTab(it.destination) }
        (key == Key.Three || key == Key.NumPad3) && !shiftPressed ->
            tabs.getOrNull(2)?.let { DesktopShortcutAction.SelectTab(it.destination) }
        (key == Key.Four || key == Key.NumPad4) && !shiftPressed ->
            tabs.getOrNull(3)?.let { DesktopShortcutAction.SelectTab(it.destination) }
        (key == Key.Five || key == Key.NumPad5) && !shiftPressed ->
            DesktopShortcutAction.OpenTransfers
        key == Key.Comma && !shiftPressed ->
            DesktopShortcutAction.SelectTab(FlashDestination.Settings)
        else -> null
    }
}

internal fun isFullWidthDesktopDestination(destination: FlashDestination): Boolean =
    destination == FlashDestination.Settings || destination == FlashDestination.Hub

// OPT-O03-10: Dedicated settings destination isolating high-frequency background/telemetry flows
// (radioUiState at 1-5 Hz, hubDropped, hubSealingKeyState, orgLease, storage usage scan) from
// root DesktopShell so chats, navigation, and window resizing do not recompose when background flows tick.
@Composable
private fun DesktopSettingsDestination(
    engine: DesktopEngine,
    ready: Boolean,
    themeMode: FlashThemeMode,
    onThemeModeSelected: (FlashThemeMode) -> Unit,
    localFriendlyName: String,
    trustedPeersCount: Int,
    enterpriseCredentials: com.transfer.flash.core.security.enterprise.EnrollmentCredentials?,
    desktopSettings: DesktopSettings,
    hubStatus: com.transfer.flash.core.enterprise.HubStatus?,
    onEditDisplayName: () -> Unit,
    onOpenEncryption: () -> Unit,
    onOpenTrustedPeers: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    settingsScroll: androidx.compose.foundation.lazy.LazyListState,
    tabBottomInset: androidx.compose.ui.unit.Dp,
    window: java.awt.Window?,
    radioGatewayController: com.transfer.flash.ui.radio.RadioGatewayController,
    showRadioGateway: Boolean,
    onOpenRadioGateway: () -> Unit,
    onCloseRadioGateway: () -> Unit,
) {
    if (showRadioGateway) {
        com.transfer.flash.ui.radio.FlashRadioGatewayScreen(
            controller = radioGatewayController,
            onBack = onCloseRadioGateway,
            bottomInset = tabBottomInset,
        )
        return
    }
    var receivedBytes by remember { mutableStateOf<Long?>(null) }
    // The organisation hub (ADR-120): live status, previously linked groups and the content opt-in.
    val hubObject = remember(ready) { engine.enterpriseHub }
    // ADR-126: the sealing key state and the dropped-message counts for the connection details.
    val hubSealingKeyFallback = remember { kotlinx.coroutines.flow.MutableStateFlow(com.transfer.flash.core.enterprise.HubSealingKeyState.IDLE) }
    val hubSealingKeyState by (hubObject?.sealingKeys?.state ?: hubSealingKeyFallback).collectAsState()
    val hubDroppedFlow = remember(engine, ready) { if (ready) engine.chats?.hubDropped else null }
    val hubDroppedFallback = remember { kotlinx.coroutines.flow.MutableStateFlow(com.transfer.flash.core.messaging.hub.HubDropCounts()) }
    val hubDropped by (hubDroppedFlow ?: hubDroppedFallback).collectAsState()
    val hubPreviousLinks by (hubObject?.trustStore?.previousHubLinks
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val hubRelinkable = remember(hubObject, hubPreviousLinks, enterpriseCredentials) { hubObject?.relinkableGroups()?.size ?: 0 }
    val orgLeaseFlow = remember(engine, ready) {
        engine.orgPeerResolver?.lease ?: kotlinx.coroutines.flow.MutableStateFlow<com.transfer.flash.core.enterprise.OrgLease>(com.transfer.flash.core.enterprise.OrgLease.None)
    }
    val orgLease by orgLeaseFlow.collectAsState()
    val orgMismatchFlow = remember(engine, ready) {
        engine.orgPeerResolver?.identityMismatch ?: kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    val orgIdentityMismatch by orgMismatchFlow.collectAsState()
    val orgTrustState = remember(orgLease, enterpriseCredentials, orgIdentityMismatch) {
        if (enterpriseCredentials == null) null
        else {
            val clockOk = (orgLease as? com.transfer.flash.core.enterprise.OrgLease.Expired)?.reason != com.transfer.flash.core.enterprise.ExpiredReason.CLOCK
            FlashHubStatusMath.orgTrustState(
                lease = orgLease,
                clockOk = clockOk,
                hubSupports = true,
                identityMismatch = orgIdentityMismatch,
                nowMs = System.currentTimeMillis(),
            )
        }
    }
    var isEnrollingDevice by remember { mutableStateOf(false) }
    var enrollDeviceError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engine, ready, desktopSettings.saveLocation) {
        if (ready) receivedBytes = withContext(Dispatchers.IO) { DesktopHelpers.receivedFilesBytes(engine) }
    }
    val radioUiState by radioGatewayController.state.collectAsState()
    val settings = remember(
        engine, ready, receivedBytes, themeMode, trustedPeersCount, desktopSettings,
        localFriendlyName, enterpriseCredentials, hubStatus, hubRelinkable,
        hubSealingKeyState, hubDropped, orgTrustState, radioUiState.enabled
    ) {
        FlashSettingsModel(
            displayName = localFriendlyName,
            deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
            appVersion = engine.appVersionName,
            protocolVersion = engine.protocolVersionLabel,
            receivedFilesBytes = receivedBytes,
            trustedPeerCount = trustedPeersCount,
            themeMode = themeMode,
            discoveryMode = desktopSettings.discoveryMode.name,
            windowsContextMenu = desktopSettings.windowsContextMenu,
            showWindowsContextMenu = WindowsContextMenuManager.isSupported,
            dynamicAccent = desktopSettings.dynamicAccent,
            autoDownloadVoice = desktopSettings.autoDownloadVoice,
            autoDownloadImage = desktopSettings.autoDownloadImage,
            autoDownloadVideo = desktopSettings.autoDownloadVideo,
            autoDownloadFile = desktopSettings.autoDownloadFile,
            prioritiseVoiceQuality = desktopSettings.prioritiseVoiceQuality,
            smallerVideoForMany = desktopSettings.smallerVideoForMany,
            performanceMode = desktopSettings.performanceMode,
            backgroundTransfers = desktopSettings.closeToTray,
            saveLocationLabel = desktopSettings.saveLocation ?: engine.canonicalRoot.absolutePath,
            swarmHelpShare = desktopSettings.swarmHelpShare,
            swarmKeepFinishedFiles = desktopSettings.swarmKeepFinishedFiles,
            swarmEnabled = desktopSettings.groupSwarmEnabled,
            launchAnimation = desktopSettings.launchAnimation,
            isEnterpriseEnrolled = enterpriseCredentials != null,
            enterpriseHubUrl = enterpriseCredentials?.hubUrl.orEmpty(),
            enterpriseDepartment = enterpriseCredentials?.department.orEmpty(),
            enterpriseRole = enterpriseCredentials?.role ?: "member",
            enterpriseRootFingerprint = enterpriseCredentials?.rootFingerprint.orEmpty(),
            hubStatus = hubStatus,
            hubContentViaHub = desktopSettings.hubContentViaHub,
            announcementNotifications = desktopSettings.announcementNotifications,
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
    }

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
            scope.launch {
                val outcome = hub.enroll(invite)
                isEnrollingDevice = false
                when (outcome) {
                    is com.transfer.flash.core.enterprise.EnrollmentOutcome.Enrolled -> {
                        enrollDeviceError = null
                        snackbarHostState.showSnackbar(
                            "You joined ${outcome.credentials.hubName.ifBlank { "your organisation" }}",
                            duration = SnackbarDuration.Long,
                        )
                    }
                    is com.transfer.flash.core.enterprise.EnrollmentOutcome.Failed -> {
                        enrollDeviceError = com.transfer.flash.ui.settings.FlashHubStatusMath.enrollFailureText(outcome.reason)
                    }
                }
            }
        }
    }

    FlashSettingsScreen(
        model = settings,
        onEditDisplayName = onEditDisplayName,
        onOpenEncryption = onOpenEncryption,
        onOpenTrustedPeers = onOpenTrustedPeers,
        onHapticsChanged = null,
        showHapticsToggle = false,
        onOpenBatterySettings = null,
        showBatteryOptimization = false,
        onThemeModeSelected = onThemeModeSelected,
        onDiscoveryModeChanged = { modeStr ->
            val mode = runCatching { FlashDiscoveryMode.valueOf(modeStr) }
                .getOrDefault(FlashDiscoveryMode.STANDARD)
            engine.setDiscoveryMode(mode)
        },
        onWindowsContextMenuChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(windowsContextMenu = next) } }
        },
        onDynamicAccentChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(dynamicAccent = next) } }
        },
        onAutoDownloadVoiceChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(autoDownloadVoice = next) } }
        },
        onAutoDownloadImageChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(autoDownloadImage = next) } }
        },
        onAutoDownloadVideoChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(autoDownloadVideo = next) } }
        },
        onAutoDownloadFileChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(autoDownloadFile = next) } }
        },
        onPrioritiseVoiceQualityChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(prioritiseVoiceQuality = next) } }
        },
        onSmallerVideoForManyChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(smallerVideoForMany = next) } }
        },
        onPerformanceModeSelected = { next ->
            scope.launch { engine.updateSettings { it.copy(performanceMode = next) } }
        },
        onBackgroundTransfersChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(closeToTray = next) } }
        },
        backgroundListenTitle = "Close to tray",
        backgroundListenSubtitle = "Closing the window keeps Flash running in the notification area",
        onSwarmHelpShareChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(swarmHelpShare = next) } }
        },
        onSwarmKeepFinishedFilesChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(swarmKeepFinishedFiles = next) } }
        },
        onSwarmEnabledChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(groupSwarmEnabled = next) } }
        },
        onLaunchAnimationChanged = { next ->
            scope.launch { engine.updateSettings { it.copy(launchAnimation = next) } }
        },
        onPickSaveLocation = {
            val chooser = javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Select Received Files Folder"
                currentDirectory = engine.canonicalRoot
            }
            if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) {
                val selectedFile = chooser.selectedFile
                if (selectedFile != null && selectedFile.isDirectory) {
                    scope.launch {
                        engine.updateSettings { it.copy(saveLocation = selectedFile.absolutePath) }
                        receivedBytes = withContext(Dispatchers.IO) {
                            DesktopHelpers.receivedFilesBytes(engine)
                        }
                    }
                }
            }
        },
        onRefreshStorageUsage = {
            scope.launch {
                receivedBytes = withContext(Dispatchers.IO) {
                    DesktopHelpers.receivedFilesBytes(engine)
                }
            }
        },
        onClearReceivedFiles = {
            scope.launch {
                withContext(Dispatchers.IO) { DesktopHelpers.clearReceivedFiles(engine) }
                receivedBytes = withContext(Dispatchers.IO) {
                    DesktopHelpers.receivedFilesBytes(engine)
                }
            }
        },
        onEnrollDevice = onEnrollDeviceAction,
        onDisconnectEnterprise = {
            scope.launch {
                val result = engine.enterpriseHub?.unenrol()
                snackbarHostState.showSnackbar(
                    if (result == null) "Flash is still starting. Try again in a moment."
                    else com.transfer.flash.ui.settings.FlashHubStatusMath.unenrolText(result),
                    duration = SnackbarDuration.Short,
                )
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
        onSetHubContentViaHub = { enabled -> engine.updateSettings { it.copy(hubContentViaHub = enabled) } },
        onAnnouncementNotificationsChanged = { enabled -> engine.updateSettings { it.copy(announcementNotifications = enabled) } },
        onRestoreHubGroups = {
            scope.launch {
                val restored = engine.enterpriseHub?.relinkPreviousGroups() ?: 0
                snackbarHostState.showSnackbar(
                    if (restored == 1) "Restored 1 group" else "Restored $restored groups",
                    duration = SnackbarDuration.Short,
                )
            }
        },
        onForgetHubGroups = { scope.launch { engine.enterpriseHub?.forgetPreviousGroups() } },
        isEnrollingDevice = isEnrollingDevice,
        enrollDeviceError = enrollDeviceError,
        onOpenRadioGateway = onOpenRadioGateway,
        radioGatewayController = radioGatewayController,
        modifier = Modifier.fillMaxSize(),
        listState = settingsScroll,
        bottomInset = tabBottomInset,
    )
}
