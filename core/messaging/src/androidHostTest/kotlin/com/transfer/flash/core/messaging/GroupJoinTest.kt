package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
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
 * GM-4 / Track GM: Joining via invite links, group secret proof, join requests, approval,
 * and roster preview / bundle installation.
 *
 * Verifies:
 * - Unpaired device joins via invite -> proof -> request -> approval -> certificate -> active member (GINV-1 to GINV-4).
 * - 3-repository loopback with forwarded join request through a non-admin member.
 * - Open join policy (auto-approval).
 * - Tombstoned subject key is not auto-approved (GINV-5).
 * - Group full refusal (capacity limit).
 * - Hostile / unsolicited bundle refusal (GINV-3).
 * - Refused join request cleans up group secret and revokes scoped vouch.
 */
class GroupJoinTest {

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
        settle()
    }

    private suspend fun settle(cycles: Int = 10, pauseMs: Long = 100) {
        repeat(cycles) {
            drain()
            delay(pauseMs)
        }
        drain()
    }

    private suspend fun drain() {
        var guard = 0
        while (true) {
            val env = wire.poll() ?: return
            if (guard++ > 2000) error("Frame storm: wire did not quiesce")
            nodes.getValue(env.to).repo.onInboundGroupWireFrame(env.from, env.frame)
        }
    }

    // ------------------------------------------------------------------------------ tests

    @Test
    fun testUnpairedDeviceJoinsViaInviteWithManualApproval() = runBlocking {
        // dev-a (Ada) and dev-c (Cy) have NEVER paired.
        val a = node("dev-a")
        val c = node("dev-c")

        assertFalse("a and c are unpaired", pairedWith["dev-a"]?.contains("dev-c") == true)
        assertFalse("c and a are unpaired", pairedWith["dev-c"]?.contains("dev-a") == true)

        // Ada creates a group for invite
        val groupResult = a.repo.createGroupForInvite("Ada's Project")
        assertTrue("Group created successfully", groupResult is FlashResult.Success)
        val groupId = (groupResult as FlashResult.Success).value

        // Ada gets an invite link
        val inviteResult = a.repo.inviteFor(groupId)
        assertTrue("Invite generated successfully", inviteResult is FlashResult.Success)
        val inviteUrl = (inviteResult as FlashResult.Success).value
        assertTrue("URL has flash:// scheme", inviteUrl.startsWith("flash://g/1/"))

        // Cy accepts the invite URL
        val acceptResult = c.repo.acceptInvite(inviteUrl)
        assertTrue("Accept succeeds", acceptResult is FlashResult.Success)
        assertEquals(groupId, (acceptResult as FlashResult.Success).value)

        // Cy now has an invite in PENDING_CONTACT state and inviter vouched
        val cInvite = c.inviteDao.getByGroupId(groupId)
        assertNotNull("Cy has invite stored", cInvite)
        assertEquals("PENDING_CONTACT", cInvite!!.state)
        assertTrue("Ada is vouched in Cy's trust store for this group", groupId in c.vouching.groupsOf("dev-a"))

        // Cy and Ada connect over LAN/P2P
        connect("dev-a", "dev-c")
        settle(cycles = 10)

        // Ada receives join request in groupJoinRequestDao
        val requests = a.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Ada has 1 join request from Cy", 1, requests.size)
        val cyReq = requests[0]
        assertEquals("dev-c", cyReq.subjectId)
        assertEquals("PENDING", cyReq.state)

        // Ada approves Cy's join request
        val approveResult = a.repo.approveJoinRequest(groupId, "dev-c")
        assertTrue("Ada approves Cy: $approveResult", approveResult is FlashResult.Success)
        settle(cycles = 10)

        // Cy should now have received decision and full bundle, becoming an active member!
        val cyMembers = c.memberDao.allMembers(groupId)
        val cySelf = cyMembers.firstOrNull { it.deviceId == "dev-c" }
        assertNotNull("Cy is a member in Cy's database", cySelf)
        assertTrue("Cy is active member", cySelf!!.isActive)
        assertEquals(1, cySelf.membershipVersion)

        // Ada's database also shows Cy as an active member
        val aCyMember = a.memberDao.member(groupId, "dev-c")
        assertNotNull("Cy is recorded on Ada's database", aCyMember)
        assertTrue("Cy is active on Ada", aCyMember!!.isActive)

        // Cy can send a message in the group and Ada receives it!
        c.repo.openConversation(groupId)
        c.repo.sendText("Hello from Cy!")
        settle(cycles = 5)

        val adaMessage = a.messageDao.messages.values.firstOrNull { it.conversationId == groupId && it.text == "Hello from Cy!" }
        assertNotNull("Ada received Cy's message in the group", adaMessage)
        assertEquals("dev-c", adaMessage!!.senderId)
    }

    @Test
    fun testJoinWhileTheInviterIsAlreadyConnected() = runBlocking {
        // The owner-reported case: the desktop is already connected to the inviter when the link is pasted, so the
        // accept itself and the hint dialer both start the proof. The request must still reach the inviter once.
        val a = node("dev-a")
        val c = node("dev-c")
        val groupId = (a.repo.createGroupForInvite("Ada's Project") as FlashResult.Success).value
        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        connect("dev-a", "dev-c")

        val accepted = c.repo.acceptInvite(inviteUrl)
        assertTrue(accepted is FlashResult.Success)
        settle(cycles = 15)

        val requests = a.joinRequestDao.getAllForGroup(groupId)
        assertEquals("the inviter got exactly one request", 1, requests.size)
        assertEquals("dev-c", requests[0].subjectId)
        assertEquals("PENDING_APPROVAL", c.inviteDao.getByGroupId(groupId)?.state)
    }

    @Test
    fun testApprovalMissedWhileOfflineCompletesOnReconnect() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")
        val groupId = (a.repo.createGroupForInvite("Ada's Project") as FlashResult.Success).value
        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        c.repo.acceptInvite(inviteUrl)
        connect("dev-a", "dev-c")
        settle(cycles = 10)
        assertEquals(1, a.joinRequestDao.getAllForGroup(groupId).size)

        // The link drops before the admin gets to the approval; the bundle and the decision go nowhere.
        disconnect("dev-a", "dev-c")
        val approved = a.repo.approveJoinRequest(groupId, "dev-c")
        assertTrue("approving works without the joiner connected: $approved", approved is FlashResult.Success)
        assertNull("the joiner has heard nothing", c.memberDao.member(groupId, "dev-c"))

        connect("dev-a", "dev-c")
        settle(cycles = 15)

        assertTrue("the joiner is in once the link is back", c.memberDao.member(groupId, "dev-c")?.isActive == true)
        assertEquals("the roster travelled with it", "Ada's Project", c.conversationDao.get(groupId)?.title)
        assertEquals("the request was not turned back into a pending one", 0,
            a.joinRequestDao.getAllForGroup(groupId).count { it.state == "PENDING" })
    }

    @Test
    fun testOpenPolicyAutoApproval() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")

        val groupResult = a.repo.createGroupForInvite("Open Community")
        val groupId = (groupResult as FlashResult.Success).value
        joinPolicies[groupId] = "open" // Configure auto-approval policy

        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        c.repo.acceptInvite(inviteUrl)

        // Connect - auto-approval should occur without manual approveJoinRequest call!
        connect("dev-a", "dev-c")
        settle(cycles = 15)

        val cySelf = c.memberDao.member(groupId, "dev-c")
        assertNotNull("Cy became active member automatically", cySelf)
        assertTrue("Cy is active", cySelf!!.isActive)

        val cyInvite = c.inviteDao.getByGroupId(groupId)
        assertEquals("APPROVED", cyInvite?.state)
    }

    @Test
    fun testTombstonedKeyIsNotAutoApprovedPerGinv5() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")

        val groupResult = a.repo.createGroupForInvite("Security Sensitive")
        val groupId = (groupResult as FlashResult.Success).value
        joinPolicies[groupId] = "open"

        // Simulate Cy previously removed / tombstoned in Ada's group member table
        val cKeyStr = GroupCanonical.encode(cryptos.getValue("dev-c").publicKey)
        a.memberDao.upsert(
            com.transfer.flash.core.persistence.db.entity.GroupMemberEntity(
                groupId = groupId,
                deviceId = "dev-c",
                displayName = "Cy",
                role = "member",
                membershipVersion = 1L,
                operationId = "tombstone-op",
                joinedAt = 1000L,
                isActive = false, // tombstoned
                subjectKey = cKeyStr,
            ),
        )

        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        c.repo.acceptInvite(inviteUrl)

        connect("dev-a", "dev-c")
        settle(cycles = 15)

        // Cy must NOT be auto-approved per GINV-5!
        val cySelf = c.memberDao.member(groupId, "dev-c")
        assertNull("Tombstoned key was not auto-approved", cySelf)

        // The request remains PENDING on Ada for manual review
        val req = a.joinRequestDao.getAllForGroup(groupId).firstOrNull { it.subjectId == "dev-c" }
        assertNotNull("Join request recorded for manual decision", req)
        assertEquals("PENDING", req!!.state)
    }

    @Test
    fun testThreeRepositoryLoopbackWithForwardedJoinRequest() = runBlocking {
        // Ada (owner) paired with Bo (member). Cy is paired with neither.
        pair("dev-a", "dev-b")
        val a = node("dev-a")
        val b = node("dev-b")
        val c = node("dev-c")

        // Ada creates group and adds Bo
        val groupResult = a.repo.createGroup("Crew", setOf("dev-b"))
        assertTrue("Ada creates group with Bo", groupResult is FlashResult.Success)
        val groupId = (groupResult as FlashResult.Success).value

        // Sync Ada and Bo so Bo becomes full member
        connect("dev-a", "dev-b")
        settle(cycles = 10)
        disconnect("dev-a", "dev-b")

        assertTrue("Bo is active member", b.memberDao.member(groupId, "dev-b")?.isActive == true)

        // Ada creates invite for Cy
        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        b.secretStore.put(a.secretStore.current(groupId)!!)
        c.repo.acceptInvite(inviteUrl)

        // Cy connects to Bo ONLY (Ada is offline)
        connect("dev-b", "dev-c")
        settle(cycles = 15)

        // Bo (non-admin) cannot approve, but stores request and prepares forward
        val boRequests = b.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Bo has Cy's join request", 1, boRequests.size)
        assertEquals("PENDING", boRequests[0].state)

        // Cy is not yet member because Bo is not admin
        assertNull("Cy not yet member", c.memberDao.member(groupId, "dev-c"))

        // Now Ada comes online and connects to Bo!
        connect("dev-a", "dev-b")
        settle(cycles = 15)

        // Bo forwarded request to Ada, Ada has the request now!
        val adaRequests = a.joinRequestDao.getAllForGroup(groupId)
        assertEquals("Ada received forwarded join request", 1, adaRequests.size)
        assertEquals("dev-c", adaRequests[0].subjectId)

        // Ada approves Cy's join request
        val approveResult = a.repo.approveJoinRequest(groupId, "dev-c")
        assertTrue("Ada approves Cy", approveResult is FlashResult.Success)

        // Also connect Cy to Ada so Cy receives bundle
        connect("dev-a", "dev-c")
        settle(cycles = 15)

        // All 3 now see Cy as active member!
        assertTrue("Ada sees Cy active", a.memberDao.member(groupId, "dev-c")?.isActive == true)
        assertTrue("Bo sees Cy active", b.memberDao.member(groupId, "dev-c")?.isActive == true)
        assertTrue("Cy sees self active", c.memberDao.member(groupId, "dev-c")?.isActive == true)

        // Cy and Bo can chat!
        c.repo.openConversation(groupId)
        c.repo.sendText("Hi Bo!")
        settle(cycles = 5)

        val boMsg = b.messageDao.messages.values.firstOrNull { it.conversationId == groupId && it.text == "Hi Bo!" }
        assertNotNull("Bo received message from Cy", boMsg)
    }

    @Test
    fun testFullGroupRefusal() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")

        val groupResult = a.repo.createGroupForInvite("Full Group")
        val groupId = (groupResult as FlashResult.Success).value

        // Fill group up to MAX_MEMBERS_V2 (20 members) with dummy members
        for (i in 2..GroupPolicy.MAX_MEMBERS_V2) {
            a.memberDao.upsert(
                com.transfer.flash.core.persistence.db.entity.GroupMemberEntity(
                    groupId = groupId,
                    deviceId = "dummy-$i",
                    displayName = "Dummy $i",
                    role = "member",
                    membershipVersion = 1L,
                    operationId = "op-dummy-$i",
                    joinedAt = 1000L,
                    isActive = true,
                    subjectKey = GroupCanonical.encode(ByteArray(32) { i.toByte() }),
                ),
            )
        }

        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        c.repo.acceptInvite(inviteUrl)

        connect("dev-a", "dev-c")
        settle(cycles = 15)

        // Cy's invite must be refused with reason 'full'
        val cyInvite = c.inviteDao.getByGroupId(groupId)
        assertEquals("REFUSED", cyInvite?.state)
        assertNull("Cy is not a member", c.memberDao.member(groupId, "dev-c"))
        assertNull("Cy forgot group secret", c.secretStore.current(groupId))
        assertFalse("Cy revoked Ada's vouch", groupId in c.vouching.groupsOf("dev-a"))
    }

    @Test
    fun testHostileBundleRefusalPerGinv3() = runBlocking {
        // Cy has no invite for group X from Ada
        val a = node("dev-a")
        val c = node("dev-c")

        val groupResult = a.repo.createGroupForInvite("Uninvited Group")
        val groupId = (groupResult as FlashResult.Success).value

        // Connect without Cy accepting an invite
        connect("dev-a", "dev-c")
        settle(cycles = 5)

        // Try pushing Ada's group bundle to Cy directly
        val bundle = a.repo.bundleForGroup(groupId)
        assertNotNull("Ada has bundle", bundle)

        c.repo.onInboundGroupWireFrame("dev-a", bundle!!)
        settle(cycles = 5)

        // Cy must refuse the bundle because Ada is unpaired and Cy has no invite!
        assertNull("Cy refused unknown uninvited group", c.conversationDao.get(groupId))
        assertNull("Cy has no members for uninvited group", c.memberDao.member(groupId, "dev-a"))
    }

    @Test
    fun testRefusedJoinRequestCleansUpSecretAndVouch() = runBlocking {
        val a = node("dev-a")
        val c = node("dev-c")

        val groupResult = a.repo.createGroupForInvite("Exclusive Club")
        val groupId = (groupResult as FlashResult.Success).value

        val inviteUrl = (a.repo.inviteFor(groupId) as FlashResult.Success).value
        c.repo.acceptInvite(inviteUrl)

        connect("dev-a", "dev-c")
        settle(cycles = 10)

        // Ada refuses the join request
        val refuseResult = a.repo.refuseJoinRequest(groupId, "dev-c", reason = "Invitation revoked")
        assertTrue("Ada refuses Cy", refuseResult is FlashResult.Success)
        settle(cycles = 10)

        // Cy receives refusal decision
        val cyInvite = c.inviteDao.getByGroupId(groupId)
        assertEquals("REFUSED", cyInvite?.state)
        assertNull("Secret deleted from secret store", c.secretStore.current(groupId))
        assertFalse("Vouch revoked for Ada", groupId in c.vouching.groupsOf("dev-a"))
    }
}
