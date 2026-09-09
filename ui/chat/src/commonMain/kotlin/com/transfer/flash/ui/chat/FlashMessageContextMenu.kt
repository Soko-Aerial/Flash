package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-007 / UI-008 Immersive Message Focus Overlay & Context Menu.
 *
 * Renders IN-SCREEN (not a separate Dialog window) as the last child of the conversation
 * layout, so scrim taps dismiss on first contact — separate dialog windows lose the first
 * tap to window-focus transitions on some OEMs. Dimmed backdrop spotlights the focused
 * bubble with a quick reaction bar and sculpted contextual action menu.
 */
@Composable
fun FlashMessageFocusOverlay(
    message: FlashMessageUi,
    onDismiss: () -> Unit,
    onReactionSelect: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSelectMultiple: () -> Unit,
    onDelete: () -> Unit,
    onDeleteForEveryone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    // Hardware back closes the overlay before anything beneath it.
    FlashBackHandler(onBack = onDismiss)

    // Dimmed backdrop — any tap here dismisses on first contact.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(FlashSpacing.space16),
        contentAlignment = Alignment.Center,
    ) {
        // Explicit close affordance (accessibility + single-tap exit guarantee).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .size(FlashDimensions.minTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .semantics {
                    role = Role.Button
                    contentDescription = "Close"
                },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = FlashIcons.Close,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                size = FlashDimensions.iconMd,
            )
        }

        Column(
            modifier = Modifier
                // Wrap content (NOT fillMaxWidth): a full-width column would swallow taps across
                // the whole screen band beside the ~220dp card, creating dead zones where "tap
                // empty space to dismiss" silently fails. Wrapping means only the card/reaction bar
                // eat the tap; every genuinely empty pixel falls through to the scrim's onDismiss.
                // Side placement now comes from aligning the column itself, not horizontalAlignment.
                .align(if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* swallow clicks inside the spotlighted column */ },
                ),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
        ) {
                // Floating Quick Reaction Bar
                FlashQuickReactionsBar(
                    onReactionSelect = { reaction ->
                        onReactionSelect(reaction)
                        onDismiss()
                    },
                    modifier = Modifier.padding(bottom = FlashSpacing.space8),
                )

                // Elevated Focus Bubble Mirror
                FlashFocusedBubblePreview(
                    message = message,
                    modifier = Modifier.padding(bottom = FlashSpacing.space12),
                )

                // Sculpted Contextual Action Menu Card
                FlashContextMenuCard(
                    message = message,
                    onReply = {
                        onReply()
                        onDismiss()
                    },
                    onCopy = {
                        onCopy()
                        onDismiss()
                    },
                    onForward = {
                        onForward()
                        onDismiss()
                    },
                    onSelectMultiple = {
                        onSelectMultiple()
                        onDismiss()
                    },
                    onDelete = {
                        onDelete()
                        onDismiss()
                    },
                    onDeleteForEveryone = onDeleteForEveryone?.let { deleteForEveryone ->
                        {
                            deleteForEveryone()
                            onDismiss()
                        }
                    },
                )
            }
        }
}

/**
 * UI-009 Floating quick emoji reaction pill.
 *
 * Staggered spring entrance choreography on appear, tactile micro-press physics,
 * and a trailing react trigger for opening extended reaction options.
 */
@Composable
fun FlashQuickReactionsBar(
    onReactionSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenEmojiPicker: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val quickReactions = listOf("❤️", "👍", "🔥", "😂", "😮", "🙏")

    var visibleCount by remember { mutableStateOf(if (motion.reduceMotion) quickReactions.size + 1 else 0) }

    LaunchedEffect(Unit) {
        if (!motion.reduceMotion) {
            for (i in 1..(quickReactions.size + 1)) {
                kotlinx.coroutines.delay(25L)
                visibleCount = i
            }
        }
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, CircleShape)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        quickReactions.forEachIndexed { index, emoji ->
            val isVisible = index < visibleCount
            val scale by animateFloatAsState(
                targetValue = if (isVisible) 1f else 0.5f,
                animationSpec = motion.springSnappySpec(),
                label = "quickReactionScale_$index",
            )
            val alpha by animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = motion.tweenFastSpec(),
                label = "quickReactionAlpha_$index",
            )

            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val pressScale by animateFloatAsState(
                targetValue = if (isPressed && !motion.reduceMotion) 0.85f else 1f,
                animationSpec = motion.springSnappySpec(),
                label = "quickReactionPress_$index",
            )

            Box(
                modifier = Modifier
                    .size(FlashSpacing.space32)
                    .graphicsLayer {
                        scaleX = scale * pressScale
                        scaleY = scale * pressScale
                        this.alpha = alpha
                    }
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            haptics(FlashHaptic.Confirm)
                            onReactionSelect(emoji)
                        },
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = "React with $emoji"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emoji,
                    fontSize = 18.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        // Trailing Add Reaction button
        val isPickerVisible = quickReactions.size < visibleCount
        val pickerScale by animateFloatAsState(
            targetValue = if (isPickerVisible) 1f else 0.5f,
            animationSpec = motion.springSnappySpec(),
            label = "quickReactionPickerScale",
        )
        val pickerAlpha by animateFloatAsState(
            targetValue = if (isPickerVisible) 1f else 0f,
            animationSpec = motion.tweenFastSpec(),
            label = "quickReactionPickerAlpha",
        )

        Box(
            modifier = Modifier
                .size(FlashSpacing.space32)
                .graphicsLayer {
                    scaleX = pickerScale
                    scaleY = pickerScale
                    alpha = pickerAlpha
                }
                .clip(CircleShape)
                .clickable(
                    onClick = {
                        haptics(FlashHaptic.Tick)
                        onOpenEmojiPicker()
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "More reactions"
                },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = FlashIcons.React,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(FlashDimensions.iconSm),
            )
        }
    }
}

/**
 * Elevated message bubble displayed in the spotlight focus layer.
 */
@Composable
fun FlashFocusedBubblePreview(
    message: FlashMessageUi,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    val bubbleBg = if (message.isMine) colors.chatBgOutgoing else colors.chatBgIncoming
    val textColor = if (message.isMine) colors.chatTextOutgoing else colors.chatTextIncoming
    val shape = if (message.isMine) FlashShapes.bubbleOutgoingTail else FlashShapes.bubbleIncomingTail
    // Media/file messages have blank text — show a content summary instead (UI-019 fix).
    val contentText = com.transfer.flash.core.messaging.util.flashMessageContentSummary(message)

    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(shape)
            .background(bubbleBg)
            .border(1.dp, colors.accentPrimary.copy(alpha = 0.5f), shape)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
    ) {
        Column {
            if (!message.isMine && message.senderName.isNotBlank()) {
                Text(
                    text = message.senderName,
                    style = typography.captionEmphasis,
                    color = colors.accentPrimary,
                    modifier = Modifier.padding(bottom = FlashSpacing.space2),
                )
            }
            if (contentText.isNotBlank()) {
                Text(
                    text = contentText,
                    style = typography.bodyDefault,
                    color = textColor,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = FlashSpacing.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.timeLabel,
                    style = typography.metadataDefault,
                    color = if (message.isMine) colors.chatTextTimestampOutgoing else colors.chatTextTimestamp,
                )
            }
        }
    }
}

/**
 * Sculpted contextual action menu card.
 */
@Composable
fun FlashContextMenuCard(
    message: FlashMessageUi,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSelectMultiple: () -> Unit,
    onDelete: () -> Unit,
    onDeleteForEveryone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    Column(
        modifier = modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(16.dp))
            .padding(vertical = FlashSpacing.space4),
    ) {
        FlashContextMenuItem(
            icon = FlashIcons.Reply,
            label = "Reply",
            onClick = onReply,
        )
        FlashContextMenuItem(
            icon = FlashIcons.Edit,
            label = "Copy Text",
            onClick = onCopy,
        )
        FlashContextMenuItem(
            icon = FlashIcons.Forward,
            label = "Forward",
            onClick = onForward,
        )
        FlashContextMenuItem(
            icon = FlashIcons.Pin,
            label = "Select Multiple",
            onClick = onSelectMultiple,
        )
        HorizontalDivider(
            color = colors.borderSubtle,
            thickness = FlashDimensions.borderHairline,
            modifier = Modifier.padding(vertical = FlashSpacing.space4),
        )
        if (message.isMine && onDeleteForEveryone != null) {
            FlashContextMenuItem(
                icon = FlashIcons.Delete,
                label = "Delete for everyone",
                onClick = onDeleteForEveryone,
                isDestructive = true,
            )
        }
        FlashContextMenuItem(
            icon = FlashIcons.Delete,
            label = "Delete",
            onClick = onDelete,
            isDestructive = true,
        )
    }
}

/** Pure action visibility used by common tests and the context menu. */
internal fun messageActionLabels(isMine: Boolean): List<String> = buildList {
    add("Reply")
    add("Copy Text")
    add("Forward")
    add("Select Multiple")
    if (isMine) add("Delete for everyone")
    add("Delete")
}

@Composable
private fun FlashContextMenuItem(
    icon: FlashIconSpec,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = icon,
            contentDescription = null,
            tint = if (isDestructive) colors.textError else colors.textPrimary,
            modifier = Modifier.size(FlashSpacing.space20),
        )
        Spacer(modifier = Modifier.width(FlashSpacing.space12))
        Text(
            text = label,
            style = typography.bodyDefault.copy(
                fontWeight = if (isDestructive) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isDestructive) colors.textError else colors.textPrimary,
        )
    }
}

@Preview(name = "Focus Overlay Preview", showBackground = true)
@Composable
private fun FlashMessageFocusOverlayPreview() {
    FlashTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
            FlashMessageFocusOverlay(
                message = FlashMessageUi(
                    id = "1",
                    senderName = "Alex Rivera",
                    senderInitials = "AR",
                    timeLabel = "10:45 AM",
                    text = "Let's test this immersive focus overlay!",
                    isMine = true,
                ),
                onDismiss = {},
                onReactionSelect = {},
                onReply = {},
                onCopy = {},
                onForward = {},
                onSelectMultiple = {},
                onDelete = {},
            )
        }
    }
}
