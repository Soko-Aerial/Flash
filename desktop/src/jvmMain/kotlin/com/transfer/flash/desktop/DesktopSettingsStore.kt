package com.transfer.flash.desktop

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.ui.settings.FlashSettingsMath
import com.transfer.flash.ui.settings.FlashThemeMode
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/**
 * Data model for persisted desktop settings.
 */
public data class DesktopSettings(
    val themeMode: FlashThemeMode = FlashThemeMode.System,
    val discoveryMode: FlashDiscoveryMode = FlashDiscoveryMode.STANDARD,
    val saveLocation: String = runCatching {
        File(System.getProperty("user.home", "."), "FlashReceived").canonicalPath
    }.getOrDefault("FlashReceived"),
    val autoDownloadVoice: Boolean = true,
    val autoDownloadImage: Boolean = true,
    val autoDownloadVideo: Boolean = false,
    val autoDownloadFile: Boolean = false,
    val prioritiseVoiceQuality: Boolean = true,
    val dynamicAccent: Boolean = false,
    val performanceMode: FlashPerformanceMode? = null,
    val closeToTray: Boolean = true,
    val showNotifications: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    val windowsContextMenu: Boolean = true,
    val uiScale: Float = 1.0f,
)

/**
 * File-backed settings for the desktop shell.
 *
 * Stands in for `androidMain`'s `FlashSettingsDataStore` (a Preferences DataStore), the same way
 * [DesktopIdentityStore] stands in for `AndroidPreferencesIdentityStore`: the contract that matters
 * is "the choice survives restart", and a `Properties` file under `~/.flash/` is a faithful
 * equivalent. State layout: `~/.flash/settings.properties`.
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

    /** Loads all persisted desktop settings with sensible defaults. */
    fun loadSettings(): DesktopSettings = synchronized(lock) {
        val props = load()
        val defaultSaveLocation = runCatching {
            File(System.getProperty("user.home", "."), "FlashReceived").canonicalPath
        }.getOrDefault("FlashReceived")
        DesktopSettings(
            themeMode = themeModeFromKey(props.getProperty(KEY_THEME_MODE)),
            discoveryMode = discoveryModeFromKey(props.getProperty(KEY_DISCOVERY_MODE)),
            saveLocation = props.getProperty(KEY_SAVE_LOCATION, defaultSaveLocation),
            autoDownloadVoice = props.getProperty(KEY_AUTO_DOWNLOAD_VOICE, "true").toBoolean(),
            autoDownloadImage = props.getProperty(KEY_AUTO_DOWNLOAD_IMAGE, "true").toBoolean(),
            autoDownloadVideo = props.getProperty(KEY_AUTO_DOWNLOAD_VIDEO, "false").toBoolean(),
            autoDownloadFile = props.getProperty(KEY_AUTO_DOWNLOAD_FILE, "false").toBoolean(),
            prioritiseVoiceQuality = props.getProperty(KEY_PRIORITISE_VOICE_QUALITY, "true").toBoolean(),
            dynamicAccent = props.getProperty(KEY_DYNAMIC_ACCENT, "false").toBoolean(),
            performanceMode = performanceModeFromKey(props.getProperty(KEY_PERFORMANCE_MODE)),
            closeToTray = props.getProperty(KEY_CLOSE_TO_TRAY, "true").toBoolean(),
            showNotifications = props.getProperty(KEY_SHOW_NOTIFICATIONS, "true").toBoolean(),
            autoStartOnBoot = props.getProperty(KEY_AUTO_START_ON_BOOT, "false").toBoolean(),
            windowsContextMenu = props.getProperty(KEY_WINDOWS_CONTEXT_MENU, "true").toBoolean(),
            uiScale = props.getProperty(KEY_UI_SCALE)?.toFloatOrNull()?.coerceIn(0.75f, 1.5f) ?: 1.0f,
        )
    }

    /** Records all desktop settings to the properties file. */
    fun saveSettings(settings: DesktopSettings) = synchronized(lock) {
        val props = load()
        props.setProperty(KEY_THEME_MODE, themeModeToKey(settings.themeMode))
        props.setProperty(KEY_DISCOVERY_MODE, settings.discoveryMode.name)
        props.setProperty(KEY_SAVE_LOCATION, settings.saveLocation)
        props.setProperty(KEY_AUTO_DOWNLOAD_VOICE, settings.autoDownloadVoice.toString())
        props.setProperty(KEY_AUTO_DOWNLOAD_IMAGE, settings.autoDownloadImage.toString())
        props.setProperty(KEY_AUTO_DOWNLOAD_VIDEO, settings.autoDownloadVideo.toString())
        props.setProperty(KEY_AUTO_DOWNLOAD_FILE, settings.autoDownloadFile.toString())
        props.setProperty(KEY_PRIORITISE_VOICE_QUALITY, settings.prioritiseVoiceQuality.toString())
        props.setProperty(KEY_DYNAMIC_ACCENT, settings.dynamicAccent.toString())
        props.setProperty(KEY_CLOSE_TO_TRAY, settings.closeToTray.toString())
        props.setProperty(KEY_SHOW_NOTIFICATIONS, settings.showNotifications.toString())
        props.setProperty(KEY_AUTO_START_ON_BOOT, settings.autoStartOnBoot.toString())
        props.setProperty(KEY_WINDOWS_CONTEXT_MENU, settings.windowsContextMenu.toString())
        props.setProperty(KEY_UI_SCALE, settings.uiScale.coerceIn(0.75f, 1.5f).toString())
        if (settings.performanceMode != null) {
            props.setProperty(KEY_PERFORMANCE_MODE, settings.performanceMode.name)
        } else {
            props.remove(KEY_PERFORMANCE_MODE)
        }
        save(props)
        if (DesktopAutoStartManager.isSupported) {
            DesktopAutoStartManager.setAutoStart(settings.autoStartOnBoot)
        }
        if (WindowsContextMenuManager.isSupported) {
            WindowsContextMenuManager.setContextMenuEnabled(settings.windowsContextMenu, stateDir)
        }
    }

    /** The stored Appearance selection, or [FlashThemeMode.System] if unset or unreadable. */
    fun themeMode(): FlashThemeMode = loadSettings().themeMode

    /** Records the Appearance selection. A failure here is swallowed — see the class KDoc. */
    fun setThemeMode(mode: FlashThemeMode) {
        saveSettings(loadSettings().copy(themeMode = mode))
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
        const val KEY_THEME_MODE: String = "theme_mode"
        const val KEY_DISCOVERY_MODE: String = "discovery_mode"
        const val KEY_SAVE_LOCATION: String = "save_location"
        const val KEY_AUTO_DOWNLOAD_VOICE: String = "auto_download_voice"
        const val KEY_AUTO_DOWNLOAD_IMAGE: String = "auto_download_image"
        const val KEY_AUTO_DOWNLOAD_VIDEO: String = "auto_download_video"
        const val KEY_AUTO_DOWNLOAD_FILE: String = "auto_download_file"
        const val KEY_PRIORITISE_VOICE_QUALITY: String = "prioritise_voice_quality"
        const val KEY_DYNAMIC_ACCENT: String = "dynamic_accent"
        const val KEY_PERFORMANCE_MODE: String = "performance_mode"
        const val KEY_CLOSE_TO_TRAY: String = "close_to_tray"
        const val KEY_SHOW_NOTIFICATIONS: String = "show_notifications"
        const val KEY_AUTO_START_ON_BOOT: String = "auto_start_on_boot"
        const val KEY_WINDOWS_CONTEXT_MENU: String = "windows_context_menu"
        const val KEY_UI_SCALE: String = "ui_scale"

        /** Same three tokens `FlashSettingsDataStore.THEME_MODE_*` uses. */
        const val THEME_MODE_SYSTEM: String = "system"
        const val THEME_MODE_LIGHT: String = "light"
        const val THEME_MODE_DARK: String = "dark"

        /**
         * Key → mode. An absent, empty or unrecognised key reads as [FlashThemeMode.System].
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

        fun performanceModeFromKey(key: String?): FlashPerformanceMode? = when (key?.trim()?.uppercase()) {
            "LOW" -> FlashPerformanceMode.LOW
            "MEDIUM" -> FlashPerformanceMode.MEDIUM
            "HIGH" -> FlashPerformanceMode.HIGH
            else -> null
        }

        fun discoveryModeFromKey(key: String?): FlashDiscoveryMode = runCatching {
            if (key != null) FlashDiscoveryMode.valueOf(key.trim().uppercase()) else FlashDiscoveryMode.STANDARD
        }.getOrDefault(FlashDiscoveryMode.STANDARD)
    }
}
