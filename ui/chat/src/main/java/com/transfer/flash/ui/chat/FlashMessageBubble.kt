package com.transfer.flash.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashMessageGroupPosition
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import com.transfer.flash.core.messaging.util.sampleFlashConversationState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics

/** Press scale for bubble tap feedback (UI-005 — see message-bubble.md). */
private const val BubblePressScale = 0.97f

/**
 * Caps the bubble at `min(fraction * available, absolute)` (UI-005). The content still decides how
 * wide the bubble actually is; this only tightens the ceiling it may grow to.
 *
 * Replaces a per-bubble `BoxWithConstraints`. That is a `SubcomposeLayout`: it defers composing its
 * children to the measure pass and carries a child slot table of its own, once per visible bubble in
 * the conversation. Everything it was used for here was reading `maxWidth` to feed a single
 * `widthIn(max = ...)`, and a measure-time constraint does that in one pass with no subcomposition.
 * Identical geometry at every performance tier, to within the sub-pixel rounding difference between
 * scaling a `Dp` and scaling the pixel constraint directly.
 */
private fun Modifier.bubbleWidthCap(): Modifier = layout { measurable, constraints ->
    val absolute = FlashDimensions.bubbleMaxWidth.roundToPx()
    val ceiling = if (constraints.hasBoundedWidth) {
        minOf((constraints.maxWidth * FlashDimensions.bubbleMaxWidthFraction).toInt(), absolute)
    } else {
        absolute
    }
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, maxWidth = ceiling.coerceAtLeast(0)),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Flash message bubble (UI-005 / UI-007).
 *
 * Geometry follows the sender-group position: grouped messages are fully rounded,
 * the last message of a run carries the concave "pulse scoop" tail on the sender
 * side. Width adapts to the parent with a fraction + absolute cap.
 *
 * Supports selection mode and long-press focus overlay invocation.
 */
@Composable
fun FlashMessageBubble(
    message: FlashMessageUi,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    inSelectionMode: Boolean = false,
    onSelectToggle: () -> Unit = {},
    onToggleReaction: (String) -> Unit = {},
    onReplySwipe: () -> Unit = {},
    onJumpToMessage: (String) -> Unit = {},
    onImageClick: (index: Int, image: com.transfer.flash.core.messaging.model.FlashImageAttachmentUi) -> Unit = { _, _ -> },
    onFileClick: (com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit = {},
    onAcceptOffer: (com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit = {},
    onDeclineOffer: (com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit = {},
    isHighlighted: Boolean = false,
    /** UI-023: when non-blank, matching substrings inside the message body are highlighted. */
    searchQuery: String? = null,
    /**
     * Direct chats keep timestamps in-bubble and never show a sender header (UI-005). Passed as a
     * flag rather than having the caller hand down a `message.copy(showSenderHeader = false)`, which
     * cost a whole [FlashMessageUi] allocation per visible row per recomposition.
     */
    suppressSenderHeader: Boolean = false,
    deliveryStatus: (@Composable () -> Unit)? = null,
) {
    val alignment = if (message.isMine) Alignment.End else Alignment.Start

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (message.showSenderHeader && !suppressSenderHeader && !message.isMine) {
            FlashMessageSenderHeader(message = message)
        }

        FlashSwipeToReplyContainer(
            onReply = onReplySwipe,
            enabled = !inSelectionMode,
            isMine = message.isMine,
        ) {
            FlashBubbleSurface(
                message = message,
                onOpenActions = onOpenActions,
                isSelected = isSelected,
                inSelectionMode = inSelectionMode,
                onSelectToggle = onSelectToggle,
                onJumpToMessage = onJumpToMessage,
                onImageClick = onImageClick,
                onFileClick = onFileClick,
                onAcceptOffer = onAcceptOffer,
                onDeclineOffer = onDeclineOffer,
                isHighlighted = isHighlighted,
                deliveryStatus = deliveryStatus,
                searchQuery = searchQuery,
            )
        }

        if (message.reactions.isNotEmpty()) {
            FlashReactionsDock(
                reactions = message.reactions,
                outgoing = message.isMine,
                onToggleReaction = onToggleReaction,
                modifier = Modifier.padding(top = FlashSpacing.space4),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlashBubbleSurface(
    message: FlashMessageUi,
    onOpenActions: () -> Unit,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onSelectToggle: () -> Unit,
    onJumpToMessage: (String) -> Unit,
    onImageClick: (index: Int, image: com.transfer.flash.core.messaging.model.FlashImageAttachmentUi) -> Unit,
    onFileClick: (com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit,
    onAcceptOffer: (com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit,
    onDeclineOffer: (com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit,
    isHighlighted: Boolean,
    deliveryStatus: (@Composable () -> Unit)?,
    searchQuery: String?,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !motion.reduceMotion) BubblePressScale else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "flashBubblePressScale",
    )

    val shape = bubbleShapeFor(message, FlashTheme.minimalChrome)
    val selectionBorder = if (isHighlighted) {
        BorderStroke(1.5.dp, colors.accentPrimary)
    } else if (isSelected) {
        BorderStroke(1.5.dp, colors.accentPrimary)
    } else if (!message.isMine) {
        BorderStroke(FlashDimensions.borderHairline, colors.chatBorderIncoming)
    } else {
        null
    }

    // Custom bubble surface — no Material Surface; clip + background + optional border stroke.
    Box(
        modifier = Modifier
            .bubbleWidthCap()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(if (message.isMine) colors.chatBgOutgoing else colors.chatBgIncoming)
            .then(
                if (selectionBorder != null) {
                    Modifier.border(selectionBorder, shape)
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    // Bug 1 fix: a single tap must NOT open the actions overlay. It only
                    // toggles selection while selection mode is active; otherwise it is a no-op
                    // (the overlay is opened exclusively by onLongClick below).
                    if (inSelectionMode) {
                        onSelectToggle()
                    }
                },
                onLongClick = {
                    haptics(FlashHaptic.Confirm)
                    onOpenActions()
                },
            )
            .drawBehind {
                if (isHighlighted) {
                    drawRect(color = colors.accentPrimary.copy(alpha = 0.24f))
                } else if (isSelected) {
                    drawRect(color = colors.accentPrimary.copy(alpha = 0.12f))
                }
            }
            // UI-038: selection/search highlight is color+border only — expose it as state.
            .semantics(mergeDescendants = true) {
                if (isHighlighted || isSelected) {
                    stateDescription = "Selected"
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = FlashSpacing.space12,
                vertical = FlashSpacing.space8,
            ),
        ) {
            message.replyTo?.let { reply ->
                FlashQuotedReplyCard(
                    quotedReply = reply,
                    isParentOutgoing = message.isMine,
                    onJumpToMessage = { onJumpToMessage(reply.messageId) },
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
            }
            if (message.images.isNotEmpty()) {
                FlashImageGrid(
                    images = message.images,
                    isMine = message.isMine,
                    onImageClick = onImageClick,
                    onLongPress = {
                        haptics(FlashHaptic.Confirm)
                        onOpenActions()
                    },
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
            }
            // ERROR-034: an `else if (message.hasImageGrid) FlashAttachmentGrid(...)` branch used to
            // sit here. It fabricated coloured rectangles from a count label when a message claimed
            // images it could not supply. Nothing in the app ever set `hasImageGrid`, so the branch
            // was unreachable *and* a placeholder generator; both it and the two dead
            // FlashMessageUi fields it read are gone. A message with images renders them above.
            if (message.fileAttachments.isNotEmpty()) {
                message.fileAttachments.forEach { file ->
                    FlashFileMessageCard(
                        attachment = file,
                        isParentOutgoing = message.isMine,
                        onCardClick = { onFileClick(file) },
                        onActionClick = { onFileClick(file) },
                        onLongPress = {
                            haptics(FlashHaptic.Confirm)
                            onOpenActions()
                        },
                        onAccept = { onAcceptOffer(file) },
                        onDecline = { onDeclineOffer(file) },
                    )
                    Spacer(modifier = Modifier.height(FlashSpacing.space4))
                }
            }
            if (message.voiceAttachments.isNotEmpty()) {
                message.voiceAttachments.forEach { voice ->
                    FlashVoiceMessageCard(
                        attachment = voice,
                        isParentOutgoing = message.isMine,
                        onCardClick = {},
                        onActionClick = {},
                        onLongPress = {
                            haptics(FlashHaptic.Confirm)
                            onOpenActions()
                        },
                    )
                    Spacer(modifier = Modifier.height(FlashSpacing.space4))
                }
            }
            message.callEvent?.let { call ->
                FlashCallEventRow(
                    event = call,
                    isParentOutgoing = message.isMine,
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
            }
            if (message.text.isNotBlank()) {
                val bodyColor = if (message.isMine) colors.chatTextOutgoing else colors.chatTextIncoming
                if (searchQuery.isNullOrBlank()) {
                    FlashText(
                        text = message.text,
                        style = typography.bodyDefault,
                        color = bodyColor,
                    )
                } else {
                    // UI-023: highlight every occurrence of the active query.
                    FlashText(
                        text = buildHighlightedMessageText(
                            text = message.text,
                            query = searchQuery,
                            highlightColor = colors.accentPrimary.copy(alpha = 0.35f),
                        ),
                        style = typography.bodyDefault,
                        color = bodyColor,
                    )
                }
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
            }
            FlashMessageTimestampRow(
                message = message,
                deliveryStatus = deliveryStatus,
            )
        }
    }
}

@Composable
private fun FlashMessageSenderHeader(
    message: FlashMessageUi,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Row(
        modifier = modifier.padding(
            start = FlashSpacing.space8,
            bottom = FlashSpacing.space4,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashAvatar(
            initials = message.senderInitials,
            seed = message.senderName,
            size = FlashDimensions.avatarXs,
        )
        FlashText(
            text = message.senderName,
            style = typography.captionEmphasis,
            color = colors.chatTextUsername,
        )
    }
}

@Composable
private fun FlashMessageTimestampRow(
    message: FlashMessageUi,
    modifier: Modifier = Modifier,
    deliveryStatus: (@Composable () -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    val timestampColor = if (message.isMine) {
        colors.chatTextTimestampOutgoing
    } else {
        colors.chatTextTimestamp
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = message.timeLabel,
            style = typography.metadataDefault,
            color = timestampColor,
        )
        if (message.isMine) {
            Spacer(modifier = Modifier.padding(start = FlashSpacing.space4))
            if (deliveryStatus != null) {
                deliveryStatus()
            } else {
                val status = message.deliveryStatus ?: com.transfer.flash.core.messaging.model.FlashMessageStatus.Read
                FlashDeliveryStatusIcon(status = status)
            }
        }
    }
}

/**
 * Bubble silhouette for [message]'s position in its sender run.
 *
 * [minimalChrome] (ERROR-033) drops the tail scoop. The scoop is an [androidx.compose.ui.graphics.
 * Outline.Generic], and a generic outline cannot take the fast rounded-rect clip path — it clips
 * through the path instead, once for `Modifier.clip` and again for the incoming bubble's
 * `Modifier.border`. Most bubbles in a conversation are tailed (every SINGLE message plus the BOTTOM
 * of every run), so below HIGH the whole list clips as round-rects. Same 20 dp corner radius either
 * way; the only difference on screen is the 8 dp concave notch. HIGH is untouched.
 */
private fun bubbleShapeFor(message: FlashMessageUi, minimalChrome: Boolean): Shape {
    if (minimalChrome) return FlashShapes.bubbleGrouped
    return when (message.groupPosition) {
        FlashMessageGroupPosition.SINGLE -> if (message.isMine) FlashShapes.bubbleOutgoingTail else FlashShapes.bubbleIncomingTail
        FlashMessageGroupPosition.TOP -> FlashShapes.bubbleGrouped
        FlashMessageGroupPosition.MIDDLE -> FlashShapes.bubbleGrouped
        FlashMessageGroupPosition.BOTTOM -> if (message.isMine) FlashShapes.bubbleOutgoingTail else FlashShapes.bubbleIncomingTail
    }
}

@Preview(name = "Message Bubble - Selected", showBackground = true)
@Composable
private fun FlashMessageBubbleSelectedPreview() {
    FlashTheme {
        Column(
            modifier = Modifier.padding(FlashSpacing.space16),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            FlashMessageBubble(
                message = FlashMessageUi(
                    id = "1",
                    senderName = "Alex Rivera",
                    senderInitials = "AR",
                    timeLabel = "10:30 AM",
                    text = "Selected message with pulse highlight",
                    isMine = true,
                ),
                onOpenActions = {},
                isSelected = true,
                inSelectionMode = true,
            )
        }
    }
}
