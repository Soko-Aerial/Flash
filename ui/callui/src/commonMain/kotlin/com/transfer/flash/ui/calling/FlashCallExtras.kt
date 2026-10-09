@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.calling.FlashCallMedia
import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallAudioRoutes
import com.transfer.flash.core.calling.model.FlashCallReaction
import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashLinkQuality
import com.transfer.flash.core.calling.model.linkQuality
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlin.math.abs

/*
 * In-call extras (UI-050f, ADR-067, docs/ui/calling-ui.md): the audio output picker, the "More" panel (reactions, raise
 * hand, data saver, mirror, picture-in-picture), the badges that show what the other end's controls say, the floating
 * reactions, the data-saver pill and the connection / verified chip.
 *
 * Motion policy is the dock's (UI-050e): HIGH animates, MEDIUM / LOW (`reduceMotion`) shows the same information without
 * movement: reactions sit in a still stack instead of floating, the panel appears without sliding. Every animated value
 * is read inside graphicsLayer, so a frame never recomposes.
 */

/** The wording of the extras, pure so it is unit-tested. */
internal object CallExtrasText {
    fun routeLabel(route: FlashCallAudioRoute): String = when (route) {
        FlashCallAudioRoute.EARPIECE -> "Earpiece"
        FlashCallAudioRoute.SPEAKER -> "Speaker"
        FlashCallAudioRoute.BLUETOOTH -> "Bluetooth"
        FlashCallAudioRoute.WIRED -> "Headset"
    }

    fun routeSubtitle(route: FlashCallAudioRoute): String = when (route) {
        FlashCallAudioRoute.EARPIECE -> "Hold the phone to your ear"
        FlashCallAudioRoute.SPEAKER -> "Loudspeaker"
        FlashCallAudioRoute.BLUETOOTH -> "Headset or earbuds"
        FlashCallAudioRoute.WIRED -> "Wired or USB headphones"
    }

    fun reactionLabel(kind: FlashCallReactionKind): String = when (kind) {
        FlashCallReactionKind.LIKE -> "Like"
        FlashCallReactionKind.LOVE -> "Love"
        FlashCallReactionKind.WOW -> "Wow"
    }

    fun reactionAnnouncement(name: String, kind: FlashCallReactionKind): String = "$name reacted: ${reactionLabel(kind)}"

    fun handLabel(raised: Boolean): String = if (raised) "Lower hand" else "Raise hand"
    const val HAND_SUBTITLE: String = "Let everyone see you want to speak"

    const val DATA_SAVER_TITLE: String = "Data saver"
    const val DATA_SAVER_SUBTITLE: String = "Stop receiving video. The call carries on as audio."
    const val DATA_SAVER_PILL: String = "Data saver on"
    const val DATA_SAVER_PILL_ACTION: String = "Turn off"
    fun peerDataSaverNote(name: String): String = "$name is on data saver, so they are not receiving your video"

    const val MIRROR_TITLE: String = "Mirror my video"
    const val MIRROR_SUBTITLE: String = "Flip your own preview like a mirror"
    const val PIP_TITLE: String = "Picture-in-picture"
    const val PIP_SUBTITLE: String = "Keep the call in a small window"

    const val VERIFIED_LABEL: String = "Verified"
    const val VERIFIED_DESCRIPTION: String = "Verified device: you paired with it and its identity was checked"
    const val LINK_LABEL: String = "Local network"

    fun linkDescription(quality: FlashLinkQuality): String = when (quality) {
        FlashLinkQuality.UNKNOWN -> "Local network, link quality not measured yet"
        FlashLinkQuality.GOOD -> "Local network, good link"
        FlashLinkQuality.FAIR -> "Local network, fair link"
        FlashLinkQuality.POOR -> "Local network, poor link"
    }

    fun badgeDescription(micMuted: Boolean, cameraOff: Boolean, handRaised: Boolean): String = buildList {
        if (micMuted) add("Microphone off")
        if (cameraOff) add("Camera off")
        if (handRaised) add("Hand raised")
    }.joinToString(", ")
}

internal fun routeIcon(route: FlashCallAudioRoute): FlashIconSpec = when (route) {
    FlashCallAudioRoute.EARPIECE -> FlashIcons.Earpiece
    FlashCallAudioRoute.SPEAKER -> FlashIcons.Speaker
    FlashCallAudioRoute.BLUETOOTH -> FlashIcons.Bluetooth
    FlashCallAudioRoute.WIRED -> FlashIcons.Headphones
}

internal fun reactionIcon(kind: FlashCallReactionKind): FlashIconSpec = when (kind) {
    FlashCallReactionKind.LIKE -> FlashIcons.ThumbUp
    FlashCallReactionKind.LOVE -> FlashIcons.Heart
    FlashCallReactionKind.WOW -> FlashIcons.Bolt
}

// ---- floating reaction math (pure, unit-tested) -----------------------------------------------

/** How long a floating reaction takes to rise and fade; a little shorter than the core's lifetime so it is gone first. */
internal const val REACTION_FLOAT_MILLIS: Int = 3_200

/** Distance a reaction rises, in dp. */
internal const val REACTION_RISE_DP: Float = 220f

/** Opacity at progress [p] (0..1): quick fade-in, hold, then fade out over the last 40 %. */
internal fun reactionFloatAlpha(p: Float): Float {
    val c = p.coerceIn(0f, 1f)
    val fadeIn = (c / 0.08f).coerceAtMost(1f)
    val fadeOut = ((1f - c) / 0.4f).coerceAtMost(1f)
    return (fadeIn * fadeOut).coerceIn(0f, 1f)
}

/** A stable sideways offset in dp for reaction [id], so neighbours do not stack on one line: -45..+44. */
internal fun reactionLaneDp(id: Long): Int = (abs(id * 37L) % 90L).toInt() - 45

// ---- panel ------------------------------------------------------------------------------------

/**
 * A bottom panel over a dimmed screen: the call's own replacement for a Material bottom sheet (UI rules, AGENTS §34).
 * A tap on the dim, the back button and the close glyph dismiss it.
 */
@Composable
internal fun FlashCallPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    FlashBackHandler(enabled = true, onBack = onDismiss)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Close",
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = motion.sheetEnter(),
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(FlashSpacing.space12)
                    .widthIn(max = PANEL_MAX_WIDTH)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FlashShapes.radius24))
                    .background(colors.backgroundSurfaceStrong)
                    // Swallow taps on the panel itself so they do not reach the dim behind it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
                    .semantics { contentDescription = title },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = FlashTheme.typography.headingMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(FlashDimensions.minTouchTarget)
                            .flashPressScale(interaction)
                            .clip(CircleShape)
                            .clickable(interactionSource = interaction, indication = null, onClick = onDismiss)
                            .semantics { role = Role.Button; contentDescription = "Close" },
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Close,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            size = FlashDimensions.iconSm,
                        )
                    }
                }
                Spacer(Modifier.height(FlashSpacing.space8))
                content()
            }
        }
    }
}

private val PANEL_MAX_WIDTH = 480.dp

/** One row of a [FlashCallPanel]. [selected] null = a plain action; true / false = a switch or a radio, see [radio]. */
@Composable
internal fun FlashCallPanelRow(
    icon: FlashIconSpec,
    title: String,
    subtitle: String?,
    selected: Boolean?,
    onClick: () -> Unit,
    radio: Boolean = false,
) {
    val colors = FlashTheme.colors
    val haptic = rememberFlashHaptics()
    val interaction = remember { MutableInteractionSource() }
    val tapped = { haptic(FlashHaptic.Tick); onClick() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .flashPressScale(interaction)
            .then(
                when {
                    selected == null -> Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = tapped,
                    )
                    radio -> Modifier.selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = tapped,
                    )
                    else -> Modifier.toggleable(
                        value = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Switch,
                        onValueChange = { tapped() },
                    )
                },
            )
            .padding(vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected == true) colors.accentPrimary else colors.backgroundSurfaceSubtle),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = icon,
                contentDescription = null,
                tint = if (selected == true) colors.textOnAccent else colors.textPrimary,
                size = FlashDimensions.iconMd,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
            }
        }
        if (selected == true) {
            Spacer(Modifier.width(FlashSpacing.space8))
            // State is the glyph and the word, never colour alone.
            FlashIcon(
                icon = FlashIcons.Check,
                contentDescription = null,
                tint = colors.accentPrimary,
                size = FlashDimensions.iconMd,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** The output picker: every output this phone can use now, the one in force marked. */
@Composable
internal fun FlashAudioRoutePicker(
    routes: FlashCallAudioRoutes,
    onSelect: (FlashCallAudioRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    FlashCallPanel(title = "Audio output", onDismiss = onDismiss) {
        routes.available.forEach { route ->
            FlashCallPanelRow(
                icon = routeIcon(route),
                title = CallExtrasText.routeLabel(route),
                subtitle = CallExtrasText.routeSubtitle(route),
                selected = route == routes.active,
                radio = true,
                onClick = {
                    onSelect(route)
                    onDismiss()
                },
            )
        }
    }
}

/** The "More" panel: reactions, hand, data saver, mirror and picture-in-picture. */
@Composable
internal fun FlashCallMorePanel(
    state: FlashCallUiState,
    mirrorSelf: Boolean,
    onToggleMirror: () -> Unit,
    onSetHandRaised: (Boolean) -> Unit,
    onSendReaction: (FlashCallReactionKind) -> Boolean,
    onSetDataSaver: (Boolean) -> Unit,
    onEnterPictureInPicture: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** ADR-102: opens the picker; null when this device cannot present now. */
    onShareScreen: (() -> Unit)? = null,
    /** ADR-102: stops this device's share; the row shows while [FlashCallUiState.sharing]. */
    onStopShare: () -> Unit = {},
) {
    val colors = FlashTheme.colors
    val haptic = rememberFlashHaptics()
    FlashCallPanel(title = "More", onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FlashCallReactionKind.entries.forEach { kind ->
                val interaction = remember { MutableInteractionSource() }
                val label = CallExtrasText.reactionLabel(kind)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .flashPressScale(interaction)
                            .clip(CircleShape)
                            .background(colors.backgroundSurfaceSubtle)
                            .clickable(interactionSource = interaction, indication = null) {
                                haptic(FlashHaptic.Tick)
                                onSendReaction(kind)
                                onDismiss()
                            }
                            .semantics { role = Role.Button; contentDescription = "Send $label" },
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = reactionIcon(kind),
                            contentDescription = null,
                            tint = reactionTint(kind),
                            size = FlashDimensions.iconLg,
                        )
                    }
                    Spacer(Modifier.height(FlashSpacing.space4))
                    Text(
                        text = label,
                        style = FlashTheme.typography.captionDefault,
                        color = colors.textSecondary,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashCallPanelRow(
            icon = FlashIcons.Hand,
            title = CallExtrasText.handLabel(state.handRaised),
            subtitle = CallExtrasText.HAND_SUBTITLE,
            selected = state.handRaised,
            onClick = { onSetHandRaised(!state.handRaised) },
        )
        if (state.sharing) {
            FlashCallPanelRow(
                icon = FlashIcons.ScreenShare,
                title = CallShareText.ROW_STOP_TITLE,
                subtitle = CallShareText.rowStopSubtitle(state.shareSourceTitle),
                selected = null,
                onClick = {
                    onDismiss()
                    onStopShare()
                },
            )
        } else if (onShareScreen != null) {
            FlashCallPanelRow(
                icon = FlashIcons.ScreenShare,
                title = CallShareText.ROW_START_TITLE,
                subtitle = CallShareText.ROW_START_SUBTITLE,
                selected = null,
                onClick = onShareScreen,
            )
        }
        if (state.video) {
            FlashCallPanelRow(
                icon = FlashIcons.DataSaver,
                title = CallExtrasText.DATA_SAVER_TITLE,
                subtitle = CallExtrasText.DATA_SAVER_SUBTITLE,
                selected = state.dataSaver,
                onClick = { onSetDataSaver(!state.dataSaver) },
            )
            FlashCallPanelRow(
                icon = FlashIcons.Mirror,
                title = CallExtrasText.MIRROR_TITLE,
                subtitle = CallExtrasText.MIRROR_SUBTITLE,
                selected = mirrorSelf,
                onClick = onToggleMirror,
            )
        }
        if (onEnterPictureInPicture != null) {
            FlashCallPanelRow(
                icon = FlashIcons.ChevronDown,
                title = CallExtrasText.PIP_TITLE,
                subtitle = CallExtrasText.PIP_SUBTITLE,
                selected = null,
                onClick = {
                    onDismiss()
                    onEnterPictureInPicture()
                },
            )
        }
    }
}

@Composable
private fun reactionTint(kind: FlashCallReactionKind): Color = when (kind) {
    FlashCallReactionKind.LIKE -> FlashTheme.colors.accentPrimary
    FlashCallReactionKind.LOVE -> FlashTheme.colors.textError
    FlashCallReactionKind.WOW -> FlashTheme.colors.statusTransfer
}

// ---- badges -----------------------------------------------------------------------------------

/**
 * What the other end's controls say, as small round badges: microphone off, camera off, hand raised. Draws nothing when
 * there is nothing to say. The words travel in the semantics, so state is never colour alone.
 */
@Composable
internal fun FlashPeerBadges(
    micMuted: Boolean,
    cameraOff: Boolean,
    handRaised: Boolean,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!micMuted && !cameraOff && !handRaised) return
    val colors = FlashTheme.colors
    val background = if (onDark) Color.Black.copy(alpha = 0.6f) else colors.backgroundSurfaceSubtle
    val tint = if (onDark) Color.White else colors.textPrimary
    Row(
        modifier = modifier.semantics { contentDescription = CallExtrasText.badgeDescription(micMuted, cameraOff, handRaised) },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (micMuted) Badge(FlashIcons.MicOff, background, tint)
        if (cameraOff) Badge(FlashIcons.VideoOff, background, tint)
        if (handRaised) Badge(FlashIcons.Hand, colors.accentPrimary, colors.textOnAccent)
    }
}

@Composable
private fun Badge(icon: FlashIconSpec, background: Color, tint: Color) {
    Box(
        modifier = Modifier.size(BADGE_SIZE).clip(CircleShape).background(background).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(icon = icon, contentDescription = null, tint = tint, size = BADGE_ICON_SIZE)
    }
}

private val BADGE_SIZE = 22.dp
private val BADGE_ICON_SIZE = 14.dp

// ---- reactions --------------------------------------------------------------------------------

/**
 * Reactions on screen now. At HIGH each rises from the bottom and fades; at MEDIUM / LOW they sit in a still stack at
 * the top and leave when the core expires them. Draws nothing and takes no touches.
 */
@Composable
internal fun FlashCallReactionLayer(
    reactions: List<FlashCallReaction>,
    nameOf: (String) -> String,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    val reduce = FlashTheme.motion.reduceMotion
    Box(modifier = modifier) {
        if (reduce) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                reactions.takeLast(MAX_STILL_REACTIONS).forEach { reaction ->
                    key(reaction.id) { ReactionBubble(reaction, nameOf(reaction.peerId)) }
                }
            }
        } else {
            reactions.forEach { reaction ->
                key(reaction.id) { FloatingReaction(reaction, nameOf(reaction.peerId)) }
            }
        }
    }
}

private const val MAX_STILL_REACTIONS = 3

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FloatingReaction(reaction: FlashCallReaction, name: String) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(REACTION_FLOAT_MILLIS, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    ReactionBubble(
        reaction = reaction,
        name = name,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = REACTION_START_BOTTOM)
            .graphicsLayer {
                translationY = -progress.value * REACTION_RISE_DP * density
                translationX = reactionLaneDp(reaction.id) * density
                alpha = reactionFloatAlpha(progress.value)
            },
    )
}

private val REACTION_START_BOTTOM = 150.dp

@Composable
private fun ReactionBubble(reaction: FlashCallReaction, name: String, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier.semantics {
            contentDescription = CallExtrasText.reactionAnnouncement(name, reaction.kind)
            liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.backgroundSurfaceStrong),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = reactionIcon(reaction.kind),
                contentDescription = null,
                tint = reactionTint(reaction.kind),
                size = FlashDimensions.iconLg,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Spacer(Modifier.height(FlashSpacing.space4))
        Text(
            text = name,
            style = FlashTheme.typography.captionDefault,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 96.dp)
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = FlashSpacing.space8, vertical = 2.dp)
                .clearAndSetSemantics { },
        )
    }
}

// ---- data saver pill and link chip --------------------------------------------------------------

/** "Data saver on · Turn off": the way back from a call that stopped receiving video. */
@Composable
internal fun FlashDataSaverPill(onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .flashPressScale(interaction)
            .clip(RoundedCornerShape(FlashShapes.radius24))
            .background(colors.backgroundSurfaceStrong.copy(alpha = if (FlashTheme.minimalChrome) 1f else 0.9f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onTurnOff)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics { contentDescription = "${CallExtrasText.DATA_SAVER_PILL}. ${CallExtrasText.DATA_SAVER_PILL_ACTION}" },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = FlashIcons.DataSaver,
            contentDescription = null,
            tint = colors.textPrimary,
            size = FlashDimensions.iconSm,
        )
        Text(
            text = CallExtrasText.DATA_SAVER_PILL,
            style = FlashTheme.typography.metadataDefault,
            color = colors.textPrimary,
        )
        Text(
            text = CallExtrasText.DATA_SAVER_PILL_ACTION,
            style = FlashTheme.typography.metadataDefault,
            color = colors.accentPrimary,
        )
    }
}

/**
 * ERROR-105: a one-line message about this device's own call media ("Joined without camera…", "Camera stopped"), with
 * an optional text action ("Try again") and an optional dismiss. A polite live region, so a screen reader says it once.
 */
@Composable
internal fun FlashCallMessagePill(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FlashShapes.radius24))
            .background(colors.backgroundSurfaceStrong.copy(alpha = if (FlashTheme.minimalChrome) 1f else 0.9f))
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = FlashTheme.typography.metadataDefault, color = colors.textPrimary)
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = FlashTheme.typography.metadataDefault,
                color = colors.accentPrimary,
                modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
            )
        }
        if (onDismiss != null) {
            Text(
                text = "Dismiss",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss),
            )
        }
    }
}

/**
 * Where the call runs and whether the other device is the one that was paired: "Local network" with a dot graded from
 * the measured link, and a shield when [peerVerified]. Only the pairing check is claimed (see [CallExtrasText.VERIFIED_DESCRIPTION]).
 */
@Composable
internal fun FlashCallLinkChip(
    session: FlashCallMedia?,
    state: FlashCallUiState,
    peerVerified: Boolean,
    onDark: Boolean,
    modifier: Modifier = Modifier,
) {
    if (state.state == FlashCallState.RINGING || state.state == FlashCallState.ENDED) return
    val colors = FlashTheme.colors
    val stats = rememberCallStats(session?.stats)
    val quality = stats?.linkQuality() ?: FlashLinkQuality.UNKNOWN
    val dot = when (quality) {
        FlashLinkQuality.GOOD -> colors.textSuccess
        FlashLinkQuality.FAIR -> colors.statusTransfer
        FlashLinkQuality.POOR -> colors.textError
        FlashLinkQuality.UNKNOWN -> colors.textTertiary
    }
    val text = if (onDark) Color.White.copy(alpha = 0.9f) else colors.textSecondary
    Row(
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
                    add(CallExtrasText.linkDescription(quality))
                    if (peerVerified) add(CallExtrasText.VERIFIED_DESCRIPTION)
                }.joinToString(". ")
            },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (peerVerified) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashIcon(
                    icon = FlashIcons.Verified,
                    contentDescription = null,
                    tint = colors.textSuccess,
                    size = FlashDimensions.iconSm,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(text = CallExtrasText.VERIFIED_LABEL, style = FlashTheme.typography.metadataDefault, color = text, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            Box(Modifier.size(FlashSpacing.space8).clip(CircleShape).background(dot).clearAndSetSemantics { })
            Text(text = CallExtrasText.LINK_LABEL, style = FlashTheme.typography.metadataDefault, color = text, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}
