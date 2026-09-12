package com.transfer.flash.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.transfer.flash.ui.transfers.FlashTransfersMath
import com.transfer.flash.ui.transfers.FlashTransferItemUi

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
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
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
internal fun PlaceholderDetailPane() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = "Select an item",
            style = FlashTheme.typography.bodyDefault,
            color = FlashTheme.colors.textSecondary,
        )
    }
}
