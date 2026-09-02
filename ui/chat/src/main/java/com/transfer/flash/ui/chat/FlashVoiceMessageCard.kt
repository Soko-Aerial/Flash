package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlinx.coroutines.delay

/**
 * Pure math + decision logic for the voice message card (UI-019).
 *
 * Free of Compose runtime types so waveform/seek/speed behavior is unit-testable
 * without instrumentation (see [FlashVoiceLogicTest]).
 */
object FlashVoiceMath {
    const val WAVEFORM_BAR_COUNT = 40
    const val TICK_MS = 100L

    /** `m:ss` duration formatting; negative or unknown input renders `0:00`. */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        return "${totalSeconds / 60L}:%02d".format(totalSeconds % 60L)
    }

    /**
     * Resample raw amplitudes into exactly [barCount] buckets normalized 0f..1f.
     * Each bucket is the max of the samples it covers (peak-preserving); empty
     * regions produce a minimal visible bar.
     */
    fun bucketAmplitudes(amplitudes: List<Int>, barCount: Int): FloatArray {
        if (barCount <= 0) return FloatArray(0)
        if (amplitudes.isEmpty()) return FloatArray(barCount) { MIN_BAR_HEIGHT }
        val result = FloatArray(barCount)
        for (bar in 0 until barCount) {
            val start = bar * amplitudes.size / barCount
            val end = ((bar + 1) * amplitudes.size / barCount).coerceAtLeast(start + 1)
            var peak = 0
            for (i in start until end.coerceAtMost(amplitudes.size)) {
                if (amplitudes[i] > peak) peak = amplitudes[i]
            }
            result[bar] = (peak / 100f).coerceIn(MIN_BAR_HEIGHT, 1f)
        }
        return result
    }

    /** Seek fraction for a tap at [tapX] within a waveform of [widthPx]. */
    fun fractionForTap(tapX: Float, widthPx: Float): Float =
        if (widthPx <= 0f) 0f else (tapX / widthPx).coerceIn(0f, 1f)

    /** Bar index snapped from a tap position (Telegram-style bar seek). */
    fun barIndexForTap(tapX: Float, widthPx: Float, barCount: Int): Int {
        if (widthPx <= 0f || barCount <= 0) return 0
        return ((tapX / widthPx) * barCount).toInt().coerceIn(0, barCount - 1)
    }

    /** Elapsed milliseconds for a played-fraction of the track. */
    fun elapsedForFraction(fraction: Float, durationMs: Long): Long =
        (fraction.coerceIn(0f, 1f) * durationMs.coerceAtLeast(0L)).toLong()

    /** Playback speed cycle: 1× → 1.5× → 2× → 1×. */
    fun nextPlaybackSpeed(current: Float): Float = when {
        current < 1.25f -> 1.5f
        current < 1.75f -> 2.0f
        else -> 1.0f
    }

    /** Pill label for a playback speed. */
    fun speedLabel(speed: Float): String = when {
        speed < 1.25f -> "1×"
        speed < 1.75f -> "1.5×"
        else -> "2×"
    }

    /**
     * Telegram-style trailing label: remaining countdown while mid-playback,
     * total duration when untouched or finished.
     */
    fun trailingLabel(elapsedMs: Long, durationMs: Long, hasStarted: Boolean): String {
        val duration = durationMs.coerceAtLeast(0L)
        val finished = elapsedMs >= duration && duration > 0L
        return if (hasStarted && !finished) {
            "-" + formatDuration((duration - elapsedMs.coerceIn(0L, duration)))
        } else {
            formatDuration(duration)
        }
    }

    /** Played-bar count for progress drawing. */
    fun playedBarCount(elapsedMs: Long, durationMs: Long, barCount: Int): Int {
        if (durationMs <= 0L || barCount <= 0) return 0
        val fraction = elapsedMs.toFloat() / durationMs.toFloat()
        return (fraction.coerceIn(0f, 1f) * barCount).toInt().coerceIn(0, barCount)
    }

    const val MIN_BAR_HEIGHT = 0.12f
}

/**
 * UI-019 Voice Message Card.
 *
 * In-bubble playback card: circular play/pause badge, discrete bar waveform with
 * tap-to-seek and drag scrubbing, Telegram-style remaining/duration label, and a
 * 1×/1.5×/2× speed pill. See docs/ui/voice-message.md.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlashVoiceMessageCard(
    attachment: FlashVoiceAttachmentUi,
    isParentOutgoing: Boolean,
    onCardClick: () -> Unit,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press opens the message context menu (UI-007/UI-008) — the card consumes presses. */
    onLongPress: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    // --- Playback state ---
    // Real audio via [FlashAudioPlayer] when the note has a downloaded local file; otherwise the
    // demo-mode ticker still drives previews / not-yet-downloaded cards.
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasAudio = attachment.uri != null && attachment.transferStatus == FlashFileTransferStatus.Downloaded
    val audioPlayer = remember(attachment.id, attachment.uri, hasAudio) {
        if (hasAudio) FlashAudioPlayer(context, attachment.uri!!) else null
    }
    DisposableEffect(audioPlayer) {
        onDispose { audioPlayer?.release() }
    }

    var isPlaying by remember(attachment.id) { mutableStateOf(false) }
    var elapsedMs by remember(attachment.id) { mutableLongStateOf(0L) }
    var hasStarted by remember(attachment.id) { mutableStateOf(false) }
    var playbackSpeed by remember(attachment.id) { mutableStateOf(1.0f) }
    var scrubFraction by remember(attachment.id) { mutableStateOf<Float?>(null) }

    val durationMs = attachment.durationMs
    val displayElapsedMs = scrubFraction?.let { FlashVoiceMath.elapsedForFraction(it, durationMs) } ?: elapsedMs

    LaunchedEffect(isPlaying, playbackSpeed, attachment.id) {
        if (audioPlayer != null) {
            audioPlayer.setSpeed(playbackSpeed)
            if (isPlaying) audioPlayer.play() else audioPlayer.pause()
            while (isPlaying) {
                elapsedMs = audioPlayer.positionMs().coerceAtMost(if (durationMs > 0L) durationMs else Long.MAX_VALUE)
                if (!audioPlayer.isPlaying() && elapsedMs > 0L) {
                    // Reached the end (MediaPlayer stops itself) — snap to full and reset.
                    if (durationMs > 0L) elapsedMs = durationMs
                    isPlaying = false
                    break
                }
                delay(FlashVoiceMath.TICK_MS)
            }
        } else {
            while (isPlaying && elapsedMs < durationMs) {
                delay(FlashVoiceMath.TICK_MS)
                elapsedMs = (elapsedMs + (FlashVoiceMath.TICK_MS * playbackSpeed.toLong())).coerceAtMost(durationMs)
            }
            if (elapsedMs >= durationMs && durationMs > 0L) {
                isPlaying = false
            }
        }
    }

    // --- Surface treatment (matches UI-016 file card language) ---
    val surfaceBg = if (isParentOutgoing) colors.chatBgAttachmentOutgoing else colors.chatBgAttachmentIncoming
    val primaryTextColor = if (isParentOutgoing) colors.chatTextOutgoing else colors.chatTextIncoming
    val secondaryTextColor = if (isParentOutgoing) colors.chatTextTimestampOutgoing else colors.chatTextTimestamp
    val playedColor = if (isParentOutgoing) colors.chatTextOutgoing else colors.accentPrimary
    val unplayedColor = secondaryTextColor.copy(alpha = 0.45f)

    val cardInteraction = remember { MutableInteractionSource() }
    val cardPressed by cardInteraction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (cardPressed && !motion.reduceMotion) 0.97f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "voiceCardPressScale",
    )

    val a11yDescription = when (attachment.transferStatus) {
        FlashFileTransferStatus.NotDownloaded ->
            "Voice message, ${FlashVoiceMath.formatDuration(durationMs)}. Double-tap to download."
        FlashFileTransferStatus.Failed ->
            "Voice message failed to download. Double-tap to retry."
        else ->
            "Voice message, ${FlashVoiceMath.formatDuration(durationMs)}. Double-tap to ${if (isPlaying) "pause" else "play"}."
    }
    val a11yState = when {
        attachment.transferStatus != FlashFileTransferStatus.Downloaded -> ""
        isPlaying -> "Playing"
        hasStarted -> "Paused"
        else -> ""
    }

    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 220.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .clip(FlashShapes.attachment)
            .background(surfaceBg)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle.copy(alpha = 0.5f),
                shape = FlashShapes.attachment,
            )
            .combinedClickable(
                interactionSource = cardInteraction,
                indication = null,
                onClick = onCardClick,
                onLongClick = {
                    haptics(FlashHaptic.Confirm)
                    onLongPress()
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = a11yDescription
                stateDescription = a11yState
            }
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: play/pause badge with speed pill beneath it.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FlashVoiceBadge(
                transferStatus = attachment.transferStatus,
                isPlaying = isPlaying,
                onTogglePlay = {
                    haptics(FlashHaptic.Tick)
                    if (isPlaying || elapsedMs < durationMs) hasStarted = true
                    if (!isPlaying && elapsedMs >= durationMs && durationMs > 0L) {
                        elapsedMs = 0L
                        audioPlayer?.seekTo(0L)
                    }
                    isPlaying = !isPlaying
                    onActionClick()
                },
            )
            Spacer(modifier = Modifier.height(FlashSpacing.space4))
            FlashVoiceSpeedPill(
                speed = playbackSpeed,
                textColor = secondaryTextColor,
                onClick = {
                    playbackSpeed = FlashVoiceMath.nextPlaybackSpeed(playbackSpeed)
                },
            )
        }

        Spacer(modifier = Modifier.width(FlashSpacing.space12))

        // Center: full-width waveform.
        FlashVoiceWaveform(
            amplitudes = attachment.amplitudes,
            playedColor = playedColor,
            unplayedColor = unplayedColor,
            playedBarCount = FlashVoiceMath.playedBarCount(displayElapsedMs, durationMs, FlashVoiceMath.WAVEFORM_BAR_COUNT),
            onSeek = { fraction ->
                scrubFraction = null
                elapsedMs = FlashVoiceMath.elapsedForFraction(fraction, durationMs)
                audioPlayer?.seekTo(elapsedMs)
                hasStarted = true
            },
            onScrub = { fraction -> scrubFraction = fraction },
            onScrubEnd = {
                scrubFraction?.let { fraction ->
                    elapsedMs = FlashVoiceMath.elapsedForFraction(fraction, durationMs)
                    audioPlayer?.seekTo(elapsedMs)
                }
                scrubFraction = null
                hasStarted = true
            },
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(FlashSpacing.space8))

        // Right side: remaining/duration label.
        FlashText(
            text = FlashVoiceMath.trailingLabel(displayElapsedMs, durationMs, hasStarted),
            style = typography.numericEmphasis.copy(fontSize = typography.metadataDefault.fontSize),
            color = primaryTextColor,
        )
    }
}

/**
 * 48dp circular play/pause/download badge (UI-016 badge geometry).
 */
@Composable
private fun FlashVoiceBadge(
    transferStatus: FlashFileTransferStatus,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !motion.reduceMotion) 0.90f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "voiceBadgePressScale",
    )

    val badgeColor = when (transferStatus) {
        FlashFileTransferStatus.Downloaded -> colors.accentPrimary
        FlashFileTransferStatus.Transferring -> colors.accentSecondary
        FlashFileTransferStatus.NotDownloaded -> Color(0xFF5A6472)
        FlashFileTransferStatus.AwaitingAcceptance -> colors.accentSecondary
        FlashFileTransferStatus.Failed -> colors.textError
    }

    val (icon, description) = when (transferStatus) {
        FlashFileTransferStatus.NotDownloaded -> FlashIcons.Download to "Download voice message"
        FlashFileTransferStatus.Failed -> FlashIcons.Retry to "Retry download"
        FlashFileTransferStatus.Transferring -> FlashIcons.Clock to "Downloading voice message"
        FlashFileTransferStatus.AwaitingAcceptance -> FlashIcons.Clock to "Voice message waiting to download"
        FlashFileTransferStatus.Downloaded ->
            if (isPlaying) FlashIcons.Pause to "Pause voice message" else FlashIcons.Play to "Play voice message"
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(badgeColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTogglePlay,
            )
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        // Animated play/pause morph: spring scale + fade swap between glyphs.
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (scaleIn(
                    animationSpec = motion.springSnappySpec(),
                    initialScale = 0.6f,
                ) + fadeIn(motion.tweenFastSpec())) togetherWith
                    (scaleOut(
                        animationSpec = motion.springSnappySpec(),
                        targetScale = 0.6f,
                    ) + fadeOut(motion.tweenFastSpec()))
            },
            label = "voiceBadgeIconSwap",
        ) { currentIcon ->
            FlashIcon(
                icon = currentIcon,
                contentDescription = null,
                tint = Color.White,
                size = FlashDimensions.iconMd,
            )
        }
    }
}

/**
 * Discrete bar waveform with played/unplayed coloring, tap-to-seek and drag scrubbing.
 * Drawn entirely in Canvas — no allocation per frame, no recomposition during ticks.
 */
@Composable
private fun FlashVoiceWaveform(
    amplitudes: List<Int>,
    playedColor: Color,
    unplayedColor: Color,
    playedBarCount: Int,
    onSeek: (Float) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barCount = FlashVoiceMath.WAVEFORM_BAR_COUNT
    val buckets = remember(amplitudes, barCount) {
        FlashVoiceMath.bucketAmplitudes(amplitudes, barCount)
    }
    var isScrubbing by remember { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(barCount) {
                detectTapGestures(
                    onTap = { offset ->
                        onSeek(FlashVoiceMath.fractionForTap(offset.x, size.width.toFloat()))
                    },
                )
            }
            .pointerInput(barCount) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isScrubbing = true
                        onScrub(FlashVoiceMath.fractionForTap(offset.x, size.width.toFloat()))
                    },
                    onDragEnd = {
                        isScrubbing = false
                        onScrubEnd()
                    },
                    onDragCancel = {
                        isScrubbing = false
                        onScrubEnd()
                    },
                ) { change, _ ->
                    onScrub(FlashVoiceMath.fractionForTap(change.position.x, size.width.toFloat()))
                    change.consume()
                }
            },
    ) {
        val gap = 2.dp.toPx()
        val barWidth = 3.dp.toPx()
        val slot = barWidth + gap
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (bar in 0 until barCount) {
            val amplitude = buckets.getOrElse(bar) { FlashVoiceMath.MIN_BAR_HEIGHT }
            val barHeight = amplitude * size.height
            val x = bar * slot
            val y = (size.height - barHeight) / 2f
            drawRoundRect(
                color = if (bar < playedBarCount) playedColor else unplayedColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/** Compact playback-speed pill cycling 1× → 1.5× → 2×. */
@Composable
private fun FlashVoiceSpeedPill(
    speed: Float,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val isActive = speed > 1.0f
    Box(
        modifier = modifier
            .clip(FlashShapes.chip)
            .border(
                width = FlashDimensions.borderHairline,
                color = if (isActive) colors.accentPrimary else colors.borderSubtle.copy(alpha = 0.6f),
                shape = FlashShapes.chip,
            )
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Playback speed ${FlashVoiceMath.speedLabel(speed)}"
            }
            .padding(horizontal = FlashSpacing.space8, vertical = 1.dp),
    ) {
        FlashText(
            text = FlashVoiceMath.speedLabel(speed),
            style = FlashTheme.typography.metadataEmphasis,
            color = if (isActive) colors.accentPrimary else textColor,
        )
    }
}

@Preview(name = "Voice Card - Idle", showBackground = true)
@Composable
private fun FlashVoiceMessageCardIdlePreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashVoiceMessageCard(
                attachment = FlashVoiceAttachmentUi(
                    id = "v1",
                    durationMs = 23_000L,
                    amplitudes = List(40) { (20..95).random() },
                ),
                isParentOutgoing = false,
                onCardClick = {},
                onActionClick = {},
            )
        }
    }
}

@Preview(name = "Voice Card - Outgoing", showBackground = true)
@Composable
private fun FlashVoiceMessageCardOutgoingPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashVoiceMessageCard(
                attachment = FlashVoiceAttachmentUi(
                    id = "v2",
                    durationMs = 41_500L,
                    amplitudes = List(40) { (10..100).random() },
                    transferStatus = FlashFileTransferStatus.NotDownloaded,
                ),
                isParentOutgoing = true,
                onCardClick = {},
                onActionClick = {},
            )
        }
    }
}
