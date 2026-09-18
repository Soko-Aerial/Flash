package com.transfer.flash.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.FlashTransfersMath

/**
 * Shared adaptive detail pane for transfer inspection on large displays (Tablets & Desktop).
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
    Column(
        modifier = modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(text = "Transfer Details", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "✕",
                modifier = Modifier.clickable(onClick = onClose),
                style = FlashTheme.typography.bodyDefault,
                color = FlashTheme.colors.textSecondary,
            )
        }
        FlashText(text = "File: ${item.fileName}", style = FlashTheme.typography.bodyDefault)
        FlashText(text = "Peer: ${item.peerName}", style = FlashTheme.typography.bodyDefault)
        FlashText(
            text = "Progress: " + (FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal) * 100).toInt() + "%",
            style = FlashTheme.typography.bodyDefault,
        )
        FlashText(text = "Speed: ${FlashTransfersMath.formatSpeed(item.speedBytesPerSec)}", style = FlashTheme.typography.bodyDefault)
        FlashText(text = "Status: ${FlashTransfersMath.statusLine(item)}", style = FlashTheme.typography.bodyDefault)

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

/**
 * Shared adaptive detail pane for inspecting nearby peers on large displays (Tablets & Desktop).
 */
@Composable
fun FlashNearbyDetailPane(
    peer: NearbyPeerUi,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPair: (() -> Unit)? = null,
    onChat: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(text = "Peer Details", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "✕",
                modifier = Modifier.clickable(onClick = onClose),
                style = FlashTheme.typography.bodyDefault,
                color = FlashTheme.colors.textSecondary,
            )
        }
        FlashText(text = "Name: ${peer.name}", style = FlashTheme.typography.bodyDefault)
        FlashText(text = "Device ID: ${peer.id.take(8)}", style = FlashTheme.typography.bodyDefault)
        FlashText(text = "Transport: ${peer.transport}", style = FlashTheme.typography.bodyDefault)
        peer.deviceKind?.let { kind ->
            FlashText(text = "Type: $kind", style = FlashTheme.typography.bodyDefault)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space8),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            if (!peer.isTrusted && onPair != null) {
                FlashText(
                    text = "Pair Device",
                    modifier = Modifier.clickable(onClick = onPair),
                    color = FlashTheme.colors.accentPrimary,
                    style = FlashTheme.typography.bodyDefault,
                )
            }
            if (peer.isTrusted && onChat != null) {
                FlashText(
                    text = "Open Chat",
                    modifier = Modifier.clickable(onClick = onChat),
                    color = FlashTheme.colors.accentPrimary,
                    style = FlashTheme.typography.bodyDefault,
                )
            }
        }
    }
}
