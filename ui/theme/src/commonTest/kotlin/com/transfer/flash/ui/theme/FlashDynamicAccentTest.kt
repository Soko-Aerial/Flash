package com.transfer.flash.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `enabled on S plus passes accents through and keeps everything else Flash-owned`() {
        val resolved = resolveAccent(
            dynamicAccent = true,
            sdkInt = 31,
            dynamicPrimary = primary,
            dynamicSecondary = secondary,
            fallback = fallback,
        )
        assertEquals(primary, resolved.accentPrimary)
        assertEquals(secondary, resolved.accentSecondary)
        assertEquals(primary, resolved.textLink)

        // Every non-accent slot must be identical to the fallback.
        assertEquals(
            fallback,
            resolved.copy(
                accentPrimary = fallback.accentPrimary,
                accentSecondary = fallback.accentSecondary,
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
}
