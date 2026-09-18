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
    com.transfer.flash.ui.adaptive.FlashTransferDetailPane(
        item = item,
        onClose = onClose,
        onPauseResume = onPauseResume,
        onCancel = onCancel,
        onRetry = onRetry,
        onOpen = onOpen,
        onReveal = onReveal,
    )
}

@Composable
internal fun NearbyDetailPane(
    peer: NearbyPeerUi,
    onClose: () -> Unit,
) {
    com.transfer.flash.ui.adaptive.FlashNearbyDetailPane(
        peer = peer,
        onClose = onClose,
    )
}

@Composable
internal fun PlaceholderDetailPane(
    onFindDevices: (() -> Unit)? = null,
) {
    com.transfer.flash.ui.adaptive.FlashPlaceholderDetailPane(
        onFindDevices = onFindDevices,
    )
}

