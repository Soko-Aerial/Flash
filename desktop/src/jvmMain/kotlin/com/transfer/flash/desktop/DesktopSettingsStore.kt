package com.transfer.flash.desktop

import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.settings.FlashThemeMode
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/**
 * File-backed settings for the desktop shell — today, the Appearance selection.
 *
 * Stands in for `androidMain`'s `FlashSettingsDataStore` (a Preferences DataStore), the same way
 * [DesktopIdentityStore] stands in for `AndroidPreferencesIdentityStore`: the contract that matters
 * is "the choice survives restart", and a `Properties` file under `~/.flash/` is a faithful
 * equivalent. State layout: `~/.flash/settings.properties`.
 *
 * ## Why this exists at all
 *
 * The Settings screen's Appearance control was wired to a literal no-op
 * (`onThemeModeSelected = { /* no persisted settings tier on desktop */ }`), and `DesktopMain`
 * called `FlashTheme { }` with no argument — so the desktop was pinned to whatever
 * `isSystemInDarkTheme()` reported and the three segments moved without repainting anything. The
 * repo's own Phase 32 file describes that screen as "a control panel connected to nothing… a screen
 * that reports success". This is the desktop half of the fix; the Android chain
 * (`FlashSettingsDataStore` → `SettingsKeys` → `resolveDarkTheme`) was already complete and is
 * deliberately untouched.
 *
 * ## Not `FlashSettingsDataStore`
 *
 * That store is `androidMain`-only (`:desktop` does not depend on `:core:persistence`), and it
 * carries a dozen unrelated preferences whose desktop behaviour is a separate question. Only the
 * key tokens are shared, and they are shared by VALUE — `"system"`/`"light"`/`"dark"`, matching
 * `FlashSettingsDataStore.THEME_MODE_*` — so the two files stay readable by eye even though nothing
 * links them at compile time.
 *
 * Persistence is best-effort: an unwritable home directory degrades to in-memory for the session
 * rather than failing the launch, because a theme preference is not worth refusing to start over.
 */
internal class DesktopSettingsStore(private val stateDir: File) {

    private val file = File(stateDir, "settings.properties")
    private val lock = Any()

    init {
        runCatching { stateDir.mkdirs() }
    }

    private fun load(): Properties {
        val props = Properties()
        runCatching {
            if (file.isFile) file.inputStream().use { input: InputStream -> props.load(input) }
        }
        return props
    }

    private fun save(props: Properties) {
        runCatching {
            file.outputStream().use { output: OutputStream -> props.store(output, "Flash desktop settings") }
        }
    }

    /** The stored Appearance selection, or [FlashThemeMode.System] if unset or unreadable. */
    fun themeMode(): FlashThemeMode =
        synchronized(lock) { themeModeFromKey(load().getProperty(KEY_THEME_MODE)) }

    /** Records the Appearance selection. A failure here is swallowed — see the class KDoc. */
    fun setThemeMode(mode: FlashThemeMode) {
        synchronized(lock) {
            val props = load()
            props.setProperty(KEY_THEME_MODE, themeModeToKey(mode))
            save(props)
        }
    }

    /**
     * The dark/light decision for the whole desktop window.
     *
     * Delegates to the SHARED resolver rather than repeating the `when`, so desktop and Android
     * cannot drift on what "System" means. `systemDark` is supplied by the caller because
     * `isSystemInDarkTheme()` is a `@Composable` read and this is plain code.
     */
    fun isDarkTheme(mode: FlashThemeMode, systemDark: Boolean): Boolean =
        FlashSettingsMath.resolveDarkTheme(mode = mode, systemDark = systemDark)

    internal companion object {
        /**
         * Token for the stored preference.
         *
         * Deliberately NOT `FlashSettingsDataStore.Keys.themeMode` — that constant is
         * `androidMain` and unreachable from here. The VALUES match, and
         * [themeModeFromKey]/[themeModeToKey] pin the mapping in both directions.
         */
        const val KEY_THEME_MODE: String = "theme_mode"

        /** Same three tokens `FlashSettingsDataStore.THEME_MODE_*` uses. */
        const val THEME_MODE_SYSTEM: String = "system"
        const val THEME_MODE_LIGHT: String = "light"
        const val THEME_MODE_DARK: String = "dark"

        /**
         * Key → mode. An absent, empty or unrecognised key reads as [FlashThemeMode.System].
         *
         * The fallback is System rather than Dark on purpose: a corrupt or hand-edited settings
         * file should leave the app following the OS, which is also the state a fresh install is
         * in, so the failure mode is indistinguishable from "not set yet" instead of looking like
         * the user's choice was honoured when it was not.
         */
        fun themeModeFromKey(key: String?): FlashThemeMode = when (key) {
            THEME_MODE_LIGHT -> FlashThemeMode.Light
            THEME_MODE_DARK -> FlashThemeMode.Dark
            else -> FlashThemeMode.System
        }

        fun themeModeToKey(mode: FlashThemeMode): String = when (mode) {
            FlashThemeMode.Light -> THEME_MODE_LIGHT
            FlashThemeMode.Dark -> THEME_MODE_DARK
            FlashThemeMode.System -> THEME_MODE_SYSTEM
        }
    }
}
