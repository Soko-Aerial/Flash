package com.transfer.flash

import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.ui.settings.FlashThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** #14: theme enum <-> persisted DataStore key mapping is pure, so JVM-testable. */
class SettingsKeysTest {

    @Test
    fun keyToThemeModeCoversEveryKey() {
        assertEquals(FlashThemeMode.System, SettingsKeys.themeModeFromKey(FlashSettingsDataStore.THEME_MODE_SYSTEM))
        assertEquals(FlashThemeMode.Light, SettingsKeys.themeModeFromKey(FlashSettingsDataStore.THEME_MODE_LIGHT))
        assertEquals(FlashThemeMode.Dark, SettingsKeys.themeModeFromKey(FlashSettingsDataStore.THEME_MODE_DARK))
    }

    @Test
    fun unknownKeyFallsBackToSystem() {
        assertEquals(FlashThemeMode.System, SettingsKeys.themeModeFromKey("garbage"))
        assertEquals(FlashThemeMode.System, SettingsKeys.themeModeFromKey(""))
    }

    @Test
    fun themeModeToKeyRoundTripsThroughFromKey() {
        FlashThemeMode.entries.forEach { mode ->
            assertEquals(mode, SettingsKeys.themeModeFromKey(SettingsKeys.themeModeToKey(mode)))
        }
    }
}
