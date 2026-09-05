package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val MAX_DISPLAYED_REACTION_CHIPS = 8

/**
 * UI-009 Bubble Reaction Dock.
 *
 * Renders a row of [FlashReactionChip] items overlapping the bottom of a message bubble.
 * Supports up to [MAX_DISPLAYED_REACTION_CHIPS] unique reactions before collapsing
 * into an overflow badge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlashReactionsDock(
    reactions: List<FlashReaction>,
    outgoing: Boolean,
    onToggleReaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongClickReaction: (FlashReaction) -> Unit = {},
    onOverflowClick: () -> Unit = {},
) {
    if (reactions.isEmpty()) return

    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion

    val displayedReactions = reactions.take(MAX_DISPLAYED_REACTION_CHIPS)
    val overflowCount = (reactions.size - MAX_DISPLAYED_REACTION_CHIPS).coerceAtLeast(0)

    val alignment = if (outgoing) Alignment.End else Alignment.Start

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(motion.springDefaultSpec()),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        displayedReactions.forEach { reaction ->
            FlashReactionChip(
                reaction = reaction,
                onToggle = { onToggleReaction(reaction.emoji) },
                onLongClick = { onLongClickReaction(reaction) },
                modifier = Modifier.padding(end = FlashSpacing.space4),
            )
        }

        if (overflowCount > 0) {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                    .clip(CircleShape)
                    .background(colors.backgroundSurfaceSubtle)
                    .border(FlashDimensions.borderHairline, colors.borderSubtle, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOverflowClick,
                    )
                    .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                    .semantics {
                        role = Role.Button
                        contentDescription = "$overflowCount more reactions. Tap to view all."
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    style = typography.metadataEmphasis,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Preview(name = "Reactions Dock - Incoming", showBackground = true)
@Composable
private fun FlashReactionsDockIncomingPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashReactionsDock(
                reactions = listOf(
                    FlashReaction(emoji = "👍", count = 3, isSelfReacted = false),
                    FlashReaction(emoji = "❤️", count = 5, isSelfReacted = true),
                    FlashReaction(emoji = "🔥", count = 2, isSelfReacted = false),
                ),
                outgoing = false,
                onToggleReaction = {},
            )
        }
    }
}

@Preview(name = "Reactions Dock - Outgoing", showBackground = true)
@Composable
private fun FlashReactionsDockOutgoingPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashReactionsDock(
                reactions = listOf(
                    FlashReaction(emoji = "❤️", count = 2, isSelfReacted = true),
                    FlashReaction(emoji = "🎉", count = 1, isSelfReacted = false),
                ),
                outgoing = true,
                onToggleReaction = {},
            )
        }
    }
}
