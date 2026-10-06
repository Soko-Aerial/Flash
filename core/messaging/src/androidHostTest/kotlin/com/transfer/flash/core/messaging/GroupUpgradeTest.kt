package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.group.GroupProofSessions
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupRotation
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.TestGroupCrypto
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.security.group.GroupInviteCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GM-7 tests: Give existing v2 groups a secret (O-11) (§7B lines 1930–1944).
 *
 * Verifies:
 * 1. Groups created before GM can invite too, without a new group:
 *    - An admin device holds a v2 group with no rotation notice, creates epoch 1 with a notice
 *      (reason UPGRADE, prevEpoch = 0).
 *    - Secret then spreads to other members by handover (GM-6).
 *    - Non-admin member cannot invite before upgrade ("An admin needs to open this group on the new version first"),
 *      but can successfully invite after upgrade & handover.
 * 2. Concurrent upgrades by two admins resolve with the GM-6 rule (smaller rotationId wins, loser re-rotates at epoch 2).
 * 3. A member on an old build is unaffected (ignores the notice, chat works uninterrupted).
 * 4. Legacy g- groups are never upgraded (D5).
 */
class GroupUpgradeTest {

    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val names = mapOf(
        "dev-a" to "Ada",
        "dev-b" to "Bo",
        "dev-c" to "Cy",
        "dev-d" to "Di",
    )
    private val cryptos = names.keys.associateWith { TestGroupCrypto() }
    private val nodes = LinkedHashMap<String, Node>()
    private val wire = ConcurrentLinkedQueue<Envelope>()
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()
    private val repoScopes = mutableListOf<CoroutineScope>()

    private class Node(
        val id: String,
        val isOldBuild: Boolean = false,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val deliveryDao: InMemoryGroupDeliveryDao = InMemoryGroupDeliveryDao(),
        val readCursorDao: InMemoryReadCursorDao = InMemoryReadCursorDao(),
        val secretStore: InMemoryGroupSecretStore? = if (isOldBuild) null else InMemoryGroupSecretStore(),
        val rotationDao: InMemoryGroupRotationDao? = if (isOldBuild) null else InMemoryGroupRotationDao(),
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

    private fun node(id: String, isOldBuild: Boolean = false): Node = nodes.getOrPut(id) {
        val created = Node(id, isOldBuild = isOldBuild)
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
            peerFeatures = { if (isOldBuild) emptySet() else setOf(GroupProofSessions.FEATURE_GS1) },
            isTrustedPeer = { true },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
            groupCrypto = cryptos.getValue(id),
            pinnedFingerprint = { peer -> cryptos[peer]?.publicKey?.let { GroupCanonical.fingerprintHex(cryptos.getValue(id), it) } },
            peerGroupProtocol = { if (isOldBuild) 1 else GroupPolicy.V2_PROTOCOL },
            peerIdentityKey = { peer -> cryptos[peer]?.publicKey },
        )
        created
    }

    private fun signedGroups(id: String): SignedGroups =
        node(id).repo.signedGroupsForTesting() ?: error("no signed groups for $id")

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

    private suspend fun say(id: String, groupId: String, text: String) {
        val repo = node(id).repo
        repo.openConversation(groupId)
        repo.sendText(text)
        settle()
    }

    private suspend fun createPreGmGroup(owner: String, name: String, vararg invitees: String): String {
        val result = nodes.getValue(owner).repo.createGroup(name, invitees.toSet())
        assertTrue("create must succeed: $result", result is FlashResult.Success)
        settle()
        val groupId = (result as FlashResult.Success).value
        // Ensure no secrets or rotation notices were seeded: simulates pre-GM group
        (listOf(owner) + invitees.toList()).forEach { id: String ->
            node(id).secretStore?.let { store ->
                assertNull(store.current(groupId))
            }
            node(id).rotationDao?.let { dao ->
                assertTrue(dao.getAllForGroup(groupId).isEmpty())
            }
        }
        return groupId
    }

    @Test
    fun `existing v2 group without secret allows non-admin invite only after admin upgrades and hands over secret`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createPreGmGroup("dev-a", "Pre-GM Group", "dev-b")

        // dev-b (regular member) attempts to invite before upgrade
        val inviteBefore = node("dev-b").repo.inviteFor(groupId)
        assertTrue("dev-b invite must fail before admin upgrade: $inviteBefore", inviteBefore is FlashResult.Failure)
        assertEquals(
            "An admin needs to open this group on the new version first",
            ((inviteBefore as FlashResult.Failure).error as FlashError.Unknown).message,
        )

        // dev-a (owner / admin) opens / upgrades the group
        val inviteAdmin = node("dev-a").repo.inviteFor(groupId)
        assertTrue("dev-a invite must succeed: $inviteAdmin", inviteAdmin is FlashResult.Success)

        // Verify dev-a created epoch 1 with reason UPGRADE and prevEpoch 0
        val rotA = node("dev-a").rotationDao!!.getLatestForGroup(groupId)
        assertNotNull("dev-a must hold rotation notice", rotA)
        assertEquals(1L, rotA!!.newEpoch)
        assertEquals(0L, rotA.prevEpoch)
        assertEquals(GroupRotation.REASON_UPGRADE, rotA.reason)
        assertEquals("dev-a", rotA.adminId)

        val secretA = node("dev-a").secretStore!!.current(groupId)
        assertNotNull("dev-a must hold epoch 1 secret", secretA)
        assertEquals(1L, secretA!!.epoch)

        // Settle wire so GsStale, GsSecretRequest, and GsSecret are exchanged
        settle()

        // dev-b must now hold the epoch 1 secret handed over from dev-a
        val rotB = node("dev-b").rotationDao!!.getLatestForGroup(groupId)
        assertNotNull("dev-b must hold rotation notice", rotB)
        assertEquals(1L, rotB!!.newEpoch)
        assertEquals(0L, rotB.prevEpoch)
        assertEquals(GroupRotation.REASON_UPGRADE, rotB.reason)

        val secretB = node("dev-b").secretStore!!.current(groupId)
        assertNotNull("dev-b must hold handed over secret", secretB)
        assertEquals(1L, secretB!!.epoch)
        assertEquals(secretA.secret, secretB.secret)

        // Now dev-b calls inviteFor(groupId) -> must succeed!
        val inviteAfter = node("dev-b").repo.inviteFor(groupId)
        assertTrue("dev-b invite must succeed after handover: $inviteAfter", inviteAfter is FlashResult.Success)
        val decodedInvite = GroupInviteCodec.decode((inviteAfter as FlashResult.Success).value)
        assertNotNull("invite must decode cleanly", decodedInvite)
        assertEquals(groupId, decodedInvite!!.groupId)
        assertEquals(1L, decodedInvite.epoch)
        assertEquals(secretA.secret, decodedInvite.secret)
    }

    @Test
    fun `concurrent upgrades by two admins resolve with GM-6 tie-breaker and converge to same epoch and secret`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createPreGmGroup("dev-a", "Admin Squad", "dev-b")

        // Promote dev-b to admin
        val promo = node("dev-a").repo.promoteAdmin(groupId, "dev-b")
        assertTrue(promo is FlashResult.Success)
        settle()

        // Both dev-a and dev-b call upgradeGroupSecret concurrently
        val signedA = signedGroups("dev-a")
        val signedB = signedGroups("dev-b")

        val rotA = signedA.upgradeGroupSecret(groupId)
        val rotB = signedB.upgradeGroupSecret(groupId)
        assertNotNull(rotA)
        assertNotNull(rotB)
        assertEquals(1L, rotA!!.newEpoch)
        assertEquals(1L, rotB!!.newEpoch)

        // Both broadcast their rotations
        wire.add(Envelope("dev-a", "dev-b", GroupWireFrame.GsStale(groupId, "dev-a", rotA.newEpoch, rotA)))
        wire.add(Envelope("dev-b", "dev-a", GroupWireFrame.GsStale(groupId, "dev-b", rotB.newEpoch, rotB)))

        // Settle network: tie-breaker and re-rotation to epoch 2 take place
        settle()

        val finalRotA = node("dev-a").rotationDao!!.getLatestForGroup(groupId)
        val finalRotB = node("dev-b").rotationDao!!.getLatestForGroup(groupId)
        assertNotNull(finalRotA)
        assertNotNull(finalRotB)

        assertEquals("Both admins must converge to epoch 2", 2L, finalRotA!!.newEpoch)
        assertEquals("Both admins must converge to epoch 2", 2L, finalRotB!!.newEpoch)
        assertEquals(finalRotA.rotationId, finalRotB.rotationId)
        assertEquals(finalRotA.commit, finalRotB.commit)

        val finalSecretA = node("dev-a").secretStore!!.current(groupId)
        val finalSecretB = node("dev-b").secretStore!!.current(groupId)
        assertNotNull("dev-a must hold converged secret", finalSecretA)
        assertNotNull("dev-b must hold converged secret", finalSecretB)
        assertEquals(2L, finalSecretA!!.epoch)
        assertEquals(2L, finalSecretB!!.epoch)
        assertEquals("Both admins must hold identical secret bytes", finalSecretA.secret, finalSecretB.secret)
    }

    @Test
    fun `member on old build is unaffected by upgrade and continues chatting uninterrupted`() = runBlocking {
        // dev-c is on an old build
        val nodeC = node("dev-c", isOldBuild = true)
        val nodeA = node("dev-a")

        liveSessions.add(pairKey("dev-a", "dev-c"))
        nodeA.repo.notifyPeerSessionUp("dev-c")
        nodeC.repo.notifyPeerSessionUp("dev-a")
        nodeA.repo.reconcileGroupMembership("dev-c")
        nodeC.repo.reconcileGroupMembership("dev-a")
        settle()

        val groupId = createPreGmGroup("dev-a", "Mixed Build Group", "dev-c")

        // dev-a upgrades group secret
        val upgradeResult = nodeA.repo.inviteFor(groupId)
        assertTrue(upgradeResult is FlashResult.Success)
        settle()

        // dev-c is on old build (has no rotation Dao, no secret store), ignored GsStale cleanly
        assertNull(nodeC.secretStore)
        assertNull(nodeC.rotationDao)

        // dev-c sends message to dev-a
        say("dev-c", groupId, "Hello from old build!")

        // dev-a received message
        val receivedOnA = nodeA.messageDao.messages.values.any { it.conversationId == groupId && it.text == "Hello from old build!" }
        assertTrue("dev-a must receive message from dev-c", receivedOnA)

        // dev-a sends message to dev-c
        say("dev-a", groupId, "Hello back from new build!")

        // dev-c received message
        val receivedOnC = nodeC.messageDao.messages.values.any { it.conversationId == groupId && it.text == "Hello back from new build!" }
        assertTrue("dev-c must receive message from dev-a", receivedOnC)
    }

    @Test
    fun `legacy g- group is never upgraded and rejects invite link creation`() = runBlocking {
        val nodeA = node("dev-a")
        val legacyGroupId = "g-legacy-999"

        // Insert legacy group into database
        nodeA.conversationDao.upsert(
            ConversationEntity(
                id = legacyGroupId,
                title = "Legacy Group",
                isGroup = true,
                sortOrder = 1000L,
                groupCreatedBy = "dev-a",
                groupCreatedAt = 1000L,
                groupProto = 1,
            ),
        )
        nodeA.memberDao.upsert(
            GroupMemberEntity(
                groupId = legacyGroupId,
                deviceId = "dev-a",
                displayName = "Ada",
                role = "owner",
                joinedAt = 1000L,
                membershipVersion = 1000L,
                operationId = "op-1",
                isActive = true,
            ),
        )

        // Run upgradeExistingV2Groups
        val signedA = signedGroups("dev-a")
        val upgraded = signedA.upgradeExistingV2Groups()
        assertTrue("Legacy group must not be upgraded: $upgraded", upgraded.isEmpty())

        // Ensure no secret or rotation notice created
        assertNull(nodeA.secretStore!!.current(legacyGroupId))
        assertTrue(nodeA.rotationDao!!.getAllForGroup(legacyGroupId).isEmpty())

        // inviteFor must reject legacy group
        val inviteResult = nodeA.repo.inviteFor(legacyGroupId)
        assertTrue(inviteResult is FlashResult.Failure)
        assertEquals(
            "Only groups made with the latest Flash version support invite links",
            ((inviteResult as FlashResult.Failure).error as FlashError.Unknown).message,
        )
    }
}
