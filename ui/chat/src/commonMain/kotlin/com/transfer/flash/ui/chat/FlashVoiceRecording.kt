package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlin.math.abs

/**
 * UI-020 Voice recording interface — gesture state machine phases.
 */
enum class FlashRecordingPhase {
    Idle,
    Holding,
    CancelArmed,
    Locked,
}

/** Slide target resolved from a hold-drag displacement. */
enum class FlashHoldSlideTarget {
    Stay,
    Cancel,
    Lock,
}

/**
 * Pure math + decision logic for the voice recording interface (UI-020).
 * Free of Compose runtime types — unit-testable (see [FlashVoiceRecordingLogicTest]).
 */
object FlashVoiceRecordingMath {
    const val CANCEL_SLIDE_DP = 96f
    const val LOCK_SLIDE_DP = 72f
    const val MIN_RECORD_MS = 500L
    const val AMPLITUDE_SMOOTHING_ALPHA = 0.35f
    const val TICK_MS = 100L
    const val STRIP_BAR_COUNT = 28
    const val DEMO_AMPLITUDE_MIN = 15
    const val DEMO_AMPLITUDE_MAX = 100

    /**
     * Dominant-axis slide resolution for a hold drag.
     *
     * Leftward displacement ([dx] negative) claims [FlashHoldSlideTarget.Cancel] when it
     * dominates vertically and passes [cancelThresholdPx]; upward displacement ([dy]
     * negative) claims [FlashHoldSlideTarget.Lock] when it dominates horizontally and
     * passes [lockThresholdPx]. Anything else stays.
     */
    fun resolveHoldSlide(
        dx: Float,
        dy: Float,
        cancelThresholdPx: Float,
        lockThresholdPx: Float,
    ): FlashHoldSlideTarget {
        val leftward = -dx
        val upward = -dy
        return when {
            leftward >= cancelThresholdPx && abs(leftward) >= abs(upward) -> FlashHoldSlideTarget.Cancel
            upward >= lockThresholdPx -> FlashHoldSlideTarget.Lock
            else -> FlashHoldSlideTarget.Stay
        }
    }

    /** Exponential moving average smoothing of amplitude samples (0..100). */
    fun smoothAmplitude(previous: Int, next: Int, alpha: Float = AMPLITUDE_SMOOTHING_ALPHA): Int {
        val smoothed = previous + (alpha * (next - previous)).toInt()
        return smoothed.coerceIn(DEMO_AMPLITUDE_MIN, DEMO_AMPLITUDE_MAX)
    }

    /** Procedural demo-mode amplitude: bounded random walk around [previous]. */
    fun nextDemoAmplitude(previous: Int, random: kotlin.random.Random = kotlin.random.Random): Int {
        val next = previous + random.nextInt(-25, 26)
        return next.coerceIn(DEMO_AMPLITUDE_MIN, DEMO_AMPLITUDE_MAX)
    }

    /** Presses shorter than [MIN_RECORD_MS] discard silently instead of sending. */
    fun shouldDiscardShortRecording(elapsedMs: Long): Boolean =
        elapsedMs < MIN_RECORD_MS

    /** Window of strip samples to render: the most recent [count] amplitudes. */
    fun stripSamples(amplitudes: List<Int>, count: Int = STRIP_BAR_COUNT): List<Int> =
        if (amplitudes.size <= count) amplitudes else amplitudes.takeLast(count)
}

/**
 * UI-020 Microphone button occupying the send-button slot when the draft is blank.
 *
 * Hold-to-record gesture: press starts recording; leftward slide arms cancel;
 * upward slide locks. Release resolves via [onRecordEnd] — the parent owns phase state.
 */
@Composable
fun FlashMicButton(
    isRecording: Boolean,
    onStartRecord: () -> Unit,
    onSlideUpdate: (Float, Float) -> Unit,
    onRecordEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cancelThresholdPx = with(density) { FlashVoiceRecordingMath.CANCEL_SLIDE_DP.dp.toPx() }
    val lockThresholdPx = with(density) { FlashVoiceRecordingMath.LOCK_SLIDE_DP.dp.toPx() }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if ((isPressed || isRecording) && !motion.reduceMotion) 0.90f else 1.0f,
        animationSpec = motion.springSnappySpec(),
        label = "mic_button_press_scale",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (isRecording) colors.accentPrimary else Color.Transparent,
        animationSpec = tween(motion.fastMillis),
        label = "mic_button_background",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isRecording) colors.textOnAccent else colors.textTertiary,
        animationSpec = tween(motion.fastMillis),
        label = "mic_button_icon_tint",
    )

    Box(
        modifier = modifier
            .size(FlashSpacing.space40)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    haptics(FlashHaptic.Confirm)
                    onStartRecord()
                    var totalDx = 0f
                    var totalDy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        onSlideUpdate(totalDx, totalDy)
                        change.consume()
                    }
                    onRecordEnd()
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Hold to record voice message"
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Microphone,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(FlashSpacing.space20),
        )
    }
}

/**
 * UI-020 Recording bar replacing the composer input row while recording.
 *
 * Hold mode: pulsing dot + timer · amplitude strip · "‹ Slide to cancel" hint.
 * Locked mode: trash · amplitude strip · pause/resume · timer · send.
 */
@Composable
fun FlashVoiceRecordingBar(
    phase: FlashRecordingPhase,
    elapsedMs: Long,
    amplitudes: List<Int>,
    isPaused: Boolean,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val isCancelArmed = phase == FlashRecordingPhase.CancelArmed
    val isLocked = phase == FlashRecordingPhase.Locked

    // Pulse the red dot while actively recording; static under reduce-motion.
    val pulseAlpha: Float = if (motion.reduceMotion) {
        if (isPaused) 0.35f else 1f
    } else {
        val pulse by rememberInfiniteTransition(label = "recordDotPulse").animateFloat(
            initialValue = 1f,
            targetValue = if (isPaused) 0.35f else 0.45f,
            animationSpec = infiniteRepeatable(tween(motion.normalMillis)),
            label = "recordDotAlpha",
        )
        pulse
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(FlashShapes.composerInput)
            .background(if (isCancelArmed) colors.textError.copy(alpha = 0.12f) else colors.composerInputBackground)
            .border(
                width = FlashDimensions.borderHairline,
                color = if (isCancelArmed) colors.textError else colors.borderSubtle,
                shape = FlashShapes.composerInput,
            )
            .padding(horizontal = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        AnimatedContent(
            targetState = isLocked,
            transitionSpec = {
                (fadeIn(motion.tweenFastSpec())) togetherWith (fadeOut(motion.tweenFastSpec()))
            },
            label = "recordingBarModeSwap",
        ) { locked ->
            if (locked) {
                // Locked mode leading control: trash (cancel).
                FlashRecordingIconButton(
                    icon = FlashIcons.Delete,
                    description = "Cancel recording",
                    tint = colors.textError,
                    onClick = {
                        haptics(FlashHaptic.Reject)
                        onCancel()
                    },
                )
            } else {
                // Hold mode leading control: pulsing red dot + timer.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer { alpha = pulseAlpha }
                            .clip(CircleShape)
                            .background(colors.textError),
                    )
                    Spacer(modifier = Modifier.width(FlashSpacing.space8))
                    FlashText(
                        text = FlashVoiceMath.formatDuration(elapsedMs),
                        style = FlashTheme.typography.numericEmphasis.copy(fontSize = FlashTheme.typography.metadataDefault.fontSize),
                        color = colors.textSecondary,
                    )
                }
            }
        }

        FlashAmplitudeStrip(
            amplitudes = amplitudes,
            color = if (isCancelArmed) colors.textError else colors.accentPrimary,
            modifier = Modifier.weight(1f),
        )

        AnimatedContent(
            targetState = isLocked,
            transitionSpec = {
                (fadeIn(motion.tweenFastSpec())) togetherWith (fadeOut(motion.tweenFastSpec()))
            },
            label = "recordingBarTrailingSwap",
        ) { locked ->
            if (locked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                ) {
                    FlashRecordingIconButton(
                        icon = if (isPaused) FlashIcons.Play else FlashIcons.Pause,
                        description = if (isPaused) "Resume recording" else "Pause recording",
                        tint = colors.textSecondary,
                        onClick = onTogglePause,
                    )
                    FlashText(
                        text = FlashVoiceMath.formatDuration(elapsedMs),
                        style = typography.numericEmphasis.copy(fontSize = typography.metadataDefault.fontSize),
                        color = colors.textSecondary,
                    )
                    FlashRecordingSendButton(onClick = onSend)
                }
            } else {
                FlashText(
                    text = if (isCancelArmed) "Release to cancel" else "‹ Slide to cancel",
                    style = typography.captionDefault,
                    color = if (isCancelArmed) colors.textError else colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Amplitude strip drawn in Canvas — mirrors the playback waveform bar language (UI-019). */
@Composable
private fun FlashAmplitudeStrip(
    amplitudes: List<Int>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val samples = remember(amplitudes) {
        FlashVoiceRecordingMath.stripSamples(amplitudes)
    }
    Canvas(modifier = modifier.height(24.dp)) {
        val gap = 2.dp.toPx()
        val slot = 3.dp.toPx() + gap
        val cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        val count = (size.width / slot).toInt().coerceAtLeast(1)
        val visible = samples.takeLast(count)
        visible.forEachIndexed { index, amplitude ->
            val normalized = (amplitude / 100f).coerceIn(FlashVoiceMath.MIN_BAR_HEIGHT, 1f)
            val barHeight = normalized * size.height
            val x = size.width - (visible.size - index) * slot
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(3.dp.toPx(), barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/** Small circular icon button used inside the recording bar. */
@Composable
private fun FlashRecordingIconButton(
    icon: FlashIconSpec,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            contentDescription = null,
            tint = tint,
            size = FlashDimensions.iconSm,
        )
    }
}

/** Accent circular send button terminating a locked recording. */
@Composable
private fun FlashRecordingSendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colors.accentPrimary)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Send voice message"
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Send,
            contentDescription = null,
            tint = colors.textOnAccent,
            size = FlashDimensions.iconSm,
        )
    }
}
