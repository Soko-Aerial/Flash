package com.transfer.flash.core.security

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.security.testutil.FakeSharedPreferences
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
}
