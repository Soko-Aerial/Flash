package com.transfer.flash.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Contextual chat-list action bar (UI-013). Replaces [FlashChatListTopBar] while the list is in
 * selection mode (entered via long-press). Shows the selected count and the bulk actions the
 * Room-backed repository can service: pin/unpin, mute/unmute, mark-read, archive/unarchive, delete.
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
    isArchivedView: Boolean = false,
    onUnarchive: () -> Unit = onArchive,
    isAllPinned: Boolean = false,
    isAllMuted: Boolean = false,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    var overflowExpanded by remember { mutableStateOf(false) }

    val pinLabel = if (isAllPinned) "Unpin conversations" else "Pin conversations"
    val muteLabel = if (isAllMuted) "Unmute conversations" else "Mute conversations"
    val archiveLabel = if (isArchivedView) "Unarchive conversations" else "Archive conversations"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactBar = maxWidth < 408.dp
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
                        .padding(start = FlashSpacing.space4)
                        .semantics { contentDescription = "$selectedCount selected" },
                    style = typography.headingSmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onPin,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Pin,
                        contentDescription = pinLabel,
                        tint = if (isAllPinned) colors.accentFill else colors.textPrimary,
                    )
                }
                IconButton(
                    onClick = onMute,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(
                        icon = FlashIcons.NotificationOff,
                        contentDescription = muteLabel,
                        tint = if (isAllMuted) colors.accentFill else colors.textPrimary,
                    )
                }
                if (!compactBar) {
                    IconButton(
                        onClick = onMarkRead,
                        modifier = Modifier.size(FlashDimensions.minTouchTarget),
                    ) {
                        FlashIcon(icon = FlashIcons.Read, contentDescription = "Mark read")
                    }
                    IconButton(
                        onClick = if (isArchivedView) onUnarchive else onArchive,
                        modifier = Modifier.size(FlashDimensions.minTouchTarget),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Archive,
                            contentDescription = archiveLabel,
                        )
                    }
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
                if (compactBar) {
                    Box {
                        IconButton(
                            onClick = { overflowExpanded = true },
                            modifier = Modifier.size(FlashDimensions.minTouchTarget),
                        ) {
                            FlashIcon(
                                icon = FlashIcons.More,
                                contentDescription = "More selection actions",
                            )
                        }
                        val menuShape = RoundedCornerShape(FlashShapes.radius16)
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                            offset = DpOffset(x = 0.dp, y = FlashSpacing.space4),
                            shape = menuShape,
                            containerColor = colors.backgroundSurface,
                            border = BorderStroke(FlashDimensions.borderHairline, colors.borderSubtle),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    FlashText(
                                        text = "Mark as read",
                                        style = typography.bodyDefault,
                                        color = colors.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    FlashIcon(
                                        icon = FlashIcons.Read,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = FlashDimensions.iconSm,
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMarkRead()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    FlashText(
                                        text = if (isArchivedView) "Unarchive" else "Archive",
                                        style = typography.bodyDefault,
                                        color = colors.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    FlashIcon(
                                        icon = FlashIcons.Archive,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = FlashDimensions.iconSm,
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    if (isArchivedView) onUnarchive() else onArchive()
                                },
                            )
                        }
                    }
                }
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
