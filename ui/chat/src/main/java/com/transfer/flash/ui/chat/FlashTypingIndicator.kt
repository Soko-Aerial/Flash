package com.transfer.flash.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme

/**
 * UI-014 Core 3-dot wave typing indicator.
 *
 * Runs a fluid GPU-accelerated wave animation via [rememberInfiniteTransition] and [graphicsLayer]
 * without triggering recomposition cycles — which is what this KDoc claimed before EXP-013 and is
 * only now true. The three waves were declared `val wave0 by …animateFloat(…)`, and `by` reads the
 * animation *here*, in this composable's body: every frame invalidated `FlashTypingIndicator`, which
 * re-ran the whole body (three `animateFloat` calls, a fresh `listOf` and three full modifier chains)
 * at display refresh rate, then handed `graphicsLayer` a constant it could not animate on its own.
 * Keeping them as `State<Float>` and reading `.value` *inside* the layer block is what actually puts
 * the animation in the render pipeline. Same motion, same easing, same phase offsets as before.
 */
@Composable
fun FlashTypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = FlashTheme.colors.textSecondary,
    dotSize: Dp = 6.dp,
    dotSpacing: Dp = 4.dp,
) {
    val motion = FlashTheme.motion
    val density = LocalDensity.current

    if (motion.reduceMotion) {
        // Accessibility fallback: Static 3 dots with subtle opacity
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(dotSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.75f)),
                )
            }
        }
        return
    }

    val maxTranslationPx = with(density) { -4.dp.toPx() }
    val infiniteTransition = rememberInfiniteTransition(label = "typingIndicatorWave")
    val totalDuration = 900
    val phaseOffset = 120

    val wave0 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = totalDuration
                0f at 0
                1f at phaseOffset with FastOutSlowInEasing
                0f at (phaseOffset * 2) with FastOutSlowInEasing
                0f at totalDuration
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotWave0",
    )

    val wave1 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = totalDuration
                0f at phaseOffset
                1f at (phaseOffset * 2) with FastOutSlowInEasing
                0f at (phaseOffset * 3) with FastOutSlowInEasing
                0f at totalDuration
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotWave1",
    )

    val wave2 = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = totalDuration
                0f at (phaseOffset * 2)
                1f at (phaseOffset * 3) with FastOutSlowInEasing
                0f at (phaseOffset * 4) with FastOutSlowInEasing
                0f at totalDuration
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotWave2",
    )

    // Remembered so the list itself is not re-allocated on the (now rare) recomposition of this
    // composable; the animation values live in the State objects, not in the list.
    val waves: List<State<Float>> = remember(wave0, wave1, wave2) { listOf(wave0, wave1, wave2) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        waves.forEach { wave ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val progress = wave.value
                        translationY = maxTranslationPx * progress
                        scaleX = 0.85f + (0.30f * progress)
                        scaleY = 0.85f + (0.30f * progress)
                        alpha = 0.45f + (0.55f * progress)
                        // One solid circle per layer, so modulating alpha into the draw is
                        // pixel-identical and skips the offscreen buffer the default
                        // CompositingStrategy.Auto can allocate for alpha < 1 (EXP-013).
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

/**
 * UI-014 Message list typing bubble container.
 */
@Composable
fun FlashTypingBubble(
    peerName: String,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    Box(
        modifier = modifier
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$peerName is typing..."
            }
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.chatBgIncoming)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.chatBorderIncoming,
                shape = FlashShapes.bubbleGrouped,
            )
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        contentAlignment = Alignment.Center,
    ) {
        FlashTypingIndicator(
            dotColor = colors.chatTextIncoming.copy(alpha = 0.8f),
            dotSize = 7.dp,
            dotSpacing = 5.dp,
        )
    }
}

/**
 * UI-014 Header subtitle typing status with label and animated mini-dots.
 */
@Composable
fun FlashHeaderTypingStatus(
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Row(
        modifier = modifier.semantics {
            contentDescription = "Status: typing..."
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "typing",
            style = typography.captionEmphasis.copy(fontSize = 11.sp),
            color = colors.accentPrimary,
        )
        Spacer(modifier = Modifier.width(FlashSpacing.space4))
        FlashTypingIndicator(
            dotColor = colors.accentPrimary,
            dotSize = 3.5.dp,
            dotSpacing = 2.5.dp,
        )
    }
}

@Preview(name = "Typing Indicator - Wave", showBackground = true)
@Composable
private fun FlashTypingIndicatorPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashTypingIndicator()
        }
    }
}

@Preview(name = "Typing Bubble - Incoming", showBackground = true)
@Composable
private fun FlashTypingBubblePreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashTypingBubble(peerName = "Sebastian")
        }
    }
}

@Preview(name = "Header Typing Status", showBackground = true)
@Composable
private fun FlashHeaderTypingStatusPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashHeaderTypingStatus()
        }
    }
}
