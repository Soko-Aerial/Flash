package com.transfer.flash.core.persistence.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Self-contained settings helper for the discovery-mode selection (plan P3.5
 * workstream B4). ADDITIVE to [FlashSettingsDataStore] — deliberately a
 * separate class with its own key so the two never contend for edits.
 *
 * Mode seam (IMPORTANT): this class persists a canonical STRING, not an enum.
 * The core enum `FlashDiscoveryMode` lives in `:core:discovery` (concurrent-
 * ownership area); coupling :core:persistence to it would create a cross-module
 * enum dependency that blocks independent evolution. The APP layer owns the
 * mapping string <-> FlashDiscoveryMode and maps modes onto orchestrator knobs
 * (e.g. FlashPeerGroupSession's endpoint list / retry policy) downstream.
 *
 * Forward-compat contract:
 * - The setter VALIDATES against [VALID] (throws on unknown input) so only
 *   known-canonical names are ever written by this version.
 * - The READER is lenient: a value written by a FUTURE app version (present on
 *   disk but unknown to this build) falls back to [DEFAULT] instead of crashing
 *   or leaking an unmapped string into core. Downgrade-safety over strictness.
 *
 * Key naming: stable `"flash_discovery_mode"` under its own key; treat as
 * migration-sensitive per C1.5 (renaming silently resets the user's mode).
 */
public class DiscoveryModeSetting(private val dataStore: DataStore<Preferences>) {

    /** Stored canonical mode strings; order is UI display order, not ranking. */
    public companion object {
        public const val KEY_NAME: String = "flash_discovery_mode"

        public const val DEFAULT: String = "STANDARD"

        public val VALID: List<String> = listOf("STANDARD", "GHOST", "BOOST", "ECO", "RECEIVE_KIOSK")

        private val KEY = stringPreferencesKey(KEY_NAME)
    }

    /**
     * Current discovery mode as one of [VALID]; emits [DEFAULT] when unset or
     * when the stored value is unknown to this build (forward-compat fallback).
     */
    public val discoveryMode: Flow<String> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs[KEY]?.takeIf { it in VALID } ?: DEFAULT }

    /**
     * Persists [canonicalName]. Throws [IllegalArgumentException] for values
     * outside [VALID] — callers (UI enums) must map before calling.
     */
    public suspend fun setDiscoveryMode(canonicalName: String) {
        require(canonicalName in VALID) {
            "Unknown discovery mode '$canonicalName'; valid: $VALID"
        }
        dataStore.edit { prefs -> prefs[KEY] = canonicalName }
    }
}
