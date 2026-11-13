@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.protocol.Base64Url
import com.transfer.flash.core.security.crypto.SoftwareFlashCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupRelaySealingTest {

    private val alice = SoftwareFlashCrypto()
    private val bob = SoftwareFlashCrypto()

    private fun epochKey(
        groupId: String = "g-eng",
        epoch: Long = 1L,
        keyId: String = "key-1",
        members: Set<String> = setOf("alice", "bob"),
        canSend: Boolean = true,
    ): GroupEpochKey = GroupEpochKey(
        groupId = groupId,
        epoch = epoch,
        keyId = keyId,
        keyBytes = GroupRelaySealing.generateEpochKeyBytes(),
        rosterVersion = 1L,
        memberDeviceIds = members,
        creatorDeviceId = "alice",
        createdAtMs = 1_700_000_000_000L,
        canSend = canSend,
    )

    @Test
    fun sealAndOpenRoundTripsAndHidesPlaintextFromHub() {
        val key = epochKey()
        val secretBody = "Confidential quarterly numbers €1.2M"
        val envelope = assertNotNull(
            GroupRelaySealing.seal(
                crypto = alice,
                epochKey = key,
                senderDeviceId = "alice",
                msgId = "msg-101",
                sentAt = 1_700_000_001_000L,
                innerType = "GROUP_FRAME",
                body = secretBody,
            ),
        )
        assertFalse(envelope.contains("Confidential"), "ciphertext must never leak plaintext")
        assertFalse(envelope.contains("quarterly"), "ciphertext must never leak plaintext")

        val meta = assertNotNull(GroupRelaySealing.peekMetadata(envelope))
        assertEquals("g-eng", meta.groupId)
        assertEquals(1L, meta.epoch)
        assertEquals("key-1", meta.keyId)
        assertEquals("alice", meta.senderId)
        assertEquals("GROUP_FRAME", meta.innerType)

        val opened = assertNotNull(
            GroupRelaySealing.open(
                crypto = bob,
                epochKey = key,
                senderIdentityPublicKey = alice.identityPublicKeyEncoded,
                senderDeviceId = "alice",
                expectedGroupId = "g-eng",
                msgId = "msg-101",
                sentAt = 1_700_000_001_000L,
                payload = envelope,
            ),
        )
        assertEquals(secretBody, opened.body)
        assertEquals(1L, opened.epoch)
        assertEquals("GROUP_FRAME", opened.innerType)
    }

    @Test
    fun wrongScopeEpochSignerSentAtAndTamperingAllFailClosed() {
        val key = epochKey()
        val envelope = assertNotNull(
            GroupRelaySealing.seal(
                crypto = alice,
                epochKey = key,
                senderDeviceId = "alice",
                msgId = "msg-1",
                sentAt = 1_700_000_005_000L,
                innerType = "GROUP_FRAME",
                body = "payload",
            ),
        )

        // Wrong group scope
        assertNull(
            GroupRelaySealing.open(bob, key, alice.identityPublicKeyEncoded, "alice", "g-other", "msg-1", 1_700_000_005_000L, envelope),
        )
        // Wrong msgId
        assertNull(
            GroupRelaySealing.open(bob, key, alice.identityPublicKeyEncoded, "alice", "g-eng", "msg-2", 1_700_000_005_000L, envelope),
        )
        // Tampered sentAt (hub cannot shift delivery timestamp without breaking signature + AAD)
        assertNull(
            GroupRelaySealing.open(bob, key, alice.identityPublicKeyEncoded, "alice", "g-eng", "msg-1", 1_700_000_099_000L, envelope),
        )
        // Insider signer forgery: Bob tries to verify envelope sealed/signed by Bob while claiming sender is Alice
        val forgedAsAlice = assertNotNull(
            GroupRelaySealing.seal(bob, key, "alice", "msg-1", 1_700_000_005_000L, "GROUP_FRAME", "forged"),
        )
        assertNull(
            GroupRelaySealing.open(alice, key, alice.identityPublicKeyEncoded, "alice", "g-eng", "msg-1", 1_700_000_005_000L, forgedAsAlice),
        )
        // Bit flip in ciphertext fails verification & GCM tag
        val parts = envelope.split('.').toMutableList()
        val ct = Base64Url.decodeOrNull(parts[7])!!
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        parts[7] = Base64Url.encode(ct)
        assertNull(
            GroupRelaySealing.open(bob, key, alice.identityPublicKeyEncoded, "alice", "g-eng", "msg-1", 1_700_000_005_000L, parts.joinToString(".")),
        )
        // Unsupported version rejected
        assertNull(GroupRelaySealing.peekMetadata("2." + parts.drop(1).joinToString(".")))
        assertNull(GroupRelaySealing.peekMetadata("malformed"))
    }

    @Test
    fun retiredEpochRefusesNewSealsAndExcludesRemovedSender() {
        val retired = epochKey(canSend = false)
        assertNull(
            GroupRelaySealing.seal(alice, retired, "alice", "m-1", 1_000L, "GROUP_FRAME", "nope"),
        )

        val keyExcludingCarol = epochKey(members = setOf("alice", "bob"), canSend = true)
        assertNull(
            GroupRelaySealing.seal(alice, keyExcludingCarol, "carol", "m-2", 1_000L, "GROUP_FRAME", "nope"),
        )
    }

    @Test
    fun keyDistGoldenVectorRoundTripsAndRejectsMalformedPayloads() {
        val raw = ByteArray(32) { i -> (i + 1).toByte() }
        val dist = GroupKeyDistPayload(
            version = 1,
            groupId = "grp-fixed",
            epoch = 7L,
            keyId = "kid-7",
            keyBytes = raw,
            rosterVersion = 12L,
            memberDeviceIds = setOf("alice", "bob", "carol"),
            creatorDeviceId = "alice",
            createdAtMs = 1_700_000_123_456L,
        )
        val encoded = GroupRelaySealing.encodeKeyDist(dist)
        // Golden exact prefix + deterministic sorted members encoding check:
        assertTrue(encoded.startsWith("1.Z3JwLWZpeGVk.7.a2lkLTc."))
        val decoded = assertNotNull(GroupRelaySealing.decodeKeyDist(encoded))
        assertEquals(dist.groupId, decoded.groupId)
        assertEquals(dist.epoch, decoded.epoch)
        assertEquals(dist.keyId, decoded.keyId)
        assertContentEquals(raw, decoded.keyBytes)
        assertEquals(12L, decoded.rosterVersion)
        assertEquals(setOf("alice", "bob", "carol"), decoded.memberDeviceIds)

        // Creator not in member list must fail decode
        val badCreator = encoded.replace(Base64Url.encode("alice".encodeToByteArray()) + ".1700000123456", Base64Url.encode("mallory".encodeToByteArray()) + ".1700000123456")
        assertNull(GroupRelaySealing.decodeKeyDist(badCreator))
        assertNull(GroupRelaySealing.decodeKeyDist(""))
        assertNull(GroupRelaySealing.decodeKeyDist("2.abc"))
    }

    @Test
    fun groupEpochKeyCodecAndMemoryStoreRoundTrip() = runTest {
        val k1 = epochKey(epoch = 1L, keyId = "k1", canSend = false)
        val k2 = epochKey(epoch = 2L, keyId = "k2", members = setOf("alice", "bob", "carol"), canSend = true)
        val encoded = GroupEpochKeyCodec.encode(listOf(k1, k2))
        val decoded = GroupEpochKeyCodec.decode(encoded)
        assertEquals(listOf(k1, k2), decoded)

        val store = MemoryHubGroupEpochKeyStore()
        store.saveAll(listOf(k1, k2))
        assertEquals(listOf(k1, k2), store.loadAll())
        store.clear()
        assertTrue(store.loadAll().isEmpty())
    }
}
