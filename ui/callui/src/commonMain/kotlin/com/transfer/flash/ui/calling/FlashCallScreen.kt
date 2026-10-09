@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

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
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.ui.shims.FlashBackHandler
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
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.shepeliev.webrtckmp.WebRtc
import com.transfer.flash.core.calling.FlashCallMedia
import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallAudioRoutes
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallNotice
import com.transfer.flash.core.calling.model.FlashCameraProblem
import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.transfer.flash.core.common.perf.FlashNetworkBand

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
    onVideoFocus: (String?) -> Unit = {},
    onShowFewerVideos: (Boolean) -> Unit = {},
    /** The CPU banner's **Send smaller** (ADR-053): the host turns the setting on and saves it. */
    onSendSmallerVideo: () -> Unit = {},
    /** The outputs this phone can play the call through and the one in force (ADR-067); empty on a host with no routing. */
    audioRoutes: FlashCallAudioRoutes = FlashCallAudioRoutes(),
    onSelectRoute: (FlashCallAudioRoute) -> Unit = {},
    /** Show the "Verified" shield: the other device is the one this device paired with (1:1 calls). */
    peerVerified: Boolean = false,
    /** The host window is a picture-in-picture window: show the picture only, no controls. */
    inPictureInPicture: Boolean = false,
    /** Set by a host that can enter picture-in-picture (Android); adds the row to the More panel. */
    onEnterPictureInPicture: (() -> Unit)? = null,
    onSetHandRaised: (Boolean) -> Unit = {},
    onSendReaction: (FlashCallReactionKind) -> Boolean = { false },
    onSetDataSaver: (Boolean) -> Unit = {},
    /**
     * ADR-078: adds this device's camera to a live 1:1 call that has none (the host asks for the camera permission
     * first). Null on a host that cannot; the dock shows its button only while [FlashCallUiState.canUpgradeToVideo].
     */
    onUpgradeToVideo: (() -> Unit)? = null,
    /** ADR-102: what a person needs to present their screen. Null on a host that cannot (Android today). */
    share: FlashCallShareHost? = null,
) {
    val colors = FlashTheme.colors
    val ended = state.state == FlashCallState.ENDED
    var panel by remember { mutableStateOf<CallPanel?>(null) }
    var mirrorSelf by remember { mutableStateOf(false) }
    // A panel left open over a call that just ended would sit on a dead screen.
    LaunchedEffect(ended) { if (ended) panel = null }
    // The picker closes by itself once the share has started (a second tap would take over from ourselves).
    LaunchedEffect(state.sharing) { if (state.sharing && panel == CallPanel.SHARE) panel = null }

    FlashBackHandler(enabled = true) {
        when {
            state.state == FlashCallState.RINGING -> onDecline()
            ended -> onDismiss()
            else -> onDismiss() // minimize: call continues, coordinator keeps state.
        }
    }

    val isVideoActive = state.video && state.state == FlashCallState.ACTIVE

    if (inPictureInPicture) {
        FlashCallPictureInPicture(state = state, session = session, isVideoActive = isVideoActive)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isVideoActive) Color.Black else colors.backgroundApp),
    ) {
        if (isVideoActive) {
            if (state.isGroup) {
                // G1 (UI-050b): every participant's video, not only the last one to arrive.
                FlashGroupVideoSurfaces(
                    state = state,
                    session = session,
                    onVideoFocus = onVideoFocus,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FlashCallVideoSurfaces(
                    state = state,
                    session = session,
                    mirrorSelf = mirrorSelf,
                    peerVerified = peerVerified,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.7f))

            if (!isVideoActive || ended) {
                FlashCallIdentityBlock(state = state, session = session, peerVerified = peerVerified)
            }

            Spacer(Modifier.weight(1.3f))

            if (isVideoActive && !ended && state.isGroup) {
                // G6 (UI-050d): heat / CPU / software-decode warning, or the "Show all" pill.
                FlashCallHealthBanner(
                    state = state,
                    onShowFewerVideos = onShowFewerVideos,
                    onSendSmallerVideo = onSendSmallerVideo,
                )
                Spacer(Modifier.height(FlashSpacing.space8))
            }

            if (isVideoActive && !ended && state.isGroup && state.compactVideo) {
                // G5 (UI-050c): everyone as a chip; a tap picks whose video the main tile shows.
                FlashGroupVideoStrip(state = state, onVideoFocus = onVideoFocus)
                Spacer(Modifier.height(FlashSpacing.space16))
            }

            val notice = state.notice
            var noticeDismissed by remember(state.callId, notice) { mutableStateOf(false) }
            if (notice != null && !noticeDismissed && !ended && state.state != FlashCallState.RINGING) {
                // ERROR-105: a video call joined with the microphone only, and why.
                FlashCallMessagePill(text = callNoticeText(notice), onDismiss = { noticeDismissed = true })
                Spacer(Modifier.height(FlashSpacing.space12))
            }

            val shareNotice = state.shareNotice
            if (shareNotice != null && !ended) {
                // ADR-102: why a share stopped or did not start, once.
                FlashCallMessagePill(
                    text = CallShareText.notice(shareNotice, sharePresenterName(state), SHARE_NOTICE_DEFAULT_CAP),
                    onDismiss = { share?.onDismissNotice?.invoke() },
                )
                Spacer(Modifier.height(FlashSpacing.space12))
            }

            val cameraProblem = state.cameraProblem
            if (cameraProblem != null && !ended) {
                // ERROR-105: this device's own camera stopped or would not switch. "Try again" is the camera button.
                FlashCallMessagePill(
                    text = cameraProblemText(cameraProblem),
                    actionLabel = cameraProblemAction(cameraProblem),
                    onAction = { onToggleCamera() },
                )
                Spacer(Modifier.height(FlashSpacing.space12))
            }

            if (state.dataSaver && state.video && !ended) {
                // The way back from a call that stopped receiving video; the dock's More panel has the same switch.
                FlashDataSaverPill(onTurnOff = { onSetDataSaver(false) })
                Spacer(Modifier.height(FlashSpacing.space12))
            }

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
                audioRoutes = audioRoutes,
                onOpenRoutes = { panel = CallPanel.ROUTES },
                onOpenMore = { panel = CallPanel.MORE },
                moreActive = state.handRaised || state.dataSaver || state.sharing,
                onUpgradeToVideo = onUpgradeToVideo,
            )
            Spacer(Modifier.height(FlashSpacing.space40))
        }

        if (!ended) {
            FlashCallReactionLayer(
                reactions = state.reactions,
                nameOf = { id -> reactionSenderName(state, id) },
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            )
        }

        when (panel) {
            CallPanel.ROUTES -> FlashAudioRoutePicker(
                routes = audioRoutes,
                onSelect = onSelectRoute,
                onDismiss = { panel = null },
            )
            CallPanel.MORE -> FlashCallMorePanel(
                state = state,
                mirrorSelf = mirrorSelf,
                onToggleMirror = { mirrorSelf = !mirrorSelf },
                onSetHandRaised = onSetHandRaised,
                onSendReaction = onSendReaction,
                onSetDataSaver = onSetDataSaver,
                onEnterPictureInPicture = onEnterPictureInPicture,
                onShareScreen = if (shareStartAvailable(state, share) && !state.sharing) ({ panel = CallPanel.SHARE }) else null,
                onStopShare = { share?.onStop?.invoke() },
                onDismiss = { panel = null },
            )
            CallPanel.SHARE -> if (share != null) {
                FlashSharePicker(state = state, host = share, onDismiss = { panel = null })
            }
            null -> Unit
        }

        // ADR-102: drawn last, so it is above the video, the dock and every panel; a person who shares always sees it.
        if (state.sharing && !ended) {
            FlashShareIndicator(
                state = state,
                onStop = { share?.onStop?.invoke() },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        }
    }
}

/** Which panel is open over the call. */
private enum class CallPanel { ROUTES, MORE, SHARE }

/** Who sent a reaction, for its caption: the peer or a participant by name, anyone else is this device. */
internal fun reactionSenderName(state: FlashCallUiState, senderId: String): String =
    state.participants.firstOrNull { it.peerId == senderId }?.name
        ?: if (!state.isGroup && senderId == state.peerId) state.peerName else "You"

/** The window the system shrinks the call into (Android picture-in-picture): the picture, or the avatar, and nothing else. */
@Composable
private fun FlashCallPictureInPicture(state: FlashCallUiState, session: FlashCallMedia?, isVideoActive: Boolean) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier.fillMaxSize().background(if (isVideoActive) Color.Black else colors.backgroundApp),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideoActive && !state.isGroup && !state.dataSaver && !state.peerCameraOff) {
            val remoteTrack = rememberVideoStreamTrack(session?.remoteVideoStreamTrack)
            FlashCallVideoSurface(track = remoteTrack, fit = CallVideoFit.Balanced, modifier = Modifier.fillMaxSize())
        } else {
            FlashAvatar(initials = state.peerName.take(2), seed = state.peerId, size = 64.dp)
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
internal fun rememberCallPulseScale(pulsing: Boolean): State<Float> =
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
private fun FlashCallIdentityBlock(state: FlashCallUiState, session: FlashCallMedia?, peerVerified: Boolean) {
    val colors = FlashTheme.colors
    val pulsing = state.state == FlashCallState.RINGING || state.state == FlashCallState.ACTIVE
    val scale = rememberCallPulseScale(pulsing)
    val peerAudioLevel = rememberCallStats(session?.stats)?.peerAudioLevel
    val isSpeaking = FlashCallRippleMath.isPeerSpeaking(
        active = state.state == FlashCallState.ACTIVE,
        peerMicMuted = state.peerMicMuted,
        peerAudioLevel = peerAudioLevel,
    )

    val outerScale = FlashCallRippleMath.computeOuterGlowScale(isSpeaking, scale.value)
    val middleScale = FlashCallRippleMath.computeMiddleGlowScale(isSpeaking, scale.value)
    val outerAlpha = if (isSpeaking) FlashCallRippleMath.SPEAKING_ALPHA else if (state.state == FlashCallState.RINGING) FlashCallRippleMath.RESTING_ALPHA_RINGING else FlashCallRippleMath.RESTING_ALPHA_ACTIVE
    val middleAlpha = if (isSpeaking) 0.26f else if (state.state == FlashCallState.RINGING) 0.16f else 0.10f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!state.isGroup || state.participants.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                // Task 3.5: Multi-tier ambient glow waves modulated by real-time speaking energy
                Box(
                    modifier = Modifier
                        .size(172.dp)
                        .graphicsLayer {
                            scaleX = outerScale
                            scaleY = outerScale
                        }
                        .clip(CircleShape)
                        .background(colors.accentPrimary.copy(alpha = outerAlpha)),
                )
                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .graphicsLayer {
                            scaleX = middleScale
                            scaleY = middleScale
                        }
                        .clip(CircleShape)
                        .background(colors.accentPrimary.copy(alpha = middleAlpha)),
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
        FlashPeerBadges(
            micMuted = state.peerMicMuted,
            cameraOff = state.peerCameraOff,
            handRaised = state.peerHandRaised,
            onDark = false,
            modifier = Modifier.padding(top = FlashSpacing.space8),
        )
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashCallStatsBadge(session = session, state = state, onDark = false)
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashCallLinkChip(session = session, state = state, peerVerified = peerVerified && !state.isGroup, onDark = false)
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
                                val ringScale = FlashCallRippleMath.computeOuterGlowScale(isSpeaking = true, pulseScale)
                                Box(
                                    modifier = Modifier
                                        .size(FlashDimensions.avatarLg + 18.dp)
                                        .graphicsLayer {
                                            scaleX = ringScale
                                            scaleY = ringScale
                                        }
                                        .clip(CircleShape)
                                        .background(colors.statusOnline.copy(alpha = 0.32f)),
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
                        val statusLabel = participantStatusLabel(participant)
                        if (statusLabel != null) {
                            Text(
                                text = statusLabel,
                                style = FlashTheme.typography.metadataDefault,
                                color = colors.textTertiary,
                            )
                        }
                        FlashPeerBadges(
                            micMuted = participant.isMuted,
                            cameraOff = false,
                            handRaised = participant.handRaised,
                            onDark = false,
                            modifier = Modifier.padding(top = FlashSpacing.space4),
                        )
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
    mirrorSelf: Boolean,
    peerVerified: Boolean,
    modifier: Modifier = Modifier,
) {
    var pipIsLocal by remember { mutableStateOf(false) }
    val remoteTrack = rememberVideoStreamTrack(session?.remoteVideoStreamTrack)
    val localTrack = rememberVideoStreamTrack(session?.localVideoStreamTrack)
    // ADR-067: with data saver on, or the other camera off, there is no remote picture to show; the surface stays
    // composed (its renderer must not be rebuilt) and a placeholder covers it, and the preview cannot be swapped to the
    // main tile.
    val remoteShown = !state.dataSaver && !state.peerCameraOff
    val swapped = pipIsLocal && remoteShown

    Box(modifier = modifier) {
        FlashCallVideoSurface(
            track = if (swapped) localTrack else remoteTrack,
            // ADR-102: a presentation is letterboxed, never cropped (text at the edges must stay readable).
            fit = if (swapped) CallVideoFit.Balanced else videoFitFor(state.presenterId != null && remoteShown),
            mirror = swapped && mirrorSelf,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { if (remoteShown) pipIsLocal = !pipIsLocal },
        )

        if (!remoteShown) {
            FlashRemoteVideoPlaceholder(state = state, modifier = Modifier.fillMaxSize())
        }

        FlashCallVideoSurface(
            track = if (swapped) remoteTrack else localTrack,
            fit = CallVideoFit.Fit,
            mirror = !swapped && mirrorSelf,
            zOrderMediaOverlay = true,
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
                ) { if (remoteShown) pipIsLocal = !pipIsLocal },
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
            if (state.presenterId != null && remoteShown) {
                FlashPresenterLabel(name = state.peerName, modifier = Modifier.padding(top = FlashSpacing.space4))
            }
            FlashPeerBadges(
                micMuted = state.peerMicMuted,
                cameraOff = state.peerCameraOff,
                handRaised = state.peerHandRaised,
                onDark = true,
                modifier = Modifier.padding(top = FlashSpacing.space4),
            )
            Spacer(Modifier.height(FlashSpacing.space4))
            FlashCallStatsBadge(session = session, state = state, onDark = true)
            Spacer(Modifier.height(FlashSpacing.space4))
            FlashCallLinkChip(session = session, state = state, peerVerified = peerVerified, onDark = true)
            if (state.peerDataSaver) {
                Spacer(Modifier.height(FlashSpacing.space4))
                Text(
                    text = CallExtrasText.peerDataSaverNote(state.peerName),
                    style = FlashTheme.typography.metadataDefault,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.width(200.dp),
                )
            }
        }
    }
}

/** What covers the remote picture when there is none to show: the peer's avatar and the reason. */
@Composable
private fun FlashRemoteVideoPlaceholder(state: FlashCallUiState, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier.background(colors.backgroundApp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FlashAvatar(initials = state.peerName.take(2), seed = state.peerId, size = 96.dp)
        Spacer(Modifier.height(FlashSpacing.space16))
        Text(
            text = if (state.dataSaver) "Video paused" else "${state.peerName}'s camera is off",
            style = FlashTheme.typography.bodyDefault,
            color = colors.textSecondary,
        )
    }
}

/**
 * Observes a session's track flow, tolerating a null session without a conditional
 * composable call (which would re-key the `remember` slots underneath it).
 */
@Composable
internal fun rememberVideoStreamTrack(flow: StateFlow<VideoStreamTrack?>?): VideoStreamTrack? {
    val source = remember(flow) { flow ?: MutableStateFlow<VideoStreamTrack?>(null) }
    return source.collectAsState().value
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
    audioRoutes: FlashCallAudioRoutes,
    onOpenRoutes: () -> Unit,
    onOpenMore: () -> Unit,
    moreActive: Boolean,
    onUpgradeToVideo: (() -> Unit)? = null,
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
                    FlashIncomingCallButton(
                        icon = FlashIcons.Hangup,
                        background = FlashTheme.colors.textError,
                        contentColor = FlashTheme.colors.textOnAccent,
                        description = "Decline call",
                        haptic = FlashHaptic.Reject,
                        ringing = false,
                        tipsOnPress = true,
                        onClick = onDecline,
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
                    FlashIncomingCallButton(
                        icon = FlashIcons.CallAccept,
                        background = FlashTheme.colors.accentPrimary,
                        contentColor = FlashTheme.colors.textOnAccent,
                        description = "Accept call",
                        haptic = FlashHaptic.Confirm,
                        ringing = true,
                        tipsOnPress = false,
                        onClick = onAccept,
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
            // UI-050e: labelled, state-aware dock (FlashCallControlDock.kt).
            FlashCallControlDock(
                state = state,
                onToggleMute = onToggleMute,
                onToggleSpeaker = onToggleSpeaker,
                onToggleCamera = onToggleCamera,
                onSwitchCamera = onSwitchCamera,
                onHangUp = onHangUp,
                audioRoutes = audioRoutes,
                onOpenRoutes = onOpenRoutes,
                onOpenMore = onOpenMore,
                moreActive = moreActive,
                onUpgradeToVideo = onUpgradeToVideo,
            )
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
 * [FlashCallVideoSurfaces] (two video-surface composables and their whole modifier chains) — so an
 * ACTIVE call recomposed them once per second, which is not what [activeDuration]'s own KDoc
 * describes. A Unit-returning composable is always restartable, so putting the call behind one stops
 * the invalidation here, at the single `Text` that actually shows the changing value.
 *
 * Purely a scope change: same text, style, colour and live-region semantics as before, at every
 * performance tier.
 */
@Composable
internal fun FlashCallStatusLine(state: FlashCallUiState, color: Color) {
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
        FlashCallState.ENDED -> endReasonText(state.endReason)
    }
}

/** The final status line of an ended call. ERROR-105: a microphone failure says so instead of "Call failed". */
internal fun endReasonText(reason: FlashCallEndReason?): String = when (reason) {
    FlashCallEndReason.NORMAL -> "Call ended"
    FlashCallEndReason.DECLINED -> "Declined"
    FlashCallEndReason.NO_ANSWER -> "No answer"
    FlashCallEndReason.DISCONNECTED -> "Connection lost"
    FlashCallEndReason.ERROR -> "Call failed"
    FlashCallEndReason.MIC_DENIED -> "Microphone permission needed"
    FlashCallEndReason.MIC_UNAVAILABLE -> "Microphone is busy or unavailable"
    FlashCallEndReason.FULL -> "Call is full"
    null -> "Call ended"
}

/** ERROR-105: what to tell the user when this device's own camera stopped or would not switch. */
internal fun cameraProblemText(problem: FlashCameraProblem): String = when (problem) {
    FlashCameraProblem.FAILED -> "Camera stopped. Another app may be using it"
    FlashCameraProblem.SWITCH_FAILED -> "Couldn't switch camera"
    FlashCameraProblem.UPGRADE_FAILED -> "Couldn't turn on the camera"
}

/** ERROR-105: the action offered next to [cameraProblemText], or null when there is nothing to do about it. */
internal fun cameraProblemAction(problem: FlashCameraProblem): String? = when (problem) {
    FlashCameraProblem.FAILED -> "Try again"
    FlashCameraProblem.SWITCH_FAILED, FlashCameraProblem.UPGRADE_FAILED -> null
}

/** ERROR-105: the one-line message for a video call this device joined without sending video. */
internal fun callNoticeText(notice: FlashCallNotice): String = when (notice) {
    FlashCallNotice.CAMERA_DENIED_AUDIO_ONLY -> "Joined without camera: camera permission is off"
    FlashCallNotice.CAMERA_UNAVAILABLE_AUDIO_ONLY -> "Joined without camera: it could not be opened"
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
internal fun FlashCallStatsBadge(
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
        val resolutionText = when {
            stats.remoteResolutionLabel != null && stats.sendResolutionLabel != null -> {
                val recv = stats.fps?.let { "${stats.remoteResolutionLabel} · ${it}fps" } ?: stats.remoteResolutionLabel
                "$recv (↑${stats.sendResolutionLabel})"
            }
            stats.remoteResolutionLabel != null -> {
                stats.fps?.let { "${stats.remoteResolutionLabel} · ${it}fps" } ?: stats.remoteResolutionLabel
            }
            stats.sendResolutionLabel != null -> "↑${stats.sendResolutionLabel}"
            else -> null
        }
        resolutionText?.let { add(it) }
        val rate = stats.inboundKbps ?: stats.outboundKbps
        rate?.let { add(formatBitrate(it)) }
        // Loss below a couple of percent is normal on Wi-Fi and not worth a readout.
        stats.packetLoss?.takeIf { it >= 0.02 }?.let { add("${(it * 100).toInt()}% loss") }
        // G2: a group call's slowest link band (debug readout until G4 uses it for budgets).
        stats.networkBand?.takeIf { it != FlashNetworkBand.UNKNOWN }?.let { add(it.label) }
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
internal fun rememberCallStats(flow: StateFlow<FlashCallStats?>?): FlashCallStats? {
    val source = remember(flow) { flow ?: MutableStateFlow<FlashCallStats?>(null) }
    return source.collectAsState().value
}

/** kbit/s as the unit a human reads it in. */
private fun formatBitrate(kbps: Int): String =
    if (kbps >= 1_000) "%.1f Mbps".format(kbps / 1000f) else "$kbps kbps"

/** Local-preview tile width; the 3:4 ratio makes it a 120x160 dp PiP. */
internal val PIP_WIDTH = 120.dp
