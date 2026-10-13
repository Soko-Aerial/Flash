@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.security.testutil.FakeSharedPreferences
import com.transfer.flash.core.security.testutil.SoftwareSecretSealer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-126: the Android store, with the in-memory stand-in for the AndroidKeystore wrapper (the project's practice, see
 * `SoftwareSecretSealer`). What AndroidKeystore itself does is a device test (HUBH-04).
 */
class AndroidHubSealingKeyStoreTest {

    private val prefs = FakeSharedPreferences()
    private val sealer = SoftwareSecretSealer()
    private fun store() = AndroidHubSealingKeyStore(prefs, sealer)

    @Test
    fun aSavedKeyComesBackIntact() = runBlocking {
        val key = HubSealingKeyPair.generate()
        store().save(key)
        val restored = store().load()!!
        assertArrayEquals(key.publicKeyEncoded, restored.publicKeyEncoded)
        assertArrayEquals(key.exportPrivateKeyPkcs8(), restored.exportPrivateKeyPkcs8())
    }

    @Test
    fun whatIsWrittenToPreferencesIsNotThePrivateKey() = runBlocking {
        val key = HubSealingKeyPair.generate()
        store().save(key)
        val stored = prefs.all.values.joinToString("|")
        val priv = com.transfer.flash.core.common.protocol.Base64.encode(key.exportPrivateKeyPkcs8())
        assertFalse("the PKCS#8 key must not be stored unwrapped", stored.contains(priv.take(32)))
        assertEquals("only the wrapped key is stored", 1, prefs.all.size)
    }

    @Test
    fun aLostWrappingKeyReadsAsNoKey() = runBlocking {
        store().save(HubSealingKeyPair.generate())
        sealer.loseKey() // a keystore reset, or the preferences restored onto another phone
        assertNull(store().load())
    }

    @Test
    fun aKeystoreThatRefusesToWrapStoresNothingAndFails() {
        sealer.failSealing = true
        val threw = runCatching { runBlocking { store().save(HubSealingKeyPair.generate()) } }.isFailure
        assertTrue(threw)
        assertTrue("nothing readable may be left behind", prefs.all.isEmpty())
    }

    @Test
    fun clearRemovesTheKey() = runBlocking {
        val s = store()
        s.save(HubSealingKeyPair.generate())
        s.clear()
        assertNull(s.load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun pinsPersistInPreferences() = runBlocking {
        val pins = mapOf("dev-1" to HubSealingPin("aa", "bb"), "dev-2" to HubSealingPin("cc"))
        AndroidHubSealingPinStore(prefs).save(pins)
        assertEquals(pins, AndroidHubSealingPinStore(prefs).load())
    }
}
