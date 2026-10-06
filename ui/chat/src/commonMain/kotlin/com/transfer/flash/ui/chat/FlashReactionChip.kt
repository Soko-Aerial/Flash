package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.core.messaging.model.FlashReaction
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-009 Interactive Reaction Chip.
 *
 * Displays an emoji glyph, an animated count odometer, and an active accent
 * state when [FlashReaction.isSelfReacted] is true.
 *
 * 1-tap toggles reaction; long-press opens reactor attribution list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlashReactionChip(
    reaction: FlashReaction,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val interactionSource = remember { MutableInteractionSource() }

    val scaleAnim = remember { Animatable(1f) }
    val puffProgress = remember { Animatable(1f) }

    LaunchedEffect(reaction.isSelfReacted) {
        if (reaction.isSelfReacted && !motion.reduceMotion) {
            scaleAnim.snapTo(0.6f)
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.65f,
                    stiffness = 500f,
                ),
            )
        }
    }

    LaunchedEffect(reaction.isSelfReacted) {
        if (reaction.isSelfReacted && !motion.reduceMotion) {
            puffProgress.snapTo(0f)
            puffProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 250,
                    easing = FlashMotion.Decelerate,
                ),
            )
        }
    }

    val backgroundColor by animateColorAsState(
        targetValue = if (reaction.isSelfReacted) {
            colors.accentPrimary.copy(alpha = 0.14f)
        } else {
            colors.backgroundSurfaceSubtle
        },
        animationSpec = motion.tweenFastSpec(),
        label = "reactionChipBg",
    )

    val borderColor by animateColorAsState(
        targetValue = if (reaction.isSelfReacted) {
            colors.accentPrimary
        } else {
            colors.borderSubtle
        },
        animationSpec = motion.tweenFastSpec(),
        label = "reactionChipBorder",
    )

    val borderWidth by animateDpAsState(
        targetValue = if (reaction.isSelfReacted) 1.5.dp else FlashDimensions.borderHairline,
        animationSpec = motion.springSnappySpec(),
        label = "reactionChipBorderWidth",
    )

    val contentDesc = buildString {
        append(reaction.emoji)
        append(": ")
        append(reaction.count)
        append(" ")
        append(if (reaction.count == 1) "reaction" else "reactions")
        if (reaction.isSelfReacted) {
            append(", you reacted")
        }
        append(". Tap to toggle.")
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (puffProgress.value < 1f) {
            val accent = colors.accentPrimary
            Canvas(Modifier.matchParentSize().graphicsLayer { alpha = 1f - puffProgress.value }) {
                val radius = (size.maxDimension / 2f + 8.dp.toPx()) * puffProgress.value
                val particleRadius = 2.dp.toPx()
                drawCircle(accent, particleRadius, center = Offset(center.x - radius, center.y))
                drawCircle(accent, particleRadius, center = Offset(center.x + radius, center.y))
                drawCircle(accent, particleRadius, center = Offset(center.x, center.y - radius))
                drawCircle(accent, particleRadius, center = Offset(center.x, center.y + radius))
            }
        }

        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                }
                .sizeIn(minWidth = 36.dp, minHeight = 28.dp)
                .flashPressScale(interactionSource, pressedScale = 0.94f)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(BorderStroke(borderWidth, borderColor), CircleShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptics(FlashHaptic.Tick)
                        onToggle()
                    },
                    onLongClick = {
                        haptics(FlashHaptic.Confirm)
                        onLongClick()
                    },
                )
                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                .semantics {
                    role = Role.Button
                    contentDescription = contentDesc
                },
            contentAlignment = Alignment.Center,
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            FlashText(
                text = reaction.emoji,
                style = typography.bodyDefault.copy(fontSize = 14.sp, lineHeight = 16.sp),
            )

            if (reaction.count > 0) {
                Spacer(modifier = Modifier.width(FlashSpacing.space4))
                AnimatedContent(
                    targetState = reaction.count,
                    transitionSpec = {
                        if (motion.reduceMotion) {
                            fadeIn() togetherWith fadeOut()
                        } else if (targetState > initialState) {
                            (slideInVertically { -it } + fadeIn()) togetherWith
                                (slideOutVertically { it } + fadeOut())
                        } else {
                            (slideInVertically { it } + fadeIn()) togetherWith
                                (slideOutVertically { -it } + fadeOut())
                        }
                    },
                    label = "reactionCountRoll",
                ) { count ->
                    FlashText(
                        text = count.toString(),
                        style = if (reaction.isSelfReacted) {
                            typography.numericEmphasis
                        } else {
                            typography.numericDefault
                        },
                        color = if (reaction.isSelfReacted) {
                            colors.accentPrimary
                        } else {
                            colors.textSecondary
                        },
                    )
                }
            }
        }
    }
    }
}

@Preview(name = "Reaction Chip - Inactive", showBackground = true)
@Composable
private fun FlashReactionChipInactivePreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashReactionChip(
                reaction = FlashReaction(emoji = "👍", count = 3, isSelfReacted = false),
                onToggle = {},
                onLongClick = {},
            )
        }
    }
}

@Preview(name = "Reaction Chip - Self Reacted", showBackground = true)
@Composable
private fun FlashReactionChipSelfPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashReactionChip(
                reaction = FlashReaction(emoji = "❤️", count = 5, isSelfReacted = true),
                onToggle = {},
                onLongClick = {},
            )
        }
    }
}
