@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.FlashTrustedPeer
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection as DomainDirection
import com.transfer.flash.core.transfer.model.FlashTransferState as DomainState
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.calling.FlashCallScreen
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashConversationScreen
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.settings.FlashThemeMode
import com.transfer.flash.ui.settings.FlashDisplayNameDialog
import com.transfer.flash.ui.chat.FlashPairingRequestUi
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    val nav = rememberFlashNavigationState()
    val scope = rememberCoroutineScope()

    val ready by engine.ready.collectAsState()
    val startError by engine.startError.collectAsState()

    // One snackbar surface for the whole window. Declared here, above the collectors that raise
    // messages, so nothing depends on declaration order.
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Phase 22: window size + selection state for the two-pane layout ──
    val sizeClass = rememberFlashDesktopWindowSize()
    val twoPane = FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)
    var selectedTransferItem by remember { mutableStateOf<FlashTransferItemUi?>(null) }
    var selectedNearbyPeer by remember { mutableStateOf<NearbyPeerUi?>(null) }

    // ── Chats: the real repository once boot builds it, honest-empty before that ──
    // Keyed on `ready` (not just `engine`): `engine.chats` swaps from the empty stand-in
    // to the durable repository during `assemble()`, and a `remember(engine)` alone would
    // pin whichever one was current at first composition — the empty one — forever.
    val chatRepository = remember(engine, ready) { engine.chats }
    val chatListState by chatRepository.chatListState.collectAsState()
    // The conversation screen's whole state. Before boot the repository is still the honest
    // empty stand-in, so the screen renders its empty state rather than nothing.
    val repositoryConversation by chatRepository.conversationState.collectAsState()

    // ── Calls, 33a: the shared coordinator behind the shared overlay ──
    // `calls` is null until assemble builds it; the empty flow keeps this collect
    // unconditional (same slot-table reasoning as the chats line above).
    val calls = remember(engine, ready) { engine.calls }
    val activeCall by remember(calls) {
        calls?.activeCall ?: MutableStateFlow(null)
    }.collectAsState()

    // 33a entry: voice call to a trusted peer. A false return (no session, no microphone,
    // call already live) surfaces on the snackbar — the "Couldn't reach…" pattern pairing
    // uses — rather than failing silently. Callers pass the name they already show, so this
    // stays above the Nearby roster declarations it would otherwise need to read.
    fun placeVoiceCall(peerId: String, peerName: String) {
        scope.launch {
            val ok = calls?.startCall(peerId, peerName, video = false) == true
            if (!ok) {
                snackbarHostState.showSnackbar(
                    message = "Couldn't start the call. Make sure the peer is reachable, then try again.",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
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

    // ── Nearby: discovery endpoints + trust store + the Phase 26-3 pairing dialog ──
    val pairingUi by engine.pairing.pairing.collectAsState()
    // Pairing status — "Connecting to X…", "Paired with X.", "Pairing ended (…)".
    //
    // These went to `println` only, because "the desktop tier has no toast surface yet". That made
    // every pairing outcome invisible unless the user happened to be watching a terminal: a declined
    // or failed pairing looked exactly like nothing having happened at all. They now reach a
    // snackbar as well, which is where the same strings land on Android (via Toast).
    //
    // The log line is kept rather than replaced — `~/.flash/desktop.log` is the artifact a bug report
    // carries, and a message that only ever appeared on screen would be gone by the time anyone
    // looked.
    LaunchedEffect(engine, snackbarHostState) {
        // `collectLatest`, NOT `collect`. `showSnackbar` SUSPENDS for the snackbar's whole lifetime,
        // so a plain `collect` processes one message per ~4 s while the rest pile up behind it —
        // and every queued message re-runs `dismiss()` then `showSnackbar()`, which is a cancelling
        // and re-showing loop for as long as messages keep arriving. `collectLatest` cancels the
        // in-flight `showSnackbar` when a newer message lands, which is the "newest wins" behaviour
        // these strings have as Toasts on Android, with no backlog and no churn.
        engine.pairing.messages.collectLatest { message ->
            FlashLog.i(TAG_PAIRING, message)
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    // The pairing DIALOG is driven entirely by this one value, so when it fails to appear there is
    // otherwise no way to tell "the engine never published a phase" from "the shell never re-read
    // it" — and that ambiguity is the whole of the 2026-09-14 report ("the request shows on the
    // phone, no dialog on the PC"). Printed on every change, like the dial lines: the next report
    // is a log line instead of an absence.
    LaunchedEffect(engine) {
        engine.pairing.pairing.collect { ui ->
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
    // From the pairing coordinator's flow, NOT a `derivedStateOf` over the trust store: that store
    // is a plain ConcurrentHashMap with no snapshot state, so a derivation read none of it — it
    // computed once at first composition and never invalidated, leaving a just-paired peer showing
    // Pair (and a revoked one showing Chat) for the life of the window.
    // Mapped at this edge: the coordinator is now shared (`:core:security`) and publishes its own
    // `FlashTrustedPeer`, so a Compose-era UI type must not be part of its published API. A plain
    // map rather than a `derivedStateOf` — it is a short list built from an already-collected state,
    // and the extra state object would only add a way to go stale.
    val trustedPeersByCoordinator by engine.pairing.trustedPeers.collectAsState()

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
    val nearby by remember(engine, engine.discovery) {
        derivedStateOf {
            nearbyUiStateOf(
                trusted = trustedPeersByCoordinator,
                discovered = discoveredEndpoints,
                discoveryState = discoveryState,
                ui = pairingUi,
                ready = ready,
                localFriendlyName = engine.localFriendlyName,
                localDeviceId = engine.localDeviceId,
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
    val conversationIdIsTrusted = trustedPeersByCoordinator.any { it.id == nav.current.conversationId }

    val conversationState = remember(
        repositoryConversation,
        nav.current.conversationId,
        trustedPeersByCoordinator,
        discoveredEndpoints,
    ) {
        val header = desktopConversationHeader(
            conversationId = nav.current.conversationId,
            trusted = trustedPeersByCoordinator,
            discovered = discoveredEndpoints,
        )
        if (header != null) repositoryConversation.copy(header = header) else repositoryConversation
    }

    // ── Settings: no persisted DataStore on desktop until 09B-3; honest defaults ──
    //
    // `receivedFilesBytes` IS live, unlike the settings tier: it is a filesystem fact, not a
    // preference, so it needs no DataStore. It is load-bearing rather than cosmetic — the storage
    // card enables its Clear control only for a scan that returned a positive total
    // (`FlashStorageMath.canClearReceivedFiles`), so leaving this null left that control
    // permanently disabled and the confirmation dialog unreachable on desktop. Null is still the
    // honest initial value (the scan has not run yet) and resolves to a real 0 on an empty folder.
    var showRenameDialog by remember { mutableStateOf(false) }
    var receivedBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(engine, ready) {
        if (ready) receivedBytes = withContext(Dispatchers.IO) { DesktopHelpers.receivedFilesBytes(engine) }
    }
    val settings = remember(engine, ready, receivedBytes, themeMode, trustedPeersByCoordinator) {
        FlashSettingsModel(
            displayName = engine.localFriendlyName,
            deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
            appVersion = engine.appVersionName,
            protocolVersion = engine.protocolVersionLabel,
            receivedFilesBytes = receivedBytes,
            // Was left at 0, so Settings → Trusted peers read "No verified devices yet" even
            // immediately after a successful pairing — while the Nearby tab beside it was listing the
            // same peer as paired. The list is already collected for that screen; this is the count.
            trustedPeerCount = trustedPeersByCoordinator.size,
            // Was left at its `System` default, so the segmented control's selection was a constant
            // and never reflected a real choice. `themeMode` is the same value `DesktopMain` resolves
            // into `FlashTheme(darkTheme = …)`, so the control and the repaint cannot disagree.
            themeMode = themeMode,
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
                    FlashDestination.Conversation -> FlashConversationScreen(
                        state = conversationState,
                        onBack = { nav.back() },
                        // Whether this peer is in the trust store, from the same list the Nearby
                        // screen's rows and this screen's header already read.
                        //
                        // Defaulted to `false` and never passed, this left EVERY desktop chat claiming
                        // to be unpaired: the peer-details sheet read "Not paired" and the header's
                        // encryption chip read "Unverified", for a device the user had just paired
                        // with and was looking at on the Nearby tab. Nothing was wrong with the trust
                        // data — it was simply not forwarded.
                        isPeerTrusted = conversationIdIsTrusted,
                        onRevokePeerTrust = if (conversationIdIsTrusted) {
                            {
                                nav.current.conversationId?.let { id ->
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
                        onSendText = { chatRepository.sendText(it) },
                        // 33a entry: voice call from the header. Video stays hidden until 33c
                        // (`showVideoCallAction = false`): 33a is audio-only, and a video button
                        // with nowhere to go would be the dead-control trap again.
                        onStartCall = {
                            nav.current.conversationId?.let { id ->
                                placeVoiceCall(id, conversationState.header.title)
                            }
                        },
                        showVideoCallAction = false,
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
                                    repo.resumeTransfer(com.transfer.flash.core.transfer.model.FlashTransferId(tid))
                                }
                            }
                        },
                        onOpenAttachment = { path, mime, _ ->
                            DesktopHelpers.openAttachment(path, mime)
                        },
                        // The desktop picker seam exists and is tested (`FlashFilePicker.jvm`), but
                        // wiring it to a send is Phase 30's job; until then this is an honest no-op
                        // rather than a stub that swallows a picked file.
                        onAttachmentClick = { },
                        // Both helpers already existed and were never called, so the media viewer's
                        // Save and Share were silently inert. Save writes a copy next to the
                        // original under the received root; Share hands the file to the OS.
                        onSaveImage = { uri, mime -> DesktopHelpers.saveImageToGallery(uri, mime) },
                        onShareImage = { uri, mime -> DesktopHelpers.shareImageUri(uri, mime) },
                    )
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
                    FlashDestination.NearbyDevices -> {
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
                    FlashNearbyScreen(
                        state = nearby,
                        onPairClick = { peer ->
                            // Ensure a session exists (dial is idempotent/coalesced), then start
                            // the handshake — the desktop twin of the app's flow (Phase 26-3).
                            val endpoint = discoveredEndpoints.firstOrNull { it.deviceId.value == peer.id }
                            val net = engine.network
                            if (endpoint != null && net != null) {
                                scope.launch {
                                    net.connectManual(endpoint.hostAddress, endpoint.port)
                                    engine.pairing.beginPair(peer.id, peer.name)
                                }
                            } else {
                                engine.pairing.beginPair(peer.id, peer.name)
                            }
                            // Two-pane: show what we know about the peer while it connects.
                            if (twoPane) selectedNearbyPeer = peer
                        },
                        onAcceptPairing = { engine.pairing.acceptLocal() },
                        onDeclinePairing = { engine.pairing.declineLocal() },
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
                        // 33a entry: voice call straight from the trusted row. Only trusted rows
                        // offer it — the coordinator refuses untrusted peers anyway (Group Phase
                        // 0 closure), so offering it elsewhere would be a button that always fails.
                        onCallTrustedClick = { trusted ->
                            placeVoiceCall(trusted.id, trusted.name)
                        },
                        modifier = Modifier.fillMaxSize(),
                        listState = nearbyScroll,
                        bottomInset = tabBottomInset,
                    )
                    }
                    FlashDestination.Settings -> FlashSettingsScreen(
                        model = settings,
                        onEditDisplayName = { showRenameDialog = true },
                        onThemeModeSelected = onThemeModeSelected,
                        // These three rows are SHOWN on desktop and deliberately not persisted yet;
                        // the switch reflects the model, which stays at its default. Hiding them was
                        // tried and rejected — see this session's log. Wiring them to
                        // `DesktopSettingsStore` is the follow-up.
                        onDynamicAccentChanged = { },
                        onHapticsChanged = { },
                        onBackgroundTransfersChanged = { },
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
                                // Re-scan rather than assuming zero: the clear is best-effort per
                                // child (`runCatching`), so a locked file survives it and the card
                                // must say so instead of claiming the folder is empty.
                                receivedBytes = withContext(Dispatchers.IO) {
                                    DesktopHelpers.receivedFilesBytes(engine)
                                }
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

        // Last sibling, so a pairing message is never painted under the layout — the same reasoning
        // as `FlashConversationScreen`'s host. `tabBottomInset` lifts it clear of the hanging bottom
        // nav in the compact layout; in two-pane there is no nav and the inset is zero.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = tabBottomInset),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SnackbarHost(hostState = snackbarHostState)
        }

        // Rename this device. `DesktopIdentityStore.updateFriendlyName` has always worked and
        // persisted; the row that would call it was wired to nothing, so every desktop install was
        // named "Flash Desktop" forever.
        if (showRenameDialog) {
            FlashDisplayNameDialog(
                initial = engine.localFriendlyName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { name ->
                    showRenameDialog = false
                    scope.launch { engine.renameLocalDevice(name) }
                },
            )
        }
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

        // Voice/video calls, 33a: the shared screen as a topmost overlay, mirroring Android's
        // `if (activeCall != null) FlashCallScreen(...)`. Last sibling so it paints above the
        // layout (same z-order rule as the snackbar host above). No permission gates (desktop
        // has no runtime grants) and no audio router (no AudioManager; the webrtc-java ADM
        // default output stands). Camera toggles pass through; the screen gates them on the
        // call's video flag, and 33a only ever places audio calls. Dismiss minimizes — the
        // call continues, the coordinator keeps state (the screen's own contract).
        val ringingCall = activeCall
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
                onDismiss = { },
            )
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
 * `:app` is not a dependency of `:desktop`): the engine's two in-flight responder sub-states collapse
 * into the actionable consent card, and a protocol Failure reads as a decline — the dialog has no
 * failure card.
 *
 * A function rather than a `val` computed in the composable body, because its result is consumed
 * INSIDE a remembered `derivedStateOf`: a value passed in from outside that lambda is a constant from
 * the derived state's point of view, frozen at the composition that created it. That is exactly how
 * the desktop came to hand the dialog a fresh request and a permanently `Idle` phase.
 */
private fun pairingPhaseOf(
    phase: com.transfer.flash.core.security.pairing.PairingPhase?,
): FlashPairingPhase = when (phase) {
    null, com.transfer.flash.core.security.pairing.PairingPhase.Idle -> FlashPairingPhase.Idle
    com.transfer.flash.core.security.pairing.PairingPhase.RequestReceived,
    com.transfer.flash.core.security.pairing.PairingPhase.AwaitingLocalDecision ->
        FlashPairingPhase.RequestReceived
    com.transfer.flash.core.security.pairing.PairingPhase.AwaitingPeerConfirmation ->
        FlashPairingPhase.AwaitingPeerConfirmation
    com.transfer.flash.core.security.pairing.PairingPhase.Confirmed -> FlashPairingPhase.Paired
    com.transfer.flash.core.security.pairing.PairingPhase.DeclinedByPeer -> FlashPairingPhase.Declined
    com.transfer.flash.core.security.pairing.PairingPhase.Expired -> FlashPairingPhase.Expired
    com.transfer.flash.core.security.pairing.PairingPhase.Failed -> FlashPairingPhase.Declined
}

/**
 * The conversation header, filled from what the DESKTOP already knows about the peer.
 *
 * The repository's header cannot know the desktop's trust names, so this derives the
 * header from that real data instead of inventing any:
 *
 *  - **title** is the peer's trusted name, falling back to the name discovery is currently reporting;
 *  - **presence** is `Online` exactly when discovery can see the peer right now, which is the same
 *    fact the Nearby dot is drawn from — not a guess and not a heartbeat;
 *  - **showCallActions is true since 33a.** The voice button starts an audio call via
 *    the shared coordinator; the video button stays hidden until 33c (the shell passes
 *    `showVideoCallAction = false`), because 33a is audio-only and a video button with
 *    nowhere to go would be the dead-control trap again.
 *
 * Returns null when the conversation is not a known peer, so the caller falls back to the
 * repository's own state rather than to a fabricated one.
 */
internal fun desktopConversationHeader(
    conversationId: String?,
    trusted: List<FlashTrustedPeer>,
    discovered: List<FlashDiscoveredEndpoint>,
): FlashChatHeaderUiState? {
    if (conversationId == null) return null
    val trustedName = trusted.firstOrNull { it.id == conversationId }?.name
    val endpoint = discovered.firstOrNull { it.deviceId.value == conversationId }
    val name = trustedName ?: endpoint?.friendlyName ?: return null
    return FlashChatHeaderUiState(
        title = name,
        avatarInitials = name.trim().split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" },
        avatarSeed = name,
        presence = if (endpoint != null) FlashPeerPresence.Online else FlashPeerPresence.Offline,
        transport = FlashNetworkTransport.Lan,
        // Direct chat, and encrypted in transit on the LAN, as the Nearby/LAN badge already says.
        isGroup = false,
        isEncrypted = true,
        showCallActions = true,
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
): NearbyUiState {
    val trustedIds = trusted.mapTo(HashSet()) { it.id }
    // Every discovered peer as a row, BEFORE the trusted filter — the join in `withDeviceKinds`
    // below looks a trusted peer up here, and a trusted peer is deliberately excluded from `peers`.
    val discoveredRows = discovered.map { ep ->
        NearbyPeerUi(
            id = ep.deviceId.value,
            name = ep.friendlyName,
            transport = ep.transportType.toDesktopTransport(),
            isTrusted = false,
            deviceKind = ep.deviceKind,
        )
    }
    return NearbyUiState(
        identity = NearbyIdentityUi(
            displayName = localFriendlyName,
            deviceIdShort = localDeviceId.take(8).ifBlank { "00000000" },
            port = discoveryState.advertisedPort,
        ),
        isScanning = discoveryState.isDiscovering,
        isLoading = !ready,
        peers = discoveredRows.filter { it.id !in trustedIds },
        trustedPeers = FlashNearbyMath.withDeviceKinds(
            trusted = trusted.map { NearbyTrustedPeerUi(id = it.id, name = it.name) },
            discovered = discoveredRows,
        ),
        pairingRequest = ui?.let { u ->
            FlashPairingRequestUi(
                peerName = u.peerName,
                peerInitials = u.peerName.trim().split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercase() }
                    .ifBlank { "?" },
                numericCode = u.numericCode,
                transport = FlashNetworkTransport.Lan,
                expiresInSeconds = u.secondsLeft,
            )
        },
        pairingPhase = pairingPhaseOf(ui?.phase),
        pairingSecondsLeft = ui?.secondsLeft ?: 0,
    )
}
