package com.transfer.flash.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.theme.FlashColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JVM tests for UI-046 bottom-nav pure math helpers. */
class FlashBottomNavLogicTest {

    @Test
    fun `indicator centers under each of four equal tabs`() {
        val barWidth = 400f
        val indicatorWidth = 56f
        assertEquals(22f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 0, indicatorWidth), 0.001f)
        assertEquals(122f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 1, indicatorWidth), 0.001f)
        assertEquals(222f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 2, indicatorWidth), 0.001f)
        assertEquals(322f, FlashBottomNavMath.indicatorStartPx(barWidth, 4, 3, indicatorWidth), 0.001f)
    }

    @Test
    fun `indicator wider than a tab clamps to bar bounds`() {
        // 2 tabs on 100px bar = 50px tabs; an 80px pill cannot center without bleeding
        // past an edge, so both positions pin to the nearest legal bar bound.
        assertEquals(0f, FlashBottomNavMath.indicatorStartPx(100f, 2, 0, 80f), 0.001f)
        assertEquals(20f, FlashBottomNavMath.indicatorStartPx(100f, 2, 1, 80f), 0.001f)
    }

    @Test
    fun `indicator math rejects invalid geometry`() {
        assertFailsWith<IllegalArgumentException> {
            FlashBottomNavMath.indicatorStartPx(400f, 0, 0, 56f)
        }
        assertFailsWith<IllegalArgumentException> {
            FlashBottomNavMath.indicatorStartPx(400f, 4, 4, 56f)
        }
        assertFailsWith<IllegalArgumentException> {
            FlashBottomNavMath.indicatorStartPx(400f, 4, -1, 56f)
        }
    }

    @Test
    fun `badge counts collapse past nine`() {
        assertEquals("1", FlashBottomNavMath.formatBadgeCount(1))
        assertEquals("9", FlashBottomNavMath.formatBadgeCount(9))
        assertEquals("9+", FlashBottomNavMath.formatBadgeCount(10))
        assertEquals("9+", FlashBottomNavMath.formatBadgeCount(999))
    }

    @Test
    fun `fractional position interpolates between tab centers`() {
        // Halfway between tab 0 (22f) and tab 1 (122f) while the chip is in flight.
        assertEquals(72f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 0.5f, 56f), 0.001f)
        assertEquals(22f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 0f, 56f), 0.001f)
        assertEquals(322f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 3f, 56f), 0.001f)
    }

    @Test
    fun `fractional position clamps overshoot from a bouncy spring`() {
        // A snappy spring overshoots past the target index; the chip must stay on the bar.
        assertEquals(22f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, -0.4f, 56f), 0.001f)
        assertEquals(322f, FlashBottomNavMath.indicatorStartPxAt(400f, 4, 3.4f, 56f), 0.001f)
    }

    @Test
    fun `stretch rests at one and peaks at the boost ceiling`() {
        assertEquals(1f, FlashBottomNavMath.indicatorStretch(0f), 0.001f)
        assertEquals(1f + FlashBottomNavMath.MAX_STRETCH_BOOST, FlashBottomNavMath.indicatorStretch(1f), 0.001f)
        // Out-of-range travel cannot inflate the chip further.
        assertEquals(1f + FlashBottomNavMath.MAX_STRETCH_BOOST, FlashBottomNavMath.indicatorStretch(4f), 0.001f)
        assertEquals(1f, FlashBottomNavMath.indicatorStretch(-2f), 0.001f)
    }

    @Test
    fun `squash thins the chip only while it stretches`() {
        assertEquals(1f, FlashBottomNavMath.indicatorSquash(1f), 0.001f)
        val squash = FlashBottomNavMath.indicatorSquash(1.3f)
        assertTrue(squash in 0.85f..0.9f, "expected partial area conservation, got $squash")
    }

    @Test
    fun `travel amplitude grows with jump distance and ignores direction`() {
        assertEquals(0.45f, FlashBottomNavMath.travelAmplitude(1), 0.001f)
        assertEquals(0.667f, FlashBottomNavMath.travelAmplitude(2), 0.01f)
        assertEquals(1f, FlashBottomNavMath.travelAmplitude(3), 0.001f)
        assertEquals(
            FlashBottomNavMath.travelAmplitude(2),
            FlashBottomNavMath.travelAmplitude(-2),
            0.001f,
        )
    }

    @Test
    fun `tab labels stay single-line without soft wrap inside the 60dp capsule`() {
        assertEquals(1, FlashBottomNavMath.LabelMaxLines)
        assertFalse(FlashBottomNavMath.LabelSoftWrap)
    }

    @Test
    fun `badge colors meet WCAG AA contrast in light, dark, and dynamic themes`() {
        val themes = listOf(
            FlashColors.light(),
            FlashColors.dark(),
            FlashColors.light().withAccent(Color(0xFF6750A4)),
            FlashColors.dark().withAccent(Color(0xFFD0BCFF)),
            FlashColors.dark().copy(onAccentFill = Color.White),
        )
        themes.forEach { colors ->
            val (bg, fg) = FlashBottomNavMath.badgeColors(colors)
            assertTrue(
                contrastRatio(fg, bg) >= 4.5f,
                "Bottom nav badge contrast below AA (bg=$bg, fg=$fg, ratio=${contrastRatio(fg, bg)})",
            )
        }
    }

    @Test
    fun `every bottom nav destination has a distinct signature icon motion style`() {
        val navDestinations = listOf(
            FlashDestination.ChatList,
            FlashDestination.Transfers,
            FlashDestination.Hub,
            FlashDestination.NearbyDevices,
            FlashDestination.Settings,
        )
        val styles = navDestinations.map { FlashBottomNavMath.iconMotionStyle(it) }
        assertEquals(navDestinations.size, styles.toSet().size)
        assertEquals(FlashNavIconMotionStyle.ChatPop, FlashBottomNavMath.iconMotionStyle(FlashDestination.ChatList))
        assertEquals(FlashNavIconMotionStyle.TransferNudge, FlashBottomNavMath.iconMotionStyle(FlashDestination.Transfers))
        assertEquals(FlashNavIconMotionStyle.HubElevate, FlashBottomNavMath.iconMotionStyle(FlashDestination.Hub))
        assertEquals(FlashNavIconMotionStyle.NearbySonar, FlashBottomNavMath.iconMotionStyle(FlashDestination.NearbyDevices))
        assertEquals(FlashNavIconMotionStyle.SettingsGear, FlashBottomNavMath.iconMotionStyle(FlashDestination.Settings))
    }

    @Test
    fun `settings gear turns cumulatively by 90 degrees per click and preserves resting angle`() {
        assertEquals(0f, FlashBottomNavMath.settingsGearAngleDeg(-1), 0.001f)
        assertEquals(0f, FlashBottomNavMath.settingsGearAngleDeg(0), 0.001f)
        assertEquals(90f, FlashBottomNavMath.settingsGearAngleDeg(1), 0.001f)
        assertEquals(180f, FlashBottomNavMath.settingsGearAngleDeg(2), 0.001f)
        assertEquals(360f, FlashBottomNavMath.settingsGearAngleDeg(4), 0.001f)

        val firstClick = FlashBottomNavMath.iconMotionSpec(
            destination = FlashDestination.Settings,
            settingsTurnCount = 1,
        )
        val secondClick = FlashBottomNavMath.iconMotionSpec(
            destination = FlashDestination.Settings,
            settingsTurnCount = 2,
        )
        assertTrue(firstClick.preserveRotationOnStart)
        val firstStart = assertNotNull(firstClick.startSnap)
        assertTrue(firstStart.scale < 1f)
        assertNull(firstClick.peak)
        assertEquals(90f, firstClick.settle.rotationDeg, 0.001f)
        assertEquals(1f, firstClick.settle.scale, 0.001f)
        assertEquals(
            FlashBottomNavMath.SETTINGS_GEAR_STEP_DEG,
            secondClick.settle.rotationDeg - firstClick.settle.rotationDeg,
            0.001f,
        )

        val restingAfterThree = FlashBottomNavMath.iconRestingTransform(
            destination = FlashDestination.Settings,
            settingsTurnCount = 3,
        )
        assertEquals(270f, restingAfterThree.rotationDeg, 0.001f)
        assertEquals(1f, restingAfterThree.scale, 0.001f)
    }

    @Test
    fun `chats plays playful message pop and tilt settle on select and reselect`() {
        val reselect = FlashBottomNavMath.iconMotionSpec(
            destination = FlashDestination.ChatList,
            hopDirection = 0,
        )
        val peak = assertNotNull(reselect.peak)
        val counter = assertNotNull(reselect.counter)
        assertTrue(peak.scale > 1f)
        assertTrue(peak.offsetYDp < 0f)
        assertNotEquals(0f, peak.rotationDeg)
        // Counter-tilt swings opposite to peak before settling at rest.
        assertTrue(peak.rotationDeg * counter.rotationDeg < 0f)
        assertEquals(FlashNavIconTransform(), reselect.settle)

        val hopLeftPeak = assertNotNull(FlashBottomNavMath.iconMotionSpec(FlashDestination.ChatList, hopDirection = -1).peak)
        val hopRightPeak = assertNotNull(FlashBottomNavMath.iconMotionSpec(FlashDestination.ChatList, hopDirection = 1).peak)
        assertTrue(hopLeftPeak.rotationDeg * hopRightPeak.rotationDeg < 0f)
    }

    @Test
    fun `transfers plays bidirectional send-receive nudge and mirrors laterally in RTL`() {
        val ltr = FlashBottomNavMath.iconMotionSpec(FlashDestination.Transfers, isRtl = false)
        val ltrPeak = assertNotNull(ltr.peak)
        val ltrCounter = assertNotNull(ltr.counter)
        assertTrue(ltrPeak.scale > 1f)
        assertTrue(ltrPeak.offsetXDp > 0f && ltrPeak.offsetYDp < 0f)
        assertTrue(ltrCounter.offsetXDp < 0f && ltrCounter.offsetYDp > 0f)
        assertEquals(FlashNavIconTransform(), ltr.settle)

        val rtl = FlashBottomNavMath.iconMotionSpec(FlashDestination.Transfers, isRtl = true)
        val rtlPeak = assertNotNull(rtl.peak)
        val rtlCounter = assertNotNull(rtl.counter)
        assertEquals(-ltrPeak.offsetXDp, rtlPeak.offsetXDp, 0.001f)
        assertEquals(-ltrCounter.offsetXDp, rtlCounter.offsetXDp, 0.001f)
        assertEquals(ltrPeak.offsetYDp, rtlPeak.offsetYDp, 0.001f)
    }

    @Test
    fun `hub plays calm upward elevation settle without tilt`() {
        val hub = FlashBottomNavMath.iconMotionSpec(FlashDestination.Hub)
        val start = assertNotNull(hub.startSnap)
        val peak = assertNotNull(hub.peak)
        assertTrue(start.offsetYDp > 0f)
        assertTrue(peak.offsetYDp < 0f)
        assertTrue(peak.scale > 1f)
        assertEquals(0f, start.rotationDeg, 0.001f)
        assertEquals(0f, peak.rotationDeg, 0.001f)
        assertEquals(0f, peak.offsetXDp, 0.001f)
        assertNull(hub.counter)
        assertEquals(FlashNavIconTransform(), hub.settle)
    }

    @Test
    fun `nearby plays radar sonar micro-tilt oscillation and scale pulse`() {
        val ltr = FlashBottomNavMath.iconMotionSpec(FlashDestination.NearbyDevices, isRtl = false)
        val peak = assertNotNull(ltr.peak)
        val counter = assertNotNull(ltr.counter)
        assertTrue(peak.scale > 1f)
        assertNotEquals(0f, peak.rotationDeg)
        assertTrue(peak.rotationDeg * counter.rotationDeg < 0f)
        assertEquals(FlashNavIconTransform(), ltr.settle)

        val rtl = FlashBottomNavMath.iconMotionSpec(FlashDestination.NearbyDevices, isRtl = true)
        val rtlPeak = assertNotNull(rtl.peak)
        val rtlCounter = assertNotNull(rtl.counter)
        assertEquals(-peak.rotationDeg, rtlPeak.rotationDeg, 0.001f)
        assertEquals(-counter.rotationDeg, rtlCounter.rotationDeg, 0.001f)
    }

    @Test
    fun `reduce motion collapses every destination click and resting transform to identity`() {
        for (destination in FlashDestination.entries) {
            val spec = FlashBottomNavMath.iconMotionSpec(
                destination = destination,
                hopDirection = 1,
                isRtl = false,
                settingsTurnCount = 5,
                reduceMotion = true,
            )
            assertNull(spec.startSnap)
            assertNull(spec.peak)
            assertNull(spec.counter)
            assertFalse(spec.preserveRotationOnStart)
            assertEquals(FlashNavIconTransform(), spec.settle)

            val rest = FlashBottomNavMath.iconRestingTransform(
                destination = destination,
                settingsTurnCount = 5,
                reduceMotion = true,
            )
            assertEquals(FlashNavIconTransform(), rest)
        }
    }

    @Test
    fun `icon transforms stay within safe capsule bounds without clipping`() {
        for (destination in FlashDestination.entries) {
            val spec = FlashBottomNavMath.iconMotionSpec(destination, hopDirection = 1, settingsTurnCount = 1)
            val keyframes = listOfNotNull(spec.startSnap, spec.peak, spec.counter, spec.settle)
            keyframes.forEach { frame ->
                assertTrue(frame.scale in 0.80f..1.15f, "$destination scale out of bounds: ${frame.scale}")
                assertTrue(abs(frame.offsetXDp) <= 4f, "$destination offsetX out of bounds: ${frame.offsetXDp}")
                assertTrue(abs(frame.offsetYDp) <= 4f, "$destination offsetY out of bounds: ${frame.offsetYDp}")
            }
            if (destination != FlashDestination.Settings) {
                assertEquals(FlashNavIconTransform(), FlashBottomNavMath.iconRestingTransform(destination))
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }
}
