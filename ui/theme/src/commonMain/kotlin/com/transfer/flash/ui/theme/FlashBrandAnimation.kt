package com.transfer.flash.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.min

/**
 * The Flash brand animation — a reusable Composables port of `logo-claude/svg/flash-splash-loop.svg`.
 *
 * The bolt "breathes" and three discovery-pulse rings radiate outward on an **infinite,
 * un-timed loop**. It is the animated identity shared by the launch splash and branded
 * loading/empty state surfaces (Bug 4 fix: the animation used to live inside
 * `FlashSplashScreen` and could not be reused).
 *
 * The caller decides when to stop by removing this composable — nothing here enforces a
 * minimum duration. `background = true` fills the canvas with the dark launch gradient
 * (the splash look); `background = false` draws just the centered bolt + rings so it can
 * be embedded at a smaller, non-full-bleed size (e.g. the transfers loading state).
 *
 * Always honors reduce-motion via [FlashTheme.motion]: when animations are disabled the
 * loop collapses to a static centered bolt at rest.
 *
 * @param modifier The drawing box. Pass `Modifier.fillMaxSize()` for the splash, or a
 *   explicit `Modifier.size(...)` for a compact embedded animation (the bolt/rings scale
 *   to the canvas box).
 * @param background Draws the dark vertical launch-gradient fill behind the animation.
 */
@Composable
fun FlashBrandAnimation(
    modifier: Modifier = Modifier,
    background: Boolean = true,
) {
    val (breathe, phase) = rememberFlashBrandPhase()

    Canvas(modifier = modifier.fillMaxSize()) {
        if (background) {
            drawRect(brush = Brush.verticalGradient(listOf(SplashBgTop, SplashBgBottom)))
        }

        val cx = size.width / 2f
        val cy = size.height / 2f
        val unit = min(size.width, size.height)
        val ringBaseRadius = unit * 0.20f

        // Discovery pulse rings: expand 0.55x -> 1.75x, opacity 0 -> 0.5 -> 0, staggered.
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            val eased = PulseEasing.transform(p)
            val scale = 0.55f + (1.75f - 0.55f) * eased
            val opacity = if (p < 0.5f) p * 2f * 0.5f else (1f - p) * 2f * 0.5f
            drawCircle(
                color = RingColor.copy(alpha = opacity.coerceIn(0f, 0.5f)),
                radius = ringBaseRadius * scale,
                center = Offset(cx, cy),
                style = Stroke(width = unit * 0.008f),
            )
        }

        // Soft charge glow behind the bolt, breathing with it.
        val glowAlpha = 0.5f + 0.4f * breathe
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(RingColor.copy(alpha = 0.55f), RingColor.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = unit * 0.30f,
            ),
            radius = unit * 0.30f,
            center = Offset(cx, cy),
            alpha = glowAlpha,
        )

        // The bolt: gentle breathe scale (1.0 .. 1.045).
        val boltScale = 1f + 0.045f * breathe
        val drawUnit = unit * 0.42f // bolt box edge on screen
        scale(scale = boltScale, pivot = Offset(cx, cy)) {
            drawBolt(cx, cy, drawUnit)
        }
    }
}

/**
 * The animated bolt is an infinite loop; under reduce-motion it returns a static bolt at
 * rest so reduced-motion users see a calm, non-moving brand mark.
 */
@Composable
private fun rememberFlashBrandPhase(): FlashBrandPhase {
    if (FlashTheme.motion.reduceMotion) {
        return FlashBrandPhase(breathe = 0f, phase = 0f)
    }

    val transition = rememberInfiniteTransition(label = "flash-brand-animation")

    // Single 0..1 loop phase drives all three rings; each ring is offset by 1/3 so they
    // stagger like the SVG's 0s / 0.8s / 1.6s begins.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = BreatheEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    return FlashBrandPhase(breathe = breathe, phase = phase)
}

private data class FlashBrandPhase(val breathe: Float, val phase: Float)

/** Draws the bolt centered at (cx,cy), filling a [boxSize]-wide 24x24 space. */
private fun DrawScope.drawBolt(cx: Float, cy: Float, boxSize: Float) {
    val k = boxSize / 24f
    val path = Path().apply {
        BOLT_POINTS.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    // Position: translate so the 24x24 box is centered, then scale into screen units.
    translate(left = cx - boxSize / 2f, top = cy - boxSize / 2f) {
        scale(scaleX = k, scaleY = k, pivot = Offset.Zero) {
            drawPath(
                path = path,
                brush = Brush.linearGradient(
                    *BoltStops,
                    start = Offset(4.2f, 2.2f),
                    end = Offset(19.8f, 21.8f),
                ),
            )
        }
    }
}

/**
 * Brand colors for the animation. `FlashPalette` is internal to this module, so the one
 * splash-only surface is inlined here; the bolt/ring stops that exist in the palette are
 * referenced canonically so they stay in sync.
 */
internal object FlashBrandPalette {
    // Dark launch backdrop (flash-splash-loop.svg) — the bottom is exactly graphite900.
    val splashBgBottom = FlashPalette.graphite900
    val splashBgTop = Color(0xFF1F2430)

    // Bolt gradient stop not present in FlashPalette (sits between pulse300/pulse400).
    val boltStart = Color(0xFF4FD1C2)

    val ring = FlashPalette.pulse400

    val boltStops = arrayOf(
        0.0f to boltStart,
        0.5f to FlashPalette.pulse400,
        0.78f to FlashPalette.pulse500,
        1.0f to FlashPalette.spark500,
    )
}

private val SplashBgTop = FlashBrandPalette.splashBgTop
private val SplashBgBottom = FlashBrandPalette.splashBgBottom
private val BoltStops = FlashBrandPalette.boltStops
private val RingColor = FlashBrandPalette.ring

// Bolt authored in a 24x24 box (path from the logo masters).
private val BOLT_POINTS = listOf(
    13.6f to 2.2f, 4.2f to 13.9f, 10.8f to 13.9f, 9.4f to 21.8f, 19.8f to 9.7f, 13.2f to 9.7f,
)

private const val PULSE_MS = 2400
private val PulseEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val BreatheEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)