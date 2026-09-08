package com.transfer.flash.core.persistence.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plain-JVM tests mirroring [FlashSettingsDataStoreTest]'s style:
 * datastore-preferences-core has a JVM target so no Robolectric is needed.
 * Each test gets a fresh DataStore backed by a uniquely-named file in a temp
 * folder to avoid the "multiple DataStores for the same file" conflict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryModeSettingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDataStore(): DataStore<Preferences> {
        val file = File(tmp.root, "discovery-mode-${Random.nextLong()}.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    @Test
    fun `default is STANDARD when store is empty`() = runTest {
        val setting = DiscoveryModeSetting(newDataStore())

        assertEquals("STANDARD", setting.discoveryMode.first())
    }

    @Test
    fun `roundtrip for every valid mode`() = runTest {
        val setting = DiscoveryModeSetting(newDataStore())

        DiscoveryModeSetting.VALID.forEach { value ->
            setting.setDiscoveryMode(value)
            assertEquals(value, setting.discoveryMode.first())
        }
    }

    @Test
    fun `setter rejects unknown values with IllegalArgumentException`() = runTest {
        val setting = DiscoveryModeSetting(newDataStore())

        try {
            setting.setDiscoveryMode("TURBO")
            fail("expected IllegalArgumentException for 'TURBO'")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Rejected write must not have persisted anything.
        assertEquals("STANDARD", setting.discoveryMode.first())
    }

    @Test
    fun `setter is case-sensitive - lowercase name rejected`() = runTest {
        val setting = DiscoveryModeSetting(newDataStore())

        try {
            setting.setDiscoveryMode("ghost")
            fail("expected IllegalArgumentException for 'ghost'")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertEquals("STANDARD", setting.discoveryMode.first())
    }

    @Test
    fun `stored unknown value from a future build falls back to STANDARD`() = runTest {
        val dataStore = newDataStore()
        // Simulate a value written by a newer app version knowing extra modes.
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(DiscoveryModeSetting.KEY_NAME)] = "WARP"
        }

        val setting = DiscoveryModeSetting(dataStore)
        assertEquals("STANDARD", setting.discoveryMode.first())
    }

    @Test
    fun `rejected setter leaves previously stored valid value intact`() = runTest {
        val dataStore = newDataStore()
        val setting = DiscoveryModeSetting(dataStore)
        setting.setDiscoveryMode("ECO")

        try {
            setting.setDiscoveryMode("BOOST!")
            fail("expected IllegalArgumentException for 'BOOST!'")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertEquals("ECO", setting.discoveryMode.first())
    }
}
