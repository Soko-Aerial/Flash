package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.group.GroupMembershipStatusText
import com.transfer.flash.core.messaging.group.GroupProofResult
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.TestGroupCrypto
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.security.group.GroupInviteCodec
import java.security.MessageDigest
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GM-8: Finding members: address hints, fallback to discovery, and proof with any discovered member.
 *
 * Verifies:
 * - Hints dialed in strict order: first hint tried first; on failure second hint tried; on success remaining hints skipped.
 * - Fallback to discovery: when hints fail / timeout, invite stays in PENDING_CONTACT and displays M-03.
 * - Proof with any member (not only inviter): a newcomer discovers an ordinary member who advertises gs1,
 *   runs mutual proof over an unnamed discovered session without TOFU trap, and sends join request.
 * - Unrelated discovered peer with gs1 safely fails proof (dummy challenge) without corrupting pending invite.
 * - Malformed invite returns M-01 sentence and stores nothing.
 */
class GroupDiscoveryTest {

    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val names = mapOf(
        "dev-a" to "Ada",
        "dev-b" to "Bo",
        "dev-c" to "Cy",
        "dev-d" to "Di",
    )
    private val cryptos = names.keys.associateWith { TestGroupCrypto() }
    private val pairedWith = ConcurrentHashMap<String, MutableSet<String>>()
    private val joinPolicies = ConcurrentHashMap<String, String>()

    private val nodes = LinkedHashMap<String, TestNode>()
    private val wire = ConcurrentLinkedQueue<Envelope>()
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()
    private val repoScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        repoScopes.forEach { it.cancel() }
        dispatcher.close()
    }

    private data class Envelope(val from: String, val to: String, val frame: GroupWireFrame)

    private class TestNode(
        val id: String,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val deliveryDao: InMemoryGroupDeliveryDao = InMemoryGroupDeliveryDao(),
        val readCursorDao: InMemoryReadCursorDao = InMemoryReadCursorDao(),
        val inviteDao: InMemoryGroupInviteDao = InMemoryGroupInviteDao(),
        val joinRequestDao: InMemoryGroupJoinRequestDao = InMemoryGroupJoinRequestDao(),
        val secretStore: InMemoryGroupSecretStore = InMemoryGroupSecretStore(),
        val rotationDao: InMemoryGroupRotationDao = InMemoryGroupRotationDao(),
        val vouching: TestVouching,
        val dialedHints: ConcurrentLinkedQueue<Pair<String, String>> = ConcurrentLinkedQueue(),
        var onHintConnectHandler: (suspend (peerId: String, hint: String) -> Boolean)? = null,
    ) {
        lateinit var repo: RealFlashChatRepository
    }

    private fun fingerprintHex(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02X".format(it) }

    private fun pinOf(id: String): String? =
        cryptos[id]?.publicKey?.let { fingerprintHex(it) }

    private fun node(id: String): TestNode = nodes.getOrPut(id) {
        val pairs = pairedWith.getOrPut(id) { ConcurrentHashMap.newKeySet() }
        val vouching = TestVouching(
            isPaired = { peer -> peer in pairs },
            pairedPin = { peer -> pinOf(peer) },
        )
        val created = TestNode(id, vouching = vouching)
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
            groupInviteDao = created.inviteDao,
            groupJoinRequestDao = created.joinRequestDao,
            groupSecretStore = created.secretStore,
            groupRotationDao = created.rotationDao,
            peerFeatures = { setOf("gs1") },
            groupJoinPolicy = { gid -> joinPolicies[gid] ?: "approve" },
            isTrustedPeer = { peer -> peer in pairs },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
            groupCrypto = cryptos.getValue(id),
            pinnedFingerprint = { peer -> pinOf(peer) },
            peerGroupProtocol = { GroupPolicy.V2_PROTOCOL },
            peerIdentityKey = { peer -> cryptos[peer]?.publicKey },
            groupVouching = vouching,
            localAddressHints = { listOf("192.168.1.100:4433") },
            onConnectPeerHint = { peerId, hint ->
                created.dialedHints.add(peerId to hint)
                created.onHintConnectHandler?.invoke(peerId, hint) ?: false
            },
        )
        created
    }

    private fun pair(a: String, b: String) {
        pairedWith.getOrPut(a) { ConcurrentHashMap.newKeySet() }.add(b)
        pairedWith.getOrPut(b) { ConcurrentHashMap.newKeySet() }.add(a)
    }

    private fun pairKey(x: String, y: String): String = listOf(x, y).sorted().joinToString("|")

    private fun isConnected(x: String, y: String): Boolean = pairKey(x, y) in liveSessions

    private fun transmit(from: String, to: String, frame: GroupWireFrame): Boolean {
        if (!isConnected(from, to)) return false
        wire.add(Envelope(from, to, frame))
        return true
    }

    private suspend fun connect(x: String, y: String) {
        node(x)
        node(y)
        liveSessions.add(pairKey(x, y))
        for ((self, peer) in listOf(x to y, y to x)) {
            val repo = nodes.getValue(self).repo
            repo.notifyPeerSessionUp(peer)
            repo.sendGroupSyncRequests(peer)
            repo.reconcileGroupMembership(peer)
        }
        settle()
    }

    private suspend fun disconnect(x: String, y: String) {
        liveSessions.remove(pairKey(x, y))
        nodes[x]?.repo?.notifyPeerSessionDown(y)
        nodes[y]?.repo?.notifyPeerSessionDown(x)
        settle()
    }

    private suspend fun settle(cycles: Int = 10, pauseMs: Long = 80) {
        repeat(cycles) {
            drain()
            delay(pauseMs)
        }
    }

    private suspend fun drain() {
        val batch = mutableListOf<Envelope>()
        while (true) {
            val env = wire.poll() ?: break
            batch += env
        }
        for (env in batch) {
            val targetRepo = nodes[env.to]?.repo ?: continue
            targetRepo.onInboundGroupWireFrame(env.from, env.frame)
        }
    }

    @Test
    fun testAddressHintsDialedInStrictOrder() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")

        // Ada creates group
        val groupResult = a.repo.createGroupForInvite("Ada's Workgroup")
        assertTrue("Group created", groupResult is FlashResult.Success)
        val groupId = (groupResult as FlashResult.Success).value

        // Generate invite link with custom hints
        val secretRecord = a.secretStore.current(groupId)
        assertNotNull("Secret exists", secretRecord)
        val hints = listOf("192.168.1.10:4433", "192.168.1.20:4433", "192.168.1.30:4433")
        val invite = com.transfer.flash.core.security.group.GroupInvite(
            groupId = groupId,
            epoch = secretRecord!!.epoch,
            secret = secretRecord.secret,
            groupName = "Ada's Workgroup",
            inviterDeviceId = "dev-a",
            inviterKeyFingerprint = cryptos.getValue("dev-a").sha256(cryptos.getValue("dev-a").publicKey),
            addressHints = hints,
            issuedAtMs = System.currentTimeMillis(),
        )
        val inviteUrl = GroupInviteCodec.encode(invite)

        // Configure Cy's hint dialer: hint 1 fails, hint 2 connects, hint 3 should never be called
        c.onHintConnectHandler = { peerId, hint ->
            when (hint) {
                "192.168.1.10:4433" -> false
                "192.168.1.20:4433" -> {
                    // Connect Cy and Ada
                    connect("dev-c", "dev-a")
                    true
                }
                else -> false
            }
        }

        // Cy accepts the invite
        val acceptResult = c.repo.acceptInvite(inviteUrl)
        assertTrue("Accept succeeds", acceptResult is FlashResult.Success)
        settle(cycles = 15)

        // Verify hints dialed in strict order
        val dialed = c.dialedHints.toList()
        assertEquals("Exactly two hints were dialed", 2, dialed.size)
        assertEquals("First hint dialed", "dev-a" to "192.168.1.10:4433", dialed[0])
        assertEquals("Second hint dialed", "dev-a" to "192.168.1.20:4433", dialed[1])
        assertFalse("Third hint was never dialed because second succeeded", dialed.any { it.second == "192.168.1.30:4433" })

        // Once connected on hint 2, mutual proof succeeded and join request reached Ada!
        val requests = a.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Ada received 1 join request from Cy", 1, requests.size)
        assertEquals("dev-c", requests[0].subjectId)
    }

    @Test
    fun testFallbackToDiscoveryAndTimeoutSentenceM03() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")

        val groupResult = a.repo.createGroupForInvite("Alpha Project")
        val groupId = (groupResult as FlashResult.Success).value

        val secretRecord = a.secretStore.current(groupId)!!
        val hints = listOf("10.0.0.1:4433")
        val invite = com.transfer.flash.core.security.group.GroupInvite(
            groupId = groupId,
            epoch = secretRecord.epoch,
            secret = secretRecord.secret,
            groupName = "Alpha Project",
            inviterDeviceId = "dev-a",
            inviterKeyFingerprint = cryptos.getValue("dev-a").sha256(cryptos.getValue("dev-a").publicKey),
            addressHints = hints,
            issuedAtMs = System.currentTimeMillis(),
        )
        val inviteUrl = GroupInviteCodec.encode(invite)

        // Cy dials hint but it fails (Ada is offline)
        c.onHintConnectHandler = { _, _ -> false }

        val acceptResult = c.repo.acceptInvite(inviteUrl)
        assertTrue("Accept succeeds", acceptResult is FlashResult.Success)
        settle(cycles = 10)

        // Hint failed and exhausted -> invite stays in PENDING_CONTACT and displays M-03
        val cInvite = c.inviteDao.getByGroupId(groupId)
        assertNotNull("Invite is stored", cInvite)
        assertEquals("PENDING_CONTACT", cInvite!!.state)

        val sentence = c.repo.inviteStatusSentence(groupId)
        assertEquals(
            "Displays M-03 sentence when hints exhausted",
            GroupMembershipStatusText.waitingForMember("Alpha Project"),
            sentence,
        )

        // Network change re-triggers hint dial
        c.dialedHints.clear()
        c.repo.onNetworkChanged()
        settle(cycles = 10)
        assertEquals("Hint redialed on network change", 1, c.dialedHints.size)
        assertEquals("10.0.0.1:4433", c.dialedHints.first().second)
    }

    @Test
    fun testProofWithDiscoveredMemberNotInviter() = runBlocking {
        // Group has Ada (owner/admin) and Bo (member).
        val a = node("dev-a")
        val b = node("dev-b")
        val c = node("dev-c")

        pair("dev-a", "dev-b")
        connect("dev-a", "dev-b")

        val groupResult = a.repo.createGroup("Shared Team", setOf("dev-b"))
        assertTrue("Group created", groupResult is FlashResult.Success)
        val groupId = (groupResult as FlashResult.Success).value
        settle(cycles = 5)

        // Ada creates an invite link for the group
        val inviteResult = a.repo.inviteFor(groupId)
        val inviteUrl = (inviteResult as FlashResult.Success).value
        b.secretStore.put(a.secretStore.current(groupId)!!)

        // Now Ada goes OFFLINE completely
        disconnect("dev-a", "dev-b")

        // Cy accepts the invite link. Hints to Ada fail because Ada is offline.
        c.onHintConnectHandler = { _, _ -> false }
        val acceptResult = c.repo.acceptInvite(inviteUrl)
        assertTrue("Accept succeeds", acceptResult is FlashResult.Success)
        settle(cycles = 5)

        // Cy is waiting for discovery (M-03)
        assertEquals(
            GroupMembershipStatusText.waitingForMember("Shared Team"),
            c.repo.inviteStatusSentence(groupId),
        )

        // Now Bo (who is NOT the inviter, NOT paired with Cy, and unknown to Cy) is discovered on LAN!
        assertFalse("Cy and Bo are unpaired", pairedWith["dev-c"]?.contains("dev-b") == true)
        connect("dev-c", "dev-b")
        settle(cycles = 15)

        // Bo and Cy completed mutual proof, and Cy sent GsJoinRequest to Bo!
        val boRequests = b.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Bo received 1 join request from Cy", 1, boRequests.size)
        assertEquals("dev-c", boRequests[0].subjectId)

        // Cy's invite transitioned to PENDING_APPROVAL and shows M-07!
        val cyInvite = c.inviteDao.getByGroupId(groupId)
        assertEquals("PENDING_APPROVAL", cyInvite!!.state)
        assertEquals(
            GroupMembershipStatusText.waitingForAdmin("Shared Team"),
            c.repo.inviteStatusSentence(groupId),
        )

        // Later, Ada (admin) comes online.
        connect("dev-a", "dev-b")
        settle(cycles = 10)

        // Bo forwards Cy's request to Ada!
        val adaRequests = a.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Ada received forwarded request from Bo", 1, adaRequests.size)
        assertEquals("dev-c", adaRequests[0].subjectId)

        // Ada approves Cy's join request
        val approveResult = a.repo.approveJoinRequest(groupId, "dev-c")
        assertTrue("Ada approves", approveResult is FlashResult.Success)

        // Cy connects to Ada (or receives bundle via Bo)
        connect("dev-a", "dev-c")
        settle(cycles = 15)

        // Cy is now an active member!
        val cyMember = c.memberDao.member(groupId, "dev-c")
        assertNotNull("Cy is a member", cyMember)
        assertTrue("Cy is active", cyMember!!.isActive)
        assertEquals("Joined", c.repo.inviteStatusSentence(groupId))

        // Cy and Bo can chat in the group!
        c.repo.openConversation(groupId)
        c.repo.sendText("Hello Bo, I joined via discovery!")
        settle(cycles = 5)

        val boMessage = b.messageDao.messages.values.firstOrNull { it.conversationId == groupId && it.text == "Hello Bo, I joined via discovery!" }
        assertNotNull("Bo received Cy's message", boMessage)
    }

    @Test
    fun testUnrelatedDiscoveredPeerDoesNotBreakPendingInvite() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")
        val d = node("dev-d")

        // Ada creates group G1
        val groupResult = a.repo.createGroupForInvite("Secret Alpha")
        val groupId = (groupResult as FlashResult.Success).value
        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value

        // Cy accepts invite for G1
        c.onHintConnectHandler = { _, _ -> false }
        c.repo.acceptInvite(inviteUrl)
        settle(cycles = 5)

        // Di is an unrelated device on the LAN that advertises gs1, but is NOT in G1
        connect("dev-c", "dev-d")
        settle(cycles = 10)

        // Proof fails with Di (dummy challenge answered), but Cy's invite remains intact in PENDING_CONTACT!
        val cyInvite = c.inviteDao.getByGroupId(groupId)
        assertNotNull("Invite exists", cyInvite)
        assertEquals("PENDING_CONTACT", cyInvite!!.state)
        assertEquals(
            GroupMembershipStatusText.waitingForMember("Secret Alpha"),
            c.repo.inviteStatusSentence(groupId),
        )

        // Later Ada connects: proof succeeds with Ada!
        connect("dev-c", "dev-a")
        settle(cycles = 10)

        val requests = a.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Ada received join request from Cy", 1, requests.size)
    }

    @Test
    fun testInvalidInviteReturnsM01() = runBlocking {
        val c = node("dev-c")

        val result = c.repo.acceptInvite("flash://g/1/invalid-corrupted-data")
        assertTrue("Accept fails on corrupted link", result is FlashResult.Failure)
        assertEquals(
            "Returns M-01 error sentence",
            GroupMembershipStatusText.INVALID_INVITE,
            ((result as FlashResult.Failure).error as FlashError.Unknown).message,
        )

        val invites = c.inviteDao.getAll()
        assertTrue("Nothing stored in database", invites.isEmpty())
    }
}
