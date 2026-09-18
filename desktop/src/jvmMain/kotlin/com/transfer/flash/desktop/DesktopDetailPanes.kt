package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.transfers.FlashTransfersMath
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState

/**
 * Phase 22, sub-step 22-4 — minimal detail panes for the two-pane layout, per the phase file's
 * Step 5 and its own note: "only `FlashConversationScreen` is a realistic detail pane" — which
 * the C3 correction voids for v1 (desktop binds `EmptyFlashChatRepository` until 09B-2, so a
 * conversation detail pane would render an inert empty screen). Transfers and nearby peers get
 * compact info cards; everything else gets the placeholder.
 */
@Composable
internal fun TransferDetailPane(
    item: FlashTransferItemUi,
    onClose: () -> Unit,
    onPauseResume: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onReveal: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(text = "Transfer Details", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "×",
                modifier = Modifier.clickable(onClick = onClose),
                style = FlashTheme.typography.bodyDefault,
            )
        }
        FlashText(text = "File: ${item.fileName}")
        FlashText(text = "Peer: ${item.peerName}")
        FlashText(
            text = "Progress: " + (FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal) * 100).toInt() + "%",
        )
        FlashText(text = "Speed: ${FlashTransfersMath.formatSpeed(item.speedBytesPerSec)}")
        FlashText(text = "Status: ${FlashTransfersMath.statusLine(item)}")

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space8),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            when (item.state) {
                FlashTransferState.Active -> {
                    if (onPauseResume != null) {
                        FlashText(
                            text = "Pause",
                            modifier = Modifier.clickable(onClick = onPauseResume),
                            color = FlashTheme.colors.accentPrimary,
                            style = FlashTheme.typography.bodyDefault,
                        )
                    }
                    if (onCancel != null) {
                        FlashText(
                            text = "Cancel",
                            modifier = Modifier.clickable(onClick = onCancel),
                            color = FlashTheme.colors.textError,
                            style = FlashTheme.typography.bodyDefault,
                        )
                    }
                }
                FlashTransferState.Paused -> {
                    if (onPauseResume != null) {
                        FlashText(
                            text = "Resume",
                            modifier = Modifier.clickable(onClick = onPauseResume),
                            color = FlashTheme.colors.accentPrimary,
                            style = FlashTheme.typography.bodyDefault,
                        )
                    }
                    if (onCancel != null) {
                        FlashText(
                            text = "Cancel",
                            modifier = Modifier.clickable(onClick = onCancel),
                            color = FlashTheme.colors.textError,
                            style = FlashTheme.typography.bodyDefault,
                        )
                    }
                }
                FlashTransferState.Failed -> {
                    if (item.retryable && onRetry != null) {
                        FlashText(
                            text = "Retry",
                            modifier = Modifier.clickable(onClick = onRetry),
                            color = FlashTheme.colors.accentPrimary,
                            style = FlashTheme.typography.bodyDefault,
                        )
                    }
                }
                FlashTransferState.Completed -> {
                    if (item.localPath != null) {
                        if (onOpen != null) {
                            FlashText(
                                text = "Open",
                                modifier = Modifier.clickable(onClick = onOpen),
                                color = FlashTheme.colors.accentPrimary,
                                style = FlashTheme.typography.bodyDefault,
                            )
                        }
                        if (onReveal != null) {
                            FlashText(
                                text = "Reveal in folder",
                                modifier = Modifier.clickable(onClick = onReveal),
                                color = FlashTheme.colors.accentPrimary,
                                style = FlashTheme.typography.bodyDefault,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
internal fun NearbyDetailPane(
    peer: NearbyPeerUi,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FlashText(text = "Peer Details", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "×",
                modifier = Modifier.clickable(onClick = onClose),
                style = FlashTheme.typography.bodyDefault,
            )
        }
        FlashText(text = "Name: ${peer.name}")
        FlashText(text = "Device: ${peer.id.take(8)}")
        FlashText(text = "Transport: ${peer.transport}")
    }
}

@Composable
internal fun PlaceholderDetailPane(
    onFindDevices: (() -> Unit)? = null,
) {
    com.transfer.flash.ui.adaptive.FlashPlaceholderDetailPane(
        onFindDevices = onFindDevices,
    )
}

