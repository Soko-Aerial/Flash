@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Design-only, self-contained, zero production coupling.
 *
 * NovaTokens is the entry's derived-token layer. Every visual value is composed from the existing Flash
 * design system (`FlashTheme.colors / typography / motion`, `FlashShapes`, spacing and elevation
 * tokens) through alpha blending and surface layering only — NO raw colour is invented, so
 * `docs/ui/design-system.md` stays the single source of colour truth (docs/ui/flash-hub-tab.md
 * "Visual specification"). Every tone also names an icon, so no state is ever colour alone.
 */

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashTheme

/** Five semantic states Nova renders. Each also names an icon and a plain sentence at its call sites. */
public enum class NovaTone {
    /** Healthy: connected, verified, sealed. */
    OK,

    /** The Flash accent: interactive, informational, "this is you". */
    INFO,

    /** Attention: degraded, expiring, needs action soon (calm, never scary). */
    WARN,

    /** Broken: revoked, expired, refused. */
    FAIL,

    /** Neutral structural information. */
    NEUTRAL,
}

/** One tint job: the strong dot/line colour, a soft fill and a hairline edge, all from one token. */
internal data class NovaWash(
    val strong: Color,
    val soft: Color,
    val edge: Color,
    val fill: Color,
) {
    val text: Color get() = strong
}

/**
 * Maps a tone onto Flash's semantic palette. Drawn only through alpha blends of tokens that already
 * exist on [com.transfer.flash.ui.theme.FlashColors].
 */
@Composable
internal fun NovaTone.wash(): NovaWash = when (this) {
    NovaTone.OK -> {
        val c = FlashTheme.colors
        NovaWash(
            strong = c.statusOnline,
            soft = c.statusOnline.copy(alpha = 0.14f),
            edge = c.statusOnline.copy(alpha = 0.55f),
            fill = c.statusOnline.copy(alpha = 0.10f),
        )
    }
    NovaTone.INFO -> {
        val c = FlashTheme.colors
        NovaWash(
            strong = c.accentPrimary,
            soft = c.accentPrimary.copy(alpha = 0.15f),
            edge = c.accentPrimary.copy(alpha = 0.45f),
            fill = c.accentPrimary.copy(alpha = 0.08f),
        )
    }
    NovaTone.WARN -> {
        val c = FlashTheme.colors
        NovaWash(
            strong = c.statusTransfer,
            soft = c.statusTransfer.copy(alpha = 0.16f),
            edge = c.statusTransfer.copy(alpha = 0.50f),
            fill = c.statusTransfer.copy(alpha = 0.10f),
        )
    }
    NovaTone.FAIL -> {
        val c = FlashTheme.colors
        NovaWash(
            strong = c.textError,
            soft = c.textError.copy(alpha = 0.14f),
            edge = c.textError.copy(alpha = 0.50f),
            fill = c.textError.copy(alpha = 0.10f),
        )
    }
    NovaTone.NEUTRAL -> {
        val c = FlashTheme.colors
        NovaWash(
            strong = c.textSecondary,
            soft = c.backgroundSurfaceStrong,
            edge = c.borderStrong,
            fill = c.backgroundSurfaceSubtle,
        )
    }
}

/** The icon a tone's pill / banner / dot row carries (state = icon + text + tone, never colour alone). */
internal fun NovaTone.icon(): FlashIconSpec = when (this) {
    NovaTone.OK -> FlashIcons.Verified
    NovaTone.INFO -> FlashIcons.Encryption
    NovaTone.WARN -> FlashIcons.Clock
    NovaTone.FAIL -> FlashIcons.Failed
    NovaTone.NEUTRAL -> FlashIcons.Relay
}

@Composable
internal fun NovaTone.strong(): Color = wash().strong

@Composable
internal fun NovaTone.soft(): Color = wash().soft

@Composable
internal fun NovaTone.edge(): Color = wash().edge

@Composable
internal fun NovaTone.fill(): Color = wash().fill

// --- Shapes, re-issued from FlashShapes so Nova diverges nowhere -------------------------------------

internal val NovaCardShape: RoundedCornerShape = RoundedCornerShape(FlashShapes.radius16)
internal val NovaHeroShape: RoundedCornerShape = RoundedCornerShape(FlashShapes.radius20)
internal val NovaTileShape: RoundedCornerShape = RoundedCornerShape(FlashShapes.radius12)
internal val NovaHairShape: RoundedCornerShape = RoundedCornerShape(FlashShapes.radius4)
internal val NovaPillShape: RoundedCornerShape = FlashShapes.navBar
internal val NovaChipShape: RoundedCornerShape = FlashShapes.chip

/**
 * Whether the ornament budget allows gradient washes, glows, pulse rings and canvas sweeps.
 * Honours exactly the semantics of `FlashTheme.minimalChrome` (the ornament budget, ERROR-033) and
 * `FlashTheme.ultraLowUi` (no shaders, no offscreen buffers, no loop-drawn canvas) documented on
 * FlashTheme. Where decoration is off this entry draws the flat equivalent — never nothing.
 */
@Composable
internal fun novaDecorAllowed(): Boolean = !FlashTheme.minimalChrome && !FlashTheme.ultraLowUi

/**
 * A 0..1 loop the running decorative sweeps (shimmer, packet flow, pulse ring) read.
 * Reduce-motion ("FlashMotionPolicy": tier floor > user preference > platform) collapses it to a
 * still frame and callers draw the flat equivalent instead.
 */
@Composable
internal fun rememberNovaSweep(): State<Float> {
    val motion = FlashTheme.motion
    return if (motion.reduceMotion || FlashTheme.minimalChrome || FlashTheme.ultraLowUi) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "novaSweep").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
            label = "novaSweepValue",
        )
    }
}

/** Shimmer band progress, back and forth; frozen (still rendered) under reduce-motion. */
@Composable
internal fun rememberNovaShimmer(): State<Float> {
    val motion = FlashTheme.motion
    return if (motion.reduceMotion || FlashTheme.ultraLowUi) {
        remember { mutableStateOf(0.45f) }
    } else {
        rememberInfiniteTransition(label = "novaShimmer").animateFloat(
            initialValue = -0.35f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing), RepeatMode.Restart),
            label = "novaShimmerValue",
        )
    }
}

/**
 * Staggered entrance lift (alpha + a small rise inside the graphics layer, so a stagger costs a render
 * pass and not a recomposition). Uses FlashMotion's shared spec, so reduce-motion is a no-op.
 */
@Composable
internal fun Modifier.novaStagger(index: Int, key: Any): Modifier {
    val progress = FlashTheme.motion.rememberStaggerProgress(index = index, key = key)
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * (index.coerceAtMost(6) * 4f + 8f)
    }
}
