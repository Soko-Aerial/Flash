package com.transfer.flash.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

@Composable
fun FlashChatListTopBar(
    /** Null hides the action, matching [onNewGroupClick]/[onLanClick]. */
    onSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Chats",
    onLanClick: (() -> Unit)? = null,
    /** Explicit "New chat" destination; falls back to [onLanClick] when null. */
    onNewChatClick: (() -> Unit)? = null,
    /** Group Phase 1A: opens the create-group sheet; null hides the action. */
    onNewGroupClick: (() -> Unit)? = null,
    /** GM-10: opens join-group-with-link dialog; null hides the action. */
    onJoinWithLinkClick: (() -> Unit)? = null,
    /** Browse department groups on the enterprise hub; null hides the action. */
    onBrowseOrgGroupsClick: (() -> Unit)? = null,
    /**
     * UI-058: opens Transfers as a pushed screen. Non-null only on an enrolled device (its Transfers
     * tab became Hub); null hides the action, so an unenrolled device is unchanged.
     */
    onTransfersClick: (() -> Unit)? = null,
    /** Transfers in progress, for the indicator on the Transfers action; 0 shows none. */
    transfersActiveCount: Int = 0,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val effectiveNewChatClick = onNewChatClick ?: onLanClick
    val hasNewMenuActions = effectiveNewChatClick != null ||
        onNewGroupClick != null ||
        onJoinWithLinkClick != null ||
        onBrowseOrgGroupsClick != null
    var newMenuExpanded by remember { mutableStateOf(false) }

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
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FlashSpacing.space12),
                style = typography.headingMedium,
                color = colors.textPrimary,
            )
            if (onTransfersClick != null) {
                val badge = FlashChatListTopBarMath.badgeText(transfersActiveCount)
                Box(contentAlignment = Alignment.TopEnd) {
                    IconButton(
                        onClick = onTransfersClick,
                        modifier = Modifier.size(FlashDimensions.minTouchTarget),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Transfer,
                            contentDescription = FlashChatListTopBarMath.contentDescription(transfersActiveCount),
                        )
                    }
                    if (badge != null) {
                        Text(
                            text = badge,
                            modifier = Modifier
                                .padding(top = FlashSpacing.space4, end = FlashSpacing.space4)
                                .background(colors.accentFill, CircleShape)
                                .padding(horizontal = FlashSpacing.space4),
                            style = typography.metadataEmphasis,
                            color = colors.onAccentFill,
                        )
                    }
                }
            }
            if (onSearchClick != null) {
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(icon = FlashIcons.Search, contentDescription = "Search chats")
                }
            }
            if (hasNewMenuActions) {
                Box {
                    IconButton(
                        onClick = { newMenuExpanded = true },
                        modifier = Modifier
                            .size(FlashDimensions.minTouchTarget)
                            .semantics { contentDescription = "New chat or group" },
                    ) {
                        val strokeColor = colors.textPrimary
                        Canvas(modifier = Modifier.size(FlashDimensions.iconMd)) {
                            val strokePx = 1.75.dp.toPx()
                            val inset = size.width * 0.22f
                            val midX = size.width * 0.5f
                            val midY = size.height * 0.5f
                            drawLine(
                                color = strokeColor,
                                start = Offset(midX, inset),
                                end = Offset(midX, size.height - inset),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = strokeColor,
                                start = Offset(inset, midY),
                                end = Offset(size.width - inset, midY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    val menuShape = RoundedCornerShape(FlashShapes.radius16)
                    DropdownMenu(
                        expanded = newMenuExpanded,
                        onDismissRequest = { newMenuExpanded = false },
                        offset = DpOffset(x = 0.dp, y = FlashSpacing.space4),
                        shape = menuShape,
                        containerColor = colors.backgroundSurface,
                        border = BorderStroke(FlashDimensions.borderHairline, colors.borderSubtle),
                    ) {
                        if (effectiveNewChatClick != null) {
                            DropdownMenuItem(
                                text = {
                                    FlashText(
                                        text = "New chat",
                                        style = typography.bodyDefault,
                                        color = colors.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    FlashIcon(
                                        icon = FlashIcons.Chat,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = FlashDimensions.iconSm,
                                    )
                                },
                                onClick = {
                                    newMenuExpanded = false
                                    effectiveNewChatClick()
                                },
                            )
                        }
                        if (onNewGroupClick != null) {
                            DropdownMenuItem(
                                text = {
                                    FlashText(
                                        text = "New group",
                                        style = typography.bodyDefault,
                                        color = colors.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    FlashIcon(
                                        icon = FlashIcons.Group,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = FlashDimensions.iconSm,
                                    )
                                },
                                onClick = {
                                    newMenuExpanded = false
                                    onNewGroupClick()
                                },
                            )
                        }
                        if (onJoinWithLinkClick != null) {
                            DropdownMenuItem(
                                text = {
                                    FlashText(
                                        text = "Join with link",
                                        style = typography.bodyDefault,
                                        color = colors.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    FlashIcon(
                                        icon = FlashIcons.Connection,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = FlashDimensions.iconSm,
                                    )
                                },
                                onClick = {
                                    newMenuExpanded = false
                                    onJoinWithLinkClick()
                                },
                            )
                        }
                        if (onBrowseOrgGroupsClick != null) {
                            DropdownMenuItem(
                                text = {
                                    FlashText(
                                        text = "Department groups",
                                        style = typography.bodyDefault,
                                        color = colors.textPrimary,
                                    )
                                },
                                leadingIcon = {
                                    FlashIcon(
                                        icon = FlashIcons.Relay,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        size = FlashDimensions.iconSm,
                                    )
                                },
                                onClick = {
                                    newMenuExpanded = false
                                    onBrowseOrgGroupsClick()
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
private fun FlashChatListTopBarPreview() {
    FlashTheme {
        FlashChatListTopBar(onSearchClick = {})
    }
}
