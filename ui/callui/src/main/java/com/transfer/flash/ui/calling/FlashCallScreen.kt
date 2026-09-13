package com.transfer.flash.ui.calling

import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.shepeliev.webrtckmp.WebRtc
import com.transfer.flash.core.calling.FlashCallMedia
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallStats
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen in-call surface (UI-050, docs/ui/calling-ui.md).
 *
 * State-driven: every visual derives from [state]. Audio calls show the peer avatar with
 * the Flash pulse; video calls render remote-full + local-PiP through [FlashVideoRenderer],
 * whose tracks arrive asynchronously (media starts ~130 ms after this screen appears) and
 * are therefore observed, not sampled.
 *
 * [session] is the read-only [FlashCallMedia] view of the live call — tracks and quality
 * metrics only. Every control is a lambda the host wires to `FlashCalling`, so this screen
 * cannot mutate a call, and the module never sees the concrete session type.
 *
 * Back behavior: decline while ringing, otherwise [onDismiss]. A host that does not implement
 * minimize should leave `onDismiss` empty for a live call — the call outlives this screen either
 * way, since call state is owned by `FlashCalling`, not by composition.
 */
@Composable
public fun FlashCallScreen(
    state: FlashCallUiState,
    session: FlashCallMedia?,
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
            Spacer(Modifier.weight(0.7f))

            if (!state.video || ended) {
                FlashCallIdentityBlock(state = state, session = session)
            }

            Spacer(Modifier.weight(1.3f))

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

/**
 * The avatar halo's breathing scale, handed out as a **[State], not a `Float`** (EXP-013).
 *
 * The value is consumed in exactly one place — a `Modifier.graphicsLayer { }` block — so it never
 * needed to be a snapshot read in composition at all. It used to be: `.value` was read at
 * [FlashCallIdentityBlock]'s body scope, which subscribed that whole composable (avatar, peer name,
 * status line, stats badge) to a 60 Hz animation clock for the entire duration of every RINGING or
 * ACTIVE call. Reading it inside the layer block instead moves the observation into the render
 * pipeline: a new frame re-runs the block and re-draws, and composition is never invalidated.
 *
 * That is strictly the bigger of the two costs this file had; the mm:ss counter that
 * [FlashCallStatusLine] now confines ticked once per second, this ticked every frame.
 *
 * The `if` is deliberately kept: `pulsing` is false during CONNECTING, so the branch flips mid-call
 * and the transition is created and discarded. That is pre-existing behaviour and it is safe —
 * the compiler emits a group per `if` branch, so the `remember` and the transition are correctly
 * scoped to their branch. (Contrast `MainActivity.kt:569`, which warns about `remember` inside a
 * `?:`; an elvis gets no group and *would* leak state across the swap.)
 *
 * Under reduce-motion this returns a constant `1f` state, exactly as before: no transition is
 * created, no frame callback is scheduled. HIGH tier animates identically to before this change.
 */
@Composable
private fun rememberCallPulseScale(pulsing: Boolean): State<Float> =
    if (pulsing && !FlashTheme.motion.reduceMotion) {
        val transition = rememberInfiniteTransition(label = "flashCallPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flashCallPulseScale",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

/** Peer avatar + name + live-region status line (audio calls / ended video calls). */
@Composable
private fun FlashCallIdentityBlock(state: FlashCallUiState, session: FlashCallMedia?) {
    val colors = FlashTheme.colors
    val pulsing = state.state == FlashCallState.RINGING || state.state == FlashCallState.ACTIVE
    val scale = rememberCallPulseScale(pulsing)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!state.isGroup || state.participants.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                // Multi-tier ambient glow waves
                Box(
                    modifier = Modifier
                        .size(172.dp)
                        .graphicsLayer {
                            scaleX = scale.value * 1.06f
                            scaleY = scale.value * 1.06f
                        }
                        .clip(CircleShape)
                        .background(
                            if (state.state == FlashCallState.RINGING) {
                                colors.accentPrimary.copy(alpha = 0.08f)
                            } else {
                                colors.accentPrimary.copy(alpha = 0.05f)
                            }
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                        .clip(CircleShape)
                        .background(
                            if (state.state == FlashCallState.RINGING) {
                                colors.accentPrimary.copy(alpha = 0.16f)
                            } else {
                                colors.accentPrimary.copy(alpha = 0.10f)
                            }
                        ),
                )
                FlashAvatar(
                    initials = state.peerName.take(2),
                    seed = state.peerId,
                    size = 96.dp,
                )
            }
        } else {
            // Group Call: Participant Tiles Grid
            FlashGroupParticipantsGrid(
                participants = state.participants,
                pulseScale = scale.value,
            )
        }
        Spacer(Modifier.height(FlashSpacing.space20))
        Text(
            text = state.peerName,
            style = FlashTheme.typography.headingLarge,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashCallStatusLine(state = state, color = colors.textSecondary)
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashCallStatsBadge(session = session, state = state, onDark = false)
    }
}

/** Multi-participant grid for group audio/video calls (Phase 2). */
@Composable
private fun FlashGroupParticipantsGrid(
    participants: List<com.transfer.flash.core.calling.model.FlashCallParticipantUi>,
    pulseScale: Float,
) {
    val colors = FlashTheme.colors
    val displayed = participants.take(6)
    val columns = if (displayed.size <= 2) 2 else 3
    val rows = displayed.chunked(columns)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
        modifier = Modifier.padding(horizontal = FlashSpacing.space16),
    ) {
        rows.forEach { rowParticipants ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space20),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowParticipants.forEach { participant ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (participant.isSpeaking) {
                                Box(
                                    modifier = Modifier
                                        .size(FlashDimensions.avatarLg + 18.dp)
                                        .graphicsLayer {
                                            scaleX = pulseScale
                                            scaleY = pulseScale
                                        }
                                        .clip(CircleShape)
                                        .background(colors.statusOnline.copy(alpha = 0.28f)),
                                )
                            }
                            FlashAvatar(
                                initials = participant.name.take(2),
                                seed = participant.peerId,
                                size = FlashDimensions.avatarLg,
                            )
                        }
                        Spacer(Modifier.height(FlashSpacing.space4))
                        Text(
                            text = participant.name,
                            style = FlashTheme.typography.metadataDefault,
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                        val statusLabel = when (participant.state) {
                            com.transfer.flash.core.calling.model.FlashCallParticipantState.INVITED -> "Invited"
                            com.transfer.flash.core.calling.model.FlashCallParticipantState.CONNECTING -> "Connecting…"
                            com.transfer.flash.core.calling.model.FlashCallParticipantState.CONNECTED -> if (participant.isMuted) "Muted" else null
                            com.transfer.flash.core.calling.model.FlashCallParticipantState.DISCONNECTED -> "Reconnecting…"
                            com.transfer.flash.core.calling.model.FlashCallParticipantState.LEFT -> "Left"
                        }
                        if (statusLabel != null) {
                            Text(
                                text = statusLabel,
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Remote-full + local-PiP video surfaces, both bound to observable track flows. */
@Composable
private fun FlashCallVideoSurfaces(
    state: FlashCallUiState,
    session: FlashCallMedia?,
    modifier: Modifier = Modifier,
) {
    var pipIsLocal by remember { mutableStateOf(false) }
    val remoteTrack = rememberVideoStreamTrack(session?.remoteVideoStreamTrack)
    val localTrack = rememberVideoStreamTrack(session?.localVideoStreamTrack)

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
                .statusBarsPadding()
                .padding(FlashSpacing.space16)
                // A fixed width, not widthIn(min): with only a minimum the PiP took the
                // Box's full max width (~352 dp on a 1080p phone) and covered the remote
                // surface it is supposed to sit on top of.
                .width(PIP_WIDTH)
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
            FlashCallStatusLine(state = state, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(FlashSpacing.space4))
            FlashCallStatsBadge(session = session, state = state, onDark = true)
        }
    }
}

/**
 * Observes a session's track flow, tolerating a null session without a conditional
 * composable call (which would re-key the `remember` slots underneath it).
 */
@Composable
private fun rememberVideoStreamTrack(flow: StateFlow<VideoStreamTrack?>?): VideoStreamTrack? {
    val source = remember(flow) { flow ?: MutableStateFlow<VideoStreamTrack?>(null) }
    return source.collectAsState().value
}

/**
 * One [SurfaceViewRenderer] bound to whichever [VideoStreamTrack] it is currently pointed at.
 *
 * The renderer is initialised exactly once (in [AndroidView]'s factory) and released only
 * when the view itself is discarded. Track changes swap sinks; they must not release.
 *
 * This is the whole bug that made video calls show two black tiles: the previous version
 * released the renderer from a `DisposableEffect(track)`, so the very first track arrival
 * (null → live, one recomposition after media started) tore down the EGL render thread.
 * `factory` never runs twice, [org.webrtc.EglRenderer.release] is terminal — it nulls the
 * render-thread handler permanently — and every decoded frame from then on was answered
 * with "Dropping frame - Not initialized or already released." for the rest of the call.
 */
@Composable
private fun FlashVideoRenderer(
    track: VideoStreamTrack?,
    scalingType: RendererCommon.ScalingType,
    modifier: Modifier = Modifier,
) {
    val holder = remember { FlashVideoSink() }

    AndroidView(
        factory = { context -> SurfaceViewRenderer(context).also { holder.attach(it, scalingType) } },
        modifier = modifier,
        update = { holder.bind(track) },
        onRelease = { holder.release() },
    )

    // onRelease covers the view being discarded; this covers the composable leaving the
    // tree (call ended, screen dismissed). Both land on the same idempotent teardown.
    DisposableEffect(holder) {
        onDispose { holder.release() }
    }
}

/**
 * Owns one [SurfaceViewRenderer]'s EGL lifetime and its current sink binding.
 *
 * Confined to the main thread: every entry point is an [AndroidView] callback
 * (factory / update / onRelease) or a Compose effect disposal.
 */
private class FlashVideoSink {

    private var view: SurfaceViewRenderer? = null
    private var bound: VideoStreamTrack? = null

    fun attach(renderer: SurfaceViewRenderer, scalingType: RendererCommon.ScalingType) {
        view = renderer
        bound = null
        runCatching {
            renderer.init(WebRtc.rootEglBase.eglBaseContext, null)
            renderer.setScalingType(scalingType)
            renderer.setEnableHardwareScaler(true)
        }.onFailure { Log.w(TAG, "renderer init failed", it) }
    }

    /** Points the surface at [track], detaching whatever it was showing before. */
    fun bind(track: VideoStreamTrack?) {
        if (track === bound) return
        val renderer = view ?: return
        // runCatching on both sides: a track can be stopped and disposed by the session
        // (peer hung up) between the flow emission and this frame's applyChanges.
        bound?.let { previous ->
            runCatching { previous.removeSink(renderer) }
                .onFailure { Log.w(TAG, "removeSink failed", it) }
        }
        bound = track
        if (track == null) {
            renderer.clearImage()
            return
        }
        runCatching { track.addSink(renderer) }
            .onFailure { Log.w(TAG, "addSink failed", it) }
    }

    /** Terminal: after this the renderer can never draw again. Idempotent. */
    fun release() {
        val renderer = view ?: return
        view = null
        bound?.let { track ->
            runCatching { track.removeSink(renderer) }
        }
        bound = null
        runCatching { renderer.release() }
    }

    private companion object {
        const val TAG = "CALLUI"
    }
}

/** Bottom control row, state-driven (professional call surface with large action buttons). */
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
        FlashCallState.RINGING -> {
            // Prominent, beautiful incoming answering row (72dp buttons with subtle action labels)
            Row(
                modifier = Modifier
                    .padding(horizontal = FlashSpacing.space32),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FlashLargeCallButton(
                        icon = FlashIcons.Hangup,
                        background = FlashTheme.colors.textError,
                        contentColor = FlashTheme.colors.textOnAccent,
                        size = 72.dp,
                        iconSize = 32.dp,
                        onClick = onDecline,
                        description = "Decline call",
                    )
                    Spacer(Modifier.height(FlashSpacing.space8))
                    Text(
                        text = "Decline",
                        style = FlashTheme.typography.captionDefault,
                        color = FlashTheme.colors.textSecondary,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FlashLargeCallButton(
                        icon = FlashIcons.CallAccept,
                        background = FlashTheme.colors.accentPrimary,
                        contentColor = FlashTheme.colors.textOnAccent,
                        size = 72.dp,
                        iconSize = 32.dp,
                        onClick = onAccept,
                        description = "Accept call",
                    )
                    Spacer(Modifier.height(FlashSpacing.space8))
                    Text(
                        text = "Accept",
                        style = FlashTheme.typography.captionDefault,
                        color = FlashTheme.colors.textSecondary,
                    )
                }
            }
        }
        FlashCallState.DIALING, FlashCallState.CONNECTING, FlashCallState.ACTIVE -> {
            // Sleek in-call control dock
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FlashShapes.radius24))
                    .background(FlashTheme.colors.backgroundSurfaceStrong.copy(alpha = 0.85f))
                    .border(
                        width = FlashDimensions.borderHairline,
                        color = FlashTheme.colors.borderSubtle,
                        shape = RoundedCornerShape(FlashShapes.radius24),
                    )
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashCallControlButton(
                        icon = FlashIcons.Mute,
                        background = if (state.micMuted) {
                            FlashTheme.colors.accentPrimary.copy(alpha = 0.20f)
                        } else {
                            FlashTheme.colors.backgroundSurfaceSubtle
                        },
                        contentColor = if (state.micMuted) {
                            FlashTheme.colors.accentPrimary
                        } else {
                            FlashTheme.colors.textPrimary
                        },
                        onClick = onToggleMute,
                        size = 54.dp,
                        iconSize = 26.dp,
                    )
                    if (state.video) {
                        FlashCallControlButton(
                            icon = FlashIcons.CameraFlip,
                            background = FlashTheme.colors.backgroundSurfaceSubtle,
                            contentColor = FlashTheme.colors.textPrimary,
                            onClick = onSwitchCamera,
                            size = 54.dp,
                            iconSize = 26.dp,
                        )
                        FlashCallControlButton(
                            icon = FlashIcons.Camera,
                            background = if (state.cameraOff) {
                                FlashTheme.colors.accentPrimary.copy(alpha = 0.20f)
                            } else {
                                FlashTheme.colors.backgroundSurfaceSubtle
                            },
                            contentColor = if (state.cameraOff) {
                                FlashTheme.colors.accentPrimary
                            } else {
                                FlashTheme.colors.textPrimary
                            },
                            onClick = onToggleCamera,
                            size = 54.dp,
                            iconSize = 26.dp,
                        )
                    } else {
                        FlashCallControlButton(
                            icon = FlashIcons.Speaker,
                            background = if (state.speakerOn) {
                                FlashTheme.colors.accentPrimary.copy(alpha = 0.20f)
                            } else {
                                FlashTheme.colors.backgroundSurfaceSubtle
                            },
                            contentColor = if (state.speakerOn) {
                                FlashTheme.colors.accentPrimary
                            } else {
                                FlashTheme.colors.textPrimary
                            },
                            onClick = onToggleSpeaker,
                            size = 54.dp,
                            iconSize = 26.dp,
                        )
                    }
                    FlashCallControlButton(
                        icon = FlashIcons.Hangup,
                        background = FlashTheme.colors.textError,
                        contentColor = FlashTheme.colors.textOnAccent,
                        onClick = onHangUp,
                        size = 54.dp,
                        iconSize = 26.dp,
                    )
                }
            }
        }
        FlashCallState.ENDED -> {
            Row {
                FlashCallControlButton(
                    icon = FlashIcons.Close,
                    background = FlashTheme.colors.backgroundSurfaceStrong,
                    contentColor = FlashTheme.colors.textPrimary,
                    onClick = onDismiss,
                    size = 56.dp,
                    iconSize = 26.dp,
                )
            }
        }
    }
}

/** Large circular button with smooth spring haptics for call acceptance & rejection. */
@Composable
private fun FlashLargeCallButton(
    icon: com.transfer.flash.ui.icons.FlashIconSpec,
    background: Color,
    contentColor: Color,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    description: String,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
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
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            tint = contentColor,
            size = iconSize,
        )
    }
}

/** Circular control button with house press feel (no Material ripple). */
@Composable
private fun FlashCallControlButton(
    icon: com.transfer.flash.ui.icons.FlashIconSpec,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = FlashDimensions.minTouchTarget,
    iconSize: androidx.compose.ui.unit.Dp = FlashDimensions.iconMd,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
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
            size = iconSize,
        )
    }
}

/** Human status line per call state (UI-050 status-text spec). */
/**
 * The status line as its **own restartable scope** — the reason this wrapper exists (EXP-013).
 *
 * [statusLine] and [activeDuration] are `@Composable` functions that *return a value*, which makes
 * them non-restartable: the `mutableStateOf` tick inside [activeDuration] is recorded against the
 * nearest restartable scope *above* them, i.e. whichever composable called `statusLine(state)`. Both
 * call sites were large — [FlashCallIdentityBlock] (avatar, infinite pulse transition) and
 * [FlashCallVideoSurfaces] (two `AndroidView` renderers and their whole modifier chains) — so an
 * ACTIVE call recomposed them once per second, which is not what [activeDuration]'s own KDoc
 * describes. A Unit-returning composable is always restartable, so putting the call behind one stops
 * the invalidation here, at the single `Text` that actually shows the changing value.
 *
 * Purely a scope change: same text, style, colour and live-region semantics as before, at every
 * performance tier.
 */
@Composable
private fun FlashCallStatusLine(state: FlashCallUiState, color: Color) {
    Text(
        text = statusLine(state),
        style = FlashTheme.typography.bodyDefault,
        color = color,
        modifier = Modifier.semantics {
            // Transitions ("Incoming call" → "Call ended") are announced; the ACTIVE second
            // hand is not. A polite live-region on the clock would post an accessibility
            // event every second for the whole call — hundreds of announcements nobody asked
            // for, each waking the accessibility pipeline on a low-end device mid-call.
            // `state.state` reads in the semantics phase, so this costs a semantics pass on
            // transitions, never a recomposition per tick.
            if (state.state != FlashCallState.ACTIVE) liveRegion = LiveRegionMode.Polite
        },
    )
}

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

/** mm:ss duration counter while ACTIVE — one tick per second, confined to one text node by
 *  [FlashCallStatusLine]'s scope. Call it only from there; calling it from a larger composable
 *  recomposes that whole composable every second, because a value-returning `@Composable` is
 *  non-restartable and its State reads land in the caller's scope (EXP-013). */
@Composable
private fun activeDuration(state: FlashCallUiState): String {
    var text by remember { mutableStateOf(formatCallDuration(0L)) }
    LaunchedEffect(state.connectedAt) {
        val startedAt = state.connectedAt ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            text = formatCallDuration(now - startedAt)
            // Sleep to the next wall-clock second boundary, not a flat 1 s: the displayed
            // second flips on its edge (no drift, no skipped/duplicated seconds) and the tick
            // coalesces into the same wakeup phase as the stats sampler's own 1 s cadence
            // instead of free-running against it. Same text, same cadence, same tier behaviour.
            val intoSecond = ((now - startedAt) % 1_000L).coerceAtLeast(0L)
            delay(1_000L - intoSecond)
        }
    }
    return text
}

/**
 * Elapsed call time as `mm:ss`, extracted from [activeDuration] so the arithmetic is JVM-testable
 * (it was previously inline in a `LaunchedEffect` and had no coverage).
 *
 * Truncates rather than rounds — a call is "00:00" for its whole first second, which is what a phone
 * dialler does. Negative input clamps to zero: [FlashCallUiState.connectedAt] comes from
 * `System.currentTimeMillis()`, so a wall-clock correction mid-call can put "now" behind the start,
 * and "-1:-3" on screen would be worse than a paused counter. Past 59:59 the minutes field widens
 * ("100:00") instead of wrapping — `%02d` is a minimum width, not a truncation.
 */
internal fun formatCallDuration(elapsedMillis: Long): String {
    val elapsedSec = (elapsedMillis / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60)
}

/**
 * Live transport readout — the call screen's latency counter (UI-050).
 *
 * Renders round-trip time, received resolution/framerate and inbound bitrate, resampled once a
 * second by the session's `getStats()` poller. Every field is independently nullable because
 * WebRTC publishes each one only when the corresponding report first exists (RTT needs an RTCP
 * round trip, fps needs a decoded frame, bitrate needs two samples), so the badge grows into
 * itself over the first few seconds instead of showing zeros.
 *
 * The dot is the at-a-glance verdict on RTT: green under 60 ms, amber under 150, red past that.
 * Shown only while ACTIVE — a stale number on a dead call is worse than no number.
 *
 * A second line appears when the audio-protective governor has traded video away
 * ([FlashCallUiState.videoLimitReason], D8): the picture getting worse on purpose has to be
 * distinguishable from the picture getting worse because the app is broken.
 */
@Composable
private fun FlashCallStatsBadge(
    session: FlashCallMedia?,
    state: FlashCallUiState,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val stats = rememberCallStats(session?.stats)
    if (state.state != FlashCallState.ACTIVE || stats == null || !stats.hasData) return

    val parts = buildList {
        stats.rttMs?.let { add("$it ms") }
        stats.remoteResolutionLabel?.let { resolution ->
            add(stats.fps?.let { "$resolution · ${it}fps" } ?: resolution)
        }
        val rate = stats.inboundKbps ?: stats.outboundKbps
        rate?.let { add(formatBitrate(it)) }
        // Loss below a couple of percent is normal on Wi-Fi and not worth a readout.
        stats.packetLoss?.takeIf { it >= 0.02 }?.let { add("${(it * 100).toInt()}% loss") }
        if (isEmpty() && state.state == FlashCallState.ACTIVE) {
            add("<1 ms")
        }
    }
    if (parts.isEmpty() && state.videoLimitReason == null) return

    val dotColor = when (val rtt = stats.rttMs) {
        null -> colors.textTertiary
        in 0..59 -> colors.textSuccess
        in 60..149 -> colors.statusTransfer
        else -> colors.textError
    }
    val textColor = if (onDark) Color.White.copy(alpha = 0.85f) else colors.textSecondary

    // ERROR-031 / D8: when the governor has traded video away to keep voice intelligible, say so.
    // Degraded video with no explanation reads as a broken app; the same picture with a reason
    // reads as a working one, and it is the only signal that the trade is deliberate.
    val limitReason = state.videoLimitReason

    Column(
        modifier = modifier
            .then(
                if (onDark) {
                    Modifier
                        .clip(RoundedCornerShape(FlashShapes.radius12))
                        .background(Color.Black.copy(alpha = 0.32f))
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = buildList {
                    if (parts.isNotEmpty()) add("Call quality: " + parts.joinToString(", "))
                    limitReason?.let { add(it) }
                }.joinToString(". ")
            },
        horizontalAlignment = if (onDark) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (parts.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                Box(
                    modifier = Modifier
                        .size(FlashSpacing.space8)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = parts.joinToString("  ·  "),
                    style = FlashTheme.typography.metadataDefault,
                    color = textColor,
                )
            }
        }
        if (limitReason != null) {
            Text(
                text = limitReason,
                style = FlashTheme.typography.metadataDefault,
                color = if (onDark) Color.White.copy(alpha = 0.72f) else colors.textTertiary,
            )
        }
    }
}

/**
 * Observes the session's metrics flow, tolerating a null session without a conditional
 * composable call — same shape as [rememberVideoStreamTrack] and for the same reason.
 */
@Composable
private fun rememberCallStats(flow: StateFlow<FlashCallStats?>?): FlashCallStats? {
    val source = remember(flow) { flow ?: MutableStateFlow<FlashCallStats?>(null) }
    return source.collectAsState().value
}

/** kbit/s as the unit a human reads it in. */
private fun formatBitrate(kbps: Int): String =
    if (kbps >= 1_000) "%.1f Mbps".format(kbps / 1000f) else "$kbps kbps"

/** Local-preview tile width; the 3:4 ratio makes it a 120x160 dp PiP. */
private val PIP_WIDTH = 120.dp
