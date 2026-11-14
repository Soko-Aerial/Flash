@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.transfer.flash.ui.nearby

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.chat.FlashOrgStrings
import com.transfer.flash.ui.chat.FlashPairingDialog
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.chat.FlashPairingRequestUi
import com.transfer.flash.ui.chat.FlashTransportBadge
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashAnimateItem
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P4 Nearby tab (UI-048, docs/ui/nearby-page.md): identity card + discovered peers +
 * trusted peers + pairing dialog. Demo state today; C3/C2 engine flows substitute at wiring.
 */
data class NearbyIdentityUi(
    val displayName: String,
    val deviceIdShort: String,
    val port: Int,
    val localAddressLabel: String? = null,
)

data class NearbyPeerUi(
    val id: String,
    val name: String,
    val transport: FlashNetworkTransport,
    /** True once this peer is a saved trusted device — flips the row CTA from Pair to Chat. */
    val isTrusted: Boolean = false,
    /**
     * Whether the peer is a PC or a phone, as it declared in its advertisement.
     *
     * Defaults to [FlashDeviceKind.UNKNOWN], which renders no badge at all — a peer that did not say
     * must not be labelled with a guess.
     */
    val deviceKind: FlashDeviceKind = FlashDeviceKind.UNKNOWN,
    /** Plan B Phase B3: whether this peer is a verified organisation colleague. */
    val isColleague: Boolean = false,
    val orgName: String? = null,
    /** True when this peer is currently discovered nearby on a local transport. */
    val isAvailableNearby: Boolean = true,
    /** True when this peer is currently reachable nearby or via an active session. */
    val isOnline: Boolean = isAvailableNearby,
)

data class NearbyTrustedPeerUi(
    val id: String,
    val name: String,
    /**
     * The trusted peer's kind, when it is currently discovered.
     *
     * Trust is persisted as id + name only, so a trusted peer that is not on the network right now
     * has no advertisement to read and stays [FlashDeviceKind.UNKNOWN]. The host joins the two at
     * its edge rather than persisting a kind that could go stale across a platform change.
     */
    val deviceKind: FlashDeviceKind = FlashDeviceKind.UNKNOWN,
    /**
     * False for a pairing made with protocol v1, whose 6-digit code a man-in-the-middle could force
     * (ADR-042). Such a peer stays trusted but is shown as unverified with a "Verify again" action,
     * which runs a v2 pairing. Defaults to true so hosts that cannot tell never show a false warning.
     */
    val verified: Boolean = true,
    /** True when this paired peer is currently discovered nearby on a local transport. */
    val isAvailableNearby: Boolean = false,
    /** True when this paired peer is discovered nearby or currently has an active session. */
    val isOnline: Boolean = isAvailableNearby,
)

/** Why "Scan network" did nothing. The host maps the network layer's refusal onto these (DR3). */
enum class NearbyScanBlock { NO_NETWORK, NOT_LOCAL, TOO_SMALL, TOO_SOON }

/**
 * The manual "Scan network" action's state (DR3, `docs/ui/nearby-page.md` addendum). The page never sees hosts,
 * ports or subnets, only how far the scan is and what it came to.
 */
sealed interface NearbyNetworkScan {
    data object Idle : NearbyNetworkScan

    data class Running(val percent: Int) : NearbyNetworkScan

    /** @property narrowed the network was large and only this device's part of it was checked. */
    data class Done(val answered: Int, val narrowed: Boolean) : NearbyNetworkScan

    data class Unavailable(val reason: NearbyScanBlock) : NearbyNetworkScan
}

data class NearbyUiState(
    val identity: NearbyIdentityUi = NearbyIdentityUi("Flash device", "00000000", 0),
    val isScanning: Boolean = true,
    val radiosAvailable: Boolean = true,
    val peers: List<NearbyPeerUi> = emptyList(),
    val trustedPeers: List<NearbyTrustedPeerUi> = emptyList(),
    val pairingRequest: FlashPairingRequestUi? = null,
    val pairingPhase: FlashPairingPhase = FlashPairingPhase.Idle,
    val pairingSecondsLeft: Int = 0,
    val isLoading: Boolean = false,
    val scan: NearbyNetworkScan = NearbyNetworkScan.Idle,
    /**
     * The network looks like it is hiding devices: paired peers exist and none is reachable (DR5). The page shows
     * its hint only after this has held for [FlashNearbyMath.QUIET_HINT_DELAY_MS]. Hosts compute it with
     * [FlashNearbyMath.discoveryQuiet].
     */
    val discoveryQuiet: Boolean = false,
)

/** Pure helpers backing the nearby page (JVM-testable). */
object FlashNearbyMath {

    /**
     * ERROR-034: [isLoading] exists because "not scanning" and "not started yet" are different
     * facts and only one of them is the user's doing. Before the discovery stack boots
     * `isScanning` is false, and this used to render that as "Scan paused" — telling the user they
     * had paused something that had not begun. Loading outranks the paused copy but not a real
     * peer count, so a scan that finds a device during boot still reports the device.
     */
    fun statusLine(isScanning: Boolean, peerCount: Int, isLoading: Boolean = false): String = when {
        peerCount > 0 -> "$peerCount device${if (peerCount == 1) "" else "s"} nearby"
        isLoading -> "Starting…"
        isScanning -> "Scanning…"
        else -> "Scan paused"
    }

    /** Returns the row action label for a nearby peer: "Chat" when trusted, "Message" when an unpaired colleague, else "Pair". */
    fun peerActionLabel(isTrusted: Boolean, isColleague: Boolean): String = when {
        isTrusted -> "Chat"
        isColleague -> "Message"
        else -> "Pair"
    }

    /** Returns whether clicking the row action directly opens chat rather than initiating pairing. */
    fun isDirectAction(isTrusted: Boolean, isColleague: Boolean): Boolean = isTrusted || isColleague

    /** How long [NearbyUiState.discoveryQuiet] must hold, on screen, before the quiet-network hint appears (DR5). */
    const val QUIET_HINT_DELAY_MS: Long = 30_000L

    const val QUIET_HINT_TITLE: String = "Can't find your devices"
    const val QUIET_HINT_BODY: String = "This network may be hiding devices from Flash."

    /**
     * Whether the network looks like it is hiding devices (DR5): discovery is running, there are paired peers, and
     * no peer is discovered **and** no session is live. A live session with a peer discovery cannot see (a
     * remembered route, an inbound dial) means the peer is reachable, so the hint would be false.
     */
    fun discoveryQuiet(pairedPeers: Int, discoveredPeers: Int, liveSessions: Int, isDiscovering: Boolean): Boolean =
        isDiscovering && pairedPeers > 0 && discoveredPeers == 0 && liveSessions == 0

    /** The line under the status for a "Scan network" run, or null when there is nothing to say. */
    fun scanCaption(scan: NearbyNetworkScan): String? = when (scan) {
        NearbyNetworkScan.Idle -> null
        is NearbyNetworkScan.Running -> "Scanning this network… ${scan.percent.coerceIn(0, 100)}%"
        is NearbyNetworkScan.Done -> {
            val result = if (scan.answered > 0) {
                "Found ${scan.answered} device${if (scan.answered == 1) "" else "s"} to connect to."
            } else {
                "Scan finished. No devices answered."
            }
            if (scan.narrowed) "$result Only this device's part of a large network was checked." else result
        }
        is NearbyNetworkScan.Unavailable -> when (scan.reason) {
            NearbyScanBlock.NO_NETWORK -> "Not connected to a local network."
            NearbyScanBlock.NOT_LOCAL -> "This isn't a home or office network, so Flash won't scan it."
            NearbyScanBlock.TOO_SMALL -> "There is nobody else on this link."
            NearbyScanBlock.TOO_SOON -> "Scanned a moment ago. Try again shortly."
        }
    }

    /** Stable display order: alphabetical by name, id as deterministic tiebreak. */
    fun sortedPeers(peers: List<NearbyPeerUi>): List<NearbyPeerUi> =
        peers.distinctBy { it.id }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun identitySubtitle(identity: NearbyIdentityUi): String =
        "id ${identity.deviceIdShort.take(8)} · port ${identity.port}"

    fun identityAddressLine(identity: NearbyIdentityUi): String? {
        val addr = identity.localAddressLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (identity.port > 0) "IP $addr · port ${identity.port}" else "IP $addr"
    }

    /**
     * Fills in each trusted row's [NearbyTrustedPeerUi.deviceKind] from the peer's CURRENT
     * advertisement.
     *
     * Trust is persisted as id + name only — a kind stored alongside it would go stale the moment a
     * peer changed platform, and would claim knowledge about a device nobody has seen for weeks. So
     * the kind is a **join**, not a stored field, and it is [FlashDeviceKind.UNKNOWN] whenever the
     * peer is not currently discovered. The badge simply does not render then.
     *
     * Shared by both hosts on purpose: this is the same class of decision that let the app's and the
     * desktop's pairing codecs drift apart, and a display rule that differs per host is a bug report
     * waiting for someone to notice one screen disagrees with the other.
     *
     * Linear, not hashed: both lists are a handful of peers, and building a map would allocate more
     * than the scan it replaces.
     */
    fun withDeviceKinds(
        trusted: List<NearbyTrustedPeerUi>,
        discovered: List<NearbyPeerUi>,
        activeSessionPeerIds: Set<String> = emptySet(),
    ): List<NearbyTrustedPeerUi> = trusted.map { row ->
        val match = discovered.firstOrNull { it.id == row.id }
        val availableNearby = match != null
        val online = availableNearby || row.id in activeSessionPeerIds
        row.copy(
            deviceKind = match?.deviceKind ?: FlashDeviceKind.UNKNOWN,
            isAvailableNearby = availableNearby,
            isOnline = online,
        )
    }

    fun trustedAvailabilityLabel(trusted: NearbyTrustedPeerUi): String = when {
        trusted.isAvailableNearby -> "Nearby"
        trusted.isOnline -> "Online"
        else -> "Offline"
    }
}

@Composable
fun FlashNearbyScreen(
    state: NearbyUiState,
    onPairClick: (NearbyPeerUi) -> Unit,
    onChatClick: (NearbyPeerUi) -> Unit,
    onRevokeClick: (NearbyTrustedPeerUi) -> Unit,
    onChatTrustedClick: (NearbyTrustedPeerUi) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hanging shell bar occupies; content scrolls under it (UI-046). */
    bottomInset: Dp = 0.dp,
    onAcceptPairing: (FlashPairingRequestUi) -> Unit = {},
    onDeclinePairing: (FlashPairingRequestUi) -> Unit = {},
    /**
     * Manual connection by IP and Port. When supplied, allows entering host/port directly.
     */
    onManualConnect: ((host: String, port: Int) -> Unit)? = null,
    /**
     * Probes the local network for devices discovery cannot see (DR3). Null hides the action, like
     * [onManualConnect]. The outcome arrives through [NearbyUiState.scan].
     */
    onScanNetwork: (() -> Unit)? = null,
    /**
     * Re-runs pairing (protocol v2) with a trusted peer whose pairing was made with v1 (ADR-042).
     * Without it an unverified row shows the label but no verification action.
     */
    onVerifyTrustedClick: ((NearbyTrustedPeerUi) -> Unit)? = null,
    onRetryPairing: ((FlashPairingRequestUi) -> Unit)? = null,
    onPeerClick: ((NearbyPeerUi) -> Unit)? = null,
    onTrustedPeerClick: ((NearbyTrustedPeerUi) -> Unit)? = null,
    onOpenWifiSettings: (() -> Unit)? = null,
    onManualConnectSuspend: (suspend (host: String, port: Int) -> String?)? = null,
) {
    val scope = rememberCoroutineScope()
    var showManualConnectDialog by remember { mutableStateOf(false) }
    var manualConnectLoading by remember { mutableStateOf(false) }
    var manualConnectError by remember { mutableStateOf<String?>(null) }
    var pendingUnpairPeer by remember { mutableStateOf<NearbyTrustedPeerUi?>(null) }
    var scanDismissed by remember(state.scan) { mutableStateOf(false) }
    val hasManualConnect = onManualConnect != null || onManualConnectSuspend != null
    val onManualConnectClick = if (hasManualConnect) {
        {
            manualConnectError = null
            showManualConnectDialog = true
        }
    } else {
        null
    }
    val statusSwap = FlashTheme.motion.statusCrossfade()
    Box(modifier.fillMaxSize()) {
        // Content clears the status bar; the pairing dialog stays a sibling so its scrim
        // still covers the full window.
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            // Crossfade on the branch, not on `state`: discovery re-emits constantly and must
            // not restart the transition.
            AnimatedContent(
                targetState = state.pageState(),
                transitionSpec = { statusSwap },
                label = "nearbyPageState",
            ) { page ->
                when (page) {
                    NearbyPageState.RadiosOff -> RadiosOffPanel(
                        modifier = Modifier.fillMaxSize(),
                        onOpenWifiSettings = onOpenWifiSettings,
                    )
                    NearbyPageState.Loading -> LoadingRows(Modifier.fillMaxSize())
                    NearbyPageState.Empty ->
                        ScanningEmptyPanel(
                            modifier = Modifier.fillMaxSize(),
                            active = state.isScanning,
                            scan = state.scan,
                            scanDismissed = scanDismissed,
                            onDismissScan = { scanDismissed = true },
                            onManualConnectClick = onManualConnectClick,
                            onScanNetworkClick = onScanNetwork,
                        )
                    NearbyPageState.Populated -> PopulatedContent(
                        state = state,
                        onPairClick = onPairClick,
                        onChatClick = onChatClick,
                        onRevokeClick = { trusted -> pendingUnpairPeer = trusted },
                        onChatTrustedClick = onChatTrustedClick,
                        onVerifyTrustedClick = onVerifyTrustedClick,
                        onManualConnectClick = onManualConnectClick,
                        onScanNetworkClick = onScanNetwork,
                        onPeerClick = onPeerClick,
                        onTrustedPeerClick = onTrustedPeerClick,
                        scanDismissed = scanDismissed,
                        onDismissScan = { scanDismissed = true },
                        listState = listState,
                        bottomInset = bottomInset,
                    )
                }
            }
        }

        state.pairingRequest?.let { request ->
            FlashPairingDialog(
                request = request,
                phase = state.pairingPhase,
                secondsLeft = state.pairingSecondsLeft,
                onAccept = { onAcceptPairing(request) },
                onDecline = { onDeclinePairing(request) },
                onDismiss = { onDeclinePairing(request) },
                onRetry = onRetryPairing?.let { retry -> { retry(request) } },
            )
        }

        if (showManualConnectDialog && hasManualConnect) {
            FlashManualConnectDialog(
                onDismiss = {
                    if (!manualConnectLoading) {
                        manualConnectError = null
                        showManualConnectDialog = false
                    }
                },
                onConnect = { host, port ->
                    if (onManualConnectSuspend != null) {
                        manualConnectLoading = true
                        manualConnectError = null
                        scope.launch {
                            val err = onManualConnectSuspend(host, port)
                            manualConnectLoading = false
                            if (err == null) {
                                showManualConnectDialog = false
                            } else {
                                manualConnectError = err
                            }
                        }
                    } else {
                        showManualConnectDialog = false
                        onManualConnect?.invoke(host, port)
                    }
                },
                isConnecting = manualConnectLoading,
                errorMessage = manualConnectError,
                onClearError = { manualConnectError = null },
            )
        }

        pendingUnpairPeer?.let { peer ->
            val colors = FlashTheme.colors
            FlashConfirmHost(
                onDismiss = { pendingUnpairPeer = null },
                containerColor = colors.backgroundSurface,
                title = {
                    FlashText(
                        text = "Unpair ${peer.name}?",
                        style = FlashTheme.typography.headingMedium,
                        color = colors.textPrimary,
                    )
                },
                text = {
                    FlashText(
                        text = "You won't be able to message or send files to ${peer.name} until you pair again with a 6-digit code.",
                        style = FlashTheme.typography.bodyDefault,
                        color = colors.textSecondary,
                    )
                },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .heightIn(min = FlashDimensions.minTouchTarget)
                            .clip(FlashShapes.bubbleGrouped)
                            .clickable(role = Role.Button) {
                                pendingUnpairPeer = null
                                onRevokeClick(peer)
                            }
                            .padding(horizontal = FlashSpacing.space12),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(
                            text = "Unpair",
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
                            .clickable(role = Role.Button) { pendingUnpairPeer = null }
                            .padding(horizontal = FlashSpacing.space12),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(
                            text = "Cancel",
                            style = FlashTheme.typography.captionEmphasis,
                            color = colors.textSecondary,
                        )
                    }
                },
            )
        }
    }
}

/** Which of the four page branches the current state resolves to (drives the crossfade). */
private enum class NearbyPageState { RadiosOff, Loading, Empty, Populated }

private fun NearbyUiState.pageState(): NearbyPageState {
    val noRows = peers.isEmpty() && trustedPeers.isEmpty()
    return when {
        !radiosAvailable -> NearbyPageState.RadiosOff
        isLoading && noRows -> NearbyPageState.Loading
        noRows -> NearbyPageState.Empty
        else -> NearbyPageState.Populated
    }
}

@Composable
private fun PopulatedContent(
    state: NearbyUiState,
    onPairClick: (NearbyPeerUi) -> Unit,
    onChatClick: (NearbyPeerUi) -> Unit,
    onRevokeClick: (NearbyTrustedPeerUi) -> Unit,
    onChatTrustedClick: (NearbyTrustedPeerUi) -> Unit,
    onVerifyTrustedClick: ((NearbyTrustedPeerUi) -> Unit)?,
    onManualConnectClick: (() -> Unit)?,
    onScanNetworkClick: (() -> Unit)?,
    onPeerClick: ((NearbyPeerUi) -> Unit)?,
    onTrustedPeerClick: ((NearbyTrustedPeerUi) -> Unit)?,
    scanDismissed: Boolean,
    onDismissScan: () -> Unit,
    listState: LazyListState,
    bottomInset: Dp,
) {
    val haptics = rememberFlashHaptics()
    val motion = FlashTheme.motion
    // DR5: the hint waits for 30 s of continuous quiet while this page is on screen; the flag going false resets it.
    var quietLongEnough by remember { mutableStateOf(false) }
    LaunchedEffect(state.discoveryQuiet) {
        quietLongEnough = false
        if (state.discoveryQuiet) {
            delay(FlashNearbyMath.QUIET_HINT_DELAY_MS)
            quietLongEnough = true
        }
    }
    val scanFoundZero = state.scan is NearbyNetworkScan.Done && state.scan.answered == 0 && !scanDismissed
    val showQuietHint = (state.discoveryQuiet && quietLongEnough) || scanFoundZero
    val sortedPeers = remember(state.peers) { FlashNearbyMath.sortedPeers(state.peers) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileColumns = ((maxWidth - FlashSpacing.space32).value /
            (360f * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 3)
        val pairedRows = remember(state.trustedPeers, tileColumns) { state.trustedPeers.chunked(tileColumns) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = FlashSpacing.space16,
                end = FlashSpacing.space16,
                top = FlashSpacing.space16,
                bottom = FlashSpacing.space16 + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            item(key = "header") {
                HeaderBlock(
                    state = state,
                    scanDismissed = scanDismissed,
                    onDismissScan = onDismissScan,
                    onManualConnectClick = onManualConnectClick,
                    onScanNetworkClick = onScanNetworkClick,
                )
            }
            item(key = "identity") { IdentityCard(state.identity) }
            if (showQuietHint) {
                item(key = "quiet-hint") {
                    QuietNetworkHint(
                        scan = state.scan,
                        onScanNetworkClick = onScanNetworkClick,
                        onManualConnectClick = onManualConnectClick,
                        modifier = flashAnimateItem(motion),
                    )
                }
            }
            if (sortedPeers.isNotEmpty()) {
                item(key = "label-discovered") { SectionLabel("Discovered") }
                items(sortedPeers, key = { "peer-${it.id}" }) { peer ->
                    // Peers appear and vanish as discovery runs, so the list item itself animates
                    // (an AnimatedVisibility pinned to `visible = true` could never play).
                    PeerRow(
                        peer = peer,
                        modifier = flashAnimateItem(motion),
                        onInspect = onPeerClick?.let { inspect -> { inspect(peer) } },
                        onAction = {
                            // Trusted peers and colleagues open chat; untrusted peers start pairing.
                            haptics(FlashHaptic.Tick)
                            if (FlashNearbyMath.isDirectAction(peer.isTrusted, peer.isColleague)) {
                                onChatClick(peer)
                            } else {
                                onPairClick(peer)
                            }
                        },
                    )
                }
            } else if (state.isScanning && state.trustedPeers.isEmpty()) {
                item(key = "scan-hint") {
                    FlashText(
                        text = "Looking for devices…",
                        style = FlashTheme.typography.metadataDefault,
                        color = FlashTheme.colors.textTertiary,
                    )
                }
            }
            if (state.trustedPeers.isNotEmpty()) {
                item(key = "label-trusted") { SectionLabel("Paired devices") }
                items(pairedRows, key = { "trusted-row-${it.first().id}" }) { devices ->
                    Row(flashAnimateItem(motion).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        verticalAlignment = Alignment.Top) {
                        devices.forEach { trusted ->
                            TrustedTile(
                                trusted = trusted,
                                modifier = Modifier.weight(1f),
                                onInspect = onTrustedPeerClick?.let { inspect -> { inspect(trusted) } },
                                onChat = {
                                    haptics(FlashHaptic.Tick)
                                    onChatTrustedClick(trusted)
                                },
                                onRevoke = {
                                    haptics(FlashHaptic.Confirm)
                                    onRevokeClick(trusted)
                                },
                                onVerify = onVerifyTrustedClick?.takeIf { !trusted.verified }?.let { handler ->
                                    {
                                        haptics(FlashHaptic.Tick)
                                        handler(trusted)
                                    }
                                },
                            )
                        }
                        repeat(tileColumns - devices.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(
    state: NearbyUiState,
    scanDismissed: Boolean,
    onDismissScan: () -> Unit,
    onManualConnectClick: (() -> Unit)? = null,
    onScanNetworkClick: (() -> Unit)? = null,
) {
    val scanning = state.scan is NearbyNetworkScan.Running
    val actions: @Composable (Boolean) -> Unit = { showConnectIcon ->
        if (onScanNetworkClick != null) {
            ScanNetworkTextAction(running = scanning, onClick = onScanNetworkClick)
        }
        if (onManualConnectClick != null) {
            ConnectByIpTextAction(showIcon = showConnectIcon, onClick = onManualConnectClick)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val typography = FlashTheme.typography
            val density = LocalDensity.current
            val inlineWidth = with(density) {
                val title = measurer.measure("Nearby", typography.headingMedium, maxLines = 1).size.width.toDp()
                val scan = if (onScanNetworkClick != null) {
                    measurer.measure(if (scanning) "Scanning…" else "Scan network", typography.captionEmphasis, maxLines = 1)
                        .size.width.toDp() + FlashSpacing.space16 + FlashSpacing.space4
                } else 0.dp
                val connect = if (onManualConnectClick != null) {
                    measurer.measure("Connect by IP", typography.captionEmphasis, maxLines = 1).size.width.toDp() +
                        FlashSpacing.space16 + FlashSpacing.space4
                } else 0.dp
                title + scan + connect
            }
            val showConnectIcon = maxWidth >= inlineWidth + FlashDimensions.iconSm + FlashSpacing.space4
            if (inlineWidth > maxWidth) {
                Column(Modifier.fillMaxWidth()) {
                    FlashText(
                        text = "Nearby",
                        modifier = Modifier.semantics { heading() },
                        style = FlashTheme.typography.headingMedium,
                        color = FlashTheme.colors.textPrimary,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        actions(true)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashText(
                        text = "Nearby",
                        modifier = Modifier.weight(1f).semantics { heading() },
                        maxLines = 1,
                        style = FlashTheme.typography.headingMedium,
                        color = FlashTheme.colors.textPrimary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                    ) {
                        actions(showConnectIcon)
                    }
                }
            }
        }
        FlashText(
            text = "Discover devices and manage pairings.",
            style = FlashTheme.typography.captionDefault,
            color = FlashTheme.colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScanningDot(active = state.isScanning)
            Spacer(Modifier.width(FlashSpacing.space8))
            FlashText(
                text = FlashNearbyMath.statusLine(
                    state.isScanning, state.peers.size, state.isLoading,
                ) + if (state.trustedPeers.isNotEmpty()) " · ${state.trustedPeers.size} paired" else "",
                style = FlashTheme.typography.metadataDefault,
                color = FlashTheme.colors.textSecondary,
            )
        }
        ScanCaption(
            scan = state.scan,
            dismissed = scanDismissed,
            onDismiss = onDismissScan,
            modifier = Modifier.padding(top = FlashSpacing.space4),
        )
    }
}

@Composable
private fun ScanningDot(active: Boolean) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val animate = active && !motion.reduceMotion
    // The pulse value is read inside graphicsLayer, so it invalidates the render layer only.
    // Reading it in composition (the previous shape) recomposed the header every frame.
    val pulse = if (animate) {
        rememberInfiniteTransition(label = "flashScanPulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.emphasisMillis, easing = FlashMotion.Standard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flashScanPulseScale",
        )
    } else {
        null
    }
    Box(
        Modifier
            .size(ScanDotSize)
            .graphicsLayer {
                val scale = pulse?.value ?: 1f
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (active) colors.accentFill else colors.textTertiary),
    )
}

private val ScanDotSize = 8.dp

@Composable
private fun IdentityCard(identity: NearbyIdentityUi) {
    val colors = FlashTheme.colors
    val addressLine = FlashNearbyMath.identityAddressLine(identity)
    val subtitleLine = if (addressLine != null) {
        "id ${identity.deviceIdShort.take(8)}"
    } else {
        FlashNearbyMath.identitySubtitle(identity)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("This device: ${identity.displayName}")
                    addressLine?.let { append(", $it") }
                    append(", $subtitleLine")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(FlashDimensions.avatarLg)
                .clip(CircleShape)
                .background(colors.accentFill.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = FlashIcons.Device,
                tint = colors.accentFill,
                size = FlashDimensions.iconLg,
                contentDescription = null,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(
                text = identity.displayName,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (addressLine != null) {
                FlashText(
                    text = addressLine,
                    style = FlashTheme.typography.captionDefault,
                    color = colors.accentFill,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlashText(
                text = subtitleLine,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "PC" / "Phone" chip for a nearby peer, from what the peer declared in its advertisement.
 *
 * Renders **nothing** for [FlashDeviceKind.UNKNOWN]. That is the whole design: the badge is a claim
 * about a device the user cannot see, so a peer that did not declare a kind gets no label rather
 * than a guess from its model string. An older build therefore shows a bare row, which is true.
 *
 * Deliberately text, not an icon: there is no phone/computer glyph in `FlashIcons` (only a generic
 * `Device`), and adding drawable assets to `:ui:resources` for this would be a larger change than
 * the feature warrants.
 */
@Composable
private fun FlashDeviceKindBadge(kind: FlashDeviceKind, modifier: Modifier = Modifier) {
    val label = when (kind) {
        FlashDeviceKind.DESKTOP -> "PC"
        FlashDeviceKind.PHONE -> "Phone"
        FlashDeviceKind.UNKNOWN -> return
    }
    val colors = FlashTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(FlashShapes.chip)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.chip)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun PeerRow(
    peer: NearbyPeerUi,
    modifier: Modifier = Modifier,
    onInspect: (() -> Unit)? = null,
    onAction: () -> Unit,
) {
    val colors = FlashTheme.colors
    val connectInteraction = remember { MutableInteractionSource() }
    val ctaLabel = FlashNearbyMath.peerActionLabel(isTrusted = peer.isTrusted, isColleague = peer.isColleague)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .let { base ->
                if (onInspect != null) {
                    base.clickable(
                        role = Role.Button,
                        onClickLabel = "Inspect ${peer.name}",
                        onClick = onInspect,
                    )
                } else {
                    base
                }
            }
            .padding(
                start = FlashSpacing.space12,
                end = FlashSpacing.space12,
                top = FlashSpacing.space12,
                bottom = FlashSpacing.space12,
            )
            .semantics(mergeDescendants = true) {
                val colleagueSuffix = if (peer.isColleague) ", ${FlashOrgStrings.ORG_CHIP_A11Y}" else ""
                contentDescription = "${peer.name}$colleagueSuffix nearby, $ctaLabel button"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(FlashDimensions.avatarMd)
                .clip(CircleShape)
                .background(colors.backgroundSurfaceStrong),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = FlashIcons.Device,
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
                contentDescription = null,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(
                text = peer.name,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashTransportBadge(transport = peer.transport)
                FlashDeviceKindBadge(kind = peer.deviceKind)
                if (peer.isColleague) {
                    FlashColleagueBadge(orgName = peer.orgName)
                }
            }
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        Box(
            Modifier
                .heightIn(min = FlashDimensions.minTouchTarget)
                .flashPressScale(connectInteraction)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.accentFill)
                .clickable(
                    interactionSource = connectInteraction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = ctaLabel,
                    onClick = onAction,
                )
                .padding(horizontal = FlashSpacing.space16),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = ctaLabel,
                style = FlashTheme.typography.captionEmphasis,
                color = colors.onAccentFill,
            )
        }
    }
}

@Composable
fun FlashColleagueBadge(
    modifier: Modifier = Modifier,
    orgName: String? = null,
) {
    val colors = FlashTheme.colors
    val a11yDesc = if (!orgName.isNullOrBlank()) "Colleague verified by $orgName" else FlashOrgStrings.ORG_CHIP_A11Y
    Box(
        modifier = modifier
            .clip(FlashShapes.chip)
            .background(colors.backgroundSurfaceSubtle)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.chip)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2)
            .semantics { contentDescription = a11yDesc },
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = FlashOrgStrings.chipText(orgName),
            style = FlashTheme.typography.captionEmphasis,
            color = colors.accentFill,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrustedTile(
    trusted: NearbyTrustedPeerUi,
    modifier: Modifier = Modifier,
    onInspect: (() -> Unit)? = null,
    onChat: () -> Unit,
    onRevoke: () -> Unit,
    onVerify: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val tileShape = RoundedCornerShape(FlashShapes.radius12)
    val identity: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlashIcon(
                icon = if (trusted.verified) FlashIcons.Verified else FlashIcons.Encryption,
                tint = if (trusted.verified) colors.statusOnline else colors.textSecondary,
                size = FlashDimensions.iconSm,
                contentDescription = if (trusted.verified) "Verified pairing" else null,
            )
            Column(Modifier.weight(1f).padding(start = FlashSpacing.space8)) {
                FlashText(
                    text = trusted.name,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    "Needs verification".takeUnless { trusted.verified },
                    when (trusted.deviceKind) {
                        FlashDeviceKind.DESKTOP -> "PC"
                        FlashDeviceKind.PHONE -> "Phone"
                        FlashDeviceKind.UNKNOWN -> null
                    },
                    FlashNearbyMath.trustedAvailabilityLabel(trusted),
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    FlashText(
                        text = detail,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    val actions: @Composable () -> Unit = { PairedTileActions(trusted, onInspect, onChat, onRevoke, onVerify) }
    BoxWithConstraints(
        modifier
            .clip(tileShape)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, tileShape)
            .let { base ->
                if (onInspect != null) {
                    base.clickable(
                        role = Role.Button,
                        onClickLabel = "Inspect ${trusted.name}",
                        onClick = onInspect,
                    )
                } else {
                    base
                }
            }
            .padding(start = FlashSpacing.space12, end = FlashSpacing.space4, top = FlashSpacing.space8, bottom = FlashSpacing.space8),
    ) {
        if (maxWidth < 250.dp || LocalDensity.current.fontScale >= 1.3f) {
            Column {
                identity()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { identity() }
                actions()
            }
        }
    }
}

@Composable
private fun PairedTileActions(
    trusted: NearbyTrustedPeerUi,
    onInspect: (() -> Unit)?,
    onChat: () -> Unit,
    onRevoke: () -> Unit,
    onVerify: (() -> Unit)?,
) {
    val colors = FlashTheme.colors
    val clipboard = LocalClipboardManager.current
    var expanded by remember(trusted.id) { mutableStateOf(false) }
    Box(
        Modifier.heightIn(min = FlashDimensions.minTouchTarget)
            .clickable(role = Role.Button, onClickLabel = "Chat with ${trusted.name}", onClick = onChat),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clip(RoundedCornerShape(FlashShapes.radius8)).background(colors.accentFill)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)) {
            FlashText("Chat", style = FlashTheme.typography.captionEmphasis, color = colors.onAccentFill)
        }
    }
    Box {
        Box(
            modifier = Modifier
                .size(FlashDimensions.minTouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "More actions for ${trusted.name}") { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(FlashIcons.More, contentDescription = "More actions for ${trusted.name}", tint = colors.textSecondary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(FlashShapes.radius12),
            containerColor = colors.backgroundSurface,
        ) {
            if (onInspect != null) {
                DropdownMenuItem(
                    text = { FlashText("Device details", style = FlashTheme.typography.bodyDefault, color = colors.textPrimary) },
                    onClick = { expanded = false; onInspect() },
                )
            }
            DropdownMenuItem(
                text = { FlashText("Copy device ID", style = FlashTheme.typography.bodyDefault, color = colors.textPrimary) },
                onClick = { expanded = false; clipboard.setText(AnnotatedString(trusted.id)) },
            )
            if (onVerify != null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            FlashText("Verify pairing", style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
                            FlashText(
                                "Confirm 6-digit code again",
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textSecondary,
                            )
                        }
                    },
                    onClick = { expanded = false; onVerify() },
                )
            }
            HorizontalDivider(color = colors.borderSubtle)
            DropdownMenuItem(
                text = { FlashText("Unpair device", style = FlashTheme.typography.bodyDefault, color = colors.textError) },
                onClick = { expanded = false; onRevoke() },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    FlashText(
        text = text,
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textSecondary,
        modifier = Modifier.padding(top = FlashSpacing.space8).semantics { heading() },
    )
}

@Composable
private fun RadiosOffPanel(
    modifier: Modifier,
    onOpenWifiSettings: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = FlashSpacing.space32, vertical = FlashSpacing.space40),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FlashText(
            text = "No local network",
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = "Turn on Wi-Fi, enable Mobile Hotspot, or connect Ethernet so Flash can find nearby devices.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (onOpenWifiSettings != null) {
            Spacer(Modifier.height(FlashSpacing.space16))
            Box(
                Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.bubbleGrouped)
                    .background(colors.accentFill)
                    .clickable(role = Role.Button, onClick = onOpenWifiSettings)
                    .padding(horizontal = FlashSpacing.space16),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Open Network Settings",
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.onAccentFill,
                )
            }
        }
    }
}

@Composable
private fun ScanningEmptyPanel(
    modifier: Modifier,
    active: Boolean,
    scan: NearbyNetworkScan = NearbyNetworkScan.Idle,
    scanDismissed: Boolean = false,
    onDismissScan: () -> Unit = {},
    onManualConnectClick: (() -> Unit)? = null,
    onScanNetworkClick: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = FlashSpacing.space32, vertical = FlashSpacing.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScanningDot(active = active)
        Spacer(Modifier.height(FlashSpacing.space12))
        FlashText(
            text = if (active) "Looking for nearby devices…" else "Scan paused",
            style = FlashTheme.typography.headingSmall,
            color = FlashTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = "Keep both devices on the same network with Flash open.",
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textSecondary,
        )
        if (onManualConnectClick != null) {
            Spacer(Modifier.height(FlashSpacing.space16))
            Box(
                Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.bubbleGrouped)
                    .background(FlashTheme.colors.accentFill)
                    .clickable(role = Role.Button, onClick = onManualConnectClick)
                    .padding(horizontal = FlashSpacing.space16),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Connect by IP",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.onAccentFill,
                )
            }
        }
        if (onScanNetworkClick != null) {
            val running = scan is NearbyNetworkScan.Running
            Spacer(Modifier.height(FlashSpacing.space8))
            Box(
                Modifier
                    .heightIn(min = FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.bubbleGrouped)
                    .background(FlashTheme.colors.backgroundSurfaceStrong)
                    .clickable(enabled = !running, role = Role.Button, onClick = onScanNetworkClick)
                    .padding(horizontal = FlashSpacing.space16),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = if (running) "Scanning…" else "Scan network",
                    style = FlashTheme.typography.captionEmphasis,
                    color = if (running) FlashTheme.colors.textTertiary else FlashTheme.colors.accentFill,
                )
            }
        }
        ScanCaption(
            scan = scan,
            dismissed = scanDismissed,
            onDismiss = onDismissScan,
            modifier = Modifier.padding(top = FlashSpacing.space12),
        )
    }
}

/**
 * DR5: the card that says the network may be hiding devices, with the two actions that can still work
 * (`docs/ui/nearby-page.md` addendum). An action the host did not supply is not shown.
 */
@Composable
private fun QuietNetworkHint(
    scan: NearbyNetworkScan,
    onScanNetworkClick: (() -> Unit)?,
    onManualConnectClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val scanning = scan is NearbyNetworkScan.Running
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12),
    ) {
        Column(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = "${FlashNearbyMath.QUIET_HINT_TITLE}. ${FlashNearbyMath.QUIET_HINT_BODY}"
            },
        ) {
            FlashText(
                text = FlashNearbyMath.QUIET_HINT_TITLE,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
            )
            FlashText(
                text = FlashNearbyMath.QUIET_HINT_BODY,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
        if (onScanNetworkClick != null || onManualConnectClick != null) {
            FlowRow {
                if (onScanNetworkClick != null) {
                    HintAction(
                        label = if (scanning) "Scanning…" else "Scan network",
                        enabled = !scanning,
                        onClick = onScanNetworkClick,
                    )
                }
                if (onManualConnectClick != null) {
                    HintAction(label = "Connect by IP", enabled = true, onClick = onManualConnectClick)
                }
            }
        }
    }
}

@Composable
private fun HintAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(end = FlashSpacing.space16),
        contentAlignment = Alignment.CenterStart,
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.captionEmphasis,
            color = if (enabled) FlashTheme.colors.accentFill else FlashTheme.colors.textTertiary,
        )
    }
}

/** The header's "Scan network" text action; it is inert while a scan runs. */
@Composable
private fun ScanNetworkTextAction(running: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clickable(enabled = !running, role = Role.Button, onClick = onClick)
            .padding(horizontal = FlashSpacing.space8),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = if (running) "Scanning…" else "Scan network",
            style = FlashTheme.typography.captionEmphasis,
            color = if (running) FlashTheme.colors.textTertiary else FlashTheme.colors.accentFill,
        )
    }
}

/** Labeled "Connect by IP" action in the Nearby header when populated. */
@Composable
private fun ConnectByIpTextAction(showIcon: Boolean, onClick: () -> Unit) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(RoundedCornerShape(FlashShapes.radius8))
            .clickable(role = Role.Button, onClickLabel = "Connect by IP", onClick = onClick)
            .padding(horizontal = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (showIcon) {
            FlashIcon(
                icon = FlashIcons.Connection,
                contentDescription = null,
                tint = colors.accentFill,
                size = FlashDimensions.iconSm,
            )
        }
        FlashText(
            text = "Connect by IP",
            style = FlashTheme.typography.captionEmphasis,
            color = colors.accentFill,
        )
    }
}

/**
 * The scan's result line. Keeps manual scan results and errors visible until dismissed or
 * re-scanned (keyed by [scan]).
 */
@Composable
private fun ScanCaption(
    scan: NearbyNetworkScan,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val caption = FlashNearbyMath.scanCaption(scan)?.takeUnless { dismissed } ?: return
    val canDismiss = scan is NearbyNetworkScan.Done || scan is NearbyNetworkScan.Unavailable
    val isError = scan is NearbyNetworkScan.Unavailable
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        FlashText(
            text = caption,
            modifier = Modifier.weight(1f, fill = false),
            style = FlashTheme.typography.metadataDefault,
            color = if (isError) FlashTheme.colors.textError else FlashTheme.colors.textSecondary,
        )
        if (canDismiss) {
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Dismiss scan result", onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Close,
                    contentDescription = "Dismiss scan result",
                    tint = FlashTheme.colors.textSecondary,
                    size = FlashDimensions.iconSm,
                )
            }
        }
    }
}

@Composable
private fun LoadingRows(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(FlashSpacing.space16)) {
        repeat(3) {
            Box(
                Modifier
                    .padding(vertical = FlashSpacing.space8)
                    .fillMaxWidth()
                    .height(FlashDimensions.chatListRowHeight)
                    .clip(RoundedCornerShape(FlashShapes.radius12))
                    .background(FlashTheme.colors.backgroundSurfaceStrong),
            )
        }
    }
}
