package com.transfer.flash.core.radio.wire

import com.transfer.flash.core.radio.driver.InMemoryRadioStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Suite RADCHAT-U01: Validates pairing-key adapter, directional key separation,
 * domain-separated HKDF, rotating station tags, and peer synchronization.
 */
class RadioPairingAdapterTest {

    private val crypto = JdkRadioCrypto()

    private class TestTrustProvider : RadioPeerTrustProvider {
        val trusted = HashSet<String>()
        val keys = HashMap<String, ByteArray>()
        val fingerprints = HashMap<String, String>()

        override fun isPeerTrusted(peerId: String): Boolean = peerId in trusted
        override fun getPairingSessionKey(peerId: String): ByteArray? = keys[peerId]
        override fun getPinnedFingerprint(peerId: String): String? = fingerprints[peerId]
        override fun getTrustedPeerIds(): Set<String> = HashSet(trusted)
    }

    @Test
    fun testDirectionalKeySeparationBetweenStations() {
        val trustAlice = TestTrustProvider()
        val trustBob = TestTrustProvider()

        val pairKey = ByteArray(32) { (it + 1).toByte() }
        val aliceId = "station-alice"
        val bobId = "station-bob"

        trustAlice.trusted += bobId
        trustAlice.keys[bobId] = pairKey

        trustBob.trusted += aliceId
        trustBob.keys[aliceId] = pairKey

        val adapterAlice = RadioPairingAdapter(aliceId, trustAlice, crypto)
        val adapterBob = RadioPairingAdapter(bobId, trustBob, crypto)

        val aliceSend = assertNotNull(adapterAlice.deriveDirectionKeys(bobId, sendDirection = true))
        val aliceRecv = assertNotNull(adapterAlice.deriveDirectionKeys(bobId, sendDirection = false))

        val bobSend = assertNotNull(adapterBob.deriveDirectionKeys(aliceId, sendDirection = true))
        val bobRecv = assertNotNull(adapterBob.deriveDirectionKeys(aliceId, sendDirection = false))

        // Alice's send keys must match Bob's receive keys
        assertContentEquals(aliceSend.key, bobRecv.key, "Alice send key must equal Bob receive key")
        assertContentEquals(aliceSend.nonceSalt, bobRecv.nonceSalt, "Alice nonce salt must equal Bob receive nonce salt")
        assertContentEquals(aliceSend.tagKey, bobRecv.tagKey, "Alice tag key must equal Bob receive tag key")

        // Bob's send keys must match Alice's receive keys
        assertContentEquals(bobSend.key, aliceRecv.key, "Bob send key must equal Alice receive key")
        assertContentEquals(bobSend.nonceSalt, aliceRecv.nonceSalt, "Bob nonce salt must equal Alice receive nonce salt")
        assertContentEquals(bobSend.tagKey, aliceRecv.tagKey, "Bob tag key must equal Alice receive tag key")

        // Crucial security property: directional separation prevents reflection attacks
        // Alice send key must NOT equal Alice receive key
        assertFalse(
            aliceSend.key.contentEquals(aliceRecv.key),
            "Alice send key and receive key must be completely distinct",
        )
        assertFalse(
            aliceSend.nonceSalt.contentEquals(aliceRecv.nonceSalt),
            "Alice send nonce salt and receive nonce salt must be distinct",
        )
        assertFalse(
            aliceSend.tagKey.contentEquals(aliceRecv.tagKey),
            "Alice send tag key and receive tag key must be distinct",
        )
    }

    @Test
    fun testRejectsUntrustedOrKeylessPeers() {
        val trust = TestTrustProvider()
        val adapter = RadioPairingAdapter("local-station", trust, crypto)

        // Untrusted peer
        assertFalse(adapter.isPairedForRadio("stranger"))
        assertNull(adapter.deriveDirectionKeys("stranger", sendDirection = true))

        // Trusted peer but missing session key
        trust.trusted += "peer-no-key"
        assertFalse(adapter.isPairedForRadio("peer-no-key"))
        assertNull(adapter.deriveDirectionKeys("peer-no-key", sendDirection = true))

        // Trusted peer with wrong key length (e.g. 16 bytes instead of 32 bytes)
        trust.trusted += "peer-short-key"
        trust.keys["peer-short-key"] = ByteArray(16)
        assertFalse(adapter.isPairedForRadio("peer-short-key"))
        assertNull(adapter.deriveDirectionKeys("peer-short-key", sendDirection = true))
    }

    @Test
    fun testSessionPeerRegistrationAndRevocationSync() {
        val trust = TestTrustProvider()
        val adapter = RadioPairingAdapter("local-station", trust, crypto)
        val store = InMemoryRadioStore()
        val session = RadioSession("local-station", crypto, store, store)

        val peerId = "peer-charlie"
        val pairKey = ByteArray(32) { (it * 3).toByte() }

        // Before trust: cannot register
        assertFalse(adapter.registerPeer(session, peerId))
        assertFalse(session.hasPeer(peerId))

        // Add trust: registers successfully
        trust.trusted += peerId
        trust.keys[peerId] = pairKey
        assertTrue(adapter.registerPeer(session, peerId))
        assertTrue(session.hasPeer(peerId))

        // Revoke trust: adapter sync removes peer
        trust.trusted.remove(peerId)
        trust.keys.remove(peerId)
        assertFalse(adapter.registerPeer(session, peerId))
        assertFalse(session.hasPeer(peerId))
    }

    @Test
    fun testRotatingStationTagAndLabelDerivation() {
        val trust = TestTrustProvider()
        val adapter = RadioPairingAdapter("station-a", trust, crypto)
        val pairKey = ByteArray(32) { 0x42 }
        val peerId = "station-b"

        trust.trusted += peerId
        trust.keys[peerId] = pairKey

        val epoch1 = 1000L
        val epoch2 = 1001L

        val tagEpoch1 = assertNotNull(adapter.computeRotatingTag(peerId, epoch1, sendDirection = true))
        val tagEpoch2 = assertNotNull(adapter.computeRotatingTag(peerId, epoch2, sendDirection = true))

        assertEquals(4, tagEpoch1.size, "Rotating tag must be 4 bytes")
        assertEquals(4, tagEpoch2.size, "Rotating tag must be 4 bytes")
        assertFalse(tagEpoch1.contentEquals(tagEpoch2), "Station tag must rotate across epochs")

        val nowMs = epoch1 * 15 * 60 * 1000L
        val label = assertNotNull(adapter.computeStationLabel(peerId, nowMs))
        assertEquals(6, label.callsign.length, "AX.25 station callsign label must be 6 characters")
        assertTrue(label.ssid in 0..15, "SSID must be in 0..15")

        // Verify Profile A signed tag uses SPKI public key prefix
        val samplePublicKey = ByteArray(64) { (it + 5).toByte() }
        val signedTag = adapter.computeSignedTag(samplePublicKey)
        val expectedShaPrefix = crypto.sha256(samplePublicKey).copyOf(4)
        assertEquals(4, signedTag.size)
        assertContentEquals(expectedShaPrefix, signedTag)
    }
}
