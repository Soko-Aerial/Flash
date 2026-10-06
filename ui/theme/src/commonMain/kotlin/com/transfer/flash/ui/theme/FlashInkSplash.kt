package com.transfer.flash.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Ink launch splash (UI-056, ADR-080, `docs/ui/launch-splash.md`).
 *
 * The word "Flash" is written by hand in Hershey Script ([FlashInkSplashGlyphs]) with ink that runs
 * from teal into amber, a glowing nib leading the pen. The pen then draws the bolt above the word,
 * the bolt fills, and one ripple goes out. The ink keeps a gentle wobble (a "line boil": the points
 * move with smooth noise, eight times a second) and, if the splash is still up after its intro, the
 * bolt breathes. Timing is [FlashInkSplashTimeline]; the host decides when the splash is shown
 * ([FlashLaunchSplashOverlay]).
 *
 * Colours follow the theme: the light or dark ground, the brand accents, or the dynamic accent when
 * that is on. With reduced motion it draws the finished frame without wobble or breathing.
 *
 * The clock is read only inside the draw block (EXP-013), so this composable does not recompose
 * while it animates; geometry, brushes and the four wobble patterns are built once per size in
 * [drawWithCache].
 *
 * @param running false draws only the ground (used while the host is still deciding).
 * @param startElapsedMillis where the clock starts, so a recreated Activity resumes mid-animation.
 */
@Composable
fun FlashInkSplash(
    modifier: Modifier = Modifier,
    running: Boolean = true,
    startElapsedMillis: Long = 0L,
) {
    val palette = FlashInkSplashPalette.from(FlashTheme.colors)
    val reduceMotion = FlashTheme.motion.reduceMotion
    val clock = remember { mutableFloatStateOf(startElapsedMillis / 1000f) }

    LaunchedEffect(running, reduceMotion) {
        if (!running || reduceMotion) return@LaunchedEffect
        val base = clock.floatValue
        val origin = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> clock.floatValue = base + (now - origin) / 1_000_000_000f }
        }
    }

    Spacer(
        modifier
            .fillMaxSize()
            .swallowPointerInput()
            .drawWithCache {
                val scene = InkSplashScene(size.width, size.height)
                val unit = scene.unit
                val ground = Brush.radialGradient(
                    0f to palette.groundCenter,
                    1f to palette.groundEdge,
                    center = Offset(size.width / 2f, size.height * 0.4f),
                    radius = 0.7f * max(size.width, size.height),
                )
                val ink = Brush.horizontalGradient(
                    0f to palette.a1,
                    0.55f to palette.a2,
                    0.9f to palette.a3,
                    1f to palette.a4,
                    startX = scene.wordLeft,
                    endX = scene.wordRight,
                )
                val boltFill = Brush.linearGradient(
                    0f to palette.a1,
                    0.5f to palette.a2,
                    0.78f to palette.a3,
                    1f to palette.a4,
                    start = scene.boltFillStart,
                    end = scene.boltFillEnd,
                )
                val haloRadius = 62f * unit
                val halo = Brush.radialGradient(
                    0f to palette.a1.copy(alpha = 0.32f),
                    1f to palette.a1.copy(alpha = 0f),
                    center = scene.boltCenter,
                    radius = haloRadius,
                )
                val nibGlowRadius = 9f * unit
                val nibGlow = Brush.radialGradient(
                    0f to palette.a4.copy(alpha = 0.75f),
                    0.45f to palette.a4.copy(alpha = 0.5f),
                    1f to palette.a4.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = nibGlowRadius,
                )
                val inkStroke = Stroke(width = 3.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val boltStroke = Stroke(width = 1.4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val rippleStroke = Stroke(width = 1.2f * unit)
                val strokePaths = Array(scene.strokes.size) { Path() }
                val boltLine = Path()
                val boltShape = Path()
                val head = FloatArray(2)

                onDrawBehind {
                    drawRect(ground)
                    if (!running) return@onDrawBehind

                    val still = reduceMotion
                    val t = if (still) STILL_FRAME_TIME else clock.floatValue
                    val pattern = if (still) -1 else wobblePattern(t)
                    val boltEnd = FlashInkSplashTimeline.BOLT_END

                    // Bolt timing: fill f, ripple r, breathing b (all from the demo's Ink option).
                    val f = ((t - FlashInkSplashTimeline.FILL_START) / FlashInkSplashTimeline.FILL_DURATION)
                        .coerceIn(0f, 1f)
                    val r = ((t - boltEnd) / FlashInkSplashTimeline.RIPPLE_DURATION).coerceIn(0f, 1f)
                    val b = if (still) 0f else sin((t - boltEnd) * 2.4f)

                    if (f > 0f) {
                        drawCircle(halo, radius = haloRadius, center = scene.boltCenter, alpha = f * (0.8f + 0.2f * b))
                    }
                    if (r > 0f && r < 1f) {
                        drawCircle(
                            color = palette.a1,
                            radius = (24f + 34f * r) * unit,
                            center = scene.boltCenter,
                            alpha = 0.55f * (1f - r),
                            style = rippleStroke,
                        )
                    }

                    val boltProgress = FlashInkSplashTimeline.ease(
                        (t - FlashInkSplashTimeline.BOLT_START) / FlashInkSplashTimeline.BOLT_DURATION,
                    )
                    if (boltProgress > 0f) {
                        val outline = scene.bolt
                        val coords = scene.boltCoords(pattern)
                        scale(1f + 0.035f * f * b, pivot = scene.boltCenter) {
                            if (f > 0f) {
                                outline.trace(outline.length, coords, boltShape)
                                boltShape.close()
                                drawPath(boltShape, boltFill, alpha = f)
                            }
                            outline.trace(outline.length * boltProgress, coords, boltLine)
                            drawPath(boltLine, palette.a2, style = boltStroke)
                        }
                    }

                    // The name, stroke by stroke. A stroke the pen has not reached is not drawn at all,
                    // so its round cap never shows as a dot before it starts.
                    var inked = false
                    var penDown = false
                    for (i in scene.strokes.indices) {
                        val e = scene.schedule.progress(i, t)
                        if (e <= 0f) continue
                        inked = true
                        val line = scene.strokes[i]
                        val coords = scene.strokeCoords(i, pattern)
                        line.trace(line.length * e, coords, strokePaths[i])
                        drawPath(strokePaths[i], ink, style = inkStroke)
                        if (e < 1f) {
                            line.headAt(line.length * e, coords, head)
                            penDown = true
                        } else if (!penDown) {
                            // Between strokes the nib rests where the last finished stroke ended.
                            head[0] = coords[coords.size - 2]
                            head[1] = coords[coords.size - 1]
                        }
                    }

                    val writeEnd = scene.schedule.end
                    val nib = when {
                        !inked -> 0f
                        penDown || t <= writeEnd -> 1f
                        else -> (1f - (t - writeEnd) / FlashInkSplashTimeline.NIB_FADE).coerceIn(0f, 1f)
                    }
                    if (nib > 0f && !still) {
                        translate(head[0], head[1]) {
                            drawCircle(nibGlow, radius = nibGlowRadius, center = Offset.Zero, alpha = 0.8f * nib)
                            drawCircle(palette.a4, radius = 2f * unit, center = Offset.Zero, alpha = nib)
                        }
                    }
                }
            },
    )
}

/** Reduced motion and the end of the intro both draw this moment: written, filled, ripple gone. */
private const val STILL_FRAME_TIME = FlashInkSplashTimeline.BOLT_END + FlashInkSplashTimeline.RIPPLE_DURATION + 0.1f

private fun wobblePattern(t: Float): Int =
    floor(t * FlashInkSplashTimeline.WOBBLE_FPS).toInt().mod(FlashInkSplashTimeline.WOBBLE_PATTERNS)

/** The splash must not let a tap through to the app it hides (it plays to the end, ADR-080). */
private fun Modifier.swallowPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The Ink splash colours. The ground is the theme's own (light or dark, never the accent). The ink
 * and bolt use the exact brand stops when the accent is Flash's own, and stops derived from the
 * accent when a dynamic accent is on; then the ink ends in the accent rather than in amber, as the
 * demo did for its violet and rose accents.
 */
@Immutable
internal data class FlashInkSplashPalette(
    val groundCenter: Color,
    val groundEdge: Color,
    val a1: Color,
    val a2: Color,
    val a3: Color,
    val a4: Color,
) {
    companion object {
        fun from(colors: FlashColors): FlashInkSplashPalette {
            val dark = colors.backgroundApp.luminance() < 0.5f
            val groundCenter = if (dark) Color(0xFF151A26) else FlashPalette.white
            val groundEdge = if (dark) FlashPalette.void else Color(0xFFE6EAF0)
            val brandAccent = if (dark) FlashPalette.pulse400 else FlashPalette.pulse500
            val accent = colors.accentPrimary
            return if (accent == brandAccent) {
                FlashInkSplashPalette(
                    groundCenter = groundCenter,
                    groundEdge = groundEdge,
                    a1 = if (dark) FlashBrandPalette.boltStart else FlashPalette.pulse400,
                    a2 = accent,
                    a3 = if (dark) FlashPalette.pulse500 else FlashPalette.pulse600,
                    a4 = FlashPalette.spark500,
                )
            } else {
                FlashInkSplashPalette(
                    groundCenter = groundCenter,
                    groundEdge = groundEdge,
                    a1 = lerp(accent, Color.White, 0.25f),
                    a2 = accent,
                    a3 = lerp(accent, Color.Black, 0.2f),
                    a4 = accent,
                )
            }
        }
    }
}

/**
 * Where everything sits, in pixels, for one canvas size. Laid out in "scene units": the demo's phone
 * was 200 x 400 units with the bolt at (100, 142) and the word, 160 wide, at (100, 218). One unit is
 * `min(width / 200, height / 300)`, so a phone in portrait matches the demo and a wide desktop window
 * gets a larger mark that still fits its height.
 */
internal class InkSplashScene(width: Float, height: Float) {
    val unit: Float = min(width / 200f, height / 300f)
    private val cx = width / 2f
    private val cy = height / 2f

    val boltCenter: Offset = Offset(cx, cy - 58f * unit)
    private val boltSize = 40f * unit

    private val wordWidth = 160f * unit
    private val fontScale = wordWidth / FlashInkSplashGlyphs.WIDTH
    val wordLeft: Float = cx - wordWidth / 2f
    val wordRight: Float = cx + wordWidth / 2f
    val wordTop: Float = cy + 18f * unit - FlashInkSplashGlyphs.HEIGHT * fontScale / 2f
    val wordBottom: Float = wordTop + FlashInkSplashGlyphs.HEIGHT * fontScale

    val strokes: List<InkPolyline> = FlashInkSplashGlyphs.strokes.map { raw ->
        val pts = FloatArray(raw.size)
        for (i in raw.indices step 2) {
            pts[i] = wordLeft + raw[i] * fontScale
            pts[i + 1] = wordTop + raw[i + 1] * fontScale
        }
        InkPolyline(flattenCatmullRom(pts, CURVE_SAMPLES))
    }

    val schedule: InkWriterSchedule = InkWriterSchedule(FloatArray(strokes.size) { strokes[it].length })

    /** The bolt outline, closed, each edge split so the wobble can bend it a little. */
    val bolt: InkPolyline

    /** The bolt fill gradient runs corner to corner of the bolt's own bounds, like the logo. */
    val boltFillStart: Offset
    val boltFillEnd: Offset

    init {
        val k = boltSize / 24f
        val ox = boltCenter.x - 12f * k
        val oy = boltCenter.y - 12f * k
        val corners = BOLT_POINTS + BOLT_POINTS.first()
        val pts = FloatArray((corners.size - 1) * BOLT_EDGE_STEPS * 2 + 2)
        var n = 0
        for (c in 0 until corners.size - 1) {
            val (x0, y0) = corners[c]
            val (x1, y1) = corners[c + 1]
            for (s in 0 until BOLT_EDGE_STEPS) {
                val u = s.toFloat() / BOLT_EDGE_STEPS
                pts[n++] = ox + (x0 + (x1 - x0) * u) * k
                pts[n++] = oy + (y0 + (y1 - y0) * u) * k
            }
        }
        pts[n++] = pts[0]
        pts[n] = pts[1]
        bolt = InkPolyline(pts)
        boltFillStart = Offset(ox + 4.2f * k, oy + 2.2f * k)
        boltFillEnd = Offset(ox + 19.8f * k, oy + 21.8f * k)
    }

    private val strokePatterns: List<List<FloatArray>> = List(FlashInkSplashTimeline.WOBBLE_PATTERNS) { p ->
        strokes.map { wobble(it.points, p, unit) }
    }
    // The noise depends only on position, so the closing point still lands on the first one.
    private val boltPatterns: List<FloatArray> = List(FlashInkSplashTimeline.WOBBLE_PATTERNS) { p ->
        wobble(bolt.points, p, unit)
    }

    /** Stroke [index]'s points for wobble [pattern]; -1 is the still, undisplaced line. */
    fun strokeCoords(index: Int, pattern: Int): FloatArray =
        if (pattern < 0) strokes[index].points else strokePatterns[pattern][index]

    fun boltCoords(pattern: Int): FloatArray = if (pattern < 0) bolt.points else boltPatterns[pattern]

    private companion object {
        const val CURVE_SAMPLES = 10
        const val BOLT_EDGE_STEPS = 6
    }
}

/**
 * A pen line: points `x0, y0, x1, y1, ...` with their running length, so the line can be drawn up
 * to any distance along it. Drawing may use a wobbled copy of the points ([coords], same count):
 * the distance is always measured on the clean line, so the wobble never changes the pen's pace.
 */
internal class InkPolyline(val points: FloatArray) {
    private val count = points.size / 2
    val cumulative: FloatArray = FloatArray(count).also { cum ->
        for (i in 1 until count) {
            val dx = points[2 * i] - points[2 * i - 2]
            val dy = points[2 * i + 1] - points[2 * i - 1]
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy)
        }
    }
    val length: Float = if (count == 0) 0f else cumulative[count - 1]

    /** Writes the first [distance] of the line into [out] as x, y. */
    fun headAt(distance: Float, coords: FloatArray, out: FloatArray) {
        val i = segmentAfter(distance)
        if (i >= count) {
            out[0] = coords[2 * count - 2]
            out[1] = coords[2 * count - 1]
            return
        }
        val span = cumulative[i] - cumulative[i - 1]
        val u = if (span > 0f) (distance - cumulative[i - 1]) / span else 0f
        out[0] = coords[2 * i - 2] + (coords[2 * i] - coords[2 * i - 2]) * u
        out[1] = coords[2 * i - 1] + (coords[2 * i + 1] - coords[2 * i - 1]) * u
    }

    /** Replaces [path] with the line drawn from its start to [distance] along it. */
    fun trace(distance: Float, coords: FloatArray, path: Path) {
        path.reset()
        if (count == 0) return
        path.moveTo(coords[0], coords[1])
        val i = segmentAfter(distance)
        for (j in 1 until min(i, count)) path.lineTo(coords[2 * j], coords[2 * j + 1])
        if (i < count) {
            val span = cumulative[i] - cumulative[i - 1]
            val u = if (span > 0f) (distance - cumulative[i - 1]) / span else 0f
            path.lineTo(
                coords[2 * i - 2] + (coords[2 * i] - coords[2 * i - 2]) * u,
                coords[2 * i - 1] + (coords[2 * i + 1] - coords[2 * i - 1]) * u,
            )
        }
    }

    /** The first point index whose running length is past [distance]; [count] if none is. */
    private fun segmentAfter(distance: Float): Int {
        var i = 1
        while (i < count && cumulative[i] <= distance) i++
        return i
    }
}

/**
 * Smooths a polyline into Catmull-Rom curves and samples each into [samples] points: the same
 * smoothing the approved demo used (cubic Béziers with control points at a sixth of the tangent).
 * Lines of fewer than three points stay straight.
 */
internal fun flattenCatmullRom(points: FloatArray, samples: Int): FloatArray {
    val n = points.size / 2
    if (n < 3) return points.copyOf()
    val out = FloatArray(2 + (n - 1) * samples * 2)
    out[0] = points[0]
    out[1] = points[1]
    var o = 2
    for (i in 0 until n - 1) {
        val p0 = max(i - 1, 0)
        val p3 = min(i + 2, n - 1)
        val x1 = points[2 * i]
        val y1 = points[2 * i + 1]
        val x2 = points[2 * i + 2]
        val y2 = points[2 * i + 3]
        val c1x = x1 + (x2 - points[2 * p0]) / 6f
        val c1y = y1 + (y2 - points[2 * p0 + 1]) / 6f
        val c2x = x2 - (points[2 * p3] - x1) / 6f
        val c2y = y2 - (points[2 * p3 + 1] - y1) / 6f
        for (s in 1..samples) {
            val t = s.toFloat() / samples
            val m = 1f - t
            val a = m * m * m
            val b = 3f * m * m * t
            val c = 3f * m * t * t
            val d = t * t * t
            out[o++] = a * x1 + b * c1x + c * c2x + d * x2
            out[o++] = a * y1 + b * c1y + c * c2y + d * y2
        }
    }
    return out
}

/** Gentle wobble: the largest a point moves, in scene units (the demo's "gentle" boil). */
internal const val WOBBLE_AMPLITUDE: Float = 1.1f

/** Noise frequency per scene unit, the demo's feTurbulence baseFrequency. */
private const val WOBBLE_FREQUENCY = 0.045f

/** A copy of [points] moved by smooth noise pattern [pattern]; nearby points move together. */
internal fun wobble(points: FloatArray, pattern: Int, unit: Float): FloatArray {
    val out = FloatArray(points.size)
    val amplitude = WOBBLE_AMPLITUDE * unit
    val seed = 1 + pattern * 7
    for (i in points.indices step 2) {
        val x = points[i] / unit * WOBBLE_FREQUENCY
        val y = points[i + 1] / unit * WOBBLE_FREQUENCY
        out[i] = points[i] + amplitude * fractalNoise(x, y, seed)
        out[i + 1] = points[i + 1] + amplitude * fractalNoise(x + 57.3f, y + 19.1f, seed + 3)
    }
    return out
}

/** Two octaves of value noise, -1..1. */
internal fun fractalNoise(x: Float, y: Float, seed: Int): Float =
    (valueNoise(x, y, seed) + 0.5f * valueNoise(2f * x, 2f * y, seed + 101)) / 1.5f

private fun valueNoise(x: Float, y: Float, seed: Int): Float {
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val fx = x - x0
    val fy = y - y0
    val sx = fx * fx * (3f - 2f * fx)
    val sy = fy * fy * (3f - 2f * fy)
    val top = lattice(x0, y0, seed) + (lattice(x0 + 1, y0, seed) - lattice(x0, y0, seed)) * sx
    val bottom = lattice(x0, y0 + 1, seed) + (lattice(x0 + 1, y0 + 1, seed) - lattice(x0, y0 + 1, seed)) * sx
    return top + (bottom - top) * sy
}

/** A repeatable pseudo-random value in -1..1 for one lattice point. */
private fun lattice(ix: Int, iy: Int, seed: Int): Float {
    var h = ix * 374_761_393 + iy * 668_265_263 + seed * 1_442_695_041
    h = (h xor (h ushr 13)) * 1_274_126_177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32_767.5f - 1f
}
