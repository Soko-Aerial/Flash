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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.shepeliev.webrtckmp.VideoTrack
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
            Spacer(Modifier.weight(1f))

            if (!state.video || ended) {
                FlashCallIdentityBlock(state = state, session = session)
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
private fun FlashCallIdentityBlock(state: FlashCallUiState, session: FlashCallMedia?) {
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
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashCallStatsBadge(session = session, state = state, onDark = false)
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
    val remoteTrack = rememberVideoTrack(session?.remoteVideoTrack)
    val localTrack = rememberVideoTrack(session?.localVideoTrack)

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
            Text(
                text = statusLine(state),
                style = FlashTheme.typography.bodyDefault,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
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
private fun rememberVideoTrack(flow: StateFlow<VideoTrack?>?): VideoTrack? {
    val source = remember(flow) { flow ?: MutableStateFlow<VideoTrack?>(null) }
    return source.collectAsState().value
}

/**
 * One [SurfaceViewRenderer] bound to whichever [VideoTrack] it is currently pointed at.
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
    track: VideoTrack?,
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
    private var bound: VideoTrack? = null

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
    fun bind(track: VideoTrack?) {
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
        stats.inboundKbps?.let { add(formatBitrate(it)) }
        // Loss below a couple of percent is normal on Wi-Fi and not worth a readout.
        stats.packetLoss?.takeIf { it >= 0.02 }?.let { add("${(it * 100).toInt()}% loss") }
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
 * composable call — same shape as [rememberVideoTrack] and for the same reason.
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
