package com.transfer.flash.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
