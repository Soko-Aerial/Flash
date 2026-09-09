package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashVideoSurface
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay

/**
 * In-app interactive video player (UI-018 video playback).
 *
 * Renders the native [FlashVideoSurface] with custom Compose playback controls:
 * - Tap to toggle controls visibility
 * - Center Play / Pause / Replay badge
 * - Bottom scrub bar with live time readout (m:ss / m:ss)
 * - Auto-hiding chrome after 3.5 seconds of playback
 */
@Composable
fun FlashVideoPlayer(
    uri: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    onClose: () -> Unit = {},
) {
    var isPlaying by remember { mutableStateOf(autoPlay) }
    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var isCompleted by remember { mutableStateOf(false) }
    var seekToTarget by remember { mutableStateOf<Long?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragPosition by remember { mutableStateOf(0f) }

    val colors = FlashTheme.colors
    val motion = FlashTheme.motion

    // Auto-hide controls after 3.5 seconds if video is actively playing and not dragging
    LaunchedEffect(controlsVisible, isPlaying, isDraggingSlider) {
        if (controlsVisible && isPlaying && !isDraggingSlider) {
            delay(3500L)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.mediaViewerBackdrop)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controlsVisible = !controlsVisible },
            ),
    ) {
        // Native video view surface
        FlashVideoSurface(
            uri = uri,
            isPlaying = isPlaying,
            seekToMs = seekToTarget,
            onPlaybackStateChanged = { dur, pos, completed ->
                durationMs = dur
                if (!isDraggingSlider) {
                    positionMs = pos
                }
                if (completed) {
                    isPlaying = false
                    isCompleted = true
                    controlsVisible = true
                }
            },
            onError = { err ->
                errorMessage = err
                isPlaying = false
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Error banner if playback fails
        if (errorMessage != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(FlashSpacing.space24),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Failed,
                        tint = Color.White.copy(alpha = 0.85f),
                        size = FlashDimensions.iconLg,
                    )
                    FlashText(
                        text = "Couldn't play video",
                        style = FlashTheme.typography.metadataDefault,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }

        // Center play / pause / replay affordance
        AnimatedVisibility(
            visible = controlsVisible || !isPlaying,
            enter = fadeIn(motion.tweenNormalSpec()),
            exit = fadeOut(motion.tweenFastSpec()),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0x99000000), CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            if (isCompleted) {
                                isCompleted = false
                                seekToTarget = 0L
                                isPlaying = true
                            } else {
                                isPlaying = !isPlaying
                            }
                        },
                    ),
            ) {
                FlashIcon(
                    icon = if (isCompleted || !isPlaying) FlashIcons.Play else FlashIcons.Pause,
                    tint = Color.White,
                    contentDescription = if (isCompleted || !isPlaying) "Play" else "Pause",
                    size = 32.dp,
                )
            }
        }

        // Controls overlay: Top bar (Close) and Bottom bar (Scrubber + Times)
        AnimatedVisibility(
            visible = controlsVisible || !isPlaying,
            enter = fadeIn(motion.tweenNormalSpec()),
            exit = fadeOut(motion.tweenFastSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top gradient bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xB3000000), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(FlashDimensions.minTouchTarget)
                            .clickable(role = Role.Button, onClick = onClose),
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Close,
                            tint = Color.White,
                            contentDescription = FlashIcons.Close.contentDescription,
                            size = FlashDimensions.iconMd,
                        )
                    }
                }

                // Bottom gradient bar with timeline controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xCC000000)),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                ) {
                    val currentPos = if (isDraggingSlider) sliderDragPosition.toLong() else positionMs
                    val totalDur = durationMs.coerceAtLeast(0L)

                    // Scrubber slider
                    Slider(
                        value = if (totalDur > 0L) currentPos.toFloat().coerceIn(0f, totalDur.toFloat()) else 0f,
                        onValueChange = { target ->
                            isDraggingSlider = true
                            sliderDragPosition = target
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            val targetMs = sliderDragPosition.toLong()
                            positionMs = targetMs
                            seekToTarget = targetMs
                            if (isCompleted) {
                                isCompleted = false
                                isPlaying = true
                            }
                        },
                        valueRange = 0f..maxOf(1f, totalDur.toFloat()),
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentPrimary,
                            activeTrackColor = colors.accentPrimary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    )

                    Spacer(modifier = Modifier.height(FlashSpacing.space4))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable(
                                        role = Role.Button,
                                        onClick = {
                                            if (isCompleted) {
                                                isCompleted = false
                                                seekToTarget = 0L
                                                isPlaying = true
                                            } else {
                                                isPlaying = !isPlaying
                                            }
                                        },
                                    ),
                            ) {
                                FlashIcon(
                                    icon = if (isCompleted || !isPlaying) FlashIcons.Play else FlashIcons.Pause,
                                    tint = Color.White,
                                    size = FlashDimensions.iconSm,
                                )
                            }

                            FlashText(
                                text = "${formatVideoDuration(currentPos)} / ${formatVideoDuration(totalDur)}",
                                style = FlashTheme.typography.metadataDefault,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Formats a duration in milliseconds to m:ss (or h:mm:ss). */
internal fun formatVideoDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
