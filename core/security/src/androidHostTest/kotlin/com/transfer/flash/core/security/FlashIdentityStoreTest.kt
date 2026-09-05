package com.transfer.flash.core.security

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.identity.AndroidPreferencesIdentityStore
import com.transfer.flash.core.security.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashIdentityStoreTest {

    @Test
    fun getIdentity_generatesAndPersistsIdentityWhenAbsent() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesIdentityStore(
            preferences = prefs,
            defaultNameProvider = { "Flash Pixel 8" },
        )

        val identity1 = store.getIdentity()
        assertNotNull(identity1.deviceId.value)
        assertTrue(identity1.deviceId.value.isNotBlank())
        assertEquals("Flash Pixel 8", identity1.friendlyName)

        // Second fetch must return identical persisted values
        val identity2 = store.getIdentity()
        assertEquals(identity1.deviceId, identity2.deviceId)
        assertEquals(identity1.friendlyName, identity2.friendlyName)

        // Raw prefs verification (Flash 1.0 key compatibility)
        assertEquals(identity1.deviceId.value, prefs.getString(AndroidPreferencesIdentityStore.KEY_DEVICE_ID, null))
        assertEquals("Flash Pixel 8", prefs.getString(AndroidPreferencesIdentityStore.KEY_FRIENDLY_NAME, null))
    }

    @Test
    fun getIdentity_preservesExistingPersistedValues() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString(AndroidPreferencesIdentityStore.KEY_DEVICE_ID, "custom-uuid-1234")
            .putString(AndroidPreferencesIdentityStore.KEY_FRIENDLY_NAME, "Office Tablet")
            .apply()

        val store = AndroidPreferencesIdentityStore(
            preferences = prefs,
            defaultNameProvider = { "Fallback Name" },
        )

        val identity = store.getIdentity()
        assertEquals("custom-uuid-1234", identity.deviceId.value)
        assertEquals("Office Tablet", identity.friendlyName)
    }

    @Test
    fun updateFriendlyName_success() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesIdentityStore(preferences = prefs)

        val result = store.updateFriendlyName("New Friendly Name")
        assertTrue(result is FlashResult.Success)

        val identity = store.getIdentity()
        assertEquals("New Friendly Name", identity.friendlyName)
    }

    @Test
    fun updateFriendlyName_rejectsBlankName() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesIdentityStore(preferences = prefs)

        val result = store.updateFriendlyName("   ")
        assertTrue(result is FlashResult.Failure)
        val error = (result as FlashResult.Failure).error
        assertTrue(error is FlashError.StorageError)
    }
}
