package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupCharter
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSigning
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MemberCert
import com.transfer.flash.core.messaging.protocol.TestGroupCrypto
import com.transfer.flash.core.messaging.protocol.VerifyBudget
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
 * ADR-044 V1 (S4): signed groups end to end through [RealFlashChatRepository], with several
 * repositories wired together by an in-memory wire and real ECDSA P-256 keys ([TestGroupCrypto]).
 *
 * Each attack test forges a frame the way a paired, hostile member could (a real, TLS-authenticated
 * peer, so the transport check `from == peer` passes) and asserts the receiver's stored state did
 * not change. The wire model, the host sink semantics and the session-up edge follow
 * [GroupLateJoinDiagnosticTest]; the difference is that every device has a signing identity, a
 * trust-store pin for every other device, and a session key for its peers.
 *
 * Not modelled: real sockets and TLS (the keys a repository is given as a peer's session key are the
 * ones its pin store vouches for, exactly what the hosts do), and the wire codec, which
 * `GroupBundleCodecTest` covers.
 */
class SignedGroupsTest {

    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val names = mapOf("dev-a" to "Ada", "dev-b" to "Bo", "dev-c" to "Cy", "dev-d" to "Di", "dev-e" to "Eve")
    private val cryptos = names.keys.associateWith { TestGroupCrypto() }

    /** `owner -> peers it has removed from its trust store`. Everyone trusts everyone else by default. */
    private val distrusted = ConcurrentHashMap<String, MutableSet<String>>()

    /** The group level a device advertised (default 2); a missing entry means a current build. */
    private val level = ConcurrentHashMap<String, Int>()

    /** Devices whose session has no TLS key (a plaintext test socket). */
    private val keyless = ConcurrentHashMap.newKeySet<String>()

    /** A key a device presented that differs from the one its pin vouches for. */
    private val presentedKey = ConcurrentHashMap<String, ByteArray>()

    private val nodes = LinkedHashMap<String, Node>()
    private val wire = ConcurrentLinkedQueue<Envelope>()
    private val outbound = ConcurrentLinkedQueue<Envelope>()
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()
    private val repoScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        repoScopes.forEach { it.cancel() }
        dispatcher.close()
    }

    // ------------------------------------------------------------------------------ create

    @Test
    fun `a group made by capable devices is v2 and every invitee joins with the signed roster`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        assertTrue("v2 ids live in the g2- namespace: $groupId", groupId.startsWith("g2-"))
        for (id in listOf("dev-a", "dev-b", "dev-c")) {
            val conversation = nodes.getValue(id).conversationDao.conversations.getValue(groupId)
            assertEquals("$id stores a v2 group", GroupPolicy.V2_PROTOCOL, conversation.groupProto)
            assertEquals("$id knows the owner", "dev-a", conversation.groupCreatedBy)
            assertEquals("$id holds the owner's key", GroupCanonical.encode(keyOf("dev-a")), conversation.groupOwnerKey)
            assertNotNull("$id stores the charter signature", conversation.groupCharterSig)
            assertEquals(setOf("dev-a", "dev-b", "dev-c"), active(id, groupId))
            rows(id, groupId).forEach { row ->
                assertEquals("$id: ${row.deviceId} has its own key on record", GroupCanonical.encode(keyOf(row.deviceId)), row.subjectKey)
                assertNotNull("$id: ${row.deviceId} keeps the cert signature so it can be relayed", row.certSig)
                assertEquals("dev-a", row.issuerId)
            }
        }
        assertEquals("the owner's label comes from the owner", "Ada", row("dev-b", groupId, "dev-a").displayName)
        assertEquals("a member's label is the name the owner gave it", "Bo", row("dev-c", groupId, "dev-b").displayName)
    }

    @Test
    fun `an invitee that only speaks level 1 makes the whole group legacy and legacy still works`() = runBlocking {
        level["dev-c"] = 1
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Old and new", "dev-b", "dev-c")

        assertFalse("legacy ids are not in the g2- namespace", groupId.startsWith("g2-"))
        assertEquals(1, nodes.getValue("dev-a").conversationDao.conversations.getValue(groupId).groupProto)
        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))
        say("dev-b", groupId, "hello legacy")
        val stored = stored("dev-c", groupId, "hello legacy")
        assertNotNull("a legacy group still delivers", stored)
        assertNull("a legacy message carries no signature", stored!!.groupSig)
    }

    @Test
    fun `a legacy group and a v2 group coexist and each keeps its own rules`() = runBlocking {
        level["dev-c"] = 1
        mesh("dev-a", "dev-b", "dev-c")
        val legacy = createGroup("dev-a", "Legacy", "dev-b", "dev-c")
        val v2 = createGroup("dev-a", "Signed", "dev-b")
        assertFalse(legacy.startsWith("g2-"))
        assertTrue(v2.startsWith("g2-"))

        say("dev-b", legacy, "legacy text")
        say("dev-b", v2, "signed text")
        assertNotNull(stored("dev-a", legacy, "legacy text"))
        assertNull(stored("dev-a", legacy, "legacy text")!!.groupSig)
        assertNotNull(stored("dev-a", v2, "signed text")!!.groupSig)

        // The same unsigned frame that a legacy group accepts is refused by the v2 group.
        val unsigned = { groupId: String, id: String ->
            GroupWireFrame.Message(groupId, id, "dev-b", "Bo", System.currentTimeMillis(), "unsigned", null, null, 0L, null)
        }
        deliver("dev-b", "dev-a", unsigned(legacy, "m-legacy-unsigned"))
        deliver("dev-b", "dev-a", unsigned(v2, "m-v2-unsigned"))
        assertNotNull("legacy accepts an unsigned message", stored("dev-a", legacy, "unsigned"))
        assertNull("v2 refuses it", stored("dev-a", v2, "unsigned"))
    }

    @Test
    fun `create fails instead of falling back to legacy when a capable invitee has no session key`() = runBlocking {
        mesh("dev-a", "dev-b")
        keyless += "dev-b"
        val result = nodes.getValue("dev-a").repo.createGroup("No key", setOf("dev-b"))

        assertKeyUnavailable(result)
        assertTrue("nothing was written", nodes.getValue("dev-a").conversationDao.conversations.isEmpty())
        assertTrue("nothing was sent", outbound.none { it.frame is GroupWireFrame })
    }

    @Test
    fun `create fails when the key a device presented is not the key its pin vouches for`() = runBlocking {
        mesh("dev-a", "dev-b")
        presentedKey["dev-b"] = keyOf("dev-e")
        val result = nodes.getValue("dev-a").repo.createGroup("Wrong key", setOf("dev-b"))

        assertKeyUnavailable(result)
        assertTrue(nodes.getValue("dev-a").conversationDao.conversations.isEmpty())
    }

    // ------------------------------------------------------------------------------ messages

    @Test
    fun `a signed message is stored with its signature and the roster label on every device`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-b", groupId, "hi all")

        for (id in listOf("dev-a", "dev-b", "dev-c")) {
            val message = stored(id, groupId, "hi all")
            assertNotNull("$id has the message", message)
            assertEquals("dev-b", message!!.senderId)
            assertEquals("Bo", message.senderName)
            assertNotNull("$id keeps the author's signature so it can relay it", message.groupSig)
        }
    }

    @Test
    fun `an unsigned message from an active member of a v2 group is dropped and not acknowledged`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val before = outbound.size

        deliver("dev-b", "dev-c", message(groupId, "m-1", "dev-b", "spoof", signature = null))

        assertNull(stored("dev-c", groupId, "spoof"))
        assertTrue("no receipt for a message that was not accepted", outbound.drop(before).none { it.frame is GroupWireFrame.Receipt })
    }

    @Test
    fun `a message whose text changed after signing is dropped`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val signature = sign("dev-b", groupId, "m-1", "dev-b", "original")

        deliver("dev-b", "dev-c", message(groupId, "m-1", "dev-b", "tampered", signature))

        assertNull(stored("dev-c", groupId, "tampered"))
        assertNull(stored("dev-c", groupId, "original"))
    }

    @Test
    fun `a member cannot speak for another member`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        // c names b as the author: the transport peer is c, so the host-level check stops it.
        val asB = message(groupId, "m-1", "dev-b", "c pretending", sign("dev-c", groupId, "m-1", "dev-b", "c pretending"))
        deliver("dev-c", "dev-a", asB)
        // c names itself but signs with b's key: the roster key for c rejects it.
        val wrongKey = message(groupId, "m-2", "dev-c", "wrong key", sign("dev-b", groupId, "m-2", "dev-c", "wrong key"))
        deliver("dev-c", "dev-a", wrongKey)

        assertNull(stored("dev-a", groupId, "c pretending"))
        assertNull(stored("dev-a", groupId, "wrong key"))
    }

    @Test
    fun `a relay that names another author but signed the message itself is dropped, an honest relay is kept`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val syncId = interceptSyncRequest(requester = "dev-a", holder = "dev-c", groupId = groupId)
        val sentAt = System.currentTimeMillis()

        // ERROR-082 / F-9: c relays a "message by b" that c itself signed.
        val forged = GroupWireFrame.Message(
            groupId, "m-forged", "dev-b", "Bo", sentAt, "b says pay c", null, null, 0L,
            GroupSigning(cryptos.getValue("dev-c")).signMessage(groupId, "m-forged", "dev-b", sentAt, null, null, "b says pay c"),
        )
        deliver("dev-c", "dev-a", GroupWireFrame.SyncPush(groupId, syncId, "dev-c", forged))
        assertNull("the forged relay is not stored", stored("dev-a", groupId, "b says pay c"))

        // The same round, an honest copy of a message b really wrote: b's own signature travels with it.
        val honest = GroupWireFrame.Message(
            groupId, "m-honest", "dev-b", "somebody else's idea of b", sentAt, "b really said this", null, null, 0L,
            GroupSigning(cryptos.getValue("dev-b")).signMessage(groupId, "m-honest", "dev-b", sentAt, null, null, "b really said this"),
        )
        deliver("dev-c", "dev-a", GroupWireFrame.SyncPush(groupId, syncId, "dev-c", honest))
        val relayed = stored("dev-a", groupId, "b really said this")
        assertNotNull("the honest relay lands", relayed)
        assertEquals("the true author, not the relayer", "dev-b", relayed!!.senderId)
        assertEquals("the roster label, not the name on the wire", "Bo", relayed.senderName)
    }

    // ------------------------------------------------------------------------------ membership

    @Test
    fun `the owner adds a member later and the newcomer gets the roster and only signed history`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-b", groupId, "before d")
        // A row that has no author signature (an attachment row, say) must never be relayed.
        val bNode = nodes.getValue("dev-b")
        bNode.messageDao.insert(
            MessageEntity(
                localId = "m-unsigned", conversationId = groupId, senderId = "dev-b", senderName = "Bo",
                text = "no signature", sentAt = System.currentTimeMillis(), status = "DELIVERED",
            ),
        )

        val added = nodes.getValue("dev-a").repo.addGroupMembers(groupId, setOf("dev-d"))
        assertTrue("add must succeed: $added", added is FlashResult.Success)
        settleLong()

        for (id in listOf("dev-a", "dev-b", "dev-c", "dev-d")) {
            assertEquals("$id sees the four members", setOf("dev-a", "dev-b", "dev-c", "dev-d"), active(id, groupId))
        }
        assertEquals("d's first cert: seq is per subject, so a newcomer starts at 1", 1L, row("dev-c", groupId, "dev-d").membershipVersion)
        assertEquals("the owner issued it", "dev-a", row("dev-c", groupId, "dev-d").issuerId)
        val history = stored("dev-d", groupId, "before d")
        assertNotNull("the newcomer catches up through a signed relay", history)
        assertEquals("dev-b", history!!.senderId)
        assertNotNull(history.groupSig)
        assertNull("an unsigned row is not relayed in a v2 group", stored("dev-d", groupId, "no signature"))
    }

    @Test
    fun `only the owner can add members to a v2 group`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        val result = nodes.getValue("dev-b").repo.addGroupMembers(groupId, setOf("dev-d"))

        assertTrue(result is FlashResult.Failure)
        assertTrue((((result as FlashResult.Failure).error) as FlashError.Unknown).message.contains("owner"))
        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-a", groupId))
    }

    @Test
    fun `a device that only speaks level 1 cannot be added to a v2 group`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        level["dev-d"] = 1

        val result = nodes.getValue("dev-a").repo.addGroupMembers(groupId, setOf("dev-d"))

        assertTrue(result is FlashResult.Failure)
        assertEquals(setOf("dev-a", "dev-b"), active("dev-a", groupId))
    }

    @Test
    fun `a member who leaves is a tombstone everywhere and their later messages are dropped`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        val left = nodes.getValue("dev-b").repo.leaveGroup(groupId)
        assertTrue(left is FlashResult.Success)
        settle()

        for (id in listOf("dev-a", "dev-b", "dev-c")) {
            assertEquals("$id: only a and c remain", setOf("dev-a", "dev-c"), active(id, groupId))
            val tombstone = row(id, groupId, "dev-b")
            assertEquals(2L, tombstone.membershipVersion)
            assertEquals("a leave is issued by the leaver", "dev-b", tombstone.issuerId)
        }
        deliver("dev-b", "dev-c", message(groupId, "m-late", "dev-b", "after leaving", sign("dev-b", groupId, "m-late", "dev-b", "after leaving")))
        assertNull(stored("dev-c", groupId, "after leaving"))
    }

    @Test
    fun `replaying the original roster does not bring a member back`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val original = outbound.map { it.frame }.filterIsInstance<GroupWireFrame.Bundle>().first { it.groupId == groupId }
        nodes.getValue("dev-b").repo.leaveGroup(groupId)
        settle()

        deliver("dev-a", "dev-c", original)

        assertEquals("the seq-1 cert is stale against the seq-2 tombstone", setOf("dev-a", "dev-c"), active("dev-c", groupId))
    }

    // ------------------------------------------------------------------------------ attacks

    @Test
    fun `legacy membership frames for a v2 id are dropped even from the owner`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val far = 9_999_999_999_999L

        deliver("dev-a", "dev-c", GroupWireFrame.Leave(groupId, "dev-a", "op-1", far, "dev-b"))
        deliver("dev-a", "dev-c", GroupWireFrame.Add(groupId, "dev-a", "op-2", far, listOf("dev-d")))
        deliver("dev-b", "dev-c", GroupWireFrame.State(groupId, "dev-b", "op-3", far, "Hijacked", "dev-b", emptyList()))
        // A fresh device is not pre-empted into a group either: the id would name a group nobody signed.
        val squatted = "g2-00000000000000000000000000000000"
        deliver("dev-b", "dev-d", GroupWireFrame.Create(squatted, "dev-b", "op-4", far, "Squat", listOf("dev-b", "dev-d")))

        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))
        assertEquals("Team", nodes.getValue("dev-c").conversationDao.conversations.getValue(groupId).title)
        assertEquals("dev-a", nodes.getValue("dev-c").conversationDao.conversations.getValue(groupId).groupCreatedBy)
        assertNull(nodes.getValue("dev-d").conversationDao.conversations[squatted])
    }

    @Test
    fun `a member cannot add anybody, the cert has to come from the owner`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-e")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val charter = charterOf("dev-a", groupId)
        val cert = certBy("dev-b", groupId, subject = "dev-e", seq = 2, active = true)

        deliver("dev-b", "dev-c", GroupWireFrame.Bundle(groupId, "dev-b", "op", charter, listOf(cert)))

        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))
        assertNull(nodes.getValue("dev-c").memberDao.members["$groupId" to "dev-e"])
    }

    @Test
    fun `a member cannot remove the owner or another member`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val charter = charterOf("dev-a", groupId)
        val removeOwner = certBy("dev-b", groupId, subject = "dev-a", seq = 9, active = false, role = MemberCert.ROLE_OWNER)
        val removeC = certBy("dev-b", groupId, subject = "dev-c", seq = 9, active = false)

        deliver("dev-b", "dev-c", GroupWireFrame.Bundle(groupId, "dev-b", "op", charter, listOf(removeOwner, removeC)))

        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))
        assertEquals("the stored certs are untouched", 1L, row("dev-c", groupId, "dev-a").membershipVersion)
    }

    @Test
    fun `a member cannot take a group over with a charter of its own`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val real = charterOf("dev-a", groupId)
        val unsigned = real.copy(ownerId = "dev-b", ownerKey = GroupCanonical.encode(keyOf("dev-b")), sig = "")
        val hijack = unsigned.copy(sig = GroupCanonical.encode(cryptos.getValue("dev-b").sign(GroupCanonical.charterBytes(unsigned)!!)))
        val certs = listOf(
            certBy("dev-b", groupId, subject = "dev-b", seq = 9, active = true, role = MemberCert.ROLE_OWNER),
            certBy("dev-b", groupId, subject = "dev-c", seq = 9, active = true),
            certBy("dev-b", groupId, subject = "dev-d", seq = 9, active = true),
        )
        val bundle = GroupWireFrame.Bundle(groupId, "dev-b", "op", hijack, certs)

        deliver("dev-b", "dev-c", bundle)
        deliver("dev-b", "dev-d", bundle)

        assertEquals("the known group keeps its owner", "dev-a", nodes.getValue("dev-c").conversationDao.conversations.getValue(groupId).groupCreatedBy)
        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))
        assertNull("the id was not derived from b's key, so d does not join", nodes.getValue("dev-d").conversationDao.conversations[groupId])
    }

    @Test
    fun `an owner cert that binds a member to a key its pin does not vouch for is ignored`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val charter = charterOf("dev-a", groupId)
        // Signed by the real owner, but for a key c has never pinned for b.
        val swapped = GroupSigning(cryptos.getValue("dev-a")).issueCert(
            groupId, "dev-b", keyOf("dev-e"), "Bo", MemberCert.ROLE_MEMBER, 2L, "op-swap", true, "dev-a",
        )

        deliver("dev-a", "dev-c", GroupWireFrame.Bundle(groupId, "dev-a", "op", charter, listOf(swapped)))

        val kept = row("dev-c", groupId, "dev-b")
        assertEquals(GroupCanonical.encode(keyOf("dev-b")), kept.subjectKey)
        assertEquals(1L, kept.membershipVersion)
    }

    @Test
    fun `a corrupted cert is dropped and the valid ones in the same bundle still apply`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val charter = charterOf("dev-a", groupId)
        val good = certBy("dev-a", groupId, subject = "dev-d", seq = 2, active = true)
        val corrupt = certBy("dev-a", groupId, subject = "dev-b", seq = 5, active = false).copy(sig = GroupCanonical.encode(ByteArray(64) { 7 }))

        deliver("dev-a", "dev-c", GroupWireFrame.Bundle(groupId, "dev-a", "op", charter, listOf(corrupt, good)))

        assertEquals("d is added", setOf("dev-a", "dev-b", "dev-c", "dev-d"), active("dev-c", groupId))
        assertEquals("b's row was not touched by the bad cert", 1L, row("dev-c", groupId, "dev-b").membershipVersion)
    }

    @Test
    fun `a relayed bundle does not admit a device to a group whose owner it has not paired with`() = runBlocking {
        mesh("dev-b", "dev-d", "dev-e")
        distrust("dev-d", "dev-e")
        val groupId = createGroup("dev-e", "Eve's group", "dev-b", "dev-d")
        val bundle = outbound.map { it.frame }.filterIsInstance<GroupWireFrame.Bundle>().first { it.groupId == groupId }

        // e's own frame to d was dropped at d's gate; b (paired with both) relays the same bundle.
        deliver("dev-b", "dev-d", bundle)

        assertNull("d never paired with e, so it does not join e's group", nodes.getValue("dev-d").conversationDao.conversations[groupId])
        assertNotNull("b did join, e is paired with b", nodes.getValue("dev-b").conversationDao.conversations[groupId])
    }

    // ------------------------------------------------------------------------------ SignedGroups alone

    @Test
    fun `a bundle that does not carry the receivers own cert is not an invitation`() = runBlocking {
        val directory = parties("owner", "recv", "other")
        val created = engine(directory, "owner").create("Not for you", mapOf("other" to keyOf(directory, "other"))) { "Other" }

        val outcome = engine(directory, "recv").onBundle("owner", created.bundle)

        assertEquals(SignedGroups.BundleOutcome.Ignored("no-own-cert"), outcome)
        assertTrue(directory.getValue("recv").conversations.conversations.isEmpty())
    }

    @Test
    fun `a paired stranger who is not in the roster cannot relay a bundle into a known group`() = runBlocking {
        val directory = parties("owner", "recv", "stranger")
        val created = engine(directory, "owner").create("Team", mapOf("recv" to keyOf(directory, "recv"))) { "Recv" }
        val receiver = engine(directory, "recv")
        assertTrue(receiver.onBundle("owner", created.bundle) is SignedGroups.BundleOutcome.Applied)

        val outcome = receiver.onBundle("stranger", created.bundle)

        assertEquals(SignedGroups.BundleOutcome.Ignored("sender-not-member"), outcome)
    }

    @Test
    fun `a v2 group cannot grow past its member cap`() = runBlocking {
        val others = (1..GroupPolicy.MAX_MEMBERS_V2).map { "m$it" }
        val directory = parties("owner", "recv", *others.toTypedArray())
        val invitees = (others + "recv").associateWith { keyOf(directory, it) }
        val created = engine(directory, "owner").create("Too big", invitees) { it }

        val outcome = engine(directory, "recv").onBundle("owner", created.bundle)

        assertEquals(SignedGroups.BundleOutcome.Ignored("too-many-members"), outcome)
        assertTrue(directory.getValue("recv").conversations.conversations.isEmpty())
    }

    @Test
    fun `verification is budgeted per peer and replays of a known bundle are free`() = runBlocking {
        val directory = parties("owner", "recv", "other")
        val invitees = mapOf("recv" to keyOf(directory, "recv"), "other" to keyOf(directory, "other"))
        val created = engine(directory, "owner").create("Budget", invitees) { it }

        // owner + recv + other = three certs, plus the charter for a group not held yet = 4 verifications.
        val tooSmall = engine(directory, "recv", VerifyBudget(perWindow = 3, windowMs = 60_000L))
        assertEquals(SignedGroups.BundleOutcome.Ignored("budget"), tooSmall.onBundle("owner", created.bundle))
        assertTrue("a bundle over budget is dropped unread", directory.getValue("recv").conversations.conversations.isEmpty())

        val exact = engine(directory, "recv", VerifyBudget(perWindow = 4, windowMs = 60_000L))
        assertTrue(exact.onBundle("owner", created.bundle) is SignedGroups.BundleOutcome.Applied)
        repeat(50) {
            assertEquals(
                "a stale bundle costs nothing, so replaying it cannot exhaust the budget",
                SignedGroups.BundleOutcome.Applied(created.groupId, joined = false),
                exact.onBundle("owner", created.bundle),
            )
        }
    }

    // ------------------------------------------------------------------------------ harness: nodes

    private class Node(
        val id: String,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val deliveryDao: InMemoryGroupDeliveryDao = InMemoryGroupDeliveryDao(),
    ) {
        lateinit var repo: RealFlashChatRepository
    }

    private data class Envelope(val from: String, val to: String, val frame: GroupWireFrame)

    private fun keyOf(id: String): ByteArray = cryptos.getValue(id).publicKey

    private fun pinOf(id: String): String? =
        cryptos[id]?.publicKey?.let { fingerprintHex(it) }

    private fun distrust(owner: String, peer: String) {
        distrusted.getOrPut(owner) { ConcurrentHashMap.newKeySet() }.add(peer)
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
            isTrustedPeer = { peer -> peer in names.keys && peer != id && distrusted[id]?.contains(peer) != true },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
            groupCrypto = cryptos.getValue(id),
            pinnedFingerprint = { peer -> pinOf(peer) },
            peerGroupProtocol = { peer -> level[peer] ?: GroupPolicy.V2_PROTOCOL },
            peerIdentityKey = { peer -> if (peer in keyless) null else presentedKey[peer] ?: cryptos[peer]?.publicKey },
        )
        created
    }

    private fun transmit(from: String, to: String, frame: GroupWireFrame): Boolean {
        outbound.add(Envelope(from, to, frame))
        if (!isConnected(from, to)) return false
        wire.add(Envelope(from, to, frame))
        return true
    }

    private fun pairKey(x: String, y: String): String = listOf(x, y).sorted().joinToString("|")

    private fun isConnected(x: String, y: String): Boolean = pairKey(x, y) in liveSessions

    /** Every listed device connected to every other, then the session-up edge both hosts run. */
    private suspend fun mesh(vararg ids: String) {
        ids.forEach { node(it) }
        for (i in ids.indices) for (j in i + 1 until ids.size) connect(ids[i], ids[j])
        settle()
    }

    private suspend fun connect(x: String, y: String) {
        liveSessions.add(pairKey(x, y))
        for ((self, peer) in listOf(x to y, y to x)) {
            val repo = nodes.getValue(self).repo
            repo.notifyPeerSessionUp(peer)
            repo.sendGroupSyncRequests(peer)
            repo.reconcileGroupMembership(peer)
        }
    }

    private suspend fun settle(pauseMs: Long = 150) {
        repeat(3) {
            drain()
            delay(pauseMs)
        }
        drain()
    }

    /** [settle] long enough for the 2 s backup pushes of a catch-up round to land. */
    private suspend fun settleLong() {
        repeat(9) {
            drain()
            delay(400)
        }
        drain()
    }

    private suspend fun drain() {
        var guard = 0
        while (true) {
            val envelope = wire.poll() ?: return
            if (guard++ > 500) error("frame storm: the harness did not quiesce")
            nodes.getValue(envelope.to).repo.onInboundGroupWireFrame(envelope.from, envelope.frame)
        }
    }

    private suspend fun deliver(from: String, to: String, frame: GroupWireFrame) {
        nodes.getValue(to).repo.onInboundGroupWireFrame(from, frame)
        settle(pauseMs = 50)
    }

    private suspend fun createGroup(owner: String, name: String, vararg invitees: String): String {
        val result = nodes.getValue(owner).repo.createGroup(name, invitees.toSet())
        assertTrue("create must succeed: $result", result is FlashResult.Success)
        settle()
        return (result as FlashResult.Success).value
    }

    private suspend fun say(id: String, groupId: String, text: String) {
        val repo = nodes.getValue(id).repo
        repo.openConversation(groupId)
        repo.sendText(text)
        settle()
    }

    /**
     * Makes [requester] ask [holder] for catch-up, then takes the request off the wire so the holder
     * never answers it, and returns its syncId: the caller plays the holder and pushes by hand.
     */
    private suspend fun interceptSyncRequest(requester: String, holder: String, groupId: String): String {
        nodes.getValue(requester).repo.sendGroupSyncRequests(holder)
        repeat(40) {
            val request = wire.firstOrNull {
                it.from == requester && it.to == holder && (it.frame as? GroupWireFrame.SyncRequest)?.groupId == groupId
            }
            if (request != null) {
                wire.remove(request)
                return (request.frame as GroupWireFrame.SyncRequest).syncId
            }
            delay(50)
        }
        error("no sync request from $requester to $holder for $groupId")
    }

    // ------------------------------------------------------------------------------ harness: forging

    private fun sign(signer: String, groupId: String, messageId: String, from: String, text: String): String =
        GroupSigning(cryptos.getValue(signer)).signMessage(groupId, messageId, from, SENT_AT, null, null, text)

    private fun message(groupId: String, id: String, from: String, text: String, signature: String?) =
        GroupWireFrame.Message(groupId, id, from, names.getValue(from), SENT_AT, text, null, null, 0L, signature)

    private fun certBy(
        signer: String,
        groupId: String,
        subject: String,
        seq: Long,
        active: Boolean,
        role: String = MemberCert.ROLE_MEMBER,
    ): MemberCert = GroupSigning(cryptos.getValue(signer)).issueCert(
        groupId = groupId,
        subjectId = subject,
        subjectKey = keyOf(subject),
        label = names.getValue(subject),
        role = role,
        seq = seq,
        opId = "op-forged-$seq-$subject",
        active = active,
        issuerId = signer,
    )

    private fun charterOf(id: String, groupId: String): GroupCharter {
        val conversation = nodes.getValue(id).conversationDao.conversations.getValue(groupId)
        return GroupCharter(
            groupId = groupId,
            name = conversation.title,
            ownerId = conversation.groupCreatedBy!!,
            ownerKey = conversation.groupOwnerKey!!,
            createdAt = conversation.groupCreatedAt!!,
            nonce = conversation.groupNonce!!,
            proto = conversation.groupProto,
            sig = conversation.groupCharterSig!!,
        )
    }

    // ------------------------------------------------------------------------------ harness: reading

    private fun rows(id: String, groupId: String) =
        nodes.getValue(id).memberDao.members.values.filter { it.groupId == groupId }

    private fun row(id: String, groupId: String, member: String) =
        nodes.getValue(id).memberDao.members.getValue(groupId to member)

    private fun active(id: String, groupId: String): Set<String> =
        rows(id, groupId).filter { it.isActive }.map { it.deviceId }.toSet()

    private fun stored(id: String, groupId: String, text: String): MessageEntity? =
        nodes.getValue(id).messageDao.messages.values.firstOrNull { it.conversationId == groupId && it.text == text }

    private fun assertKeyUnavailable(result: FlashResult<String>) {
        assertTrue("create must fail: $result", result is FlashResult.Failure)
        val error = (result as FlashResult.Failure).error as FlashError.Unknown
        assertTrue("the user is told the key could not be verified: ${error.message}", error.message.contains("security key could not be verified"))
    }

    // ------------------------------------------------------------------------------ harness: SignedGroups alone

    private class Party(val id: String) {
        val crypto = TestGroupCrypto()
        val conversations = InMemoryConversationDao()
        val members = InMemoryGroupMemberDao()
    }

    private fun parties(vararg ids: String): Map<String, Party> = ids.associateWith { Party(it) }

    private fun keyOf(directory: Map<String, Party>, id: String): ByteArray = directory.getValue(id).crypto.publicKey

    private val counter = AtomicInteger()

    private fun engine(directory: Map<String, Party>, id: String, budget: VerifyBudget = VerifyBudget()): SignedGroups {
        val party = directory.getValue(id)
        return SignedGroups(
            localDeviceId = id,
            localDisplayName = id,
            crypto = party.crypto,
            conversationDao = party.conversations,
            members = party.members,
            isPaired = { peer -> peer in directory && peer != id },
            pinnedFingerprint = { peer -> directory[peer]?.crypto?.publicKey?.let { fingerprintHex(it) } },
            nowMs = { 1_700_000_000_000L },
            newId = { "id-${counter.incrementAndGet()}" },
            budget = budget,
        )
    }

    private fun fingerprintHex(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02X".format(it) }

    private companion object {
        const val SENT_AT = 1_700_000_000_123L
    }
}
