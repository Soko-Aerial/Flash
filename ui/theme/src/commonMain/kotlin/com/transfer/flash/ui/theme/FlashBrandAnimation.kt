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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
 * Both animated values are read **inside the `Canvas` draw block**, never in composition — see
 * [rememberFlashBrandPhase]. This composable therefore does not recompose while it animates; each
 * frame only re-runs the draw lambda.
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
    val (boltBreathe, ringPhase) = rememberFlashBrandPhase()

    Canvas(modifier = modifier.fillMaxSize()) {
        val breathe = boltBreathe.value
        val phase = ringPhase.value

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
 *
 * Hands out the two **[State]s, not their `Float`s** (EXP-013). This is a `@Composable` that returns
 * a value, so it is not restartable: `val phase by transition.animateFloat(…)` recorded the read in
 * the *caller's* scope, which meant [FlashBrandAnimation] recomposed on every frame of a 2.4 s loop —
 * rebuilding its modifier chain, re-allocating the `Canvas` draw lambda and one `FlashBrandPhase`
 * per frame — and then handed the draw block two constants it could not animate on its own.
 *
 * That was the worst place in the app to pay for recomposition: this is the launch splash, so it runs
 * while the whole transport stack is booting, on the device where boot is slowest (ERROR-034 — on the
 * Belfone SCP810 boot outlasts the 6 s splash ceiling). Reading `.value` inside the draw block instead
 * keeps the animation in the draw phase: a new frame invalidates draw only, composition never re-runs,
 * and the CPU the splash used to spend on itself is available to the boot it is covering for.
 *
 * Same easings, same durations, same `PULSE_MS`, same static reduce-motion fallback as before.
 */
@Composable
private fun rememberFlashBrandPhase(): FlashBrandPhase {
    if (FlashTheme.motion.reduceMotion) {
        return remember { FlashBrandPhase(mutableFloatStateOf(0f), mutableFloatStateOf(0f)) }
    }

    val transition = rememberInfiniteTransition(label = "flash-brand-animation")

    // Single 0..1 loop phase drives all three rings; each ring is offset by 1/3 so they
    // stagger like the SVG's 0s / 0.8s / 1.6s begins.
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val breathe = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_MS, easing = BreatheEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )

    return remember(breathe, phase) { FlashBrandPhase(breathe = breathe, phase = phase) }
}

/**
 * Carries the two animation [State]s to the draw block. Deliberately holds `State<Float>` rather
 * than `Float`: unwrapping it here would move the read back into composition and undo the fix
 * described in [rememberFlashBrandPhase].
 */
private data class FlashBrandPhase(val breathe: State<Float>, val phase: State<Float>)

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