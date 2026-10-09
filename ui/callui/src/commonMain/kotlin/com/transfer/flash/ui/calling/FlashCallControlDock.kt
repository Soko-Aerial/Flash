@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallAudioRoutes
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * In-call control dock (UI-050e, docs/ui/calling-ui.md).
 *
 * Motion policy: the three performance tiers collapse to two UI behaviours (FlashPerformanceMode).
 *  - HIGH   -> every animation below plays.
 *  - MEDIUM / LOW -> `FlashTheme.motion.reduceMotion` is true: the glyph swaps instantly, colours snap,
 *    there is no ripple, no wiggle loop and no spin. The *information* does not change: a toggle that is
 *    off still shows the slashed glyph, the solid inverted surface and the new label, and a haptic tick
 *    still fires (haptics are deliberately independent of reduce-motion, FlashHapticPolicy).
 *  - minimalChrome (same tiers) additionally swaps the translucent dock for an opaque one: no blending
 *    over the video surface.
 * Every animated value is read inside graphicsLayer / drawBehind, so a frame costs a render pass and
 * never a recomposition (EXP-013).
 */

/** What the glyph does when its control changes (or is pressed). One signature per control. */
internal enum class DockGesture {
    /** Mic, speaker: the new glyph pops in on a spring. */
    Pop,

    /** Camera: the new glyph opens like an eyelid (vertical squash back to full height). */
    Eyelid,

    /** Flip: a half turn per tap; the glyph is 180-degree symmetric so each turn lands on itself. */
    Spin,

    /** End / decline: the handset tips onto its cradle while the finger is down. */
    Tilt,
}

/** Words a control shows under its button and tells TalkBack. Pure so the wording is unit-tested. */
internal object CallDockText {
    fun micLabel(muted: Boolean): String = if (muted) "Muted" else "Mic on"
    fun micDescription(muted: Boolean): String = if (muted) "Unmute microphone" else "Mute microphone"

    fun videoLabel(cameraOff: Boolean): String = if (cameraOff) "Video off" else "Video on"
    fun videoDescription(cameraOff: Boolean): String = if (cameraOff) "Turn camera on" else "Turn camera off"
    fun videoDescription(cameraOff: Boolean, sharing: Boolean): String =
        if (sharing) CallShareText.CAMERA_OFF_WHILE_SHARING else videoDescription(cameraOff)

    /** ADR-078: the button on a voice call that adds this device's camera to it. */
    const val UPGRADE_LABEL: String = "Camera"
    const val UPGRADE_DESCRIPTION: String = "Turn camera on"

    const val FLIP_LABEL: String = "Flip"
    const val FLIP_DESCRIPTION: String = "Switch camera"

    fun routeLabel(speakerOn: Boolean): String = if (speakerOn) "Speaker" else "Earpiece"
    fun routeDescription(speakerOn: Boolean): String =
        if (speakerOn) "Switch to earpiece" else "Switch to speaker"

    /** ADR-067: with a headset in play the button names the output in force and opens the list. */
    fun routeDescription(route: FlashCallAudioRoute): String = "Audio output: ${CallExtrasText.routeLabel(route)}. Choose another"

    const val MORE_LABEL: String = "More"
    const val MORE_DESCRIPTION: String = "More call options"

    const val END_LABEL: String = "End"
    const val END_DESCRIPTION: String = "End call"
}

// ---- motion math (pure, unit-tested) ---------------------------------------------------------

/** How far the ripple ring travels beyond the button edge, as a fraction of the button radius. */
internal const val RIPPLE_GROWTH: Float = 0.32f
internal const val RIPPLE_ALPHA: Float = 0.45f

/** Glyph scale for [DockGesture.Pop] at spring progress [p] (the spring overshoots 1, so this can exceed 1). */
internal fun popScale(p: Float): Float = 0.5f + 0.5f * p

/** Vertical scale for [DockGesture.Eyelid]: nearly shut at 0, fully open at 1. */
internal fun eyelidScaleY(p: Float): Float = 0.08f + 0.92f * p

/** Glyph alpha while it arrives; never fully invisible so a dropped frame cannot blank the icon. */
internal fun arrivalAlpha(p: Float): Float = 0.35f + 0.65f * p.coerceIn(0f, 1f)

/**
 * Ring radius as a multiple of the button radius. [outward] rings leave the button (signal going out:
 * mic live, camera on, speaker on); inward rings collapse onto it (signal cut).
 */
internal fun rippleRadiusScale(p: Float, outward: Boolean): Float {
    val c = p.coerceIn(0f, 1f)
    return 1f + RIPPLE_GROWTH * if (outward) c else 1f - c
}

internal fun rippleAlpha(p: Float): Float = RIPPLE_ALPHA * (1f - p.coerceIn(0f, 1f))

/** Fraction of an incoming-call loop spent ringing; the rest is rest. */
internal const val RING_ACTIVE_FRACTION: Float = 0.55f
internal const val RING_WIGGLE_MAX_DEGREES: Float = 14f

/**
 * Handset angle at [phase] (0..1) of the incoming-call loop: two decaying swings of a ringing handset,
 * then stillness. Exactly 0 at both ends so the loop restarts without a jump.
 */
internal fun ringWiggleDegrees(phase: Float): Float {
    if (phase <= 0f || phase >= RING_ACTIVE_FRACTION) return 0f
    val u = phase / RING_ACTIVE_FRACTION
    return (sin(u * 4.0 * PI) * RING_WIGGLE_MAX_DEGREES * (1f - u)).toFloat()
}

// ---- the dock --------------------------------------------------------------------------------

private val BUTTON_SIZE = 52.dp
private val MIN_BUTTON_SIZE = 40.dp
private val ICON_SIZE = 24.dp
private const val DISABLED_ALPHA = 0.38f
private const val PRESSED_TILT_DEGREES = 28f

/**
 * Bottom control dock for DIALING / CONNECTING / ACTIVE calls (audio: 4 controls, video: 6).
 *
 * The output button follows [audioRoutes] (ADR-067): with no headset in play it is the old speaker toggle; with one it
 * shows the output in force and [onOpenRoutes] opens the list. A host with no routing (the desktop) reports none and keeps
 * the toggle. [moreActive] marks the More button while a hand is up or data saver is on.
 */
@Composable
internal fun FlashCallControlDock(
    state: FlashCallUiState,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onHangUp: () -> Unit,
    audioRoutes: FlashCallAudioRoutes = FlashCallAudioRoutes(),
    onOpenRoutes: () -> Unit = {},
    onOpenMore: (() -> Unit)? = null,
    moreActive: Boolean = false,
    onUpgradeToVideo: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val shape = RoundedCornerShape(FlashShapes.radius24)
    // ADR-078: a call with no camera of ours on it (voice, or a video call joined without a camera) can add one.
    val upgrade = if (state.canUpgradeToVideo) onUpgradeToVideo else null
    val count = (if (state.video) 5 else 3) + (if (!state.video && upgrade != null) 1 else 0) + (if (onOpenMore != null) 1 else 0)
    // Six buttons must fit a 360 dp phone: shrink the buttons (never below 40 dp) rather than overflow.
    BoxWithConstraints(
        modifier = Modifier
            .padding(horizontal = FlashSpacing.space16)
            .widthIn(max = (count * DOCK_SLOT_MAX_DP).dp),
    ) {
    val buttonSize = ((maxWidth - 16.dp) / count - 4.dp).coerceIn(MIN_BUTTON_SIZE, BUTTON_SIZE)
    val active = audioRoutes.active
    val routeIconSpec = when {
        active != null -> routeIcon(active)
        state.speakerOn -> FlashIcons.Speaker
        else -> FlashIcons.Earpiece
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                // minimalChrome: opaque, so the dock never alpha-blends over a video surface.
                if (FlashTheme.minimalChrome) colors.backgroundSurfaceStrong
                else colors.backgroundSurfaceStrong.copy(alpha = 0.85f),
            )
            .border(FlashDimensions.borderHairline, colors.borderSubtle, shape)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            FlashCallDockButton(
                icon = if (state.micMuted) FlashIcons.MicOff else FlashIcons.Microphone,
                label = CallDockText.micLabel(state.micMuted),
                description = CallDockText.micDescription(state.micMuted),
                emphasised = state.micMuted,
                rippleOutward = !state.micMuted,
                gesture = DockGesture.Pop,
                onClick = onToggleMute,
                buttonSize = buttonSize,
                modifier = Modifier.weight(1f),
            )
            if (!state.video && upgrade != null) {
                FlashCallDockButton(
                    icon = FlashIcons.Video,
                    label = CallDockText.UPGRADE_LABEL,
                    description = CallDockText.UPGRADE_DESCRIPTION,
                    emphasised = false,
                    rippleOutward = true,
                    gesture = DockGesture.Eyelid,
                    onClick = upgrade,
                    buttonSize = buttonSize,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.video) {
                // ADR-102: while this device presents, the one video is the screen; the camera is off and cannot be switched on.
                val camOff = state.cameraOff || state.sharing
                FlashCallDockButton(
                    icon = if (camOff) FlashIcons.VideoOff else FlashIcons.Video,
                    label = CallDockText.videoLabel(camOff),
                    description = CallDockText.videoDescription(state.cameraOff, state.sharing),
                    emphasised = camOff,
                    rippleOutward = !camOff,
                    gesture = DockGesture.Eyelid,
                    enabled = !state.sharing,
                    // A video call joined without a camera has nothing to switch on: the button adds one.
                    onClick = upgrade ?: onToggleCamera,
                    buttonSize = buttonSize,
                    modifier = Modifier.weight(1f),
                )
                FlashCallDockButton(
                    icon = FlashIcons.CameraFlip,
                    label = CallDockText.FLIP_LABEL,
                    description = CallDockText.FLIP_DESCRIPTION,
                    emphasised = false,
                    rippleOutward = true,
                    gesture = DockGesture.Spin,
                    // Nothing to flip while our camera is off: dim it rather than let it vanish and shift the row.
                    enabled = !state.cameraOff && !state.sharing,
                    onClick = onSwitchCamera,
                    buttonSize = buttonSize,
                    modifier = Modifier.weight(1f),
                )
            }
            FlashCallDockButton(
                icon = routeIconSpec,
                label = if (active != null) CallExtrasText.routeLabel(active) else CallDockText.routeLabel(state.speakerOn),
                description = if (audioRoutes.needsPicker && active != null) {
                    CallDockText.routeDescription(active)
                } else {
                    CallDockText.routeDescription(state.speakerOn)
                },
                emphasised = if (active != null) active == FlashCallAudioRoute.SPEAKER else state.speakerOn,
                rippleOutward = if (active != null) active == FlashCallAudioRoute.SPEAKER else state.speakerOn,
                gesture = DockGesture.Pop,
                onClick = if (audioRoutes.needsPicker) onOpenRoutes else onToggleSpeaker,
                buttonSize = buttonSize,
                modifier = Modifier.weight(1f),
            )
            if (onOpenMore != null) {
                FlashCallDockButton(
                    icon = FlashIcons.More,
                    label = CallDockText.MORE_LABEL,
                    description = CallDockText.MORE_DESCRIPTION,
                    emphasised = moreActive,
                    rippleOutward = true,
                    gesture = DockGesture.Pop,
                    onClick = onOpenMore,
                    buttonSize = buttonSize,
                    modifier = Modifier.weight(1f),
                )
            }
            FlashCallDockButton(
                icon = FlashIcons.Hangup,
                label = CallDockText.END_LABEL,
                description = CallDockText.END_DESCRIPTION,
                emphasised = false,
                rippleOutward = true,
                gesture = DockGesture.Tilt,
                destructive = true,
                onClick = onHangUp,
                buttonSize = buttonSize,
                modifier = Modifier.weight(1f),
            )
        }
    }
    }
}

/** Widest a slot grows on a wide window (desktop), so three audio controls do not spread across 1000 dp. */
private const val DOCK_SLOT_MAX_DP = 84

/**
 * One dock control: a round button, its glyph, and a one-word label under it.
 *
 * [emphasised] is the inverted solid style (muted mic, camera off, speaker on); the glyph itself also
 * changes, so state never depends on colour alone. A change of [emphasised] plays the [gesture] and a
 * ripple ring, except on first composition.
 */
@Composable
internal fun FlashCallDockButton(
    icon: FlashIconSpec,
    label: String,
    description: String,
    emphasised: Boolean,
    rippleOutward: Boolean,
    gesture: DockGesture,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    buttonSize: Dp = BUTTON_SIZE,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val reduce = motion.reduceMotion
    val haptic = rememberFlashHaptics()
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()

    val background by animateColorAsState(
        targetValue = when {
            destructive -> colors.textError
            emphasised -> colors.textPrimary
            else -> colors.backgroundSurfaceSubtle
        },
        animationSpec = motion.tweenFastSpec(),
        label = "dockBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            destructive -> colors.textOnAccent
            emphasised -> colors.backgroundApp
            else -> colors.textPrimary
        },
        animationSpec = motion.tweenFastSpec(),
        label = "dockContent",
    )
    val ringColor = colors.accentPrimary

    // 0 -> 1 after each state change; the spring overshoots, which is what makes the glyph "pop".
    val burst = remember { Animatable(1f) }
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(emphasised) {
        if (!primed) {
            primed = true
            return@LaunchedEffect
        }
        if (reduce) return@LaunchedEffect
        burst.snapTo(0f)
        burst.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
    }

    val turns = remember { Animatable(0f) }
    val pressed by interaction.collectIsPressedAsState()
    val tilt = animateFloatAsState(
        targetValue = if (gesture == DockGesture.Tilt && pressed && !reduce) PRESSED_TILT_DEGREES else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "dockTilt",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                // Unclipped outer box: the ring is allowed to leave the button's circle.
                .drawBehind {
                    val a = rippleAlpha(burst.value)
                    if (!reduce && a > 0f && burst.value < 1f) {
                        drawCircle(
                            color = ringColor.copy(alpha = a),
                            radius = size.minDimension / 2f * rippleRadiusScale(burst.value, rippleOutward),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .flashPressScale(interaction)
                    .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
                    .clip(CircleShape)
                    .background(background)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                    ) {
                        haptic(if (destructive) FlashHaptic.Reject else FlashHaptic.Tick)
                        if (gesture == DockGesture.Spin && !reduce) {
                            scope.launch {
                                turns.animateTo(
                                    turns.targetValue + 180f,
                                    spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
                                )
                            }
                        }
                        onClick()
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = description
                        if (!enabled) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = icon,
                    tint = content,
                    size = ICON_SIZE,
                    modifier = Modifier
                        .clearAndSetSemantics { }
                        .graphicsLayer {
                            if (reduce) return@graphicsLayer
                            val p = burst.value
                            when (gesture) {
                                DockGesture.Pop -> {
                                    val s = popScale(p)
                                    scaleX = s
                                    scaleY = s
                                    alpha = arrivalAlpha(p)
                                }
                                DockGesture.Eyelid -> {
                                    scaleY = eyelidScaleY(p)
                                    alpha = arrivalAlpha(p)
                                }
                                DockGesture.Spin -> rotationZ = turns.value
                                DockGesture.Tilt -> rotationZ = tilt.value
                            }
                        },
                )
            }
        }
        Spacer(Modifier.height(FlashSpacing.space4))
        Text(
            text = label,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // The button already carries the action in its description; do not read the label twice.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

// ---- incoming call ---------------------------------------------------------------------------

/**
 * The large Accept / Decline button of the incoming-call row.
 *
 * [ringing] (Accept only) plays the ring loop at HIGH: the handset swings like a ringing phone and a
 * ripple leaves the button, then it rests for the remainder of the cycle. Decline tips its handset onto
 * the cradle while pressed. At MEDIUM / LOW neither runs; the buttons are static and still tick.
 */
@Composable
internal fun FlashIncomingCallButton(
    icon: FlashIconSpec,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    description: String,
    haptic: FlashHaptic,
    ringing: Boolean,
    tipsOnPress: Boolean,
    onClick: () -> Unit,
    size: Dp = 72.dp,
    iconSize: Dp = 32.dp,
) {
    val motion = FlashTheme.motion
    val reduce = motion.reduceMotion
    val doHaptic = rememberFlashHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ringColor = background

    val phase = remember { Animatable(0f) }
    if (ringing && !reduce) {
        LaunchedEffect(Unit) {
            while (true) {
                phase.snapTo(0f)
                phase.animateTo(1f, tween(RING_CYCLE_MILLIS, easing = LinearEasing))
                delay(RING_REST_MILLIS)
            }
        }
    }
    val tilt = animateFloatAsState(
        targetValue = if (tipsOnPress && pressed && !reduce) PRESSED_TILT_DEGREES else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "incomingTilt",
    )

    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                val p = phase.value
                if (ringing && !reduce && p > 0f && p < 1f) {
                    drawCircle(
                        color = ringColor.copy(alpha = rippleAlpha(p) * 0.8f),
                        radius = this.size.minDimension / 2f * (1f + 0.5f * p),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .flashPressScale(interaction)
                .clip(CircleShape)
                .background(background)
                .clickable(interactionSource = interaction, indication = null) {
                    doHaptic(haptic)
                    onClick()
                }
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
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .graphicsLayer {
                        if (reduce) return@graphicsLayer
                        rotationZ = if (ringing) ringWiggleDegrees(phase.value) else tilt.value
                    },
            )
        }
    }
}

private const val RING_CYCLE_MILLIS = 1_100
private const val RING_REST_MILLIS = 1_400L
