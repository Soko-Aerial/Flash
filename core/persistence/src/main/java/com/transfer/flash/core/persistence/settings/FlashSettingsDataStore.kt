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
class FlashSettingsDataStore(
    produceFile: () -> File,
    /**
     * Scope in which DataStore performs its IO. Provided by the app layer
     * (e.g. an application-scoped CoroutineScope injected via DI, C0.5);
     * defaults to Dispatchers.IO + SupervisorJob for standalone/JVM use.
     */
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicAccent = booleanPreferencesKey("dynamic_accent")
        val hapticsEnabled = booleanPreferencesKey("haptics_enabled")
        val reduceMotionOverride = stringPreferencesKey("reduce_motion_override")
        val soundsEnabled = booleanPreferencesKey("sounds_enabled")
        val autoAcceptTrusted = booleanPreferencesKey("auto_accept_trusted")
        val backgroundTransfers = booleanPreferencesKey("background_transfers")
        val saveLocationUri = stringPreferencesKey("save_location_uri")
        val retentionDays = intPreferencesKey("retention_days")
        val displayName = stringPreferencesKey("display_name")
    }

    companion object {
        const val THEME_MODE_SYSTEM = "system"
        const val THEME_MODE_LIGHT = "light"
        const val THEME_MODE_DARK = "dark"

        const val MOTION_OVERRIDE_SYSTEM = "system"
        const val MOTION_OVERRIDE_ON = "on"
        const val MOTION_OVERRIDE_OFF = "off"

        const val DEFAULT_RETENTION_DAYS = 365
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

    val themeMode: Flow<String> =
        preferences.map { it[Keys.themeMode] ?: THEME_MODE_SYSTEM }

    /** Default FALSE: matches the shipped UI default (dynamic accent is opt-in, UI-049). */
    val dynamicAccent: Flow<Boolean> =
        preferences.map { it[Keys.dynamicAccent] ?: false }

    val hapticsEnabled: Flow<Boolean> =
        preferences.map { it[Keys.hapticsEnabled] ?: true }

    val reduceMotionOverride: Flow<String> =
        preferences.map { it[Keys.reduceMotionOverride] ?: MOTION_OVERRIDE_SYSTEM }

    /** Default FALSE: sounds are opt-in only (owner decision D6, UI-040). */
    val soundsEnabled: Flow<Boolean> =
        preferences.map { it[Keys.soundsEnabled] ?: false }

    val autoAcceptTrusted: Flow<Boolean> =
        preferences.map { it[Keys.autoAcceptTrusted] ?: false }

    /** Keep transfers running when the app leaves the foreground (UI-049). Default FALSE. */
    val backgroundTransfers: Flow<Boolean> =
        preferences.map { it[Keys.backgroundTransfers] ?: false }

    val saveLocationUri: Flow<String?> =
        preferences.map { it[Keys.saveLocationUri] }

    /** Retention window in days feeding C1.6 ([com.transfer.flash.core.persistence.retention]). 0 disables retention pruning. */
    val retentionDays: Flow<Int> =
        preferences.map { it[Keys.retentionDays] ?: DEFAULT_RETENTION_DAYS }

    val displayName: Flow<String> =
        preferences.map { it[Keys.displayName].orEmpty() }

    suspend fun setThemeMode(value: String) {
        dataStore.edit { it[Keys.themeMode] = value }
    }

    suspend fun setDynamicAccent(value: Boolean) {
        dataStore.edit { it[Keys.dynamicAccent] = value }
    }

    suspend fun setHapticsEnabled(value: Boolean) {
        dataStore.edit { it[Keys.hapticsEnabled] = value }
    }

    suspend fun setReduceMotionOverride(value: String) {
        dataStore.edit { it[Keys.reduceMotionOverride] = value }
    }

    suspend fun setSoundsEnabled(value: Boolean) {
        dataStore.edit { it[Keys.soundsEnabled] = value }
    }

    suspend fun setAutoAcceptTrusted(value: Boolean) {
        dataStore.edit { it[Keys.autoAcceptTrusted] = value }
    }

    suspend fun setBackgroundTransfers(value: Boolean) {
        dataStore.edit { it[Keys.backgroundTransfers] = value }
    }

    suspend fun setSaveLocationUri(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.saveLocationUri) else prefs[Keys.saveLocationUri] = value
        }
    }

    suspend fun setRetentionDays(value: Int) {
        dataStore.edit { it[Keys.retentionDays] = value }
    }

    suspend fun setDisplayName(value: String) {
        dataStore.edit { it[Keys.displayName] = value }
    }
}
