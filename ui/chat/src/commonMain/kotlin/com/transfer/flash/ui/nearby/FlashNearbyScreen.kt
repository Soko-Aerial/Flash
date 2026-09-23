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
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
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

/**
 * P4 Nearby tab (UI-048, docs/ui/nearby-page.md): identity card + discovered peers +
 * trusted peers + pairing dialog. Demo state today; C3/C2 engine flows substitute at wiring.
 */
data class NearbyIdentityUi(
    val displayName: String,
    val deviceIdShort: String,
    val port: Int,
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
)

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

    /** Stable display order: alphabetical by name, id as deterministic tiebreak. */
    fun sortedPeers(peers: List<NearbyPeerUi>): List<NearbyPeerUi> =
        peers.distinctBy { it.id }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun identitySubtitle(identity: NearbyIdentityUi): String =
        "id ${identity.deviceIdShort.take(8)} · port ${identity.port}"

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
    ): List<NearbyTrustedPeerUi> = trusted.map { row ->
        row.copy(
            deviceKind = discovered.firstOrNull { it.id == row.id }?.deviceKind
                ?: FlashDeviceKind.UNKNOWN,
        )
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
     * Voice-call action for trusted rows (Phase 33a). Nullable on purpose, like the
     * search/group icon params elsewhere: a host that does not implement calling passes
     * nothing and the row shows no Call button, rather than a rendered button that does
     * nothing. The desktop passes it; Android keeps its header call buttons and is
     * unchanged.
     */
    onCallTrustedClick: ((NearbyTrustedPeerUi) -> Unit)? = null,
    /**
     * Manual connection by IP and Port. When supplied, allows entering host/port directly.
     */
    onManualConnect: ((host: String, port: Int) -> Unit)? = null,
    /**
     * Re-runs pairing (protocol v2) with a trusted peer whose pairing was made with v1 (ADR-042).
     * Nullable like [onCallTrustedClick]: without it an unverified row shows the label but no action.
     */
    onVerifyTrustedClick: ((NearbyTrustedPeerUi) -> Unit)? = null,
) {
    var showManualConnectDialog by remember { mutableStateOf(false) }
    val onManualConnectClick = if (onManualConnect != null) { { showManualConnectDialog = true } } else null
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
                    NearbyPageState.RadiosOff -> RadiosOffPanel(Modifier.fillMaxSize())
                    NearbyPageState.Loading -> LoadingRows(Modifier.fillMaxSize())
                    NearbyPageState.Empty ->
                        ScanningEmptyPanel(
                            modifier = Modifier.fillMaxSize(),
                            active = state.isScanning,
                            onManualConnectClick = onManualConnectClick,
                        )
                    NearbyPageState.Populated -> PopulatedContent(
                        state = state,
                        onPairClick = onPairClick,
                        onChatClick = onChatClick,
                        onRevokeClick = onRevokeClick,
                        onChatTrustedClick = onChatTrustedClick,
                        onCallTrustedClick = onCallTrustedClick,
                        onVerifyTrustedClick = onVerifyTrustedClick,
                        onManualConnectClick = onManualConnectClick,
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
            )
        }

        if (showManualConnectDialog && onManualConnect != null) {
            FlashManualConnectDialog(
                onDismiss = { showManualConnectDialog = false },
                onConnect = { host, port ->
                    showManualConnectDialog = false
                    onManualConnect(host, port)
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
    onCallTrustedClick: ((NearbyTrustedPeerUi) -> Unit)?,
    onVerifyTrustedClick: ((NearbyTrustedPeerUi) -> Unit)?,
    onManualConnectClick: (() -> Unit)?,
    listState: LazyListState,
    bottomInset: Dp,
) {
    val haptics = rememberFlashHaptics()
    val motion = FlashTheme.motion
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = FlashSpacing.space16,
            end = FlashSpacing.space16,
            top = FlashSpacing.space16,
            bottom = FlashSpacing.space16 + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        item(key = "header") { HeaderBlock(state, onManualConnectClick) }
        item(key = "identity") { IdentityCard(state.identity) }
        if (state.peers.isNotEmpty()) {
            item(key = "label-discovered") { SectionLabel("DISCOVERED") }
            items(FlashNearbyMath.sortedPeers(state.peers), key = { "peer-${it.id}" }) { peer ->
                // Peers appear and vanish as discovery runs, so the list item itself animates
                // (an AnimatedVisibility pinned to `visible = true` could never play).
                PeerRow(
                    peer = peer,
                    modifier = flashAnimateItem(motion),
                    onAction = {
                        // Trusted peers open a chat; untrusted peers start pairing.
                        haptics(FlashHaptic.Tick)
                        if (peer.isTrusted) onChatClick(peer) else onPairClick(peer)
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
            item(key = "label-trusted") { SectionLabel("TRUSTED PEERS") }
            items(state.trustedPeers, key = { "trusted-${it.id}" }) { trusted ->
                TrustedRow(
                    trusted = trusted,
                    modifier = flashAnimateItem(motion),
                    onChat = {
                        haptics(FlashHaptic.Tick)
                        onChatTrustedClick(trusted)
                    },
                    onCall = onCallTrustedClick?.let { handler ->
                        {
                            haptics(FlashHaptic.Tick)
                            handler(trusted)
                        }
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
        }
    }
}

@Composable
private fun HeaderBlock(
    state: NearbyUiState,
    onManualConnectClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            FlashText(
                text = "Nearby",
                style = FlashTheme.typography.headingMedium,
                color = FlashTheme.colors.textPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScanningDot(active = state.isScanning)
                Spacer(Modifier.width(FlashSpacing.space8))
                FlashText(
                    // Count trusted (paired) peers too: once a peer is paired it leaves the DISCOVERED
                    // list for the TRUSTED section, so counting only `peers` made the header snap back
                    // to "Scanning…" even though a device was clearly connected.
                    text = FlashNearbyMath.statusLine(
                        state.isScanning,
                        state.peers.size + state.trustedPeers.size,
                        state.isLoading,
                    ),
                    style = FlashTheme.typography.metadataDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
        }
        if (onManualConnectClick != null) {
            IconButton(
                onClick = onManualConnectClick,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(
                    icon = FlashIcons.Connection,
                    tint = FlashTheme.colors.accentPrimary,
                    contentDescription = "Connect by IP",
                )
            }
        }
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
            .background(if (active) colors.accentPrimary else colors.textTertiary),
    )
}

private val ScanDotSize = 8.dp

@Composable
private fun IdentityCard(identity: NearbyIdentityUi) {
    val colors = FlashTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = "This device: ${identity.displayName}, " +
                    FlashNearbyMath.identitySubtitle(identity)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(FlashDimensions.avatarLg)
                .clip(CircleShape)
                .background(colors.accentPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = FlashIcons.Device,
                tint = colors.accentPrimary,
                size = FlashDimensions.iconLg,
                contentDescription = null,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space12))
        Column {
            FlashText(
                text = identity.displayName,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
            )
            FlashText(
                text = FlashNearbyMath.identitySubtitle(identity),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
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
    onAction: () -> Unit,
) {
    val colors = FlashTheme.colors
    val connectInteraction = remember { MutableInteractionSource() }
    val ctaLabel = if (peer.isTrusted) "Chat" else "Pair"
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(
                start = FlashSpacing.space12,
                end = FlashSpacing.space12,
                top = FlashSpacing.space12,
                bottom = FlashSpacing.space12,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "${peer.name} nearby, $ctaLabel button"
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
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashTransportBadge(transport = peer.transport)
                FlashDeviceKindBadge(kind = peer.deviceKind)
            }
        }
        Box(
            Modifier
                .height(FlashDimensions.minTouchTarget)
                .flashPressScale(connectInteraction)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.accentPrimary)
                .clickable(
                    interactionSource = connectInteraction,
                    indication = null,
                    onClickLabel = ctaLabel,
                    onClick = onAction,
                )
                .padding(horizontal = FlashSpacing.space16),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = ctaLabel,
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textOnAccent,
            )
        }
    }
}

@Composable
private fun TrustedRow(
    trusted: NearbyTrustedPeerUi,
    modifier: Modifier = Modifier,
    onChat: () -> Unit,
    onCall: (() -> Unit)?,
    onRevoke: () -> Unit,
    /** Non-null only for an unverified (v1) pairing whose host can re-run pairing. */
    onVerify: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val verifyInteraction = remember { MutableInteractionSource() }
    val chatInteraction = remember { MutableInteractionSource() }
    val callInteraction = remember { MutableInteractionSource() }
    val revokeInteraction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurfaceSubtle)
            .padding(start = FlashSpacing.space12, end = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ADR-042: a v1 pairing's code could have been forced, so it is not presented as verified.
        FlashIcon(
            icon = if (trusted.verified) FlashIcons.Verified else FlashIcons.Encryption,
            tint = if (trusted.verified) colors.statusOnline else colors.textTertiary,
            size = FlashDimensions.iconSm,
            contentDescription = if (trusted.verified) null else "Not verified",
        )
        Spacer(Modifier.width(FlashSpacing.space8))
        Column(Modifier.weight(1f)) {
            FlashText(
                text = trusted.name,
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
            if (!trusted.verified) {
                FlashText(
                    text = "Not verified",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textTertiary,
                )
            }
        }
        if (onVerify != null) {
            Box(
                Modifier
                    .height(FlashDimensions.minTouchTarget)
                    .flashPressScale(verifyInteraction)
                    .clickable(
                        interactionSource = verifyInteraction,
                        indication = null,
                        onClickLabel = "Verify again",
                        onClick = onVerify,
                    )
                    .padding(horizontal = FlashSpacing.space12),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Verify",
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.accentPrimary,
                )
            }
        }
        FlashDeviceKindBadge(kind = trusted.deviceKind, modifier = Modifier.padding(end = FlashSpacing.space8))
        Box(
            Modifier
                .height(FlashDimensions.minTouchTarget)
                .flashPressScale(chatInteraction)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.accentPrimary)
                .clickable(
                    interactionSource = chatInteraction,
                    indication = null,
                    onClickLabel = "Chat",
                    onClick = onChat,
                )
                .padding(horizontal = FlashSpacing.space16),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = "Chat",
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textOnAccent,
            )
        }
        // Voice call (Phase 33a). Rendered only when the host supplies the action — an
        // untrusted peer can never reach this row, so no trust check is needed here; the
        // coordinator itself refuses untrusted peers as well (Group Phase 0 closure).
        // Text-style rather than filled, so the row keeps one primary CTA.
        if (onCall != null) {
            Box(
                Modifier
                    .height(FlashDimensions.minTouchTarget)
                    .flashPressScale(callInteraction)
                    .clickable(
                        interactionSource = callInteraction,
                        indication = null,
                        onClickLabel = "Call",
                        onClick = onCall,
                    )
                    .padding(horizontal = FlashSpacing.space12),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Call",
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.accentPrimary,
                )
            }
        }
        Box(
            Modifier
                .height(FlashDimensions.minTouchTarget)
                .flashPressScale(revokeInteraction)
                .clickable(
                    interactionSource = revokeInteraction,
                    indication = null,
                    onClickLabel = "Revoke",
                    onClick = onRevoke,
                )
                .padding(horizontal = FlashSpacing.space12),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = "Revoke",
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textError,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    FlashText(
        text = text,
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textTertiary,
        modifier = Modifier.padding(top = FlashSpacing.space8),
    )
}

@Composable
private fun RadiosOffPanel(modifier: Modifier) {
    Column(
        modifier.padding(horizontal = FlashSpacing.space32, vertical = FlashSpacing.space40),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlashText(
            text = "No radios available",
            style = FlashTheme.typography.headingSmall,
            color = FlashTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = "Turn on Wi-Fi (or enable Wi-Fi Direct) so Flash can find nearby devices.",
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ScanningEmptyPanel(
    modifier: Modifier,
    active: Boolean,
    onManualConnectClick: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = FlashSpacing.space32),
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
                    .height(FlashDimensions.minTouchTarget)
                    .clip(FlashShapes.bubbleGrouped)
                    .background(FlashTheme.colors.accentPrimary)
                    .clickable(onClick = onManualConnectClick)
                    .padding(horizontal = FlashSpacing.space16),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Connect by IP",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textOnAccent,
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
                    .background(FlashTheme.colors.backgroundSurfaceSubtle),
            )
        }
    }
}
