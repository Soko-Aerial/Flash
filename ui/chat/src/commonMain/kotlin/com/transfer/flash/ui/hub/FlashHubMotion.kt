package com.transfer.flash.ui.hub

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.flow.first

/*
 * The visual and motion layer of the Hub (2026-10-20 redesign, docs/ui/flash-hub-tab.md "Redesign 2026-10-20").
 *
 * Three modes decide how much of it is drawn, always through the existing flags and never through a Hub setting:
 *   - full:            everything below (gradient washes, pulsing rings, shimmer, count-up, staggered entrances);
 *   - reduced motion:  FlashTheme.motion.reduceMotion (user preference, performance tier floor or platform) - nothing moves,
 *                      every animated value reports its end value at once, the still shapes are still drawn;
 *   - minimal / ultra: FlashTheme.minimalChrome drops gradients and glows, FlashTheme.ultraLowUi also drops shimmer,
 *                      canvas rings that loop and offscreen layers (flat fills only).
 */

/** Gradient washes, glows and pulse rings may be drawn (the ornament budget, ERROR-033). */
@Composable
internal fun hubDecorAllowed(): Boolean = !FlashTheme.minimalChrome && !FlashTheme.ultraLowUi

/** Looping decoration (pulse, shimmer, float) may run. A still frame is drawn instead when this is false. */
@Composable
internal fun hubLoopAllowed(): Boolean = !FlashTheme.motion.reduceMotion && !FlashTheme.minimalChrome && !FlashTheme.ultraLowUi

/** What a colour means on a Hub surface. Always paired with an icon and a word at the call site, never colour alone. */
internal enum class HubTone { SUCCESS, ACCENT, WARNING, DANGER, NEUTRAL }

@Composable
internal fun HubTone.color(): Color {
    val c = FlashTheme.colors
    return when (this) {
        HubTone.SUCCESS -> c.textSuccess
        HubTone.ACCENT -> c.accentPrimary
        HubTone.WARNING -> c.accentSecondary
        HubTone.DANGER -> c.textError
        HubTone.NEUTRAL -> c.textSecondary
    }
}

internal fun FlashHubLeaseTone.hubTone(): HubTone = when (this) {
    FlashHubLeaseTone.HEALTHY -> HubTone.SUCCESS
    FlashHubLeaseTone.SOON -> HubTone.WARNING
    FlashHubLeaseTone.EXPIRED -> HubTone.DANGER
    FlashHubLeaseTone.UNKNOWN -> HubTone.NEUTRAL
}

/**
 * Entrance lift for the [index]-th item of a page: alpha plus a small rise inside the graphics layer (a render pass, not a
 * recomposition). [key] restarts it; pass the page identity. Reduce-motion reports the end value at once.
 */
@Composable
internal fun Modifier.hubStagger(index: Int, key: Any): Modifier {
    if (index < 0) return this
    // Played once per item: a LazyColumn disposes an item that scrolls out and composes it again on the way back, which
    // restarted the Animatable at 0 (cards re-faded on every scroll). The saveable flag survives that, so only the first
    // appearance animates.
    var played by rememberSaveable(key, index) { mutableStateOf(false) }
    if (played) return this
    val progress = FlashTheme.motion.rememberStaggerProgress(index = index, key = key)
    LaunchedEffect(key, index) {
        snapshotFlow { progress.value }.first { it >= 1f }
        played = true
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * (index.coerceAtMost(FlashMotion.MaxStaggerSteps) * 4f + 8f)
    }
}

/**
 * A clickable surface with the Flash press response (scale spring, snap under reduce-motion) and the host's indication.
 * Use instead of a bare `clickable` for every tappable card, row, chip and button of the Hub.
 */
@Composable
internal fun Modifier.hubClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .flashPressScale(source)
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = role, onClick = onClick)
}

/** A 0..1 loop for decoration. A fixed [still] value when looping decoration is not allowed. */
@Composable
internal fun rememberHubPhase(periodMs: Int, still: Float = 0f): State<Float> =
    if (hubLoopAllowed()) {
        rememberInfiniteTransition(label = "hubPhase").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
            label = "hubPhaseValue",
        )
    } else {
        remember(still) { mutableStateOf(still) }
    }

/** The state mark of the Hub: a small square. When [live] it breathes a square halo (full mode only); otherwise it is plain. */
@Composable
internal fun HubLiveDot(color: Color, live: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val phase = rememberHubPhase(periodMs = 1800)
    val ring = live && hubLoopAllowed()
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (ring) {
                    Modifier.drawBehind {
                        val p = phase.value
                        drawCircle(color = color.copy(alpha = (1f - p) * 0.38f), radius = this.size.minDimension / 2f * (1f + p * 1.5f))
                    }
                } else {
                    Modifier
                },
            )
            .clip(HubShapes.capsule)
            .background(color),
    )
}

/**
 * An integer that counts up to [target] the first time it is shown and glides to any later change. Reduce-motion shows
 * the target at once. Reading it recomposes only the text that shows it.
 */
@Composable
internal fun rememberHubCount(target: Int): Int {
    val motion = FlashTheme.motion
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { armed = true }
    val progress by animateFloatAsState(
        targetValue = if (armed || motion.reduceMotion) 1f else 0f,
        animationSpec = if (motion.reduceMotion) snap() else tween(motion.emphasisMillis + 240, easing = FlashMotion.Decelerate),
        label = "hubCountProgress",
    )
    val shown by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = if (motion.reduceMotion) snap() else tween(motion.slowMillis, easing = FlashMotion.Decelerate),
        label = "hubCountTarget",
    )
    return FlashHubVisualMath.countAt(shown.toInt().coerceAtLeast(0), progress)
}

/** A fraction that grows from 0 to [target] the first time it is shown (a bar, a ring) and glides on later changes. */
@Composable
internal fun rememberHubGrow(target: Float, label: String = "hubGrow"): Float {
    val motion = FlashTheme.motion
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { armed = true }
    val value by animateFloatAsState(
        targetValue = if (armed || motion.reduceMotion) target else 0f,
        animationSpec = if (motion.reduceMotion) snap() else tween(motion.emphasisMillis + 120, easing = FlashMotion.Decelerate),
        label = label,
    )
    return value
}

/**
 * The offline-trust meter of the Hub masthead: [ticks] small squares, as many filled as [fraction] of them (a day each for the
 * default 30 day window). Square ends, no ring: the "ledger" look of the 2026-10-20 second redesign. Grows on first show.
 */
@Composable
internal fun HubTickMeter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    ticks: Int = FlashHubVisualMath.LEASE_WINDOW_DAYS,
    height: Dp = 6.dp,
) {
    val grown = rememberHubGrow(fraction, label = "hubMeter")
    val track = FlashTheme.colors.backgroundSurfaceStrong
    Canvas(modifier.fillMaxWidth().height(height)) {
        val gap = 2.dp.toPx()
        val tick = ((size.width - gap * (ticks - 1)) / ticks).coerceAtLeast(1f)
        val filled = (ticks * grown.coerceIn(0f, 1f) + 0.0001f).toInt()
        for (i in 0 until ticks) {
            drawRoundRect(
                color = if (i < filled) color else track,
                topLeft = Offset(i * (tick + gap), 0f),
                size = Size(tick, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** Soft sweep over a skeleton block: a moving highlight in the full mode, nothing otherwise (the flat block remains). */
@Composable
internal fun Modifier.hubShimmer(highlight: Color, phase: State<Float>, enabled: Boolean): Modifier {
    if (!enabled) return this
    // The gradient is built once per size and translated each frame, not rebuilt (a new shader) every frame.
    return drawWithCache {
        val w = size.width
        val brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, highlight, Color.Transparent),
            startX = -w * 0.5f,
            endX = w * 0.5f,
        )
        val band = Size(w, size.height)
        onDrawBehind {
            translate(left = (phase.value * 2.2f - 0.6f) * w) {
                drawRect(brush = brush, topLeft = Offset(-w * 0.5f, 0f), size = band)
            }
        }
    }
}

/** The accent wash behind a hero: a diagonal gradient in the full mode, one flat tint when the ornament budget is spent. */
@Composable
internal fun hubHeroBrush(strong: Float = 0.20f, soft: Float = 0.06f): Brush {
    val c = FlashTheme.colors
    return if (hubDecorAllowed()) {
        Brush.linearGradient(listOf(c.accentPrimary.copy(alpha = strong), c.accentSecondary.copy(alpha = soft), c.accentPrimary.copy(alpha = soft)))
    } else {
        Brush.linearGradient(listOf(c.accentPrimary.copy(alpha = soft + 0.02f), c.accentPrimary.copy(alpha = soft + 0.02f)))
    }
}

/** Keeps the last non-null value, so an overlay can finish its exit animation after the selection that opened it was cleared. */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { HubHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

internal class HubHolder<T : Any> {
    var value: T? = null
}

/**
 * The geometry of the Hub: soft but not round. Cards 16 dp, controls (buttons, fields, rows) 12 dp, chips 6 dp, marks a
 * squircle of 30 % of their side. Capsules only for things that are capsules (switch, progress track, tab underline).
 * Local to the Hub on purpose: the rest of the app keeps its own shapes.
 */
internal object HubShapes {
    val card = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(6.dp)
    val capsule = RoundedCornerShape(50)
    fun mark(size: Dp) = RoundedCornerShape(size * 0.3f)
}

/** Small upper-case label style: group titles, tab counts, stat captions. Callers pass the text already upper-cased. */
@Composable
internal fun hubCapsStyle(): TextStyle = FlashTheme.typography.metadataEmphasis.copy(letterSpacing = 0.8.sp)

/** A hairline rule along the bottom edge of a row. Drawn, not a child, so it costs no layout. */
@Composable
internal fun Modifier.hubRuleBelow(inset: Dp = 0.dp): Modifier {
    val rule = FlashTheme.colors.borderSubtle
    return drawBehind {
        val y = size.height - 0.5.dp.toPx()
        drawLine(rule, Offset(inset.toPx(), y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}

/** The square mark of a person, group or department: initials on a 4 dp tile in the seed's colour (the avatar palette). */
@Composable
internal fun HubMark(initials: String, seed: String, size: Dp, modifier: Modifier = Modifier) {
    val (bg, fg) = com.transfer.flash.ui.avatar.flashAvatarColorsFor(seed)
    Box(
        modifier = modifier.size(size).clip(HubShapes.mark(size)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        com.transfer.flash.ui.theme.FlashText(
            initials.take(2).uppercase(),
            style = if (size >= 48.dp) FlashTheme.typography.headingSmall else FlashTheme.typography.bodyEmphasis,
            color = fg,
            maxLines = 1,
        )
    }
}
