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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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

/** Pure composer input ergonomics helpers (JVM-testable). */
object FlashComposerMath {
    /**
     * Determines whether an Enter/NumPadEnter key press should submit the draft.
     * On desktop, bare Enter sends while Shift+Enter or Ctrl+Enter inserts a newline.
     * On Android, bare Enter inserts a newline cleanly on soft keyboards; Ctrl+Enter sends.
     */
    fun shouldSendOnEnterKey(
        isDesktop: Boolean,
        isShiftPressed: Boolean,
        isCtrlPressed: Boolean,
    ): Boolean = if (isDesktop) {
        !isShiftPressed && !isCtrlPressed
    } else {
        isCtrlPressed && !isShiftPressed
    }
}

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
    /** Feedback callback when a touch press is released below [FlashVoiceRecordingMath.MIN_RECORD_MS]. */
    onVoiceTapTooShort: () -> Unit = {},
    /** Feedback callback when a locked voice recording is submitted below [FlashVoiceRecordingMath.MIN_RECORD_MS]. */
    onVoiceRecordingTooShort: (() -> Unit)? = null,
    /** Task 3.2: pending attachments staged for sending. */
    stagedAttachments: List<FlashShareItemUi> = emptyList(),
    onRemoveStagedAttachment: (FlashShareItemUi) -> Unit = {},
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val density = androidx.compose.ui.platform.LocalDensity.current
    // OPT-O24-02: an attachment already marked isSending is waiting on its own send, so it must not
    // make the composer sendable again — a second tap would hand it to onSendFile a second time.
    val canSend = (draft.isNotBlank() || stagedAttachments.any { !it.isSending }) && enabled
    var isComposerFocused by remember { mutableStateOf(false) }

    // --- UI-020 voice recording state (local to the composer) ---
    var recordingPhase by remember { mutableStateOf(FlashRecordingPhase.Idle) }
    var recordElapsedMs by remember { mutableLongStateOf(0L) }
    var recordAmplitudes by remember { mutableStateOf(listOf<Int>()) }
    var recordPaused by remember { mutableStateOf(false) }
    // True only after the host accepted the press. A refused start (permission prompt, call/PTT
    // lease, busy encoder) must not let the same hold arm cancel or lock a recording that is not
    // running — that produced a phantom locked panel with no audio behind it.
    var voiceCaptureStarted by remember { mutableStateOf(false) }
    // A locked recording whose hold gesture has ended. Only then does the trailing mic become the
    // voice send key; swapping it while the finger is down would dispose the pointer stream that
    // owns the hold (ERROR-010).
    var voiceLockedHandsFree by remember { mutableStateOf(false) }
    var showShortTapHint by remember { mutableStateOf(false) }
    var shortHintText by remember { mutableStateOf("Hold to record") }

    val isRecording = recordingPhase != FlashRecordingPhase.Idle

    LaunchedEffect(showShortTapHint) {
        if (showShortTapHint) {
            kotlinx.coroutines.delay(2_000L)
            showShortTapHint = false
        }
    }

    fun resetRecording() {
        recordingPhase = FlashRecordingPhase.Idle
        recordElapsedMs = 0L
        recordAmplitudes = emptyList()
        recordPaused = false
        voiceCaptureStarted = false
        voiceLockedHandsFree = false
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
            shortHintText = "Recording too short"
            showShortTapHint = true
            if (isDesktopChatPlatform) {
                onVoiceTapTooShort()
            } else {
                onVoiceRecordingTooShort?.invoke()
            }
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
            .imePadding(),
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

        // Task 3.2: Staged Attachments Tray
        AnimatedVisibility(
            visible = stagedAttachments.isNotEmpty(),
            enter = motion.replyExpandEnter(),
            exit = if (motion.reduceMotion) fadeOut(tween(0)) else fadeOut(tween(motion.fastMillis)),
        ) {
            FlashAttachmentStagingTray(
                stagedAttachments = stagedAttachments,
                onRemove = onRemoveStagedAttachment,
                onAddMore = onAttachmentClick,
            )
        }

        // Main input bar row � transforms into the recording surface (UI-020).
        // The mic button lives OUTSIDE the swapped region so ONE persistent node owns
        // the hold gesture across Idle/Holding/CancelArmed � swapping it mid-hold would
        // dispose its pointerInput stream and kill slide/release handling.
        val isRecordingActive = recordingPhase != FlashRecordingPhase.Idle
        // Once a locked recording's hold has ended, the trailing slot becomes its send key.
        val voiceSendVisible = FlashVoiceRecordingMath.showsVoiceSend(recordingPhase, voiceLockedHandsFree)
        val showHoldLockHint = voiceCaptureStarted && recordingPhase == FlashRecordingPhase.Holding

        // Floating/inline lock hint during hold, or "Hold to record" feedback after a <500ms touch tap.
        AnimatedVisibility(
            visible = showHoldLockHint || showShortTapHint,
            enter = fadeIn(motion.tweenFastSpec()),
            exit = fadeOut(motion.tweenFastSpec()),
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = FlashSpacing.space8, top = FlashSpacing.space4),
        ) {
            FlashVoiceHintPill(
                text = if (showHoldLockHint) "↑ Slide up to lock" else shortHintText,
                isError = false,
            )
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
                                    .animateContentSize()
                                    .clip(FlashShapes.composerInput)
                                    .background(colors.composerInputBackground)
                                    .border(
                                        width = FlashDimensions.borderHairline,
                                        color = if (isComposerFocused) colors.accentFill else Color.Transparent,
                                        shape = FlashShapes.composerInput,
                                    )
                                    .heightIn(min = 44.dp)
                                    .padding(
                                        horizontal = FlashSpacing.space14,
                                        vertical = FlashSpacing.space10,
                                    ),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { newDraft ->
                                        // A paste over the limit is shortened, not dropped (dropping it showed nothing at all).
                                        onDraftChanged(
                                            FlashPinnedMessageMath.capLength(
                                                newDraft,
                                                com.transfer.flash.core.messaging.protocol.GroupPolicy.MAX_MESSAGE_TEXT_LENGTH,
                                            ),
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 20.dp, max = 120.dp)
                                        .onFocusChanged { isComposerFocused = it.isFocused }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown &&
                                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                            ) {
                                                if (FlashComposerMath.shouldSendOnEnterKey(
                                                        isDesktop = isDesktopChatPlatform,
                                                        isShiftPressed = event.isShiftPressed,
                                                        isCtrlPressed = event.isCtrlPressed,
                                                    )
                                                ) {
                                                    if (canSend) {
                                                        onSend()
                                                    }
                                                    true
                                                } else {
                                                    // Let Shift+Enter (desktop) or soft-keyboard Enter (Android) insert newline
                                                    false
                                                }
                                            } else {
                                                false
                                            }
                                        },
                                    enabled = enabled,
                                    textStyle = typography.bodyDefault.copy(
                                        color = if (enabled) colors.textPrimary else colors.textTertiary,
                                    ),
                                    cursorBrush = SolidColor(colors.accentFill),
                                    maxLines = 6,
                                    decorationBox = { innerTextField ->
                                        if (draft.isEmpty()) {
                                            Text(
                                                text = placeholderText,
                                                style = typography.bodyDefault,
                                                color = colors.textSecondary,
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
                        // Hands-free panel: full-width bar with trash / pause / (in-bar send only while finger is down).
                        FlashVoiceRecordingBar(
                            phase = phase,
                            elapsedMs = recordElapsedMs,
                            amplitudes = recordAmplitudes,
                            isPaused = recordPaused,
                            onCancel = { cancelRecording() },
                            onTogglePause = { recordPaused = !recordPaused },
                            onSend = { sendVoiceRecording() },
                            showInBarSend = !voiceSendVisible,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // One 44dp slot for the mic and the send keys. The mic stays composed (its hold gesture
            // must survive Holding / CancelArmed) and the send key cross-fades over it: no width
            // animation, no second control appearing beside the pill.
            val sendProgress by animateFloatAsState(
                targetValue = if (canSend) 1f else 0f,
                animationSpec = tween(motion.fastMillis),
                label = "composer_trailing_progress",
            )
            Box(
                modifier = Modifier.defaultMinSize(
                    minWidth = FlashDimensions.minTouchTarget,
                    minHeight = FlashDimensions.minTouchTarget,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.then(
                        if (sendProgress > 0.5f || voiceSendVisible) Modifier.clearAndSetSemantics {} else Modifier,
                    ),
                ) {
                    FlashMicButton(
                        isRecording = isRecordingActive,
                        enabled = enabled && !canSend && !voiceSendVisible,
                        onStartRecord = {
                            // Only enter the recording surface if the host actually started capture
                            // (mic permission granted) and this gesture is the one allowed to start.
                            // A refused start must not let the hold slide into a phantom recording.
                            if (FlashVoiceRecordingMath.canStartCapture(recordingPhase, voiceCaptureStarted) &&
                                onVoiceRecordStart()
                            ) {
                                voiceCaptureStarted = true
                                showShortTapHint = false
                                recordingPhase = FlashRecordingPhase.Holding
                            }
                        },
                        onInterrupted = {
                            if (voiceCaptureStarted && !voiceLockedHandsFree) cancelRecording()
                        },
                        onStartHandsFree = {
                            if (FlashVoiceRecordingMath.canStartCapture(recordingPhase, voiceCaptureStarted) &&
                                onVoiceRecordStart()
                            ) {
                                voiceCaptureStarted = true
                                showShortTapHint = false
                                recordingPhase = FlashRecordingPhase.Locked
                                voiceLockedHandsFree = true
                            }
                        },
                        onSlideUpdate = { totalDx, totalDy ->
                            if (FlashVoiceRecordingMath.acceptsSlide(recordingPhase, voiceCaptureStarted)) {
                                val cancelPx = with(density) { FlashVoiceRecordingMath.CANCEL_SLIDE_DP.dp.toPx() }
                                val lockPx = with(density) { FlashVoiceRecordingMath.LOCK_SLIDE_DP.dp.toPx() }
                                recordingPhase = when (FlashVoiceRecordingMath.resolveHoldSlide(totalDx, totalDy, cancelPx, lockPx)) {
                                    FlashHoldSlideTarget.Cancel -> FlashRecordingPhase.CancelArmed
                                    FlashHoldSlideTarget.Lock -> FlashRecordingPhase.Locked
                                    FlashHoldSlideTarget.Stay ->
                                        if (recordingPhase == FlashRecordingPhase.CancelArmed) FlashRecordingPhase.Holding else recordingPhase
                                }
                            }
                        },
                        onRecordEnd = {
                            when (
                                FlashVoiceRecordingMath.resolveReleaseAction(
                                    phase = recordingPhase,
                                    captureStarted = voiceCaptureStarted,
                                    elapsedMs = recordElapsedMs,
                                    isDesktop = isDesktopChatPlatform,
                                )
                            ) {
                                FlashVoiceRecordingMath.ReleaseAction.Cancel -> cancelRecording()
                                FlashVoiceRecordingMath.ReleaseAction.DiscardShortTap -> {
                                    cancelRecording()
                                    shortHintText = "Hold to record"
                                    showShortTapHint = true
                                    onVoiceTapTooShort()
                                }
                                FlashVoiceRecordingMath.ReleaseAction.LockHandsFree -> {
                                    // Desktop click-to-record: lock into hands-free mode so user
                                    // can stop & send via FlashVoiceTrailingSendButton or cancel.
                                    recordingPhase = FlashRecordingPhase.Locked
                                    voiceLockedHandsFree = true
                                }
                                // The lock gesture is over: from now on the trailing slot sends.
                                FlashVoiceRecordingMath.ReleaseAction.FinishLockGesture -> voiceLockedHandsFree = true
                                FlashVoiceRecordingMath.ReleaseAction.Send -> sendVoiceRecording()
                                FlashVoiceRecordingMath.ReleaseAction.None -> Unit
                            }
                        },
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - sendProgress
                            scaleX = 1f - 0.12f * sendProgress
                            scaleY = 1f - 0.12f * sendProgress
                        },
                    )
                }
                Box(
                    modifier = Modifier.then(
                        if (sendProgress <= 0.5f) Modifier.clearAndSetSemantics {} else Modifier,
                    ),
                ) {
                    FlashSendButton(
                        canSend = canSend,
                        onClick = onSend,
                        enabled = enabled,
                        modifier = Modifier.graphicsLayer {
                            alpha = sendProgress
                            scaleX = 0.7f + 0.3f * sendProgress
                            scaleY = 0.7f + 0.3f * sendProgress
                        },
                    )
                }
                if (voiceSendVisible) {
                    FlashVoiceTrailingSendButton(
                        onClick = { sendVoiceRecording() },
                    )
                }
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
            canSend -> colors.accentFill
            else -> Color.Transparent
        },
        animationSpec = tween(motion.fastMillis),
        label = "send_button_background_color",
    )

    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textTertiary
            canSend -> colors.onAccentFill
            else -> colors.textTertiary
        },
        animationSpec = tween(motion.fastMillis),
        label = "send_button_icon_tint",
    )

    Box(
        modifier = modifier
            .size(FlashSpacing.space44)
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
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space8)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurfaceSubtle)
            .border(
                FlashDimensions.borderHairline,
                colors.borderSubtle,
                RoundedCornerShape(FlashShapes.radius12),
            )
            .padding(start = FlashSpacing.space8, end = FlashSpacing.space4, top = FlashSpacing.space4, bottom = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Accent indicator bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 30.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(colors.accentFill),
        )

        Spacer(modifier = Modifier.width(FlashSpacing.space8))

        // Reply snippet text
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = replyingTo.senderName,
                style = typography.metadataEmphasis,
                color = colors.accentFill,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = replyingTo.text.ifBlank { "Attachment" },
                style = typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Dismiss reply button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(32.dp)
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
