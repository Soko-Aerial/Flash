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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.channels.Channel
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
    val density = LocalDensity.current
    // pointerInput(enabled) keeps the lambdas it first saw, so the reply must be read through this (RVW22 F31).
    val currentOnReply by rememberUpdatedState(onReply)

    val thresholdPx = with(density) { thresholdDp.toPx() }
    val dampingConstantPx = with(density) { 24.dp.toPx() }
    val maxDragPx = with(density) { 80.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    // H-19: nothing in composition reads the drag offset. The bubble moves through a layout-phase `offset { }` and the badge through
    // a draw-phase `graphicsLayer { }`, so a drag recomposes nothing per frame; `revealed` flips only at the start and end of a gesture
    // (it also keeps the badge composed while the spring settles) so an idle bubble carries no badge at all.
    var revealed by remember { mutableStateOf(false) }

    // Drag positions go through a conflated channel to ONE collector instead of a new coroutine per touch event (a
    // 120 Hz drag launched hundreds per swipe). `null` means "let go": it is queued behind the last position, so the
    // settle can never run before a late snap and leave the bubble stuck off-centre.
    val dragEvents = remember { Channel<Float?>(Channel.CONFLATED) }
    val lastTarget = remember { FloatArray(1) }
    val reduceMotion = motion.reduceMotion
    LaunchedEffect(dragEvents, reduceMotion) {
        for (event in dragEvents) {
            if (event != null) {
                offsetX.snapTo(event)
            } else {
                launch {
                    if (reduceMotion) {
                        offsetX.snapTo(0f)
                    } else {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                        )
                    }
                    revealed = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(enabled) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        hasTriggeredHaptic = false
                        lastTarget[0] = offsetX.value
                        revealed = true
                    },
                    onDragEnd = {
                        val reachedThreshold = abs(lastTarget[0]) >= thresholdPx
                        if (reachedThreshold) {
                            currentOnReply()
                        }
                        lastTarget[0] = 0f
                        dragEvents.trySend(null)
                    },
                    onDragCancel = {
                        lastTarget[0] = 0f
                        dragEvents.trySend(null)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        // The latest position this gesture asked for: offsetX itself lags behind the collector.
                        val current = lastTarget[0]
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

                            lastTarget[0] = dampened
                            dragEvents.trySend(dampened)

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
        if (revealed) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = FlashSpacing.space8)
                    .graphicsLayer {
                        val progress = (abs(offsetX.value) / thresholdPx).coerceIn(0f, 1f)
                        val iconScale = (0.4f + (0.6f * progress)).coerceIn(0.4f, 1.15f)
                        scaleX = iconScale
                        scaleY = iconScale
                        rotationZ = -35f * (1f - progress)
                        alpha = if (progress > 0.05f) (progress * progress).coerceIn(0f, 1f) else 0f
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
