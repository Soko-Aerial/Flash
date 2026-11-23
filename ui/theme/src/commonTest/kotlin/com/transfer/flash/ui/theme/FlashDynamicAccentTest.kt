package com.transfer.flash.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** UI-036: pure decision logic for dynamic accent tinting (no Robolectric needed). */
class FlashDynamicAccentTest {

    private val fallback = FlashColors.dark()
    private val primary = Color(0xFF7C4DFF)
    private val secondary = Color(0xFF9575FF)

    @Test
    fun `SDK below S ignores dynamic accent even when enabled`() {
        val resolved = resolveAccent(
            dynamicAccent = true,
            sdkInt = 30,
            dynamicPrimary = primary,
            dynamicSecondary = secondary,
            fallback = fallback,
        )
        assertEquals(fallback, resolved)
    }

    @Test
    fun `dynamic accent disabled returns Pulse palette untouched`() {
        val resolved = resolveAccent(
            dynamicAccent = false,
            sdkInt = 34,
            dynamicPrimary = primary,
            dynamicSecondary = secondary,
            fallback = fallback,
        )
        assertEquals(fallback, resolved)
    }

    @Test
    fun `null dynamic color falls back to Pulse palette`() {
        val resolved = resolveAccent(
            dynamicAccent = true,
            sdkInt = 31,
            dynamicPrimary = null,
            dynamicSecondary = null,
            fallback = fallback,
        )
        assertEquals(fallback, resolved)
    }

    @Test
    fun `enabled on S plus adapts accentFill, onAccentFill, and textLink while keeping neutrals Flash-owned`() {
        val resolved = resolveAccent(
            dynamicAccent = true,
            sdkInt = 31,
            dynamicPrimary = primary,
            dynamicSecondary = secondary,
            fallback = fallback,
        )
        assertEquals(primary, resolved.accentPrimary)
        assertEquals(secondary, resolved.accentSecondary)
        assertNotEquals(fallback.accentFill, resolved.accentFill)
        assertTrue(flashContrastRatio(resolved.onAccentFill, resolved.accentFill) >= 4.5f)
        assertTrue(flashContrastRatio(resolved.accentFill, resolved.backgroundSurfaceStrong) >= 4.5f)
        assertTrue(flashContrastRatio(resolved.textLink, resolved.backgroundSurface) >= 4.5f)
        assertTrue(flashContrastRatio(resolved.textLink, resolved.backgroundSurfaceStrong) >= 4.5f)

        // Every non-accent slot must be identical to the fallback.
        assertEquals(
            fallback,
            resolved.copy(
                accentPrimary = fallback.accentPrimary,
                accentSecondary = fallback.accentSecondary,
                accentFill = fallback.accentFill,
                onAccentFill = fallback.onAccentFill,
                textOnAccent = fallback.textOnAccent,
                textLink = fallback.textLink,
            ),
        )
    }

    @Test
    fun `missing dynamic secondary falls back to primary hue`() {
        val resolved = resolveAccent(
            dynamicAccent = true,
            sdkInt = 33,
            dynamicPrimary = primary,
            dynamicSecondary = null,
            fallback = FlashColors.light(),
        )
        assertEquals(primary, resolved.accentSecondary)
    }

    @Test
    fun `withAccent guarantees 4_5 to 1 contrast across extreme light and dark accents`() {
        val accents = listOf(
            Color(0xFFD0BCFF),
            Color(0xFF6750A4),
            Color(0xFF006A60),
            Color(0xFF4CDAD4),
            Color(0xFF0D9488),
            Color(0xFF1FB8A6),
            Color(0xFFFFEB3B),
            Color(0xFF00E5FF),
            Color(0xFF1A237E),
            Color(0xFF7C4DFF),
            Color(0x80FF5722),
            Color.White,
            Color.Black,
        )

        val lightBase = FlashColors.light()
        val darkBase = FlashColors.dark()

        for (accent in accents) {
            val lightResolved = lightBase.withAccent(accent)
            assertEquals(1f, lightResolved.accentPrimary.alpha)
            assertEquals(lightResolved.onAccentFill, lightResolved.textOnAccent)
            assertTrue(
                flashContrastRatio(lightResolved.onAccentFill, lightResolved.accentFill) >= 4.5f,
                "Light accentFill contrast failed for $accent",
            )
            assertTrue(
                flashContrastRatio(lightResolved.textOnAccent, lightResolved.accentFill) >= 4.5f,
                "Light textOnAccent contrast failed for $accent",
            )
            listOf(
                lightResolved.backgroundApp,
                lightResolved.backgroundChat,
                lightResolved.backgroundSurface,
                lightResolved.backgroundSurfaceSubtle,
            ).forEach { bg ->
                assertTrue(
                    flashContrastRatio(lightResolved.textLink, bg) >= 4.5f,
                    "Light textLink contrast failed on $bg for $accent",
                )
            }

            val darkResolved = darkBase.withAccent(accent)
            assertEquals(1f, darkResolved.accentPrimary.alpha)
            assertEquals(darkResolved.onAccentFill, darkResolved.textOnAccent)
            assertTrue(
                flashContrastRatio(darkResolved.onAccentFill, darkResolved.accentFill) >= 4.5f,
                "Dark accentFill contrast failed for $accent",
            )
            assertTrue(
                flashContrastRatio(darkResolved.textOnAccent, darkResolved.accentFill) >= 4.5f,
                "Dark textOnAccent contrast failed for $accent",
            )
            assertTrue(
                flashContrastRatio(darkResolved.accentFill, darkResolved.backgroundSurfaceStrong) >= 4.5f,
                "Dark accentFill surface separation failed for $accent",
            )
            listOf(
                darkResolved.backgroundApp,
                darkResolved.backgroundChat,
                darkResolved.backgroundSurface,
                darkResolved.backgroundSurfaceSubtle,
                darkResolved.backgroundSurfaceStrong,
            ).forEach { bg ->
                assertTrue(
                    flashContrastRatio(darkResolved.textLink, bg) >= 4.5f,
                    "Dark textLink contrast failed on $bg for $accent",
                )
            }
        }

        // Non-Pulse hue must not leave accentFill stuck on Pulse teal.
        assertNotEquals(lightBase.accentFill, lightBase.withAccent(Color(0xFF6750A4)).accentFill)
        assertNotEquals(darkBase.accentFill, darkBase.withAccent(Color(0xFFD0BCFF)).accentFill)
    }

    @Test
    fun `withAccent keeps already AA-compliant accents exact`() {
        val m3LightTeal = Color(0xFF006A60)
        val lightResolved = FlashColors.light().withAccent(m3LightTeal)
        assertEquals(m3LightTeal, lightResolved.accentFill)
        assertEquals(m3LightTeal, lightResolved.textLink)

        val m3DarkPurple = Color(0xFFD0BCFF)
        val darkResolved = FlashColors.dark().withAccent(m3DarkPurple)
        assertEquals(m3DarkPurple, darkResolved.accentFill)
        assertEquals(m3DarkPurple, darkResolved.textLink)
    }
}
