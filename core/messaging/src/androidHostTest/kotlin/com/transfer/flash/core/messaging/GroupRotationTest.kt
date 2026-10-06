package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.group.GroupProofResult
import com.transfer.flash.core.messaging.group.GroupProofSessions
import com.transfer.flash.core.messaging.group.GroupSecretSource
import com.transfer.flash.core.messaging.group.GroupTraffic
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupRotation
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MemberCert
import com.transfer.flash.core.messaging.protocol.TestGroupCrypto
import com.transfer.flash.core.messaging.protocol.toRotation
import com.transfer.flash.core.security.group.GroupSecret
import com.transfer.flash.core.security.group.GroupSecretCommit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GM-6 tests: Removal -> rotation, secret handover, and "Change group code" (§7B).
 */
class GroupRotationTest {

    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val names = mapOf(
        "dev-a" to "Ada",
        "dev-b" to "Bo",
        "dev-c" to "Cy",
        "dev-d" to "Di",
        "dev-e" to "Eve",
    )
    private val cryptos = names.keys.associateWith { TestGroupCrypto() }
    private val nodes = LinkedHashMap<String, Node>()
    private val wire = ConcurrentLinkedQueue<Envelope>()
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()
    private val repoScopes = mutableListOf<CoroutineScope>()

    private class Node(
        val id: String,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val deliveryDao: InMemoryGroupDeliveryDao = InMemoryGroupDeliveryDao(),
        val readCursorDao: InMemoryReadCursorDao = InMemoryReadCursorDao(),
        val secretStore: InMemoryGroupSecretStore = InMemoryGroupSecretStore(),
        val rotationDao: InMemoryGroupRotationDao = InMemoryGroupRotationDao(),
        val inviteDao: InMemoryGroupInviteDao = InMemoryGroupInviteDao(),
        val joinRequestDao: InMemoryGroupJoinRequestDao = InMemoryGroupJoinRequestDao(),
    ) {
        lateinit var repo: RealFlashChatRepository
    }

    private data class Envelope(val from: String, val to: String, val frame: GroupWireFrame)

    @After
    fun tearDown() {
        repoScopes.forEach { it.cancel() }
        dispatcher.close()
    }

    private fun node(id: String): Node = nodes.getOrPut(id) {
        val created = Node(id)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        repoScopes += scope
        created.repo = RealFlashChatRepository(
            localDeviceId = id,
            localDisplayName = names.getValue(id),
            messageDao = created.messageDao,
            conversationDao = created.conversationDao,
            outboxDao = created.outboxDao,
            receiptDao = NoopReceiptDao(),
            draftDao = NoopDraftDao(),
            recentSearchDao = NoopRecentSearchDao(),
            reactionDao = NoopReactionDao(),
            groupMemberDao = created.memberDao,
            groupDeliveryDao = created.deliveryDao,
            readCursorDao = created.readCursorDao,
            groupSecretStore = created.secretStore,
            groupRotationDao = created.rotationDao,
            groupInviteDao = created.inviteDao,
            groupJoinRequestDao = created.joinRequestDao,
            peerFeatures = { setOf(GroupProofSessions.FEATURE_GS1) },
            isTrustedPeer = { true },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
            groupCrypto = cryptos.getValue(id),
            pinnedFingerprint = { peer -> cryptos[peer]?.publicKey?.let { GroupCanonical.fingerprintHex(cryptos.getValue(id), it) } },
            peerGroupProtocol = { GroupPolicy.V2_PROTOCOL },
            peerIdentityKey = { peer -> cryptos[peer]?.publicKey },
        )
        created
    }

    private fun transmit(from: String, to: String, frame: GroupWireFrame): Boolean {
        if (!isConnected(from, to)) return false
        wire.add(Envelope(from, to, frame))
        return true
    }

    private fun pairKey(x: String, y: String): String = listOf(x, y).sorted().joinToString("|")
    private fun isConnected(x: String, y: String): Boolean = pairKey(x, y) in liveSessions

    private suspend fun mesh(vararg ids: String) {
        ids.forEach { node(it) }
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                connect(ids[i], ids[j])
            }
        }
        settle()
    }

    private suspend fun connect(x: String, y: String) {
        liveSessions.add(pairKey(x, y))
        for ((self, peer) in listOf(x to y, y to x)) {
            val repo = nodes.getValue(self).repo
            repo.notifyPeerSessionUp(peer)
            repo.reconcileGroupMembership(peer)
        }
    }

    private fun disconnect(x: String, y: String) {
        liveSessions.remove(pairKey(x, y))
    }

    private suspend fun settle(pauseMs: Long = 100) {
        repeat(4) {
            drain()
            delay(pauseMs)
        }
        drain()
    }

    private suspend fun drain() {
        var guard = 0
        while (true) {
            val envelope = wire.poll() ?: return
            if (guard++ > 1000) error("frame storm in test")
            nodes.getValue(envelope.to).repo.onInboundGroupWireFrame(envelope.from, envelope.frame)
        }
    }

    private suspend fun createGroup(owner: String, name: String, vararg invitees: String): String {
        val result = nodes.getValue(owner).repo.createGroup(name, invitees.toSet())
        assertTrue("create must succeed: $result", result is FlashResult.Success)
        settle()
        val groupId = (result as FlashResult.Success).value

        // Seed initial secret at epoch 1 for all members so they hold an active secret
        val secret1 = GroupSecret.generate()
        val commit1 = GroupSecretCommit.ofHex(groupId, 1L, secret1)
        val allMembers = listOf(owner) + invitees
        allMembers.forEach { id ->
            node(id).secretStore.put(
                StoredGroupSecret(
                    groupId = groupId,
                    epoch = 1L,
                    secret = secret1,
                    commit = commit1,
                    source = GroupSecretSource.CREATED,
                    receivedAtMs = 1000L,
                ),
            )
        }
        return groupId
    }

    // ------------------------------------------------------------------------- Tests

    @Test
    fun `remove then rotate then offline member gets secret from non-admin member`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Squad", "dev-b", "dev-c", "dev-d")

        // Offline dev-c
        disconnect("dev-a", "dev-c")
        disconnect("dev-b", "dev-c")
        disconnect("dev-c", "dev-d")

        // dev-a (owner) removes dev-d
        val remResult = node("dev-a").repo.removeGroupMember(groupId, "dev-d")
        assertTrue(remResult is FlashResult.Success)
        settle()

        // dev-a rotated secret to epoch 2
        val rotA = node("dev-a").rotationDao.getLatestForGroup(groupId)
        assertNotNull("dev-a must hold rotation notice", rotA)
        assertEquals(2L, rotA!!.newEpoch)
        assertEquals(listOf("dev-d"), rotA.removedIds.split(',').filter { it.isNotBlank() })
        val secretA2 = node("dev-a").secretStore.get(groupId, 2L)
        assertNotNull("dev-a must hold epoch 2 secret", secretA2)

        // dev-b (regular member) received removal bundle, requested secret from dev-a, and holds epoch 2 secret
        val rotB = node("dev-b").rotationDao.getLatestForGroup(groupId)
        assertNotNull("dev-b must hold rotation notice", rotB)
        assertEquals(2L, rotB!!.newEpoch)
        val secretB2 = node("dev-b").secretStore.get(groupId, 2L)
        assertNotNull("dev-b must have received epoch 2 secret from dev-a", secretB2)
        assertEquals(secretA2!!.secret, secretB2!!.secret)

        // Now disconnect dev-a (owner goes offline), reconnect dev-c with dev-b only
        disconnect("dev-a", "dev-b")
        connect("dev-b", "dev-c")
        settle()

        // dev-c was offline during removal, now connected only to dev-b (a regular non-admin member)
        val rotC = node("dev-c").rotationDao.getLatestForGroup(groupId)
        assertNotNull("dev-c must receive rotation notice from dev-b", rotC)
        assertEquals(2L, rotC!!.newEpoch)

        val secretC2 = node("dev-c").secretStore.get(groupId, 2L)
        assertNotNull("dev-c must get epoch 2 secret from non-admin member dev-b", secretC2)
        assertEquals(secretA2.secret, secretC2!!.secret)
        assertEquals(GroupSecretSource.HANDOVER, secretC2.source)
    }

    @Test
    fun `removed device asks for secret and is refused`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Squad", "dev-b", "dev-c")

        // dev-a removes dev-c
        val remResult = node("dev-a").repo.removeGroupMember(groupId, "dev-c")
        assertTrue(remResult is FlashResult.Success)
        settle()

        // dev-c attempts to request epoch 2 secret from dev-b
        node("dev-c").secretStore.forget(groupId)
        node("dev-b").repo.onInboundGroupWireFrame(
            "dev-c",
            GroupWireFrame.GsSecretRequest(groupId, from = "dev-c", epoch = 2L),
        )
        settle()

        // dev-c must NOT receive the secret
        val secretC = node("dev-c").secretStore.get(groupId, 2L)
        assertNull("removed device must be refused secret handover", secretC)
    }

    @Test
    fun `secret not matching commit is refused`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Squad", "dev-b")

        // dev-a rotates group secret manually
        val rotResult = node("dev-a").repo.changeGroupCode(groupId)
        assertTrue(rotResult is FlashResult.Success)
        settle()

        val rotA = node("dev-a").rotationDao.getLatestForGroup(groupId)
        assertNotNull(rotA)
        assertEquals(2L, rotA!!.newEpoch)

        // Forged secret with wrong commit sent to dev-b
        val fakeSecret = GroupSecret.generate()
        node("dev-b").repo.onInboundGroupWireFrame(
            "dev-a",
            GroupWireFrame.GsSecret(groupId, from = "dev-a", epoch = 2L, secret = fakeSecret.toByteArray()),
        )
        settle()

        // dev-b must have rejected fake secret
        val secretB = node("dev-b").secretStore.get(groupId, 2L)
        // If it stored anything, it must match the authentic commit, not fakeSecret
        if (secretB != null) {
            assertFalse("fake secret must not be stored", secretB.secret.constantTimeEquals(fakeSecret))
        }
    }

    @Test
    fun `two admins rotate concurrently and both converge`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Squad", "dev-b", "dev-c", "dev-d")

        // Promote dev-b to admin
        val promo = node("dev-a").repo.promoteAdmin(groupId, "dev-b")
        assertTrue(promo is FlashResult.Success)
        settle()

        // Partition network: dev-a and dev-b cannot talk temporarily
        disconnect("dev-a", "dev-b")

        // Admin A removes dev-c -> creates rotation at epoch 2
        val remC = node("dev-a").repo.removeGroupMember(groupId, "dev-c")
        assertTrue(remC is FlashResult.Success)

        // Admin B removes dev-d -> creates rotation at epoch 2
        val remD = node("dev-b").repo.removeGroupMember(groupId, "dev-d")
        assertTrue(remD is FlashResult.Success)

        val rotA = node("dev-a").rotationDao.getLatestForGroup(groupId)!!
        val rotB = node("dev-b").rotationDao.getLatestForGroup(groupId)!!
        assertEquals(2L, rotA.newEpoch)
        assertEquals(2L, rotB.newEpoch)

        // Reconnect dev-a and dev-b
        connect("dev-a", "dev-b")
        settle()

        // The admin whose rotation lost must have re-rotated at epoch 3
        val finalRotA = node("dev-a").rotationDao.getLatestForGroup(groupId)!!
        val finalRotB = node("dev-b").rotationDao.getLatestForGroup(groupId)!!

        assertEquals("both admins must converge to same epoch", finalRotA.newEpoch, finalRotB.newEpoch)
        assertEquals(3L, finalRotA.newEpoch)

        // Verify both removals are accounted for across rotations
        val allRotsA = node("dev-a").rotationDao.getAllForGroup(groupId)
        val allRemovedA = allRotsA.flatMap { it.removedIds.split(',').filter { id -> id.isNotBlank() } }.toSet()
        assertTrue("dev-c must be removed", allRemovedA.contains("dev-c"))
        assertTrue("dev-d must be removed", allRemovedA.contains("dev-d"))
    }

    @Test
    fun `crash between tombstone and rotation is recovered on start`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Squad", "dev-b")

        // Simulate crash: tombstone for dev-b written by dev-a, but rotation notice NOT written
        val currentMember = node("dev-a").memberDao.member(groupId, "dev-b")!!
        node("dev-a").memberDao.upsert(
            currentMember.copy(
                isActive = false,
                issuerId = "dev-a",
                membershipVersion = currentMember.membershipVersion + 1L,
            ),
        )

        // Verify dev-a holds no rotation covering dev-b yet
        val latestBefore = node("dev-a").rotationDao.getLatestForGroup(groupId)
        assertNull("no rotation notice before recovery", latestBefore)

        // Call startup recovery on dev-a
        val recovered = node("dev-a").repo.bundleForGroup(groupId) // triggers or we call directly via signedGroups
        // Let's create a SignedGroups instance directly to test recoverUnrotatedTombstones
        val signedGroups = SignedGroups(
            localDeviceId = "dev-a",
            localDisplayName = "Ada",
            crypto = cryptos.getValue("dev-a"),
            conversationDao = node("dev-a").conversationDao,
            members = node("dev-a").memberDao,
            isPaired = { true },
            pinnedFingerprint = { null },
            nowMs = { 2000L },
            newId = { "id-1" },
            groupRotationDao = node("dev-a").rotationDao,
            groupSecretStore = node("dev-a").secretStore,
        )

        val rotations = signedGroups.recoverUnrotatedTombstones()
        assertEquals(1, rotations.size)
        assertEquals(2L, rotations.first().newEpoch)
        assertEquals(listOf("dev-b"), rotations.first().removedIds)

        // Verify rotation notice is stored in database
        val latestAfter = node("dev-a").rotationDao.getLatestForGroup(groupId)
        assertNotNull("rotation notice created by recovery", latestAfter)
        assertEquals(listOf("dev-b"), latestAfter!!.removedIds.split(',').filter { it.isNotBlank() })
    }

    @Test
    fun `old invite after rotation is refused and gives no notice`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Squad", "dev-b")

        // Group is at epoch 1. Prospective joiner dev-e holds epoch 1 secret
        val secret1 = node("dev-a").secretStore.get(groupId, 1L)!!
        node("dev-e").secretStore.put(
            StoredGroupSecret(
                groupId = groupId,
                epoch = 1L,
                secret = secret1.secret,
                commit = secret1.commit,
                source = GroupSecretSource.INVITE,
                receivedAtMs = 1000L,
            ),
        )

        // Admin changes group code -> advances to epoch 2
        val changeResult = node("dev-a").repo.changeGroupCode(groupId)
        assertTrue(changeResult is FlashResult.Success)
        settle()

        // dev-e connects to dev-a and attempts mutual proof using stale epoch 1
        connect("dev-a", "dev-e")
        settle()

        val proofDeferred = async(dispatcher) {
            node("dev-e").repo.initiateGroupProof("dev-a", groupId, 1L)
        }
        settle()
        val proofResult = proofDeferred.await()
        // Responder answered stale, but because dev-e is NOT an active member, no GsStale was sent!
        assertEquals(GroupProofResult.STALE, proofResult)

        // dev-e holds no rotation notice
        val rotE = node("dev-e").rotationDao.getLatestForGroup(groupId)
        assertNull("prospective joiner with old invite must receive no rotation notice", rotE)
    }

    @Test
    fun `admin manual rotation changes group code and keeps members intact`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Squad", "dev-b")

        // Check epoch 1
        assertEquals(1L, node("dev-a").secretStore.current(groupId)?.epoch)
        assertEquals(1L, node("dev-b").secretStore.current(groupId)?.epoch)

        // dev-a changes group code
        val res = node("dev-a").repo.changeGroupCode(groupId)
        assertTrue(res is FlashResult.Success)
        settle()

        // Both dev-a and dev-b hold epoch 2 secret
        val secA = node("dev-a").secretStore.current(groupId)
        val secB = node("dev-b").secretStore.current(groupId)

        assertNotNull(secA)
        assertNotNull(secB)
        assertEquals(2L, secA!!.epoch)
        assertEquals(2L, secB!!.epoch)
        assertEquals(secA.secret, secB.secret)

        // Both remain active members
        assertTrue(node("dev-a").memberDao.member(groupId, "dev-a")!!.isActive)
        assertTrue(node("dev-a").memberDao.member(groupId, "dev-b")!!.isActive)
    }
}
