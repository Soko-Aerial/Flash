package com.transfer.flash.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.chat.fileCategoryColorFor
import com.transfer.flash.ui.chat.FlashSwarmPieceMap
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.transfers.FlashTransferDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.FlashTransfersMath
import com.transfer.flash.ui.transfers.TransferProgressBar

private fun extensionOf(name: String): String = name.substringAfterLast('.', "").trim()

/** Pure plain-language copy and contrast helpers for [FlashTransferDetailPane] and [FlashNearbyDetailPane]. */
internal object FlashDetailPaneCopy {
    fun transferSecurityTitle(
        isVerified: Boolean,
        isFailed: Boolean,
        isVerifying: Boolean = false,
    ): String = when {
        isVerified -> "File check passed"
        isVerifying -> "Verifying file…"
        isFailed -> "File check failed"
        else -> "Private transfer"
    }

    fun transferSecurityBody(
        isVerified: Boolean,
        isFailed: Boolean,
        errorMessage: String?,
        isVerifying: Boolean = false,
    ): String = when {
        isVerified -> "Confirmed this file matches what was sent with no changes."
        isVerifying -> "Checking the finished file against what was sent."
        isFailed -> errorMessage?.trim()?.takeIf { it.isNotBlank() }
            ?: "The file couldn't be verified. Try sending it again."
        else -> "Protected while sending. Flash checks the finished file automatically."
    }

    fun peerTrustTitle(isTrusted: Boolean): String =
        if (isTrusted) "Paired device" else "Not paired yet"

    fun peerTrustBody(isTrusted: Boolean, isColleague: Boolean, orgName: String?): String = when {
        isTrusted -> "Both devices have confirmed a matching pairing code for private chats and files."
        isColleague -> {
            val org = orgName?.trim()?.takeIf { it.isNotBlank() }
            if (org != null) {
                "Verified coworker in $org. Pair with a code for extra verification."
            } else {
                "Verified coworker. Pair with a code for extra verification."
            }
        }
        else -> "Pair with this device using a 6-digit code before sending files or messages."
    }

    fun peerTransportLabel(transport: FlashNetworkTransport): String = when (transport) {
        FlashNetworkTransport.Lan -> "Local network (Wi-Fi)"
        FlashNetworkTransport.WifiDirect -> "Wi-Fi Direct"
        FlashNetworkTransport.Relay -> "Relay"
        FlashNetworkTransport.Radio -> "Radio"
        FlashNetworkTransport.Unknown -> "Nearby"
    }

    fun peerDeviceTypeLabel(kind: FlashDeviceKind): String = when (kind) {
        FlashDeviceKind.PHONE -> "Phone"
        FlashDeviceKind.DESKTOP -> "Computer"
        FlashDeviceKind.UNKNOWN -> "Device"
    }

    fun buttonColors(
        colors: FlashColors,
        isPrimary: Boolean,
        isDestructive: Boolean,
    ): Pair<Color, Color> = when {
        isDestructive -> colors.textError.copy(alpha = 0.12f) to colors.textError
        isPrimary -> colors.accentFill to colors.onAccentFill
        else -> colors.backgroundSurfaceStrong to colors.textPrimary
    }
}

/**
 * Shared adaptive detail pane for transfer inspection on large displays (Tablets & Desktop).
 * Redesigned for rich inspector card language, live metrics, verification, and action dock.
 */
@Composable
fun FlashTransferDetailPane(
    item: FlashTransferItemUi,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPauseResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onReveal: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val scrollState = rememberScrollState()
    val ext = remember(item.fileName) { extensionOf(item.fileName) }
    val categoryColor = remember(ext) { fileCategoryColorFor(ext) }
    val progress = FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal)
    val progressPercent = (progress * 100).toInt()

    val fraction = animateFloatAsState(
        targetValue = progress,
        animationSpec = FlashTheme.motion.tweenNormalSpec(),
        label = "detailTransferProgress",
    )

    val fillTint = when (item.state) {
        FlashTransferState.Offered -> colors.statusTransfer
        FlashTransferState.Paused, FlashTransferState.Queued -> colors.statusTransfer
        FlashTransferState.Failed -> colors.textError
        else -> colors.accentPrimary
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        // Top header with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(
                text = "Transfer details",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClose)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Close details"
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Close,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    size = FlashDimensions.iconMd,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }

        // Hero File Card
        DetailCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(FlashDimensions.avatarLg)
                        .clip(CircleShape)
                        .background(categoryColor.copy(alpha = 0.9f))
                        .border(FlashDimensions.borderHairline, colors.borderSubtle.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ext.isNotEmpty() && ext.length <= 4) {
                        FlashText(
                            text = ext.uppercase(),
                            style = FlashTheme.typography.captionEmphasis.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
                            ),
                            color = Color.White,
                        )
                    } else {
                        FlashIcon(
                            icon = if (item.direction == FlashTransferDirection.Send) FlashIcons.Upload else FlashIcons.Download,
                            contentDescription = null,
                            tint = Color.White,
                            size = FlashDimensions.iconMd,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
                Spacer(Modifier.width(FlashSpacing.space12))
                Column(Modifier.weight(1f)) {
                    FlashText(
                        text = item.fileName,
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(FlashSpacing.space4))
                    FlashText(
                        text = FlashTransfersMath.formatSize(item.bytesTotal),
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // Progress & Metrics Card
        DetailCard(title = "Progress") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = "$progressPercent%",
                    style = FlashTheme.typography.numericDefault,
                    color = colors.textPrimary,
                )
                FlashText(
                    text = "${FlashTransfersMath.formatSize(item.bytesDone)} of ${FlashTransfersMath.formatSize(item.bytesTotal)}",
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                )
            }

            Spacer(Modifier.height(FlashSpacing.space8))

            // Animated progress bar with the same velocity shimmer as the Transfers rows.
            TransferProgressBar(
                fraction = fraction,
                fillTint = fillTint,
                isActive = item.state == FlashTransferState.Active && !item.isVerifying,
                speedBytesPerSec = item.speedBytesPerSec,
                reduceMotion = FlashTheme.motion.reduceMotion,
                barHeight = 6.dp,
            )

            Spacer(Modifier.height(FlashSpacing.space12))

            DetailMetricRow(
                label = "Status",
                value = FlashTransfersMath.statusLine(item),
                valueColor = fillTint,
            )
            DetailMetricRow(
                label = "Speed",
                value = FlashTransfersMath.formatSpeed(item.speedBytesPerSec).ifBlank { "—" },
            )
            DetailMetricRow(
                label = "Time left",
                value = FlashTransfersMath.formatEta(item.etaSeconds).ifBlank { "—" },
            )
            if (item.transportLabel != null) {
                DetailMetricRow(
                    label = "Connection",
                    value = item.transportLabel,
                )
            }
        }

        // Task 3.6: Swarm Transfer Block Availability Grid Map
        // Real piece state only: a 1:1 transfer has none, so no card (it used to claim 2 peers).
        if (item.pieceBlocks.isNotEmpty()) {
            DetailCard(title = "File pieces") {
                FlashSwarmPieceMap(
                    blocks = item.pieceBlocks,
                    holdersOnline = item.holdersOnline,
                    isDownloading = item.state == FlashTransferState.Active && !item.isVerifying && item.direction == FlashTransferDirection.Receive,
                )
            }
        }

        // Integrity & Security Card
        DetailCard(title = "Security & Verification") {
            val isVerified = item.state == FlashTransferState.Completed && item.verified
            val isVerifying = item.state == FlashTransferState.Active && item.isVerifying
            val isFailed = item.state == FlashTransferState.Failed

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isVerified -> colors.statusOnline.copy(alpha = 0.14f)
                                isFailed -> colors.textError.copy(alpha = 0.14f)
                                else -> colors.accentFill.copy(alpha = 0.12f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(
                        icon = when {
                            isVerified -> FlashIcons.Verified
                            isFailed -> FlashIcons.Failed
                            else -> FlashIcons.Encryption
                        },
                        contentDescription = null,
                        tint = when {
                            isVerified -> colors.statusOnline
                            isFailed -> colors.textError
                            else -> colors.accentFill
                        },
                        size = FlashDimensions.iconSm,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                Spacer(Modifier.width(FlashSpacing.space12))
                Column(Modifier.weight(1f)) {
                    FlashText(
                        text = FlashDetailPaneCopy.transferSecurityTitle(isVerified, isFailed, isVerifying = isVerifying),
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = FlashDetailPaneCopy.transferSecurityBody(isVerified, isFailed, item.errorMessage, isVerifying = isVerifying),
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // Peer Card
        DetailCard(title = if (item.direction == FlashTransferDirection.Send) "Recipient" else "Sender") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashAvatar(
                    initials = item.peerName.take(2),
                    size = FlashDimensions.avatarMd,
                    seed = item.peerName,
                )
                Spacer(Modifier.width(FlashSpacing.space12))
                Column(Modifier.weight(1f)) {
                    FlashText(
                        text = item.peerName,
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = "Reference: ${item.id.take(8)}",
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
            }
            if (item.localPath != null) {
                Spacer(Modifier.height(FlashSpacing.space8))
                DetailMetricRow(
                    label = "Saved To",
                    value = item.localPath,
                )
            }
        }

        // Action Dock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlashSpacing.space8),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            when (item.state) {
                FlashTransferState.Active -> {
                    if (!item.isVerifying && onPauseResume != null) {
                        FlashDetailButton(
                            text = "Pause",
                            onClick = onPauseResume,
                            modifier = Modifier.weight(1f),
                            icon = FlashIcons.Pause,
                        )
                    }
                    if (onCancel != null) {
                        FlashDetailButton(
                            text = "Cancel",
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            isDestructive = true,
                            icon = FlashIcons.Close,
                        )
                    }
                }
                FlashTransferState.Paused -> {
                    if (onPauseResume != null) {
                        FlashDetailButton(
                            text = "Resume",
                            onClick = onPauseResume,
                            modifier = Modifier.weight(1f),
                            isPrimary = true,
                            icon = FlashIcons.Play,
                        )
                    }
                    if (onCancel != null) {
                        FlashDetailButton(
                            text = "Cancel",
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            isDestructive = true,
                            icon = FlashIcons.Close,
                        )
                    }
                }
                FlashTransferState.Failed -> {
                    if (item.retryable && onRetry != null) {
                        FlashDetailButton(
                            text = "Retry Transfer",
                            onClick = onRetry,
                            modifier = Modifier.weight(1f),
                            isPrimary = true,
                            icon = FlashIcons.Retry,
                        )
                    }
                }
                FlashTransferState.Completed -> {
                    if (item.localPath != null) {
                        if (onOpen != null) {
                            FlashDetailButton(
                                text = "Open File",
                                onClick = onOpen,
                                modifier = Modifier.weight(1f),
                                isPrimary = true,
                            )
                        }
                        if (onReveal != null) {
                            FlashDetailButton(
                                text = "Reveal in Folder",
                                onClick = onReveal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

/**
 * Shared adaptive detail pane for inspecting nearby peers on large displays (Tablets & Desktop).
 * Redesigned for large avatar hero, verification status, connection details, and quick action bar.
 */
@Composable
fun FlashNearbyDetailPane(
    peer: NearbyPeerUi,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPair: (() -> Unit)? = null,
    onChat: (() -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onSendFile: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        // Top header with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(
                text = "Device details",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
            )
            Box(
                modifier = Modifier
                    .size(FlashDimensions.minTouchTarget)
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onClose)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Close details"
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Close,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    size = FlashDimensions.iconMd,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }

        // Hero Peer Card
        DetailCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(FlashSpacing.space8))
                FlashAvatar(
                    initials = peer.name.take(2),
                    size = 72.dp,
                    seed = peer.name,
                )
                Spacer(Modifier.height(FlashSpacing.space12))
                FlashText(
                    text = peer.name,
                    style = FlashTheme.typography.headingSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(FlashSpacing.space4))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.statusOnline),
                    )
                    FlashText(
                        text = "Available nearby",
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.height(FlashSpacing.space8))
            }
        }

        // Security & Verification Card
        DetailCard(title = "Trust & Pairing") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (peer.isTrusted) colors.statusOnline.copy(alpha = 0.14f)
                            else colors.statusTransfer.copy(alpha = 0.14f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(
                        icon = if (peer.isTrusted) FlashIcons.Verified else FlashIcons.Device,
                        contentDescription = null,
                        tint = if (peer.isTrusted) colors.statusOnline else colors.statusTransfer,
                        size = FlashDimensions.iconSm,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                Spacer(Modifier.width(FlashSpacing.space12))
                Column(Modifier.weight(1f)) {
                    FlashText(
                        text = FlashDetailPaneCopy.peerTrustTitle(peer.isTrusted),
                        style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(FlashSpacing.space2))
                    FlashText(
                        text = FlashDetailPaneCopy.peerTrustBody(peer.isTrusted, peer.isColleague, peer.orgName),
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                    )
                }
            }
            if (peer.isTrusted && peer.id.length >= 8) {
                Spacer(Modifier.height(FlashSpacing.space8))
                DetailMetricRow(
                    label = "Security code",
                    value = "${peer.id.take(4)} · ${peer.id.substring(4, minOf(8, peer.id.length))} · ${peer.id.takeLast(4)}",
                )
            }
        }

        // Connection & Hardware Details
        DetailCard(title = "Connection") {
            DetailMetricRow(
                label = "Connection type",
                value = FlashDetailPaneCopy.peerTransportLabel(peer.transport),
            )
            DetailMetricRow(
                label = "Device code",
                value = peer.id.take(16) + if (peer.id.length > 16) "…" else "",
            )
            if (peer.deviceKind != FlashDeviceKind.UNKNOWN) {
                DetailMetricRow(
                    label = "Device type",
                    value = FlashDetailPaneCopy.peerDeviceTypeLabel(peer.deviceKind),
                )
            }
        }

        // Action Dock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlashSpacing.space8),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            if (!peer.isTrusted && onPair != null) {
                FlashDetailButton(
                    text = "Pair Device",
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth(),
                    isPrimary = !peer.isColleague || onChat == null,
                    icon = FlashIcons.Connection,
                )
            }
            if (onChat != null && (peer.isTrusted || peer.isColleague)) {
                FlashDetailButton(
                    text = "Open Chat",
                    onClick = onChat,
                    modifier = Modifier.fillMaxWidth(),
                    isPrimary = true,
                    icon = FlashIcons.Chat,
                )
            }
            if (peer.isTrusted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    if (onVoiceCall != null) {
                        FlashDetailButton(
                            text = "Voice Call",
                            onClick = onVoiceCall,
                            modifier = Modifier.weight(1f),
                            icon = FlashIcons.Call,
                        )
                    }
                    if (onVideoCall != null) {
                        FlashDetailButton(
                            text = "Video Call",
                            onClick = onVideoCall,
                            modifier = Modifier.weight(1f),
                            icon = FlashIcons.VideoCall,
                        )
                    }
                    if (onSendFile != null) {
                        FlashDetailButton(
                            text = "Send File",
                            onClick = onSendFile,
                            modifier = Modifier.weight(1f),
                            icon = FlashIcons.Attach,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle.copy(alpha = 0.5f),
                shape = RoundedCornerShape(FlashShapes.radius12),
            )
            .padding(FlashSpacing.space16),
    ) {
        if (title != null) {
            FlashText(
                text = title,
                style = FlashTheme.typography.captionEmphasis,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(FlashSpacing.space12))
        }
        content()
    }
}

@Composable
private fun DetailMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = FlashTheme.colors.textPrimary,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = label,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
        Spacer(Modifier.width(FlashSpacing.space12))
        FlashText(
            text = value,
            modifier = Modifier.weight(1f),
            style = FlashTheme.typography.bodyDefault,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun FlashDetailButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
    isPrimary: Boolean = false,
    icon: FlashIconSpec? = null,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val (bg, fg) = FlashDetailPaneCopy.buttonColors(colors, isPrimary = isPrimary, isDestructive = isDestructive)
    val shape = RoundedCornerShape(FlashShapes.radius12)

    Box(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .flashPressScale(interactionSource)
            .clip(shape)
            .background(bg)
            .then(
                if (!isPrimary && !isDestructive) {
                    Modifier.border(FlashDimensions.borderHairline, colors.borderSubtle, shape)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            if (icon != null) {
                FlashIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = fg,
                    size = FlashDimensions.iconSm,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            FlashText(
                text = text,
                color = fg,
                style = FlashTheme.typography.bodyEmphasis,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

