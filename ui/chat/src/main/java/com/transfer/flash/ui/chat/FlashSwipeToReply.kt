package com.transfer.flash.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * UI-010 Swipe-to-Reply Gesture Container.
 *
 * Wraps a message bubble, enabling an inward left-swipe drag gesture.
 * When dragged past [thresholdDp] (52dp), triggers haptic feedback and reveals a rotating
 * reply icon badge. On release, snaps back via spring physics and invokes [onReply].
 */
@Composable
fun FlashSwipeToReplyContainer(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isMine: Boolean = false,
    thresholdDp: Dp = 52.dp,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier) { content() }
        return
    }

    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val thresholdPx = with(density) { thresholdDp.toPx() }
    val dampingConstantPx = with(density) { 24.dp.toPx() }
    val maxDragPx = with(density) { 80.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    val progress = (abs(offsetX.value) / thresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(enabled) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        hasTriggeredHaptic = false
                    },
                    onDragEnd = {
                        val reachedThreshold = abs(offsetX.value) >= thresholdPx
                        if (reachedThreshold) {
                            onReply()
                        }
                        coroutineScope.launch {
                            if (motion.reduceMotion) {
                                offsetX.snapTo(0f)
                            } else {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.65f,
                                        stiffness = 500f,
                                    ),
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, spring())
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        val current = offsetX.value
                        val newRaw = current + dragAmount

                        // Only allow leftward swipe (negative translationX)
                        if (newRaw <= 0f) {
                            change.consume()
                            val dragDistance = abs(newRaw)
                            val dampened = if (dragDistance <= thresholdPx) {
                                -dragDistance
                            } else {
                                val extra = dragDistance - thresholdPx
                                val dampenedExtra = dampingConstantPx * ln(1f + extra / dampingConstantPx)
                                -(thresholdPx + dampenedExtra).coerceAtMost(maxDragPx)
                            }

                            coroutineScope.launch {
                                offsetX.snapTo(dampened)
                            }

                            if (abs(dampened) >= thresholdPx && !hasTriggeredHaptic) {
                                haptics(FlashHaptic.Confirm)
                                hasTriggeredHaptic = true
                            } else if (abs(dampened) < thresholdPx && hasTriggeredHaptic) {
                                hasTriggeredHaptic = false
                            }
                        }
                    },
                )
            },
    ) {
        // Behind-the-bubble reply icon badge (revealed on the right side)
        if (progress > 0.05f) {
            val iconScale = (0.4f + (0.6f * progress)).coerceIn(0.4f, 1.15f)
            val iconRotation = -35f * (1f - progress)
            val iconAlpha = (progress * progress).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = FlashSpacing.space8)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        rotationZ = iconRotation
                        alpha = iconAlpha
                    }
                    .size(FlashSpacing.space32)
                    .clip(CircleShape)
                    .background(colors.accentPrimary),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Reply,
                    contentDescription = "Reply",
                    tint = colors.textOnAccent,
                    modifier = Modifier.size(FlashDimensions.iconSm),
                )
            }
        }

        // Foreground Message Bubble. The container is fillMaxWidth (so the reply badge can sit at
        // CenterEnd), which would otherwise pin the narrower bubble to TopStart and defeat the
        // parent Column's horizontalAlignment — so align the bubble to its own side here.
        Box(
            modifier = Modifier
                .align(if (isMine) Alignment.CenterEnd else Alignment.CenterStart)
                .offset { IntOffset(x = offsetX.value.roundToInt(), y = 0) },
        ) {
            content()
        }
    }
}
