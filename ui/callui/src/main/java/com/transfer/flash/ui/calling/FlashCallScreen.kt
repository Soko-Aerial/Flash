package com.transfer.flash.ui.calling

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shepeliev.webrtckmp.VideoTrack
import com.shepeliev.webrtckmp.WebRtc
import com.transfer.flash.core.calling.FlashCallSession
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen in-call surface (UI-050, docs/ui/calling-ui.md).
 *
 * State-driven: every visual derives from [state]. Audio calls show the peer avatar with
 * the Flash pulse; video calls render remote-full + local-PiP via [SurfaceViewRenderer]
 * (init on ON_RESUME, release on ON_PAUSE, sinks added/removed in runCatching — the
 * track may already be disposed while paused; webrtc-kmp sample pattern).
 *
 * Back behavior: minimize for active calls (the call continues behind a header chip),
 * decline while ringing, dismiss when ended.
 */
@Composable
public fun FlashCallScreen(
    state: FlashCallUiState,
    session: FlashCallSession?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val ended = state.state == FlashCallState.ENDED

    BackHandler(enabled = true) {
        when {
            state.state == FlashCallState.RINGING -> onDecline()
            ended -> onDismiss()
            else -> onDismiss() // minimize: call continues, coordinator keeps state.
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (state.video) Color.Black else colors.backgroundApp),
    ) {
        if (state.video && !ended) {
            FlashCallVideoSurfaces(
                state = state,
                session = session,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            if (!state.video || ended) {
                FlashCallIdentityBlock(state)
            }

            Spacer(Modifier.weight(1f))

            FlashCallControls(
                state = state,
                onAccept = onAccept,
                onDecline = onDecline,
                onHangUp = onHangUp,
                            onDismiss = onDismiss,
                            onToggleMute = onToggleMute,
                            onToggleSpeaker = onToggleSpeaker,
                            onToggleCamera = onToggleCamera,
                            onSwitchCamera = onSwitchCamera,
                        )
            Spacer(Modifier.height(FlashSpacing.space40))
        }
    }
}

/** Peer avatar + name + live-region status line (audio calls / ended video calls). */
@Composable
private fun FlashCallIdentityBlock(state: FlashCallUiState) {
    val colors = FlashTheme.colors
    val pulsing = state.state == FlashCallState.RINGING || state.state == FlashCallState.ACTIVE

    val scale = if (pulsing && !FlashTheme.motion.reduceMotion) {
        val transition = rememberInfiniteTransition(label = "flashCallPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flashCallPulseScale",
        ).value
    } else {
        1f
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(FlashDimensions.avatarXl * 2)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(colors.accentPrimary.copy(alpha = 0.12f)),
            )
            FlashAvatar(
                initials = state.peerName.take(2),
                seed = state.peerId,
                size = FlashDimensions.avatarXl,
            )
        }
        Spacer(Modifier.height(FlashSpacing.space16))
        Text(
            text = state.peerName,
            style = FlashTheme.typography.headingLarge,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space4))
        Text(
            text = statusLine(state),
            style = FlashTheme.typography.bodyDefault,
            color = colors.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Remote-full + local-PiP video surfaces with lifecycle-safe sink management. */
@Composable
private fun FlashCallVideoSurfaces(
    state: FlashCallUiState,
    session: FlashCallSession?,
    modifier: Modifier = Modifier,
) {
    var pipIsLocal by remember { mutableStateOf(false) }
    val remoteTrack = session?.remoteVideoTrack
    val localTrack = session?.localVideoTrack

    Box(modifier = modifier) {
        FlashVideoRenderer(
            track = if (pipIsLocal) localTrack else remoteTrack,
            scalingType = RendererCommon.ScalingType.SCALE_ASPECT_BALANCED,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { pipIsLocal = !pipIsLocal },
        )

        FlashVideoRenderer(
            track = if (pipIsLocal) remoteTrack else localTrack,
            scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(FlashSpacing.space16)
                .widthIn(min = 96.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .border(1.dp, FlashTheme.colors.backgroundSurfaceSubtle, RoundedCornerShape(FlashShapes.radius12))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { pipIsLocal = !pipIsLocal },
        )

        // Identity + status overlay for video calls (small, top-start).
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(FlashSpacing.space16),
        ) {
            Text(
                text = state.peerName,
                style = FlashTheme.typography.headingMedium,
                color = Color.White,
            )
            Text(
                text = statusLine(state),
                style = FlashTheme.typography.bodyDefault,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * One [SurfaceViewRenderer] bound to a [VideoTrack]. Init on ON_RESUME, release on
 * ON_PAUSE; sinks added/removed in runCatching (track may be disposed while paused).
 */
@Composable
private fun FlashVideoRenderer(
    track: VideoTrack?,
    scalingType: RendererCommon.ScalingType,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(WebRtc.rootEglBase.eglBaseContext, null)
                setScalingType(scalingType)
                setEnableHardwareScaler(true)
            }.also { renderer = it }
        },
        modifier = modifier,
    )

    DisposableEffect(track, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                    Lifecycle.Event.ON_RESUME -> runCatching {
                        val r = renderer ?: return@runCatching
                        track?.addSink(r)
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                runCatching {
                    val r = renderer ?: return@runCatching
                    track?.removeSink(r)
                }
                runCatching { renderer?.release() }
            }
        }
}

/** Bottom control row, state-driven (48dp targets, FlashSpacing.space4 gaps). */
@Composable
private fun FlashCallControls(
    state: FlashCallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
) {
    when (state.state) {
        FlashCallState.RINGING -> Row(
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            FlashCallControlButton(
                icon = FlashIcons.CallAccept,
                background = FlashTheme.colors.accentPrimary,
                contentColor = FlashTheme.colors.textOnAccent,
                onClick = onAccept,
            )
            FlashCallControlButton(
                icon = FlashIcons.Hangup,
                background = FlashTheme.colors.textError,
                contentColor = FlashTheme.colors.textOnAccent,
                onClick = onDecline,
            )
        }
        FlashCallState.DIALING, FlashCallState.CONNECTING, FlashCallState.ACTIVE -> Row(
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        ) {
            FlashCallControlButton(
                icon = FlashIcons.Mute,
                background = if (state.micMuted) {
                    FlashTheme.colors.backgroundSurfaceStrong
                } else {
                    FlashTheme.colors.backgroundSurfaceSubtle
                },
                contentColor = if (state.micMuted) {
                    FlashTheme.colors.accentPrimary
                } else {
                    FlashTheme.colors.textPrimary
                },
                onClick = onToggleMute,
            )
            if (state.video) {
                FlashCallControlButton(
                    icon = FlashIcons.CameraFlip,
                    background = FlashTheme.colors.backgroundSurfaceSubtle,
                    contentColor = FlashTheme.colors.textPrimary,
                    onClick = onSwitchCamera,
                )
                FlashCallControlButton(
                    icon = FlashIcons.Camera,
                    background = if (state.cameraOff) {
                        FlashTheme.colors.backgroundSurfaceStrong
                    } else {
                        FlashTheme.colors.backgroundSurfaceSubtle
                    },
                    contentColor = if (state.cameraOff) {
                        FlashTheme.colors.accentPrimary
                    } else {
                        FlashTheme.colors.textPrimary
                    },
                    onClick = onToggleCamera,
                )
            } else {
                FlashCallControlButton(
                    icon = FlashIcons.Speaker,
                    background = if (state.speakerOn) {
                        FlashTheme.colors.backgroundSurfaceStrong
                    } else {
                        FlashTheme.colors.backgroundSurfaceSubtle
                    },
                    contentColor = if (state.speakerOn) {
                        FlashTheme.colors.accentPrimary
                    } else {
                        FlashTheme.colors.textPrimary
                    },
                    onClick = onToggleSpeaker,
                )
            }
            FlashCallControlButton(
                icon = FlashIcons.Hangup,
                background = FlashTheme.colors.textError,
                contentColor = FlashTheme.colors.textOnAccent,
                onClick = onHangUp,
            )
        }
        FlashCallState.ENDED -> Row {
            FlashCallControlButton(
                icon = FlashIcons.Close,
                background = FlashTheme.colors.backgroundSurfaceSubtle,
                contentColor = FlashTheme.colors.textPrimary,
                onClick = onDismiss,
            )
        }
    }
}

/** 48dp circular control button with the house press feel (no Material ripple). */
@Composable
private fun FlashCallControlButton(
    icon: com.transfer.flash.ui.icons.FlashIconSpec,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(FlashDimensions.minTouchTarget)
            .flashPressScale(interaction)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = icon.contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            tint = contentColor,
        )
    }
}

/** Human status line per call state (UI-050 status-text spec). */
@Composable
private fun statusLine(state: FlashCallUiState): String {
    return when (state.state) {
        FlashCallState.DIALING -> "Calling…"
        FlashCallState.RINGING -> "Incoming call"
        FlashCallState.CONNECTING -> "Connecting…"
        FlashCallState.ACTIVE -> activeDuration(state)
        FlashCallState.ENDED -> when (state.endReason) {
            FlashCallEndReason.NORMAL -> "Call ended"
            FlashCallEndReason.DECLINED -> "Declined"
            FlashCallEndReason.NO_ANSWER -> "No answer"
            FlashCallEndReason.DISCONNECTED -> "Connection lost"
            FlashCallEndReason.ERROR -> "Call failed"
            null -> "Call ended"
        }
    }
}

/** mm:ss duration counter while ACTIVE — one tick per second on a leaf text node. */
@Composable
private fun activeDuration(state: FlashCallUiState): String {
    var text by remember { mutableStateOf("00:00") }
    LaunchedEffect(state.connectedAt) {
        val startedAt = state.connectedAt ?: return@LaunchedEffect
        while (true) {
            val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
            val minutes = elapsedSec / 60
            val seconds = elapsedSec % 60
            text = "%02d:%02d".format(minutes, seconds)
            delay(1_000L)
        }
    }
    return text
}
