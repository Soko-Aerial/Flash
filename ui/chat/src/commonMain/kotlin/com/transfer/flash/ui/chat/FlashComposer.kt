package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-011 / UI-013 Flash Custom Message Composer & Send Button.
 *
 * Adaptive chat composer featuring:
 * - Dynamic single/multiline text expansion (1 to 6 lines) using [BasicTextField]
 * - IME keyboard synchronization with [imePadding] and navigation bar inset safety with [navigationBarsPadding]
 * - Contextual reply/edit preview dock
 * - Dedicated tactile [FlashSendButton] with micro-press physics and state transitions
 * - Zero dependency on generic Material text field overhead
 */
@Composable
fun FlashComposer(
    draft: String,
    onDraftChanged: (String) -> Unit,
    onAttachmentClick: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    replyingTo: FlashMessageUi? = null,
    onDismissReply: () -> Unit = {},
    isAttachmentExpanded: Boolean = false,
    enabled: Boolean = true,
    placeholderText: String = "Message...",
    /** UI-020: invoked when a voice recording is sent. [onSendVoice] carries duration + waveform;
     *  the host owns the recorded audio file (started via [onVoiceRecordStart]). */
    onSendVoice: (FlashVoiceAttachmentUi) -> Unit = {},
    /** B9: fired when a hold begins. Return false if capture could not start (e.g. mic permission
     *  not yet granted) so the composer aborts the recording gesture. Default keeps demo behavior. */
    onVoiceRecordStart: () -> Boolean = { true },
    /** B9: fired when a recording is discarded (slide-to-cancel or below the minimum length). */
    onVoiceRecordCancel: () -> Unit = {},
    /** B9: real per-tick loudness (0..100) from the live recorder; null falls back to demo samples. */
    voiceAmplitudeProvider: (() -> Int)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val density = androidx.compose.ui.platform.LocalDensity.current
    val canSend = draft.isNotBlank() && enabled

    // --- UI-020 voice recording state (local to the composer) ---
    var recordingPhase by remember { mutableStateOf(FlashRecordingPhase.Idle) }
    var recordElapsedMs by remember { mutableLongStateOf(0L) }
    var recordAmplitudes by remember { mutableStateOf(listOf<Int>()) }
    var recordPaused by remember { mutableStateOf(false) }

    val isRecording = recordingPhase != FlashRecordingPhase.Idle

    fun resetRecording() {
        recordingPhase = FlashRecordingPhase.Idle
        recordElapsedMs = 0L
        recordAmplitudes = emptyList()
        recordPaused = false
    }

    fun cancelRecording() {
        onVoiceRecordCancel()
        resetRecording()
    }

    fun sendVoiceRecording() {
        if (recordElapsedMs >= FlashVoiceRecordingMath.MIN_RECORD_MS) {
            onSendVoice(
                FlashVoiceAttachmentUi(
                    // `System.currentTimeMillis()` is `java.lang` and does not exist in
                    // `commonMain`; `SystemTimeSource.nowMs()` is `:core:common`'s Phase 06 seam
                    // for exactly this call and reads the same wall clock on both targets.
                    id = "voice-${SystemTimeSource.nowMs()}",
                    durationMs = recordElapsedMs,
                    amplitudes = recordAmplitudes.toList(),
                ),
            )
            resetRecording()
        } else {
            // Below the minimum length — discard the partial capture rather than sending noise.
            cancelRecording()
        }
    }

    // Recording ticker: advances timer + appends waveform samples (real mic loudness when a
    // [voiceAmplitudeProvider] is supplied, otherwise smoothed demo-mode values).
    LaunchedEffect(isRecording, recordPaused) {
        var last = 60
        while (isRecording && !recordPaused) {
            kotlinx.coroutines.delay(FlashVoiceRecordingMath.TICK_MS)
            if (!recordPaused) {
                recordElapsedMs += FlashVoiceRecordingMath.TICK_MS
                val sample = voiceAmplitudeProvider?.invoke()
                    ?.takeIf { it > 0 }
                    ?: FlashVoiceRecordingMath.nextDemoAmplitude(last).also { last = it }
                recordAmplitudes = (recordAmplitudes + FlashVoiceRecordingMath.smoothAmplitude(recordAmplitudes.lastOrNull() ?: sample, sample))
                    .takeLast(FlashVoiceRecordingMath.STRIP_BAR_COUNT * 3)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.composerSurface)
            .navigationBarsPadding()
            .imePadding()
            .animateContentSize(),
    ) {
        HorizontalDivider(
            color = colors.borderSubtle,
            thickness = FlashDimensions.borderHairline,
        )

        // Contextual Reply Preview Dock
        AnimatedVisibility(
            visible = replyingTo != null,
            enter = motion.replyExpandEnter(),
            exit = if (motion.reduceMotion) fadeOut(tween(0)) else fadeOut(tween(motion.fastMillis)),
        ) {
            if (replyingTo != null) {
                FlashReplyDock(
                    replyingTo = replyingTo,
                    onDismiss = onDismissReply,
                )
            }
        }

        // Main input bar row � transforms into the recording surface (UI-020).
        // The mic button lives OUTSIDE the swapped region so ONE persistent node owns
        // the hold gesture across Idle/Holding/CancelArmed � swapping it mid-hold would
        // dispose its pointerInput stream and kill slide/release handling.
        val isRecordingActive = recordingPhase != FlashRecordingPhase.Idle
        val showMic = when (recordingPhase) {
            FlashRecordingPhase.Locked -> false
            FlashRecordingPhase.Idle -> draft.isBlank()
            else -> true
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space8),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            AnimatedContent(
                targetState = recordingPhase,
                transitionSpec = {
                    (fadeIn(motion.tweenFastSpec())) togetherWith (fadeOut(motion.tweenFastSpec()))
                },
                label = "composerRecordingSwap",
                modifier = Modifier.weight(1f),
            ) { phase ->
                when (phase) {
                    FlashRecordingPhase.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            // Tactile Flash Attachment Button (UI-012)
                            FlashAttachmentButton(
                                onClick = onAttachmentClick,
                                isExpanded = isAttachmentExpanded,
                                enabled = enabled,
                            )

                            // Expanding Input Pill
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(FlashShapes.composerInput)
                                    .background(colors.composerInputBackground)
                                    .border(
                                        width = FlashDimensions.borderHairline,
                                        color = colors.borderSubtle,
                                        shape = FlashShapes.composerInput,
                                    )
                                    .padding(
                                        horizontal = FlashSpacing.space16,
                                        vertical = FlashSpacing.space8,
                                    ),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicTextField(
                                    value = draft,
                                    onValueChange = onDraftChanged,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 20.dp, max = 120.dp),
                                    enabled = enabled,
                                    textStyle = typography.bodyDefault.copy(
                                        color = if (enabled) colors.textPrimary else colors.textTertiary,
                                    ),
                                    cursorBrush = SolidColor(colors.accentPrimary),
                                    maxLines = 6,
                                    decorationBox = { innerTextField ->
                                        if (draft.isEmpty()) {
                                            Text(
                                                text = placeholderText,
                                                style = typography.bodyDefault,
                                                color = colors.textTertiary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        innerTextField()
                                    },
                                )
                            }
                        }
                    }

                    FlashRecordingPhase.Holding, FlashRecordingPhase.CancelArmed -> {
                        // Hold-mode bar; the persistent trailing mic keeps the gesture alive.
                        FlashVoiceRecordingBar(
                            phase = phase,
                            elapsedMs = recordElapsedMs,
                            amplitudes = recordAmplitudes,
                            isPaused = recordPaused,
                            onCancel = { cancelRecording() },
                            onTogglePause = { recordPaused = !recordPaused },
                            onSend = { sendVoiceRecording() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    FlashRecordingPhase.Locked -> {
                        // Hands-free panel: full-width bar with trash / pause / send buttons.
                        FlashVoiceRecordingBar(
                            phase = phase,
                            elapsedMs = recordElapsedMs,
                            amplitudes = recordAmplitudes,
                            isPaused = recordPaused,
                            onCancel = { cancelRecording() },
                            onTogglePause = { recordPaused = !recordPaused },
                            onSend = { sendVoiceRecording() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Persistent trailing slot: the SAME mic node survives Idle/Holding/CancelArmed.
            if (showMic) {
                FlashMicButton(
                    isRecording = isRecordingActive,
                    enabled = enabled,
                    onStartRecord = {
                        // Only enter the recording surface if the host actually started capture
                        // (mic permission granted). Otherwise stay Idle — a permission prompt fired.
                        if (onVoiceRecordStart()) {
                            recordingPhase = FlashRecordingPhase.Holding
                        }
                    },
                    onSlideUpdate = { totalDx, totalDy ->
                        val cancelPx = with(density) { FlashVoiceRecordingMath.CANCEL_SLIDE_DP.dp.toPx() }
                        val lockPx = with(density) { FlashVoiceRecordingMath.LOCK_SLIDE_DP.dp.toPx() }
                        recordingPhase = when (FlashVoiceRecordingMath.resolveHoldSlide(totalDx, totalDy, cancelPx, lockPx)) {
                            FlashHoldSlideTarget.Cancel -> FlashRecordingPhase.CancelArmed
                            FlashHoldSlideTarget.Lock -> FlashRecordingPhase.Locked
                            FlashHoldSlideTarget.Stay ->
                                if (recordingPhase == FlashRecordingPhase.CancelArmed) FlashRecordingPhase.Holding else recordingPhase
                        }
                    },
                    onRecordEnd = {
                        when (recordingPhase) {
                            FlashRecordingPhase.CancelArmed -> cancelRecording()
                            FlashRecordingPhase.Holding -> sendVoiceRecording()
                            FlashRecordingPhase.Locked, FlashRecordingPhase.Idle -> Unit
                        }
                    },
                )
            } else if (!isRecordingActive) {
                // Tactile Flash Send Button
                FlashSendButton(
                    canSend = canSend,
                    onClick = onSend,
                    enabled = enabled,
                )
            }
        }
    }
}

/**
 * UI-013 Stateful tactile send button.
 */
@Composable
fun FlashSendButton(
    canSend: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // The press scale stays a `State` and is read in the `graphicsLayer` below, not here
    // (EXP-013). It also gained the reduce-motion guard it never had: HIGH keeps this exact
    // spring — deliberately its own, not `springSnappySpec()`, so the send button's feel is
    // unchanged — while LOW/MEDIUM snap, like every other animation at those tiers.
    val scale = animateFloatAsState(
        targetValue = if (isPressed && canSend) 0.90f else 1.0f,
        animationSpec = if (motion.reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = 0.6f,
                stiffness = 500f,
            )
        },
        label = "send_button_press_scale",
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.backgroundSurfaceSubtle
            canSend -> colors.accentPrimary
            else -> Color.Transparent
        },
        animationSpec = tween(motion.fastMillis),
        label = "send_button_background_color",
    )

    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textTertiary
            canSend -> colors.textOnAccent
            else -> colors.textTertiary
        },
        animationSpec = tween(motion.fastMillis),
        label = "send_button_icon_tint",
    )

    Box(
        modifier = modifier
            .size(FlashSpacing.space40)
            // Same chain position, same node: `Modifier.scale(f)` *is*
            // `graphicsLayer(scaleX = f, scaleY = f)` with the same centre pivot, so this is
            // pixel-identical — but the read now happens in draw instead of being a
            // composition-time argument, which used to recompose the whole button (both
            // `animateColorAsState` calls, the `clickable` chain, the semantics block and the
            // icon) on every frame of the press spring.
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = canSend && enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (canSend) "Send message" else "Send message, empty draft"
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Send,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(FlashSpacing.space20),
        )
    }
}

/**
 * Docked reply preview bar displayed immediately above the input field.
 */
@Composable
fun FlashReplyDock(
    replyingTo: FlashMessageUi,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent indicator bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(colors.accentPrimary),
        )

        Spacer(modifier = Modifier.width(FlashSpacing.space8))

        // Reply snippet text
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = replyingTo.senderName,
                style = typography.captionEmphasis.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = replyingTo.text.ifBlank { "Attachment" },
                style = typography.captionDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Dismiss reply button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(FlashSpacing.space24)
                .semantics { contentDescription = "Cancel reply" },
        ) {
            FlashIcon(
                icon = FlashIcons.Close,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(FlashSpacing.space16),
            )
        }
    }
}

@Preview(name = "Composer - Empty", showBackground = true)
@Composable
private fun FlashComposerEmptyPreview() {
    FlashTheme {
        FlashComposer(
            draft = "",
            onDraftChanged = {},
            onAttachmentClick = {},
            onSend = {},
        )
    }
}

@Preview(name = "Composer - Typing", showBackground = true)
@Composable
private fun FlashComposerTypingPreview() {
    FlashTheme {
        FlashComposer(
            draft = "Ready to send",
            onDraftChanged = {},
            onAttachmentClick = {},
            onSend = {},
        )
    }
}

@Preview(name = "Composer - Replying", showBackground = true)
@Composable
private fun FlashComposerReplyingPreview() {
    FlashTheme {
        FlashComposer(
            draft = "Replying with text",
            onDraftChanged = {},
            onAttachmentClick = {},
            onSend = {},
            replyingTo = FlashMessageUi(
                id = "1",
                senderName = "Alex Rivera",
                senderInitials = "AR",
                timeLabel = "10:30 AM",
                text = "Original message to quote",
                isMine = false,
            ),
        )
    }
}
