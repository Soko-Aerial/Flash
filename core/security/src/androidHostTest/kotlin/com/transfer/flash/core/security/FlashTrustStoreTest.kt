package com.transfer.flash.core.security

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.testutil.FakeSharedPreferences
import com.transfer.flash.core.security.testutil.SoftwareSecretSealer
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashTrustStoreTest {

    @Test
    fun trustPeer_persistsAndRecognizesPeer() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesTrustStore(prefs)
        val peerId = FlashDeviceId("peer-device-uuid-999")

        assertFalse(store.isTrusted(peerId))
        assertFalse(store.isTrusted("peer-device-uuid-999"))

        val result = store.trustPeer(peerId, "Galaxy S23")
        assertTrue(result is FlashResult.Success)

        assertTrue(store.isTrusted(peerId))
        assertTrue(store.isTrusted("peer-device-uuid-999"))
        assertEquals("Galaxy S23", prefs.getString("paired_peer-device-uuid-999", null))
    }

    @Test
    fun revokeTrust_removesPeer() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesTrustStore(prefs)
        val peerId = FlashDeviceId("peer-device-uuid-999")

        store.trustPeer(peerId, "Galaxy S23")
        assertTrue(store.isTrusted(peerId))

        val revokeResult = store.revokeTrust(peerId)
        assertTrue(revokeResult is FlashResult.Success)
        assertFalse(store.isTrusted(peerId))
        assertFalse(prefs.contains("paired_peer-device-uuid-999"))
    }

    @Test
    fun getTrustedPeers_returnsAllPairedPeers() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesTrustStore(prefs)

        store.trustPeer(FlashDeviceId("id-1"), "Device 1")
        store.trustPeer(FlashDeviceId("id-2"), "Device 2")

        val peers = store.getTrustedPeers()
        assertEquals(2, peers.size)
        assertEquals("Device 1", peers[FlashDeviceId("id-1")])
        assertEquals("Device 2", peers[FlashDeviceId("id-2")])
    }

    @Test
    fun saveSessionKey_persistsAndRetrievesSessionKey() {
        val prefs = FakeSharedPreferences()
        val store = AndroidPreferencesTrustStore(prefs, SoftwareSecretSealer())
        val peerId = FlashDeviceId("peer-device-uuid-999")
        val sampleKey = ByteArray(32) { (it + 1).toByte() }

        org.junit.Assert.assertNull(store.getSessionKey(peerId))

        val result = store.saveSessionKey(peerId, sampleKey)
        assertTrue(result is FlashResult.Success)

        val retrieved = store.getSessionKey(peerId)
        org.junit.Assert.assertNotNull(retrieved)
        org.junit.Assert.assertArrayEquals(sampleKey, retrieved)

        // revokeTrust cleans up both trust and session key
        store.revokeTrust(peerId)
        org.junit.Assert.assertNull(store.getSessionKey(peerId))
        assertFalse(prefs.contains("session_key_peer-device-uuid-999"))
    }

    // --- Audit S4: session keys sealed at rest -----------------------------------------------------

    private val sampleKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val peer = FlashDeviceId("peer-s4")

    @Test
    fun sessionKey_isNotStoredInTheClear() {
        val prefs = FakeSharedPreferences()
        AndroidPreferencesTrustStore(prefs, SoftwareSecretSealer()).saveSessionKey(peer, sampleKey)

        val stored = prefs.getString("session_key_peer-s4", null)!!
        assertTrue("stored value must be marked sealed", stored.startsWith(AndroidPreferencesTrustStore.SEALED_PREFIX))
        assertFalse(
            "the raw key's Base64 must not appear on disk",
            stored.contains(com.transfer.flash.core.common.protocol.Base64.encode(sampleKey)),
        )
    }

    @Test
    fun legacyPlaintextEntry_isServedAndResealedOnFirstRead() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(
            "session_key_peer-s4",
            com.transfer.flash.core.common.protocol.Base64.encode(sampleKey),
        ).apply()
        val store = AndroidPreferencesTrustStore(prefs, SoftwareSecretSealer())

        org.junit.Assert.assertArrayEquals(sampleKey, store.getSessionKey(peer))
        assertTrue(prefs.getString("session_key_peer-s4", null)!!.startsWith(AndroidPreferencesTrustStore.SEALED_PREFIX))
        org.junit.Assert.assertArrayEquals("still readable after migration", sampleKey, store.getSessionKey(peer))
    }

    @Test
    fun unopenableEntry_afterKeyLoss_isDroppedNotReturned() {
        val prefs = FakeSharedPreferences()
        val sealer = SoftwareSecretSealer()
        val store = AndroidPreferencesTrustStore(prefs, sealer)
        store.saveSessionKey(peer, sampleKey)

        sealer.loseKey() // restore onto another device / keystore wiped

        org.junit.Assert.assertNull(store.getSessionKey(peer))
        assertFalse("unrecoverable entry is removed", prefs.contains("session_key_peer-s4"))
    }

    @Test
    fun sealingFailure_failsTheSaveAndStoresNothing() {
        val prefs = FakeSharedPreferences()
        val sealer = SoftwareSecretSealer().apply { failSealing = true }
        val result = AndroidPreferencesTrustStore(prefs, sealer).saveSessionKey(peer, sampleKey)

        assertTrue(result is FlashResult.Failure)
        assertFalse("no plaintext fallback", prefs.contains("session_key_peer-s4"))
    }
}
