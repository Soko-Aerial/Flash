package com.transfer.flash.core.persistence.settings

import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plain-JVM unit tests: datastore-preferences-core has a JVM target, so no
 * Robolectric is needed. Each test gets a fresh DataStore instance backed by a
 * uniquely-named file in a temp folder to avoid the "multiple DataStores for
 * the same file" singleton conflict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlashSettingsDataStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSettings(): FlashSettingsDataStore {
        val file = File(tmp.root, "settings-${Random.nextLong()}.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        return FlashSettingsDataStore(produceFile = { file }, scope = scope)
    }

    @Test
    fun `defaults are returned when store is empty`() = runTest {
        val settings = newSettings()

        assertEquals("system", settings.themeMode.first())
        assertFalse(settings.dynamicAccent.first())
        assertTrue(settings.hapticsEnabled.first())
        assertEquals("system", settings.reduceMotionOverride.first())
        assertFalse(settings.soundsEnabled.first())
        assertFalse(settings.autoAcceptTrusted.first())
        assertFalse(settings.backgroundTransfers.first())
        assertNull(settings.saveLocationUri.first())
        assertEquals(365, settings.retentionDays.first())
        assertEquals("", settings.displayName.first())
    }

    @Test
    fun `soundsEnabled defaults to FALSE specifically (D6 opt-in)`() = runTest {
        val settings = newSettings()
        assertFalse(settings.soundsEnabled.first())
    }

    @Test
    fun `themeMode roundtrip`() = runTest {
        val settings = newSettings()
        listOf("light", "dark", "system").forEach { value ->
            settings.setThemeMode(value)
            assertEquals(value, settings.themeMode.first())
        }
    }

    @Test
    fun `dynamicAccent roundtrip`() = runTest {
        val settings = newSettings()
        settings.setDynamicAccent(false)
        assertFalse(settings.dynamicAccent.first())
        settings.setDynamicAccent(true)
        assertTrue(settings.dynamicAccent.first())
    }

    @Test
    fun `hapticsEnabled roundtrip`() = runTest {
        val settings = newSettings()
        settings.setHapticsEnabled(false)
        assertFalse(settings.hapticsEnabled.first())
        settings.setHapticsEnabled(true)
        assertTrue(settings.hapticsEnabled.first())
    }

    @Test
    fun `reduceMotionOverride roundtrip`() = runTest {
        val settings = newSettings()
        listOf("on", "off", "system").forEach { value ->
            settings.setReduceMotionOverride(value)
            assertEquals(value, settings.reduceMotionOverride.first())
        }
    }

    @Test
    fun `soundsEnabled roundtrip`() = runTest {
        val settings = newSettings()
        settings.setSoundsEnabled(true)
        assertTrue(settings.soundsEnabled.first())
        settings.setSoundsEnabled(false)
        assertFalse(settings.soundsEnabled.first())
    }

    @Test
    fun `autoAcceptTrusted roundtrip`() = runTest {
        val settings = newSettings()
        settings.setAutoAcceptTrusted(true)
        assertTrue(settings.autoAcceptTrusted.first())
        settings.setAutoAcceptTrusted(false)
        assertFalse(settings.autoAcceptTrusted.first())
    }

    @Test
    fun `saveLocationUri roundtrip and clear-to-null`() = runTest {
        val settings = newSettings()
        settings.setSaveLocationUri("content://com.android.externalstorage/tree/primary")
        assertEquals(
            "content://com.android.externalstorage/tree/primary",
            settings.saveLocationUri.first(),
        )
        settings.setSaveLocationUri(null)
        assertNull(settings.saveLocationUri.first())
    }

    @Test
    fun `retentionDays roundtrip`() = runTest {
        val settings = newSettings()
        settings.setRetentionDays(30)
        assertEquals(30, settings.retentionDays.first())
        settings.setRetentionDays(0)
        assertEquals(0, settings.retentionDays.first())
    }

    @Test
    fun `displayName roundtrip`() = runTest {
        val settings = newSettings()
        settings.setDisplayName("Kali")
        assertEquals("Kali", settings.displayName.first())
        settings.setDisplayName("")
        assertEquals("", settings.displayName.first())
    }

    @Test
    fun `backgroundTransfers roundtrip`() = runTest {
        val settings = newSettings()
        settings.setBackgroundTransfers(true)
        assertTrue(settings.backgroundTransfers.first())
        settings.setBackgroundTransfers(false)
        assertFalse(settings.backgroundTransfers.first())
    }

    @Test
    fun `corrupted preferences file falls back to emptyPreferences`() = runTest {
        val file = File(tmp.root, "corrupt-${Random.nextLong()}.preferences_pb")
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val settings = FlashSettingsDataStore(produceFile = { file }, scope = scope)

        assertEquals("system", settings.themeMode.first())
        assertFalse(settings.soundsEnabled.first())

        settings.setDisplayName("after-corruption")
        assertEquals("after-corruption", settings.displayName.first())
    }
}
