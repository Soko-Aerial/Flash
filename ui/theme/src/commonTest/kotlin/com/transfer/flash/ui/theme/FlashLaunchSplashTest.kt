package com.transfer.flash.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI-056 / ADR-080: the Ink launch splash's timing, gate, geometry, wobble, colours and letter data. */
class FlashLaunchSplashTest {

    // ---- Timeline -------------------------------------------------------------------------------

    @Test
    fun `the intro ends after the bolt is filled and lasts about three and a half seconds`() {
        assertEquals(3.45f, FlashInkSplashTimeline.INTRO_END, 0.001f)
        assertTrue(FlashInkSplashTimeline.INTRO_END > FlashInkSplashTimeline.FILL_END)
        assertEquals(3_450L, FlashInkSplashTimeline.introEndMillis(reduceMotion = false))
        assertEquals(800L, FlashInkSplashTimeline.introEndMillis(reduceMotion = true))
    }

    @Test
    fun `the splash always plays to the end and then waits for the engine up to the ceiling`() {
        // Ready before the intro ends: it still plays to the end.
        assertEquals(3_450L, FlashInkSplashTimeline.finishAtMillis(reduceMotion = false, engineSettled = true))
        // Not ready: it waits, but never past the 6 s ceiling (ERROR-034).
        assertEquals(6_000L, FlashInkSplashTimeline.finishAtMillis(reduceMotion = false, engineSettled = false))
        assertEquals(800L, FlashInkSplashTimeline.finishAtMillis(reduceMotion = true, engineSettled = true))
        assertEquals(6_000L, FlashInkSplashTimeline.finishAtMillis(reduceMotion = true, engineSettled = false))
    }

    @Test
    fun `the pen easing starts at zero, ends at one and never goes backwards`() {
        assertEquals(0f, FlashInkSplashTimeline.ease(-1f))
        assertEquals(0f, FlashInkSplashTimeline.ease(0f))
        assertEquals(0.5f, FlashInkSplashTimeline.ease(0.5f), 0.0001f)
        assertEquals(1f, FlashInkSplashTimeline.ease(1f))
        assertEquals(1f, FlashInkSplashTimeline.ease(2f))
        var last = 0f
        for (i in 1..100) {
            val e = FlashInkSplashTimeline.ease(i / 100f)
            assertTrue(e >= last, "ease went backwards at ${i / 100f}")
            last = e
        }
    }

    @Test
    fun `strokes are written one after another at one pen speed with a lift between them`() {
        val schedule = InkWriterSchedule(floatArrayOf(1f, 2f, 1f), start = 0.3f, duration = 1.9f, lift = 0.08f)
        assertEquals(0.3f, schedule.starts[0], 0.0001f)
        assertEquals(2.2f, schedule.end, 0.0001f)
        // Same speed: the stroke twice as long takes twice as long.
        assertEquals(2f * schedule.durations[0], schedule.durations[1], 0.0001f)
        // Each stroke starts one lift after the previous one ends.
        for (i in 1 until 3) {
            assertEquals(schedule.starts[i - 1] + schedule.durations[i - 1] + 0.08f, schedule.starts[i], 0.0001f)
        }
        assertEquals(0f, schedule.progress(1, schedule.starts[1]))
        assertEquals(1f, schedule.progress(1, schedule.starts[1] + schedule.durations[1]), 0.0001f)
        assertEquals(0f, schedule.progress(2, 0f))
    }

    @Test
    fun `the real word is written in the planned time`() {
        val scene = InkSplashScene(400f, 800f)
        assertEquals(FlashInkSplashTimeline.WRITE_END, scene.schedule.end, 0.001f)
        assertTrue(scene.schedule.durations.all { it > 0.04f }, "a stroke hit the minimum duration")
    }

    // ---- Gate -----------------------------------------------------------------------------------

    private class FakeClock(var now: Long = 1_000L) {
        fun read(): Long = now
    }

    @Test
    fun `the first decision wins and a playing splash counts its time`() {
        val clock = FakeClock()
        val gate = FlashLaunchSplashGate(clock::read)
        assertEquals(FlashLaunchSplashGate.Phase.Pending, gate.phase)
        assertEquals(0L, gate.elapsedMillis)

        gate.decide(enabled = true)
        assertEquals(FlashLaunchSplashGate.Phase.Playing, gate.phase)
        clock.now += 1_234L
        assertEquals(1_234L, gate.elapsedMillis)

        // A recreated Activity or a reopened window decides again: nothing changes.
        gate.decide(enabled = false)
        gate.skip()
        assertEquals(FlashLaunchSplashGate.Phase.Playing, gate.phase)

        gate.finish()
        assertEquals(FlashLaunchSplashGate.Phase.Finished, gate.phase)
        assertEquals(0L, gate.elapsedMillis)
        gate.decide(enabled = true)
        assertEquals(FlashLaunchSplashGate.Phase.Finished, gate.phase)
    }

    @Test
    fun `a skipped splash stays skipped`() {
        val gate = FlashLaunchSplashGate(FakeClock()::read)
        gate.skip()
        assertEquals(FlashLaunchSplashGate.Phase.Skipped, gate.phase)
        gate.decide(enabled = true)
        gate.finish()
        assertEquals(FlashLaunchSplashGate.Phase.Skipped, gate.phase)
    }

    @Test
    fun `the setting turned off skips the splash`() {
        val gate = FlashLaunchSplashGate(FakeClock()::read)
        gate.decide(enabled = false)
        assertEquals(FlashLaunchSplashGate.Phase.Skipped, gate.phase)
    }

    @Test
    fun `a clock that steps back never gives a negative elapsed time`() {
        val clock = FakeClock()
        val gate = FlashLaunchSplashGate(clock::read)
        gate.decide(enabled = true)
        clock.now -= 500L
        assertEquals(0L, gate.elapsedMillis)
    }

    // ---- Lines ----------------------------------------------------------------------------------

    @Test
    fun `a line can be drawn up to any distance along it`() {
        val line = InkPolyline(floatArrayOf(0f, 0f, 3f, 4f, 3f, 14f))
        assertContentEquals(floatArrayOf(0f, 5f, 15f), line.cumulative)
        assertEquals(15f, line.length)
        val out = FloatArray(2)
        line.headAt(0f, line.points, out)
        assertContentEquals(floatArrayOf(0f, 0f), out)
        line.headAt(2.5f, line.points, out)
        assertEquals(1.5f, out[0], 0.0001f)
        assertEquals(2f, out[1], 0.0001f)
        line.headAt(10f, line.points, out)
        assertEquals(3f, out[0], 0.0001f)
        assertEquals(9f, out[1], 0.0001f)
        line.headAt(99f, line.points, out)
        assertContentEquals(floatArrayOf(3f, 14f), out)
    }

    @Test
    fun `the head follows a wobbled copy of the line at the clean line's distance`() {
        val line = InkPolyline(floatArrayOf(0f, 0f, 10f, 0f))
        val moved = floatArrayOf(0f, 1f, 10f, 1f)
        val out = FloatArray(2)
        line.headAt(5f, moved, out)
        assertContentEquals(floatArrayOf(5f, 1f), out)
    }

    @Test
    fun `smoothing passes through every original point and leaves short lines straight`() {
        val pts = floatArrayOf(0f, 0f, 10f, 5f, 20f, 0f, 30f, 10f)
        val samples = 10
        val flat = flattenCatmullRom(pts, samples)
        assertEquals(2 + 3 * samples * 2, flat.size)
        for (i in 0 until 4) {
            val o = 2 * i * samples
            assertEquals(pts[2 * i], flat[o], 0.001f)
            assertEquals(pts[2 * i + 1], flat[o + 1], 0.001f)
        }
        val bar = floatArrayOf(252f, 316f, 535f, 316f)
        assertContentEquals(bar, flattenCatmullRom(bar, samples))
    }

    // ---- Wobble ---------------------------------------------------------------------------------

    @Test
    fun `the wobble is gentle, repeatable, smooth and different for each pattern`() {
        val unit = 2f
        val pts = FloatArray(400) { it * 1.7f }
        val a = wobble(pts, 0, unit)
        assertContentEquals(a, wobble(pts, 0, unit))
        val b = wobble(pts, 1, unit)
        assertFalse(a.contentEquals(b))
        for (i in pts.indices) {
            assertTrue(abs(a[i] - pts[i]) <= WOBBLE_AMPLITUDE * unit + 0.0001f, "point $i moved too far")
        }
        // Two points a tenth of a unit apart move almost together, so the line bends rather than breaks.
        val near = floatArrayOf(100f, 100f, 100.2f, 100f)
        val moved = wobble(near, 2, unit)
        val dx0 = moved[0] - near[0]
        val dx1 = moved[2] - near[2]
        assertTrue(abs(dx0 - dx1) < 0.05f * unit, "neighbours moved apart: $dx0 vs $dx1")
    }

    @Test
    fun `the noise stays between minus one and one`() {
        for (i in 0 until 2_000) {
            val n = fractalNoise(i * 0.137f - 50f, i * 0.071f + 3f, i % 9)
            assertTrue(n in -1f..1f, "noise $n out of range")
        }
    }

    // ---- Layout ---------------------------------------------------------------------------------

    @Test
    fun `a phone in portrait matches the demo's proportions`() {
        val scene = InkSplashScene(400f, 800f)
        assertEquals(2f, scene.unit)
        assertEquals(320f, scene.wordRight - scene.wordLeft, 0.01f)
        assertEquals(200f, (scene.wordLeft + scene.wordRight) / 2f, 0.01f)
        assertEquals(400f - 116f, scene.boltCenter.y, 0.01f)
    }

    @Test
    fun `the bolt sits above the word and everything fits on screen at every shape`() {
        for ((w, h) in listOf(400f to 800f, 800f to 400f, 1200f to 800f, 640f to 480f, 2560f to 1440f)) {
            val scene = InkSplashScene(w, h)
            val boltBottom = scene.boltCenter.y + 20f * scene.unit
            val boltTop = scene.boltCenter.y - 20f * scene.unit
            assertTrue(boltBottom < scene.wordTop, "bolt overlaps the word at $w x $h")
            assertTrue(boltTop >= 0f && scene.wordBottom <= h, "splash leaves the screen at $w x $h")
            assertTrue(scene.wordLeft >= 0f && scene.wordRight <= w, "word leaves the screen at $w x $h")
            for (line in scene.strokes) {
                for (i in line.points.indices step 2) {
                    assertTrue(line.points[i] in 0f..w && line.points[i + 1] in 0f..h)
                }
            }
        }
    }

    @Test
    fun `the bolt outline is closed and every wobble keeps it closed`() {
        val scene = InkSplashScene(400f, 800f)
        for (pattern in -1 until FlashInkSplashTimeline.WOBBLE_PATTERNS) {
            val c = scene.boltCoords(pattern)
            assertEquals(c[0], c[c.size - 2])
            assertEquals(c[1], c[c.size - 1])
        }
    }

    // ---- Colours --------------------------------------------------------------------------------

    @Test
    fun `the brand theme uses the demo's exact ink and bolt stops`() {
        val light = FlashInkSplashPalette.from(FlashColors.light())
        assertEquals(Color(0xFF1FB8A6), light.a1)
        assertEquals(Color(0xFF0D9488), light.a2)
        assertEquals(Color(0xFF0A7A70), light.a3)
        assertEquals(Color(0xFFE8950A), light.a4)
        assertEquals(Color(0xFFFFFFFF), light.groundCenter)

        val dark = FlashInkSplashPalette.from(FlashColors.dark())
        assertEquals(Color(0xFF4FD1C2), dark.a1)
        assertEquals(Color(0xFF1FB8A6), dark.a2)
        assertEquals(Color(0xFF0D9488), dark.a3)
        assertEquals(Color(0xFFE8950A), dark.a4)
        assertEquals(Color(0xFF151A26), dark.groundCenter)
        assertEquals(Color(0xFF07080A), dark.groundEdge)
    }

    @Test
    fun `a dynamic accent tints the ink but never the ground`() {
        val violet = Color(0xFF6D4AE0)
        for (base in listOf(FlashColors.light(), FlashColors.dark())) {
            val brand = FlashInkSplashPalette.from(base)
            val tinted = FlashInkSplashPalette.from(base.withAccent(violet, Color(0xFF8E7CC3)))
            assertEquals(violet, tinted.a2)
            assertEquals(violet, tinted.a4)
            assertTrue(tinted.a1.luminance() > violet.luminance())
            assertTrue(tinted.a3.luminance() < violet.luminance())
            assertEquals(brand.groundCenter, tinted.groundCenter)
            assertEquals(brand.groundEdge, tinted.groundEdge)
        }
    }

    // ---- Letter data ----------------------------------------------------------------------------

    @Test
    fun `the glyph strokes fill exactly their stated box`() {
        val strokes = FlashInkSplashGlyphs.strokes
        assertEquals(9, strokes.size)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (s in strokes) {
            assertTrue(s.size >= 4 && s.size % 2 == 0)
            for (i in s.indices step 2) {
                minX = minOf(minX, s[i]); maxX = maxOf(maxX, s[i])
                minY = minOf(minY, s[i + 1]); maxY = maxOf(maxY, s[i + 1])
            }
        }
        assertEquals(0, minX)
        assertEquals(0, minY)
        assertEquals(FlashInkSplashGlyphs.WIDTH, maxX)
        assertEquals(FlashInkSplashGlyphs.HEIGHT, maxY)
    }

    @Test
    fun `no stroke jumps, so the pen never draws a long straight line by mistake`() {
        for (s in FlashInkSplashGlyphs.strokes) {
            for (i in 2 until s.size step 2) {
                val jump = hypot((s[i] - s[i - 2]).toDouble(), (s[i + 1] - s[i - 1]).toDouble())
                assertTrue(jump < 300.0, "a ${jump.toInt()}-unit jump inside a stroke")
            }
        }
    }
}
