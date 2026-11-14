package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
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

    /**
     * Whether the composer may ask the host to start capture for the gesture beginning now.
     *
     * Only an idle composer can start, and a gesture that already asked once cannot ask again. A
     * refused start (permission prompt, call/PTT lease, busy encoder) must therefore leave the
     * phase Idle: the same hold cannot slide into a recording panel that has no audio behind it.
     */
    fun canStartCapture(phase: FlashRecordingPhase, captureStarted: Boolean): Boolean =
        !captureStarted && phase == FlashRecordingPhase.Idle

    /**
     * Whether a hold-drag may change the phase.
     *
     * Slides only mean something for the gesture that actually started capture, and only in hold
     * mode. A locked (hands-free) recording is ended by its own trash/send controls, never by a
     * stray drag across the trailing key that now sends it.
     */
    fun acceptsSlide(phase: FlashRecordingPhase, captureStarted: Boolean): Boolean =
        captureStarted &&
            (phase == FlashRecordingPhase.Holding || phase == FlashRecordingPhase.CancelArmed)

    /**
     * The trailing 44dp slot becomes the voice send key once a locked recording's finger is up.
     *
     * While the finger is still down the same slot stays the mic, so the hold/release pointer
     * stream that produced the lock is never disposed mid-gesture (ERROR-010).
     */
    fun showsVoiceSend(phase: FlashRecordingPhase, lockedHandsFree: Boolean): Boolean =
        lockedHandsFree && phase == FlashRecordingPhase.Locked

    /** Outcome when a voice mic press/hold ends. */
    enum class ReleaseAction {
        None,
        Cancel,
        DiscardShortTap,
        LockHandsFree,
        FinishLockGesture,
        Send,
    }

    /**
     * Resolves what releasing the mic button does given the current gesture state.
     *
     * On desktop (`isDesktop == true`), a quick click (< [MIN_RECORD_MS]) locks hands-free mode
     * so mouse users can click to start recording and click Send/Cancel to finish, whereas touch
     * users get a "Hold to record" prompt on accidental short taps.
     */
    fun resolveReleaseAction(
        phase: FlashRecordingPhase,
        captureStarted: Boolean,
        elapsedMs: Long,
        isDesktop: Boolean,
    ): ReleaseAction {
        if (!captureStarted) return ReleaseAction.None
        return when (phase) {
            FlashRecordingPhase.CancelArmed -> ReleaseAction.Cancel
            FlashRecordingPhase.Locked -> ReleaseAction.FinishLockGesture
            FlashRecordingPhase.Holding -> when {
                elapsedMs < MIN_RECORD_MS && isDesktop -> ReleaseAction.LockHandsFree
                elapsedMs < MIN_RECORD_MS -> ReleaseAction.DiscardShortTap
                else -> ReleaseAction.Send
            }
            FlashRecordingPhase.Idle -> ReleaseAction.None
        }
    }

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
    onInterrupted: () -> Unit = {},
    onStartHandsFree: (() -> Unit)? = null,
) {
    val startRecord by rememberUpdatedState(onStartRecord)
    val slideUpdate by rememberUpdatedState(onSlideUpdate)
    val recordEnd by rememberUpdatedState(onRecordEnd)
    val interrupted by rememberUpdatedState(onInterrupted)
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cancelThresholdPx = with(density) { FlashVoiceRecordingMath.CANCEL_SLIDE_DP.dp.toPx() }
    val lockThresholdPx = with(density) { FlashVoiceRecordingMath.LOCK_SLIDE_DP.dp.toPx() }

    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val slideX = if (isRecording) (dragOffsetX * 0.45f).coerceIn(-cancelThresholdPx, 0f) else 0f
    val slideY = if (isRecording) (dragOffsetY * 0.45f).coerceIn(-lockThresholdPx, 0f) else 0f
    val isNearCancel = isRecording && -dragOffsetX >= cancelThresholdPx * 0.75f
    val isNearLock = isRecording && -dragOffsetY >= lockThresholdPx * 0.75f

    // `pressScale` stays an Animatable and is read inside `graphicsLayer` (EXP-013) so the spring
    // never recomposes the button. Fast taps/clicks still dip briefly before springing back so both
    // Desktop clicks and Android taps feel responsive.
    val pressScale = remember { Animatable(1f) }
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isNearCancel -> colors.textError.copy(alpha = 0.25f)
            isRecording -> colors.accentFill
            else -> Color.Transparent
        },
        animationSpec = tween(motion.fastMillis),
        label = "mic_button_background",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isNearCancel -> colors.textError
            isRecording -> colors.onAccentFill
            else -> colors.textTertiary
        },
        animationSpec = tween(motion.fastMillis),
        label = "mic_button_icon_tint",
    )

    val currentIcon = when {
        isNearCancel -> FlashIcons.Delete
        isNearLock -> FlashIcons.Pin
        else -> FlashIcons.Microphone
    }

    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = FlashDimensions.minTouchTarget,
                minHeight = FlashDimensions.minTouchTarget,
            )
            .pointerInput(enabled, motion.reduceMotion) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val pressInteraction = PressInteraction.Press(down.position)
                    scope.launch {
                        interactionSource.emit(pressInteraction)
                    }
                    if (!motion.reduceMotion) {
                        scope.launch {
                            pressScale.animateTo(
                                targetValue = 0.84f,
                                animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
                            )
                        }
                    }
                    haptics(FlashHaptic.Confirm)
                    startRecord()
                    val touchSlop = viewConfiguration.touchSlop
                    var totalDx = 0f
                    var totalDy = 0f
                    var touchSlopPassed = false
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                released = true
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            if (!touchSlopPassed) {
                                if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                    touchSlopPassed = true
                                }
                            }
                            if (touchSlopPassed) {
                                dragOffsetX = totalDx
                                dragOffsetY = totalDy
                                slideUpdate(totalDx, totalDy)
                            } else {
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                slideUpdate(0f, 0f)
                            }
                            change.consume()
                        }
                    } finally {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        scope.launch {
                            interactionSource.emit(
                                if (released) {
                                    PressInteraction.Release(pressInteraction)
                                } else {
                                    PressInteraction.Cancel(pressInteraction)
                                },
                            )
                        }
                        if (motion.reduceMotion) {
                            scope.launch { pressScale.snapTo(1f) }
                        } else {
                            scope.launch {
                                if (pressScale.value > 0.90f) {
                                    pressScale.animateTo(
                                        targetValue = 0.86f,
                                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 1200f),
                                    )
                                }
                                pressScale.animateTo(
                                    targetValue = 1.0f,
                                    animationSpec = spring(dampingRatio = 0.38f, stiffness = 520f),
                                )
                            }
                        }
                        if (!released) interrupted()
                    }
                    if (released) recordEnd()
                }
            }
            .onKeyEvent { event ->
                if (enabled && onStartHandsFree != null && event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.Spacebar)
                ) {
                    onStartHandsFree()
                    true
                } else false
            }
            .focusable(enabled && onStartHandsFree != null)
            .semantics {
                role = Role.Button
                contentDescription = "Voice message"
                stateDescription = if (isRecording) "Recording" else "Ready to record"
                if (enabled && onStartHandsFree != null) {
                    onClick(label = "Start hands-free recording") {
                        onStartHandsFree()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(FlashSpacing.space44)
                .graphicsLayer {
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                    translationX = slideX
                    translationY = slideY
                }
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = currentIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(FlashSpacing.space20),
            )
        }
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
    showInBarSend: Boolean = true,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val isCancelArmed = phase == FlashRecordingPhase.CancelArmed
    val isLocked = phase == FlashRecordingPhase.Locked

    // Pulse the red dot while actively recording; static under reduce-motion.
    // Kept as a `State`, not unwrapped to a `Float` (EXP-013): the only consumer is a
    // `graphicsLayer` block far below, so reading `.value` here subscribed this whole bar —
    // the AnimatedContent mode swap, the amplitude strip, the timer and four buttons — to a
    // 60 Hz clock for the entire duration of every recording. Read inside the layer it costs a
    // re-draw of one 10dp dot and no recomposition at all.
    val pulseAlpha: State<Float> = if (motion.reduceMotion) {
        val resting = if (isPaused) 0.35f else 1f
        remember(resting) { mutableFloatStateOf(resting) }
    } else {
        rememberInfiniteTransition(label = "recordDotPulse").animateFloat(
            initialValue = 1f,
            targetValue = if (isPaused) 0.35f else 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = FlashMotion.Standard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recordDotAlpha",
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
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
                            .graphicsLayer {
                                alpha = pulseAlpha.value
                                // One solid dot in this layer: modulating alpha into the draw is
                                // identical and avoids the offscreen buffer Auto can allocate.
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
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
                    if (showInBarSend) {
                        FlashRecordingSendButton(onClick = onSend)
                    }
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
            .background(colors.accentFill)
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
            tint = colors.onAccentFill,
            size = FlashDimensions.iconSm,
        )
    }
}

/**
 * 44dp accent send key occupying the composer's trailing slot once a locked recording is
 * hands-free (the hold that produced the lock has ended).
 *
 * It replaces the mic in that slot, so tapping the prominent trailing control sends the recording
 * instead of asking the host to start a second capture. The recording bar keeps its own 32dp send
 * button as the in-bar alternative.
 */
@Composable
internal fun FlashVoiceTrailingSendButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    Box(
        modifier = modifier
            .size(FlashSpacing.space44)
            .clip(CircleShape)
            .background(colors.accentFill)
            .clickable {
                haptics(FlashHaptic.Tick)
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = "Send voice message"
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Send,
            contentDescription = null,
            tint = colors.onAccentFill,
            modifier = Modifier.size(FlashSpacing.space20),
        )
    }
}

/** Compact floating pill shown above the mic during hold or after a short accidental tap. */
@Composable
internal fun FlashVoiceHintPill(
    text: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Box(
        modifier = modifier
            .clip(FlashShapes.chip)
            .background(colors.backgroundSurface)
            .border(
                FlashDimensions.borderHairline,
                if (isError) colors.textError else colors.borderSubtle,
                FlashShapes.chip,
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = text,
            style = typography.captionEmphasis,
            color = if (isError) colors.textError else colors.textSecondary,
            maxLines = 1,
        )
    }
}
