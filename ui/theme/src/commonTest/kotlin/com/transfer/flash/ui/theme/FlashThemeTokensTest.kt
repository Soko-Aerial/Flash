package com.transfer.flash.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlashThemeTokensTest {

    @Test
    fun lightColors_haveExpectedValues() {
        val colors = FlashColors.light()
        assertNotNull(colors.accentPrimary)
        assertNotNull(colors.backgroundApp)
        assertNotNull(colors.textPrimary)
        assertEquals(FlashColors.light().accentPrimary, colors.accentPrimary)
    }

    @Test
    fun darkColors_haveExpectedValues() {
        val darkColors = FlashColors.dark()
        assertNotNull(darkColors.accentPrimary)
        assertNotNull(darkColors.backgroundApp)
        assertNotNull(darkColors.textPrimary)
    }

    @Test
    fun spacing_tokensArePositive() {
        // Was `kotlin.assert`, which only exists on JVM (and only fires with `-ea`, which this
        // repo's Gradle test tasks do enable). `assertTrue` is the multiplatform equivalent and
        // is unconditional, so this is strictly stronger than what it replaces.
        assertTrue(FlashSpacing.space2.value > 0)
        assertTrue(FlashSpacing.space4.value > 0)
        assertTrue(FlashSpacing.space8.value > 0)
        assertTrue(FlashSpacing.space12.value > 0)
        assertTrue(FlashSpacing.space16.value > 0)
    }

    @Test
    fun dimensions_tokensArePositive() {
        assertTrue(FlashDimensions.headerHeight.value > 0)
        assertTrue(FlashDimensions.minTouchTarget.value > 0)
    }

    @Test
    fun materialColorSchemes_neverLeakMaterialTemplatePurple() {
        val forbiddenTemplateColors = setOf(
            Color(0xFFD0BCFF), // M3 template Purple80 / dark primary
            Color(0xFFCCC2DC), // PurpleGrey80
            Color(0xFFEFB8C8), // Pink80
            Color(0xFF6650A4), // Purple40
            Color(0xFF6750A4), // M3 baseline light primary
            Color(0xFF625B71), // PurpleGrey40
            Color(0xFF7D5260), // Pink40
            Color(0xFFEADDFF), // M3 baseline primaryContainer
            Color(0xFF4F378B), // M3 baseline dark primaryContainer
            Color(0xFF381E72), // M3 baseline dark onPrimary
            Color(0xFFFEF7FF), // M3 baseline light surface
            Color(0xFFF3EDF7), // M3 baseline light surfaceContainerHigh
            Color(0xFF141218), // M3 baseline dark background
            Color(0xFF1D1B20), // M3 baseline dark surfaceContainerLow
            Color(0xFF211F26), // M3 baseline dark surfaceContainer
            Color(0xFF2B2930), // M3 baseline dark surfaceContainerHigh
        )

        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            scheme.allSlots().forEach { slotColor ->
                assertFalse(
                    slotColor in forbiddenTemplateColors,
                    "Material3 ColorScheme leaked baseline purple/pink token $slotColor",
                )
            }
        }
    }

    @Test
    fun materialColorSchemes_mapFromFlashColorsAndMeetContrast() {
        val light = FlashColors.light()
        assertEquals(light.accentFill, LightColorScheme.primary)
        assertEquals(light.onAccentFill, LightColorScheme.onPrimary)
        assertEquals(light.backgroundApp, LightColorScheme.background)
        assertEquals(light.backgroundSurface, LightColorScheme.surface)
        assertEquals(light.sheetSurface, LightColorScheme.surfaceContainerHigh)
        assertEquals(light.textError, LightColorScheme.error)

        val dark = FlashColors.dark()
        assertEquals(dark.accentFill, DarkColorScheme.primary)
        assertEquals(dark.onAccentFill, DarkColorScheme.onPrimary)
        assertEquals(dark.backgroundApp, DarkColorScheme.background)
        assertEquals(dark.backgroundSurface, DarkColorScheme.surface)
        assertEquals(dark.backgroundSurfaceStrong, DarkColorScheme.surfaceContainerHigh)
        assertEquals(dark.textError, DarkColorScheme.error)

        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            val pairs = listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiary to scheme.tertiary,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.primary to scheme.surface,
                scheme.primary to scheme.surfaceContainerHigh,
            )
            pairs.forEach { (fg, bg) ->
                val ratio = flashContrastRatio(fg, bg)
                assertTrue(
                    ratio >= 4.5f,
                    "Material3 scheme contrast $ratio < 4.5 for $fg on $bg",
                )
            }
        }
    }

    private fun ColorScheme.allSlots(): List<Color> = listOf(
        primary,
        onPrimary,
        primaryContainer,
        onPrimaryContainer,
        inversePrimary,
        secondary,
        onSecondary,
        secondaryContainer,
        onSecondaryContainer,
        tertiary,
        onTertiary,
        tertiaryContainer,
        onTertiaryContainer,
        background,
        onBackground,
        surface,
        onSurface,
        surfaceVariant,
        onSurfaceVariant,
        surfaceTint,
        inverseSurface,
        inverseOnSurface,
        error,
        onError,
        errorContainer,
        onErrorContainer,
        outline,
        outlineVariant,
        scrim,
        surfaceBright,
        surfaceDim,
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
    )
}
