package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashListPreviewDelivery
import com.transfer.flash.core.messaging.util.chatListRowContentDescription
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Resting -> pressed scale of a chat-list row; mirrors `Modifier.flashPressScale`'s default. */
private const val RowPressedScale = 0.98f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlashChatListRow(
    item: FlashChatListItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    showDivider: Boolean = true,
    onLongClick: () -> Unit = {},
    onArchive: (String) -> Unit = {},
    swipeActionLabel: String = "Archive",
) {
    val motion = FlashTheme.motion
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onArchive(item.id)
            }
            false
        },
    )

    // Hoisted so the swipe background can tell whether it is actually visible. The value itself is
    // only ever read in the render phase (graphicsLayer below); what crosses into composition is the
    // derived boolean, which flips twice per press instead of once per frame.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) RowPressedScale else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "flashChatListRowPress",
    )
    val pressInset = remember(pressScale) { derivedStateOf { pressScale.value < 1f } }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            FlashChatListSwipeBackground(
                dismissValue = dismissState.dismissDirection,
                showRestingFill = pressInset.value,
                actionLabel = swipeActionLabel,
            )
        },
        modifier = modifier,
    ) {
        FlashChatListRowContent(
            item = item,
            onClick = onClick,
            onLongClick = onLongClick,
            isSelected = isSelected,
            selectionMode = selectionMode,
            showDivider = showDivider,
            interactionSource = interactionSource,
            pressScale = pressScale,
        )
    }
}

@Composable
private fun FlashChatListSwipeBackground(
    dismissValue: SwipeToDismissBoxValue,
    showRestingFill: Boolean,
    actionLabel: String = "Archive",
) {
    val colors = FlashTheme.colors
    val showArchive = dismissValue == SwipeToDismissBoxValue.EndToStart

    // While the row sits flush over this slot there is nothing to show: the row's own opaque
    // background covers it pixel for pixel, so the fill that used to be painted unconditionally was
    // a second full-width rect per row on every frame of the list. It is still painted for the two
    // states where it is genuinely visible - the inset the press scale opens up, and the archive
    // reveal once a drag has begun. `matchParentSize` inside SwipeToDismissBox means an empty
    // background contributes no size, so skipping it cannot move the row.
    if (!showArchive && !showRestingFill) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.chatListRowHeight)
            .background(if (showArchive) colors.accentSecondary else colors.backgroundSurfaceSubtle)
            .padding(horizontal = FlashSpacing.space16),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (showArchive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashIcon(
                    icon = FlashIcons.Archive,
                    contentDescription = null,
                    tint = colors.textOnAccent,
                )
                Text(
                    text = actionLabel,
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textOnAccent,
                )
            }
        }
    }
}

@Composable
private fun FlashChatListRowContent(
    item: FlashChatListItemUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean,
    selectionMode: Boolean,
    showDivider: Boolean,
    interactionSource: MutableInteractionSource,
    pressScale: State<Float>,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val rowDescription = remember(item) { chatListRowContentDescription(item) }
    val previewKey = remember(item.isTyping, item.previewText) {
        if (item.isTyping) "typing" else item.previewText
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Read in the layer phase, not composition: a press costs one render pass instead of
            // recomposing this row's whole subtree (avatar, both text rows, every indicator) at
            // spring frequency. Same 2% squeeze on screen as before.
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .background(
                when {
                    isSelected -> colors.backgroundSurfaceStrong
                    item.isPinned -> colors.backgroundSurfaceSubtle
                    else -> colors.backgroundSurface
                },
            )
            .semantics { contentDescription = rowDescription }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FlashDimensions.chatListRowHeight)
                .padding(
                    start = if (selectionMode) FlashSpacing.space8 else FlashSpacing.space16,
                    end = FlashSpacing.space16,
                    top = FlashSpacing.space12,
                    bottom = FlashSpacing.space12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                FlashChatListSelectionIndicator(selected = isSelected)
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
            }

            // Group Phase C: groups show a live "M online" count chip instead of the plain dot,
            // overlaid on the avatar's bottom-end exactly where the single-peer dot sits.
            Box {
                FlashChatListAvatar(
                    initials = item.avatarInitials,
                    seed = item.avatarSeed,
                    presence = item.presence,
                )
                if (item.isGroup && item.groupOnlineCount > 0) {
                    FlashChatListGroupOnlineBadge(onlineCount = item.groupOnlineCount)
                }
            }

            Spacer(modifier = Modifier.width(FlashSpacing.space12))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = if (item.unreadCount > 0) typography.bodyEmphasis else typography.bodyDefault,
                        color = colors.textPrimary,
                        fontWeight = if (item.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(modifier = Modifier.width(FlashSpacing.space8))
                    Text(
                        text = item.timestamp,
                        maxLines = 1,
                        style = typography.metadataDefault,
                        color = if (item.unreadCount > 0) colors.accentPrimary else colors.textTertiary,
                    )
                }

                Spacer(modifier = Modifier.height(FlashSpacing.space2))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashChatListPreviewLeadingIcons(item = item)
                    AnimatedContent(
                        targetState = previewKey,
                        transitionSpec = { motion.statusCrossfade() },
                        modifier = Modifier.weight(1f),
                        label = "flashChatListPreview",
                    ) {
                        Text(
                            text = previewLabel(item),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = if (item.isTyping) typography.metadataEmphasis else typography.captionDefault,
                            color = when {
                                item.isTyping -> colors.accentPrimary
                                item.isMuted -> colors.textTertiary
                                else -> colors.textSecondary
                            },
                        )
                    }
                    FlashChatListTrailingIndicators(item = item)
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = FlashDimensions.avatarLg + FlashSpacing.space16 + FlashSpacing.space12,
                ),
                color = colors.borderSubtle,
                thickness = FlashDimensions.borderHairline,
            )
        }
    }
}

@Composable
private fun FlashChatListAvatar(
    initials: String,
    seed: String,
    presence: FlashPeerPresence,
) {
    val colors = FlashTheme.colors

    Box {
        FlashAvatar(
            initials = initials,
            seed = seed,
            size = FlashDimensions.avatarLg,
        )
        if (presence == FlashPeerPresence.Online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(colors.backgroundSurface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(colors.statusOnline)
                    .semantics { contentDescription = "Online" },
            )
        }
    }
}

/**
 * Group Phase C: the avatar badge variant for group rows — a filled online chip showing how many
 * members are live right now ("2"), rendered only when at least one member is online.
 */
@Composable
private fun BoxScope.FlashChatListGroupOnlineBadge(onlineCount: Int) {
    if (onlineCount <= 0) return
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .clip(CircleShape)
            .background(colors.backgroundSurface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(colors.statusOnline)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .semantics { contentDescription = "$onlineCount members online" },
    ) {
        Text(
            text = onlineCount.toString(),
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.backgroundSurface,
        )
    }
}

@Composable
private fun FlashChatListPreviewLeadingIcons(item: FlashChatListItemUi) {
    val colors = FlashTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
    ) {
        item.previewDelivery?.let { delivery ->
            FlashIcon(
                icon = delivery.iconSpec(),
                contentDescription = delivery.name,
                size = FlashDimensions.iconSm,
                tint = if (delivery == FlashListPreviewDelivery.Failed) {
                    colors.textError
                } else {
                    colors.textTertiary
                },
            )
        }
        if (item.isGroup && !item.isTyping) {
            FlashIcon(
                icon = FlashIcons.Group,
                contentDescription = "Group",
                size = FlashDimensions.iconSm,
                tint = colors.textTertiary,
            )
        }
        if (item.previewIsMedia && !item.isTyping) {
            FlashIcon(
                icon = FlashIcons.Gallery,
                contentDescription = "Media",
                size = FlashDimensions.iconSm,
                tint = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun FlashChatListTrailingIndicators(item: FlashChatListItemUi) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (item.isMuted) {
            FlashIcon(
                icon = FlashIcons.Mute,
                contentDescription = "Muted",
                size = FlashDimensions.iconSm,
                tint = colors.textTertiary,
            )
        }
        if (item.isPinned) {
            FlashIcon(
                icon = FlashIcons.Pin,
                contentDescription = "Pinned",
                size = FlashDimensions.iconSm,
                tint = colors.textTertiary,
            )
        }
        AnimatedVisibility(
            visible = item.unreadCount > 0,
            enter = scaleIn(
                initialScale = 0.8f,
                animationSpec = motion.springSnappySpec(),
            ),
            exit = scaleOut(
                targetScale = 0.8f,
                animationSpec = motion.tweenFastSpec(),
            ),
        ) {
            FlashUnreadBadge(count = item.unreadCount)
        }
    }
}

@Composable
private fun FlashUnreadBadge(count: Int) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val label = if (count > 99) "99+" else count.toString()

    Box(
        modifier = Modifier
            .heightIn(min = FlashDimensions.unreadBadgeMinSize)
            .clip(CircleShape)
            .background(colors.accentPrimary)
            .padding(horizontal = FlashSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.metadataEmphasis,
            color = colors.textOnAccent,
        )
    }
}

@Composable
private fun FlashChatListSelectionIndicator(selected: Boolean) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(colors.accentPrimary)
                } else {
                    Modifier
                        .background(colors.backgroundSurface)
                        .border(FlashDimensions.borderHairline, colors.borderStrong, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            FlashIcon(
                icon = FlashIcons.Read,
                contentDescription = "Selected",
                size = FlashDimensions.iconSm,
                tint = colors.textOnAccent,
            )
        }
    }
}

private fun previewLabel(item: FlashChatListItemUi): String {
    if (item.isTyping) return "typing…"
    return item.previewText
}

private fun FlashListPreviewDelivery.iconSpec(): FlashIconSpec = when (this) {
    FlashListPreviewDelivery.Sending -> FlashIcons.Upload
    FlashListPreviewDelivery.Sent -> FlashIcons.Delivered
    FlashListPreviewDelivery.Delivered -> FlashIcons.Delivered
    FlashListPreviewDelivery.Read -> FlashIcons.Read
    FlashListPreviewDelivery.Failed -> FlashIcons.Failed
}

@Preview(name = "Row — unread pinned", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListRowUnreadPreview() {
    FlashTheme {
        FlashChatListRow(
            item = sampleFlashChatListState().items.first(),
            onClick = {},
        )
    }
}

@Preview(name = "Row — typing", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListRowTypingPreview() {
    FlashTheme {
        FlashChatListRow(
            item = sampleFlashChatListState().items[1],
            onClick = {},
        )
    }
}

@Preview(name = "Row — selection", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListRowSelectionPreview() {
    FlashTheme {
        FlashChatListRow(
            item = sampleFlashChatListState().items[2],
            onClick = {},
            selectionMode = true,
            isSelected = true,
        )
    }
}

@Preview(name = "Row — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListRowDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashChatListRow(
            item = sampleFlashChatListState().items.last(),
            onClick = {},
            showDivider = false,
        )
    }
}
