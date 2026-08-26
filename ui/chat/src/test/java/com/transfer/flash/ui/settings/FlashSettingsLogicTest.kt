package com.transfer.flash.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for UI-049 settings-page pure helpers. */
class FlashSettingsLogicTest {

    @Test
    fun `theme mode labels are user facing`() {
        assertEquals("System", FlashSettingsMath.themeModeLabel(FlashThemeMode.System))
        assertEquals("Light", FlashSettingsMath.themeModeLabel(FlashThemeMode.Light))
        assertEquals("Dark", FlashSettingsMath.themeModeLabel(FlashThemeMode.Dark))
    }

    @Test
    fun `trusted peers subtitle pluralizes honestly`() {
        assertEquals(
            "No verified devices yet",
            FlashSettingsMath.trustedPeersSubtitle(0),
        )
        assertEquals("1 device verified", FlashSettingsMath.trustedPeersSubtitle(1))
        assertEquals("4 devices verified", FlashSettingsMath.trustedPeersSubtitle(4))
    }

    @Test
    fun `system theme mode follows the OS`() {
        assertTrue(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.System, systemDark = true))
        assertFalse(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.System, systemDark = false))
    }

    @Test
    fun `explicit theme modes override the OS`() {
        assertFalse(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.Light, systemDark = true))
        assertTrue(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.Dark, systemDark = false))
    }
}
