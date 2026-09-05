package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Contextual chat-list action bar (UI-013). Replaces [FlashChatListTopBar] while the list is in
 * selection mode (entered via long-press). Shows the selected count and the bulk actions the
 * Room-backed repository can service: pin, mute, mark-read, archive, delete. Close exits selection.
 */
@Composable
fun FlashChatListSelectionBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlashDimensions.headerHeight)
                .padding(horizontal = FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(icon = FlashIcons.Close, contentDescription = "Exit selection")
            }
            Text(
                text = "$selectedCount selected",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FlashSpacing.space4),
                style = typography.headingMedium,
                color = colors.textPrimary,
            )
            IconButton(
                onClick = onPin,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(icon = FlashIcons.Pin, contentDescription = "Pin conversations")
            }
            IconButton(
                onClick = onMute,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(icon = FlashIcons.Mute, contentDescription = "Mute conversations")
            }
            IconButton(
                onClick = onMarkRead,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(icon = FlashIcons.Read, contentDescription = "Mark read")
            }
            IconButton(
                onClick = onArchive,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(icon = FlashIcons.Archive, contentDescription = "Archive conversations")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(
                    icon = FlashIcons.Delete,
                    contentDescription = "Delete conversations",
                    tint = colors.textError,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListSelectionBarPreview() {
    FlashTheme {
        FlashChatListSelectionBar(
            selectedCount = 3,
            onClose = {},
            onPin = {},
            onMute = {},
            onMarkRead = {},
            onArchive = {},
            onDelete = {},
        )
    }
}
