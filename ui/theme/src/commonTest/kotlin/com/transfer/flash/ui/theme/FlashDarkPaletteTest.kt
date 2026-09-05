package com.transfer.flash.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * UI-035: verifies the authored dark palette against the light palette.
 * Graphite/void rule — never pure black/white app/surface backgrounds in dark.
 */
class FlashDarkPaletteTest {

    private val light = FlashColors.light()
    private val dark = FlashColors.dark()

    // --- Slot-by-slot divergence -------------------------------------------

    @Test
    fun `dark key slots are deliberately different from light`() {
        assertNotEquals(light.accentPrimary, dark.accentPrimary)
        assertNotEquals(light.backgroundApp, dark.backgroundApp)
        assertNotEquals(light.backgroundSurface, dark.backgroundSurface)
        assertNotEquals(light.chatBgIncoming, dark.chatBgIncoming)
        assertNotEquals(light.chatBgOutgoing, dark.chatBgOutgoing)
        assertNotEquals(light.scrim, dark.scrim)
        assertNotEquals(light.mediaViewerBackdrop, dark.mediaViewerBackdrop)
    }

    @Test
    fun `dark scrim is stronger than light scrim`() {
        assertTrue(dark.scrim.alpha > light.scrim.alpha)
    }

    // --- Graphite/void rule -------------------------------------------------

    @Test
    fun `dark backgrounds never use pure black or pure white`() {
        listOf(
            dark.backgroundApp,
            dark.backgroundChat,
            dark.backgroundSurface,
            dark.backgroundSurfaceSubtle,
            dark.backgroundSurfaceStrong,
            dark.chatBgIncoming,
            dark.composerInputBackground,
            dark.sheetSurface,
        ).forEach { color ->
            assertNotEquals(Color.Black, color)
            assertNotEquals(Color.White, color)
        }
    }

    @Test
    fun `dark surfaces layer monotonically above void`() {
        assertTrue(dark.backgroundChat.red > dark.backgroundApp.red)
        assertTrue(dark.backgroundSurface.red > dark.backgroundChat.red)
        assertTrue(dark.backgroundSurfaceStrong.blue > dark.backgroundSurface.blue)
    }

    // --- WCAG contrast guards ------------------------------------------------

    @Test
    fun `dark body text meets 4_5 to 1 on its bubbles and app background`() {
        contrastGuard(dark.chatTextIncoming, dark.chatBgIncoming, 4.5f)
        contrastGuard(dark.chatTextOutgoing, dark.chatBgOutgoing, 4.5f)
        contrastGuard(dark.textPrimary, dark.backgroundApp, 4.5f)
        contrastGuard(light.chatTextIncoming, light.chatBgIncoming, 4.5f)
        contrastGuard(light.chatTextOutgoing, light.chatBgOutgoing, 4.5f)
    }

    @Test
    fun `dark textOnAccent meets 3 to 1 on pulse400 after UI-035 audit fix`() {
        // Regression guard for the audit fix (white was ~2.5:1 on pulse400).
        contrastGuard(dark.textOnAccent, dark.accentPrimary, 3f)
    }

    @Test
    fun `waveform played bars meet 3 to 1 on their bubble background`() {
        // UI-019 voice waveform: played color is chatTextOutgoing on outgoing bubbles,
        // accentPrimary on incoming bubbles.
        contrastGuard(dark.chatTextOutgoing, dark.chatBgOutgoing, 3f)
        contrastGuard(dark.accentPrimary, dark.chatBgIncoming, 3f)
        contrastGuard(light.chatTextOutgoing, light.chatBgOutgoing, 3f)
        contrastGuard(light.accentPrimary, light.chatBgIncoming, 3f)
    }

    private fun contrastGuard(foreground: Color, background: Color, minimum: Float) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            ratio >= minimum,
            "Contrast ${ratio.toTwoDecimals()}:1 < $minimum:1 for $foreground on $background",
        )
    }

    // `"%.2f".format(x)` is `kotlin.text.String.format`, which exists only on JVM-family targets
    // via `java.lang.String.format` — it would compile here today (both targets are JVM) and
    // break the moment a Native target is added, which is exactly the leak R6.1 warns about.
    // Ratios are always >= 1, so integer scaling is exact and needs no locale.
    private fun Float.toTwoDecimals(): String {
        val scaled = (this * 100f).roundToInt()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun relativeLuminance(color: Color): Float =
        0.2126f * linearize(color.red) +
            0.7152f * linearize(color.green) +
            0.0722f * linearize(color.blue)

    private fun linearize(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    // Deliberate sameness, documented not tested elsewhere.
    @Test
    fun `tertiary text tone is intentionally shared across themes`() {
        // graphite500 is a mid-tone that passes ~4.4:1 on both graphite50 and void.
        assertEquals(light.textTertiary, dark.textTertiary)
        assertEquals(light.chatTextTimestamp, dark.chatTextTimestamp)
    }
}
