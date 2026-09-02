package com.transfer.flash.core.persistence.settings

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Typed wrapper around Jetpack Preferences DataStore holding every user-facing
 * setting required by core-upgrade-plan step C1.5 / capability A14.
 *
 * Construction contract:
 * - Takes a file-producing lambda instead of a Context so it is constructible
 *   and unit-testable on the plain JVM (datastore-preferences-core ships a JVM
 *   target per the official KMP setup guide).
 * - The lambda MUST return the same [File] path on every invocation and that
 *   file MUST have the `.preferences_pb` extension (enforced by
 *   PreferenceDataStoreFactory.create).
 * - The app layer provides a long-lived scope and treats the instance as a
 *   SINGLETON per file: never create more than one DataStore instance for the
 *   same file in a process, otherwise reading/updating throws
 *   IllegalStateException ("multiple DataStores active for the same file").
 *
 * Error handling:
 * - CorruptionException (unparseable preferences_pb) is handled by
 *   ReplaceFileCorruptionHandler returning emptyPreferences(), i.e. corrupted
 *   settings reset to defaults rather than crashing reads forever.
 * - IOException while reading is caught and mapped to emptyPreferences()
 *   following the documented graceful-read pattern.
 *
 * Key naming uses stable snake_case names under no prefix; renaming a key here
 * would silently reset the user's value, so treat key strings as migration-
 * sensitive (C1.5 "migration-safe key naming").
 */
public class FlashSettingsDataStore(
    produceFile: () -> File,
    /**
     * Scope in which DataStore performs its IO. Provided by the app layer
     * (e.g. an application-scoped CoroutineScope injected via DI, C0.5);
     * defaults to Dispatchers.IO + SupervisorJob for standalone/JVM use.
     */
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    public object Keys {
        public val themeMode: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        public val dynamicAccent: Preferences.Key<Boolean> = booleanPreferencesKey("dynamic_accent")
        public val hapticsEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("haptics_enabled")
        public val reduceMotionOverride: Preferences.Key<String> = stringPreferencesKey("reduce_motion_override")
        public val soundsEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("sounds_enabled")
        public val autoAcceptTrusted: Preferences.Key<Boolean> = booleanPreferencesKey("auto_accept_trusted")
        public val backgroundTransfers: Preferences.Key<Boolean> = booleanPreferencesKey("background_transfers")
        public val autoDownloadVoice: Preferences.Key<Boolean> = booleanPreferencesKey("auto_download_voice")
        public val autoDownloadImage: Preferences.Key<Boolean> = booleanPreferencesKey("auto_download_image")
        public val autoDownloadVideo: Preferences.Key<Boolean> = booleanPreferencesKey("auto_download_video")
        public val autoDownloadFile: Preferences.Key<Boolean> = booleanPreferencesKey("auto_download_file")
        public val prioritiseVoiceQuality: Preferences.Key<Boolean> =
            booleanPreferencesKey("prioritise_voice_quality")
        public val saveLocationUri: Preferences.Key<String> = stringPreferencesKey("save_location_uri")
        public val retentionDays: Preferences.Key<Int> = intPreferencesKey("retention_days")
        public val displayName: Preferences.Key<String> = stringPreferencesKey("display_name")
    }

    public companion object {
        public const val THEME_MODE_SYSTEM: String = "system"
        public const val THEME_MODE_LIGHT: String = "light"
        public const val THEME_MODE_DARK: String = "dark"

        public const val MOTION_OVERRIDE_SYSTEM: String = "system"
        public const val MOTION_OVERRIDE_ON: String = "on"
        public const val MOTION_OVERRIDE_OFF: String = "off"

        public const val DEFAULT_RETENTION_DAYS: Int = 365
    }

    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = produceFile,
    )

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }

    public val themeMode: Flow<String> =
        preferences.map { it[Keys.themeMode] ?: THEME_MODE_SYSTEM }

    /** Default FALSE: matches the shipped UI default (dynamic accent is opt-in, UI-049). */
    public val dynamicAccent: Flow<Boolean> =
        preferences.map { it[Keys.dynamicAccent] ?: false }

    public val hapticsEnabled: Flow<Boolean> =
        preferences.map { it[Keys.hapticsEnabled] ?: true }

    public val reduceMotionOverride: Flow<String> =
        preferences.map { it[Keys.reduceMotionOverride] ?: MOTION_OVERRIDE_SYSTEM }

    /** Default FALSE: sounds are opt-in only (owner decision D6, UI-040). */
    public val soundsEnabled: Flow<Boolean> =
        preferences.map { it[Keys.soundsEnabled] ?: false }

    public val autoAcceptTrusted: Flow<Boolean> =
        preferences.map { it[Keys.autoAcceptTrusted] ?: false }

    /** Keep transfers running when the app leaves the foreground (UI-049). Default FALSE. */
    public val backgroundTransfers: Flow<Boolean> =
        preferences.map { it[Keys.backgroundTransfers] ?: false }

    /**
     * Bug 3: per-MIME auto-download of inbound offers, resolved in the chat bubble.
     * Defaults (owner decision): voice + images auto-download (TRUE); videos + files ask (FALSE).
     */
    public val autoDownloadVoice: Flow<Boolean> =
        preferences.map { it[Keys.autoDownloadVoice] ?: true }

    public val autoDownloadImage: Flow<Boolean> =
        preferences.map { it[Keys.autoDownloadImage] ?: true }

    public val autoDownloadVideo: Flow<Boolean> =
        preferences.map { it[Keys.autoDownloadVideo] ?: false }

    public val autoDownloadFile: Flow<Boolean> =
        preferences.map { it[Keys.autoDownloadFile] ?: false }

    /**
     * Spend a congested link on voice before video in a video call. Default TRUE.
     *
     * Default-on because the failure it prevents is worse than the one it causes: a caller who
     * cannot be understood has lost the call, whereas a caller whose picture went soft for a few
     * seconds has not. Turning it off restores WebRTC's symmetric treatment of the two streams
     * and disables the adaptive governor in `core:calling` entirely.
     */
    public val prioritiseVoiceQuality: Flow<Boolean> =
        preferences.map { it[Keys.prioritiseVoiceQuality] ?: true }

    public val saveLocationUri: Flow<String?> =
        preferences.map { it[Keys.saveLocationUri] }

    /** Retention window in days feeding C1.6 ([com.transfer.flash.core.persistence.retention]). 0 disables retention pruning. */
    public val retentionDays: Flow<Int> =
        preferences.map { it[Keys.retentionDays] ?: DEFAULT_RETENTION_DAYS }

    public val displayName: Flow<String> =
        preferences.map { it[Keys.displayName].orEmpty() }

    public suspend fun setThemeMode(value: String) {
        dataStore.edit { it[Keys.themeMode] = value }
    }

    public suspend fun setDynamicAccent(value: Boolean) {
        dataStore.edit { it[Keys.dynamicAccent] = value }
    }

    public suspend fun setHapticsEnabled(value: Boolean) {
        dataStore.edit { it[Keys.hapticsEnabled] = value }
    }

    public suspend fun setReduceMotionOverride(value: String) {
        dataStore.edit { it[Keys.reduceMotionOverride] = value }
    }

    public suspend fun setSoundsEnabled(value: Boolean) {
        dataStore.edit { it[Keys.soundsEnabled] = value }
    }

    public suspend fun setAutoAcceptTrusted(value: Boolean) {
        dataStore.edit { it[Keys.autoAcceptTrusted] = value }
    }

    public suspend fun setBackgroundTransfers(value: Boolean) {
        dataStore.edit { it[Keys.backgroundTransfers] = value }
    }

    public suspend fun setAutoDownloadVoice(value: Boolean) {
        dataStore.edit { it[Keys.autoDownloadVoice] = value }
    }

    public suspend fun setAutoDownloadImage(value: Boolean) {
        dataStore.edit { it[Keys.autoDownloadImage] = value }
    }

    public suspend fun setAutoDownloadVideo(value: Boolean) {
        dataStore.edit { it[Keys.autoDownloadVideo] = value }
    }

    public suspend fun setAutoDownloadFile(value: Boolean) {
        dataStore.edit { it[Keys.autoDownloadFile] = value }
    }

    public suspend fun setPrioritiseVoiceQuality(value: Boolean) {
        dataStore.edit { it[Keys.prioritiseVoiceQuality] = value }
    }

    public suspend fun setSaveLocationUri(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.saveLocationUri) else prefs[Keys.saveLocationUri] = value
        }
    }

    public suspend fun setRetentionDays(value: Int) {
        dataStore.edit { it[Keys.retentionDays] = value }
    }

    public suspend fun setDisplayName(value: String) {
        dataStore.edit { it[Keys.displayName] = value }
    }
}
