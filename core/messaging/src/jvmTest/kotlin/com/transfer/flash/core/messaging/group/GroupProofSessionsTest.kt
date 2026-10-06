package com.transfer.flash.core.messaging.group

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.security.group.GroupSecret
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class GroupProofSessionsTest {

    private class TestCrypto(override val publicKey: ByteArray) : GroupCrypto {
        override fun sign(data: ByteArray): ByteArray = ByteArray(64)
        override fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean = true
        override fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)
    }

    private class InMemoryGroupSecretStore : GroupSecretStore {
        private val records = mutableMapOf<Pair<String, Long>, StoredGroupSecret>()
        private val currentEpochs = mutableMapOf<String, Long>()

        fun set(groupId: String, epoch: Long, secret: ByteArray) {
            val rec = StoredGroupSecret(
                groupId = groupId,
                epoch = epoch,
                secret = GroupSecret.fromBytes(secret),
                source = GroupSecretSource.CREATED,
                receivedAtMs = 1000L,
            )
            records[groupId to epoch] = rec
            val cur = currentEpochs[groupId] ?: 0L
            if (epoch >= cur) currentEpochs[groupId] = epoch
        }

        override suspend fun get(groupId: String, epoch: Long): StoredGroupSecret? =
            records[groupId to epoch]

        override suspend fun current(groupId: String): StoredGroupSecret? {
            val cur = currentEpochs[groupId] ?: return null
            return records[groupId to cur]
        }

        override suspend fun put(record: StoredGroupSecret) {
            records[record.groupId to record.epoch] = record
            val cur = currentEpochs[record.groupId] ?: 0L
            if (record.epoch >= cur) currentEpochs[record.groupId] = record.epoch
        }

        override suspend fun forget(groupId: String) {
            val toRemove = records.keys.filter { it.first == groupId }
            toRemove.forEach { records.remove(it) }
            currentEpochs.remove(groupId)
        }
    }

    private class MutableTimeSource(var timeMs: Long = 1_000_000L) : FlashTimeSource {
        override fun nowMs(): Long = timeMs
    }

    private val aliceId = "alice-dev-01"
    private val bobId = "bob-dev-02"

    private val aliceKey = "alice-pubkey-bytes-123456789012".encodeToByteArray()
    private val bobKey = "bob-pubkey-bytes-09876543210987".encodeToByteArray()

    private val aliceCrypto = TestCrypto(aliceKey)
    private val bobCrypto = TestCrypto(bobKey)

    private val sharedSecret = ByteArray(32) { (it + 7).toByte() }
    private val wrongSecret = ByteArray(32) { (it + 99).toByte() }

    private val testGroupId = "g2-family-vault-xyz"

    @Test
    fun test_successful_mutual_proof() = runBlocking {
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val bobStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }

        var aliceSessionsRef: GroupProofSessions? = null
        var bobSessionsRef: GroupProofSessions? = null

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { if (it == bobId) bobKey else null },
            peerFeatures = { if (it == bobId) setOf("gs1") else emptySet() },
            groupSecretStore = aliceStore,
            sendFrame = { peer, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> bobSessionsRef?.onHello(aliceId, frame)
                    is GroupWireFrame.GsChallenge -> bobSessionsRef?.onChallenge(aliceId, frame)
                    is GroupWireFrame.GsProof -> bobSessionsRef?.onProof(aliceId, frame)
                    is GroupWireFrame.GsResult -> bobSessionsRef?.onResult(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceSessionsRef = aliceSessions

        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { if (it == aliceId) aliceKey else null },
            peerFeatures = { if (it == aliceId) setOf("gs1") else emptySet() },
            groupSecretStore = bobStore,
            sendFrame = { peer, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> aliceSessionsRef?.onHello(bobId, frame)
                    is GroupWireFrame.GsChallenge -> aliceSessionsRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsProof -> aliceSessionsRef?.onProof(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceSessionsRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobSessionsRef = bobSessions

        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
        assertFalse(bobSessions.hasProved(aliceId, testGroupId))

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        assertEquals(GroupProofResult.OK, result)

        assertTrue(aliceSessions.hasProved(bobId, testGroupId))
        assertTrue(bobSessions.hasProved(aliceId, testGroupId))
    }

    @Test
    fun test_wrong_secret_fails_proof() = runBlocking {
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val bobStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, wrongSecret) }

        var aliceSessionsRef: GroupProofSessions? = null
        var bobSessionsRef: GroupProofSessions? = null

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { if (it == bobId) bobKey else null },
            peerFeatures = { if (it == bobId) setOf("gs1") else emptySet() },
            groupSecretStore = aliceStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> bobSessionsRef?.onHello(aliceId, frame)
                    is GroupWireFrame.GsChallenge -> bobSessionsRef?.onChallenge(aliceId, frame)
                    is GroupWireFrame.GsProof -> bobSessionsRef?.onProof(aliceId, frame)
                    is GroupWireFrame.GsResult -> bobSessionsRef?.onResult(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceSessionsRef = aliceSessions

        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { if (it == aliceId) aliceKey else null },
            peerFeatures = { if (it == aliceId) setOf("gs1") else emptySet() },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> aliceSessionsRef?.onHello(bobId, frame)
                    is GroupWireFrame.GsChallenge -> aliceSessionsRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsProof -> aliceSessionsRef?.onProof(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceSessionsRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobSessionsRef = bobSessions

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        assertEquals(GroupProofResult.FAILED, result)

        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
        assertFalse(bobSessions.hasProved(aliceId, testGroupId))
    }

    @Test
    fun test_stale_epoch_handling() = runBlocking {
        // Alice only holds epoch 1. Bob holds epoch 1 and current epoch 2.
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val epoch2Secret = ByteArray(32) { (it + 42).toByte() }
        val bobStore = InMemoryGroupSecretStore().apply {
            set(testGroupId, 1L, sharedSecret)
            set(testGroupId, 2L, epoch2Secret)
        }

        var aliceSessionsRef: GroupProofSessions? = null
        var bobSessionsRef: GroupProofSessions? = null

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { if (it == bobId) bobKey else null },
            peerFeatures = { if (it == bobId) setOf("gs1") else emptySet() },
            groupSecretStore = aliceStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> bobSessionsRef?.onHello(aliceId, frame)
                    is GroupWireFrame.GsChallenge -> bobSessionsRef?.onChallenge(aliceId, frame)
                    is GroupWireFrame.GsProof -> bobSessionsRef?.onProof(aliceId, frame)
                    is GroupWireFrame.GsResult -> bobSessionsRef?.onResult(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceSessionsRef = aliceSessions

        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { if (it == aliceId) aliceKey else null },
            peerFeatures = { if (it == aliceId) setOf("gs1") else emptySet() },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> aliceSessionsRef?.onHello(bobId, frame)
                    is GroupWireFrame.GsChallenge -> aliceSessionsRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsProof -> aliceSessionsRef?.onProof(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceSessionsRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobSessionsRef = bobSessions

        // Alice proves knowledge of epoch 1. Bob sees epoch 1 is valid, but current is 2, so returns STALE.
        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        assertEquals(GroupProofResult.STALE, result)

        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
        assertFalse(bobSessions.hasProved(aliceId, testGroupId))
    }

    @Test
    fun test_stranger_unknown_group_dummy_responder_privacy() = runBlocking {
        // Bob knows NOTHING about testGroupId (empty store).
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val bobStore = InMemoryGroupSecretStore()

        var aliceSessionsRef: GroupProofSessions? = null
        var bobSessionsRef: GroupProofSessions? = null
        val bobSentFrames = mutableListOf<GroupWireFrame>()

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { if (it == bobId) bobKey else null },
            peerFeatures = { if (it == bobId) setOf("gs1") else emptySet() },
            groupSecretStore = aliceStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> bobSessionsRef?.onHello(aliceId, frame)
                    is GroupWireFrame.GsChallenge -> bobSessionsRef?.onChallenge(aliceId, frame)
                    is GroupWireFrame.GsProof -> bobSessionsRef?.onProof(aliceId, frame)
                    is GroupWireFrame.GsResult -> bobSessionsRef?.onResult(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceSessionsRef = aliceSessions

        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { if (it == aliceId) aliceKey else null },
            peerFeatures = { if (it == aliceId) setOf("gs1") else emptySet() },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                bobSentFrames.add(frame)
                when (frame) {
                    is GroupWireFrame.GsHello -> aliceSessionsRef?.onHello(bobId, frame)
                    is GroupWireFrame.GsChallenge -> aliceSessionsRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsProof -> aliceSessionsRef?.onProof(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceSessionsRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobSessionsRef = bobSessions

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        // Responder privacy: Bob sent a dummy challenge rather than rejecting upfront.
        // Alice detected invalid challenge MAC and failed locally without proceeding.
        assertEquals(GroupProofResult.FAILED, result)
        assertEquals(1, bobSentFrames.size)
        assertTrue(bobSentFrames[0] is GroupWireFrame.GsChallenge)

        // If an attacker follows up with a GsProof to Bob:
        bobSessions.onProof(aliceId, GroupWireFrame.GsProof(testGroupId, aliceId, 1L, ByteArray(32)))
        assertEquals(2, bobSentFrames.size)
        val res = bobSentFrames[1] as GroupWireFrame.GsResult
        assertFalse(res.ok)
        assertEquals("failed", res.reason)

        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
        assertFalse(bobSessions.hasProved(aliceId, testGroupId))
    }

    @Test
    fun test_relay_attack_mitm_tls_mismatch_fails() = runBlocking {
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val bobStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }

        val eveKey = "eve-attacker-key-99999999999999".encodeToByteArray()

        var aliceSessionsRef: GroupProofSessions? = null
        var bobSessionsRef: GroupProofSessions? = null

        // Alice thinks she speaks to Bob (bobKey).
        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { if (it == bobId) bobKey else null },
            peerFeatures = { setOf("gs1") },
            groupSecretStore = aliceStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> bobSessionsRef?.onHello(aliceId, frame)
                    is GroupWireFrame.GsChallenge -> bobSessionsRef?.onChallenge(aliceId, frame)
                    is GroupWireFrame.GsProof -> bobSessionsRef?.onProof(aliceId, frame)
                    is GroupWireFrame.GsResult -> bobSessionsRef?.onResult(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceSessionsRef = aliceSessions

        // Bob's connection sees Eve's key for Alice instead of Alice's real key (MITM relay).
        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { if (it == aliceId) eveKey else null },
            peerFeatures = { setOf("gs1") },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> aliceSessionsRef?.onHello(bobId, frame)
                    is GroupWireFrame.GsChallenge -> aliceSessionsRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsProof -> aliceSessionsRef?.onProof(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceSessionsRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobSessionsRef = bobSessions

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        // Relay binding fails because TLS fingerprints do not match the expected transcript!
        assertEquals(GroupProofResult.FAILED, result)
        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
    }

    @Test
    fun test_timeout_after_20_seconds() = runBlocking {
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val timeSource = MutableTimeSource(1_000_000L)

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { bobKey },
            peerFeatures = { setOf("gs1") },
            groupSecretStore = aliceStore,
            sendFrame = { _, _ -> true }, // Bob never responds
            timeSource = timeSource,
            timeoutMs = 50L, // Short timeout for test
        )

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        assertEquals(GroupProofResult.TIMEOUT, result)
        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
    }

    @Test
    fun test_rate_limiter_blocks_excessive_failures() = runBlocking {
        val timeSource = MutableTimeSource(1_000_000L)
        val bobStore = InMemoryGroupSecretStore() // Empty store -> failures

        var challengeCount = 0
        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { aliceKey },
            peerFeatures = { setOf("gs1") },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                if (frame is GroupWireFrame.GsChallenge) challengeCount++
                true
            },
            timeSource = timeSource,
        )

        val dummyNonce = ByteArray(16) { 1 }

        // Attempt 5 failed proofs
        for (i in 1..5) {
            bobSessions.onHello(aliceId, GroupWireFrame.GsHello(testGroupId, aliceId, 1L, dummyNonce))
            assertEquals(i, challengeCount)
            bobSessions.onProof(aliceId, GroupWireFrame.GsProof(testGroupId, aliceId, 1L, ByteArray(32)))
        }

        // 6th attempt should be blocked by rate limiter: onHello does not send a challenge!
        bobSessions.onHello(aliceId, GroupWireFrame.GsHello(testGroupId, aliceId, 1L, dummyNonce))
        assertEquals(5, challengeCount) // Did not increment!
    }

    @Test
    fun test_session_down_clears_proved_groups() = runBlocking {
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        val bobStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }

        var aliceSessionsRef: GroupProofSessions? = null
        var bobSessionsRef: GroupProofSessions? = null

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { bobKey },
            peerFeatures = { setOf("gs1") },
            groupSecretStore = aliceStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> bobSessionsRef?.onHello(aliceId, frame)
                    is GroupWireFrame.GsChallenge -> bobSessionsRef?.onChallenge(aliceId, frame)
                    is GroupWireFrame.GsProof -> bobSessionsRef?.onProof(aliceId, frame)
                    is GroupWireFrame.GsResult -> bobSessionsRef?.onResult(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceSessionsRef = aliceSessions

        val bobSessions = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { aliceKey },
            peerFeatures = { setOf("gs1") },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> aliceSessionsRef?.onHello(bobId, frame)
                    is GroupWireFrame.GsChallenge -> aliceSessionsRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsProof -> aliceSessionsRef?.onProof(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceSessionsRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobSessionsRef = bobSessions

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        assertEquals(GroupProofResult.OK, result)
        assertTrue(aliceSessions.hasProved(bobId, testGroupId))

        // Session down callback
        aliceSessions.onSessionDown(bobId)
        // In-memory proof MUST be cleared!
        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
    }

    @Test
    fun test_unsupported_when_peer_lacks_gs1_feature() = runBlocking {
        val aliceStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        var frameSent = false

        val aliceSessions = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { bobKey },
            peerFeatures = { emptySet() }, // NO gs1 advertised!
            groupSecretStore = aliceStore,
            sendFrame = { _, _ ->
                frameSent = true
                true
            },
        )

        val result = aliceSessions.initiateProof(bobId, testGroupId, 1L)
        assertEquals(GroupProofResult.UNSUPPORTED, result)
        assertFalse(frameSent) // No frames sent!
        assertFalse(aliceSessions.hasProved(bobId, testGroupId))
    }

    /**
     * Two callers start the same proof at once: joining an invite triggers one directly, and the hint dialer
     * triggers another when it finds the inviter already connected. The secret lookup suspends between the
     * "already in flight" check and the registration, so both used to pass the check, the second initiator
     * overwrote the first, and each GsChallenge then failed against the wrong nonce: no proof, no join request.
     */
    @Test
    fun test_two_simultaneous_initiations_share_one_proof() = runBlocking {
        val slowStore = object : GroupSecretStore {
            private val inner = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
            override suspend fun get(groupId: String, epoch: Long): StoredGroupSecret? {
                delay(50)
                return inner.get(groupId, epoch)
            }
            override suspend fun current(groupId: String) = inner.current(groupId)
            override suspend fun put(record: StoredGroupSecret) = inner.put(record)
            override suspend fun forget(groupId: String) = inner.forget(groupId)
        }
        val bobStore = InMemoryGroupSecretStore().apply { set(testGroupId, 1L, sharedSecret) }
        var aliceRef: GroupProofSessions? = null
        var bobRef: GroupProofSessions? = null
        var hellos = 0

        val alice = GroupProofSessions(
            localDeviceId = aliceId,
            groupCrypto = aliceCrypto,
            peerIdentityKey = { if (it == bobId) bobKey else null },
            peerFeatures = { if (it == bobId) setOf("gs1") else emptySet() },
            groupSecretStore = slowStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsHello -> { hellos++; delay(20); bobRef?.onHello(aliceId, frame) }
                    is GroupWireFrame.GsProof -> bobRef?.onProof(aliceId, frame)
                    else -> Unit
                }
                true
            },
        )
        aliceRef = alice
        val bob = GroupProofSessions(
            localDeviceId = bobId,
            groupCrypto = bobCrypto,
            peerIdentityKey = { if (it == aliceId) aliceKey else null },
            peerFeatures = { if (it == aliceId) setOf("gs1") else emptySet() },
            groupSecretStore = bobStore,
            sendFrame = { _, frame ->
                when (frame) {
                    is GroupWireFrame.GsChallenge -> aliceRef?.onChallenge(bobId, frame)
                    is GroupWireFrame.GsResult -> aliceRef?.onResult(bobId, frame)
                    else -> Unit
                }
                true
            },
        )
        bobRef = bob

        val results = listOf(
            async { alice.initiateProof(bobId, testGroupId, 1L) },
            async { alice.initiateProof(bobId, testGroupId, 1L) },
        ).awaitAll()

        assertEquals("both callers see the one proof succeed", listOf(GroupProofResult.OK, GroupProofResult.OK), results)
        assertEquals("only one hello went out", 1, hellos)
        assertTrue(alice.hasProved(bobId, testGroupId))
        assertTrue(bob.hasProved(aliceId, testGroupId))
    }
}
