package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.model.FlashMessageInfoUi
import com.transfer.flash.core.messaging.model.FlashGroupSyncUi
import com.transfer.flash.core.messaging.model.FlashSelfMembership
import com.transfer.flash.core.messaging.model.FlashWaitingReason
import com.transfer.flash.core.messaging.protocol.GroupCanonical
import com.transfer.flash.core.messaging.protocol.GroupCharter
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSignatureRules
import com.transfer.flash.core.messaging.protocol.GroupSigning
import com.transfer.flash.core.messaging.protocol.GroupVouching
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** Five named devices, plus dev-f..dev-u so a group of twenty (and one more) can be built. */
    private val names = mapOf("dev-a" to "Ada", "dev-b" to "Bo", "dev-c" to "Cy", "dev-d" to "Di", "dev-e" to "Eve") +
        ('f'..'u').associate { "dev-$it" to "Member $it" }
    private val cryptos = names.keys.associateWith { TestGroupCrypto() }

    /** `owner -> peers it has removed from its trust store`. Everyone trusts everyone else by default. */
    private val distrusted = ConcurrentHashMap<String, MutableSet<String>>()

    /** The group level a device advertised (default 2); a missing entry means a current build. */
    private val level = ConcurrentHashMap<String, Int>()

    /** Devices whose session has no TLS key (a plaintext test socket). */
    private val keyless = ConcurrentHashMap.newKeySet<String>()

    /** A key a device presented that differs from the one its pin vouches for. */
    private val presentedKey = ConcurrentHashMap<String, ByteArray>()

    /** ADR-044 V2: when true every repository gets a trust store that can vouch. The default keeps V1 behaviour. */
    private var vouchedTrust = false

    /** How long the catch-up banner outlives its last arrival in these tests (the product value is 3 s). */
    private val syncQuietMs = 700L

    /** Each device's trust store as the group layer sees it; created on first use. */
    private val vouchings = ConcurrentHashMap<String, TestVouching>()

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
    fun `a group is not created as legacy for an invitee that is simply not connected`() = runBlocking {
        // ERROR-095: an offline device reports level 1 because nothing is known about it, and the group used to be made
        // legacy without a word; a legacy group can never call a member the caller is not paired with.
        level["dev-c"] = 1
        mesh("dev-a", "dev-b", "dev-c")
        keyless += "dev-c" // no live session to dev-c
        val result = nodes.getValue("dev-a").repo.createGroup("Offline one", setOf("dev-b", "dev-c"))

        assertTrue("create must fail: $result", result is FlashResult.Failure)
        val message = ((result as FlashResult.Failure).error as FlashError.Unknown).message
        assertTrue("the owner is told whom to wait for: $message", message.contains("Cy") && !message.contains("Bo") && message.contains("online"))
        assertTrue("nothing was written", nodes.getValue("dev-a").conversationDao.conversations.isEmpty())
        assertTrue("nothing was sent", outbound.none { it.frame is GroupWireFrame })
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

    @Test
    fun `sendGroupAttachment signs message and beginGroupAttachment propagates signature over wire in v2 group`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val aNode = nodes.getValue("dev-a")

        aNode.repo.sendGroupAttachment(
            conversationId = groupId,
            messageId = "att-1",
            transferId = "xfer-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            localPath = "content://photo",
        )
        settle()

        val stored = aNode.messageDao.getByLocalId("att-1")
        assertNotNull("attachment row exists", stored)
        assertNotNull("groupSig is signed for v2 group attachment", stored!!.groupSig)

        val before = outbound.size
        val sent = aNode.repo.beginGroupAttachment(
            groupId = groupId,
            recipientDeviceId = "dev-b",
            messageId = "att-1",
            transferId = "xfer-1",
            wireFileId = "wire-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
        )
        assertTrue(sent)
        val mediaFrame = outbound.drop(before).map { it.frame }.filterIsInstance<GroupWireFrame.GroupMedia>().single()
        assertEquals(stored.groupSig, mediaFrame.signature)
        assertEquals(stored.sentAt, mediaFrame.sentAt)
    }

    @Test
    fun `beginGroupAttachment before sendGroupAttachment in production order signs and matches stored message row`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val aNode = nodes.getValue("dev-a")
        val bNode = nodes.getValue("dev-b")

        val before = outbound.size
        // In MainActivity.kt and DesktopShell.kt, beginGroupAttachment is called in a loop for each member first:
        val sentB = aNode.repo.beginGroupAttachment(
            groupId = groupId,
            recipientDeviceId = "dev-b",
            messageId = "att-prod-1",
            transferId = "xfer-b-1",
            wireFileId = "wire-prod-1",
            fileName = "diagram.png",
            mimeType = "image/png",
            sizeBytes = 2048L,
        )
        val sentC = aNode.repo.beginGroupAttachment(
            groupId = groupId,
            recipientDeviceId = "dev-c",
            messageId = "att-prod-1",
            transferId = "xfer-c-1",
            wireFileId = "wire-prod-1",
            fileName = "diagram.png",
            mimeType = "image/png",
            sizeBytes = 2048L,
        )
        assertTrue(sentB)
        assertTrue(sentC)

        val frames = outbound.drop(before).map { it.frame }.filterIsInstance<GroupWireFrame.GroupMedia>()
        assertEquals(2, frames.size)
        val frameB = frames.first { it.transferId == "xfer-b-1" }
        val frameC = frames.first { it.transferId == "xfer-c-1" }
        assertNotNull("frame for dev-b carries v2 signature", frameB.signature)
        assertEquals("both frames share the exact same signature", frameB.signature, frameC.signature)
        assertEquals("both frames share the exact same sentAt timestamp", frameB.sentAt, frameC.sentAt)

        // Then sendGroupAttachment is called afterwards to create the local sender bubble
        aNode.repo.sendGroupAttachment(
            conversationId = groupId,
            messageId = "att-prod-1",
            transferId = "att-prod-1",
            fileName = "diagram.png",
            mimeType = "image/png",
            sizeBytes = 2048L,
            localPath = "content://diagram",
        )
        settle()

        val storedA = aNode.messageDao.getByLocalId("att-prod-1")
        assertNotNull("sender row exists", storedA)
        assertEquals("stored sender row matches wire signature", frameB.signature, storedA!!.groupSig)
        assertEquals("stored sender row matches wire sentAt", frameB.sentAt, storedA.sentAt)

        // Now deliver the wire frame to dev-b: it must be accepted and verified
        deliver("dev-a", "dev-b", frameB)
        val storedB = bNode.messageDao.getByLocalId("att-prod-1")
        assertNotNull("recipient accepted and stored the attachment", storedB)
        assertEquals("Ada", storedB!!.senderName)
        assertEquals(frameB.signature, storedB.groupSig)
    }

    @Test
    fun `inbound GroupMedia with invalid signature is dropped, honest signature accepted and author label verified`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val bNode = nodes.getValue("dev-b")
        val cNode = nodes.getValue("dev-c")
        val sentAt = System.currentTimeMillis()

        // 1. Inbound GroupMedia with forged/invalid signature -> dropped
        val forgedSig = GroupSigning(cryptos.getValue("dev-c")).signMessage(groupId, "att-forged", "dev-b", sentAt, null, null, "")
        deliver("dev-b", "dev-c", GroupWireFrame.GroupMedia(
            groupId = groupId,
            messageId = "att-forged",
            transferId = "xfer-forged",
            wireFileId = "wire-forged",
            from = "dev-b",
            senderName = "Spoofed Name",
            fileName = "doc.pdf",
            mimeType = "application/pdf",
            sizeBytes = 500L,
            sentAt = sentAt,
            signature = forgedSig,
        ))
        assertNull("forged media not stored", cNode.messageDao.getByLocalId("att-forged"))

        // 2. Inbound GroupMedia with honest signature by dev-b -> accepted, signed roster label used
        val honestSig = GroupSigning(cryptos.getValue("dev-b")).signMessage(groupId, "att-honest", "dev-b", sentAt, null, null, "")
        deliver("dev-b", "dev-c", GroupWireFrame.GroupMedia(
            groupId = groupId,
            messageId = "att-honest",
            transferId = "xfer-honest",
            wireFileId = "wire-honest",
            from = "dev-b",
            senderName = "Ignored Wire Name",
            fileName = "doc.pdf",
            mimeType = "application/pdf",
            sizeBytes = 500L,
            sentAt = sentAt,
            signature = honestSig,
        ))
        val accepted = cNode.messageDao.getByLocalId("att-honest")
        assertNotNull("honest media stored", accepted)
        assertEquals("dev-b", accepted!!.senderId)
        assertEquals("Bo", accepted.senderName) // Roster signed label
        assertEquals(honestSig, accepted.groupSig)
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

    // ------------------------------------------------------------------------------ V2: vouched members

    @Test
    fun `unpaired members of a v2 group are vouched by the owner and talk to each other`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-b", groupId))
        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))
        assertEquals("b vouches c's real key on the owner's word", setOf(groupId), vouchingOf("dev-b").groupsOf("dev-c"))
        assertEquals(pinOf("dev-c"), vouchingOf("dev-b").pinOf("dev-c"))
        assertEquals(setOf(groupId), vouchingOf("dev-c").groupsOf("dev-b"))
        assertEquals(pinOf("dev-b"), vouchingOf("dev-c").pinOf("dev-b"))

        say("dev-b", groupId, "hello from b")
        val received = stored("dev-c", groupId, "hello from b")
        assertNotNull("c accepts b although they never paired", received)
        assertEquals("the name shown is the roster's signed label", "Bo", received!!.senderName)
        say("dev-c", groupId, "and back")
        assertNotNull("b accepts c", stored("dev-b", groupId, "and back"))
    }

    @Test
    fun `the member list says who introduced a member this device never paired`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        val atC = nodes.getValue("dev-c").repo.groupMembers(groupId).associateBy { it.id }
        assertEquals("b was never paired with c, the owner vouched", "Ada", atC.getValue("dev-b").introducedBy)
        assertNull("the owner is paired by construction", atC.getValue("dev-a").introducedBy)
        assertNull("nor is the local device introduced to itself", atC.getValue("dev-c").introducedBy)

        val atA = nodes.getValue("dev-a").repo.groupMembers(groupId).associateBy { it.id }
        assertNull("the owner paired with everyone it invited", atA.getValue("dev-b").introducedBy)
        assertNull(atA.getValue("dev-c").introducedBy)

        // Pairing (Verify) removes the label: the row shows the member as an ordinary contact.
        distrusted["dev-c"]?.remove("dev-b")
        val afterVerify = nodes.getValue("dev-c").repo.groupMembers(groupId).associateBy { it.id }
        assertNull(afterVerify.getValue("dev-b").introducedBy)
    }

    @Test
    fun `without a trust store that can vouch an unpaired member is still refused`() = runBlocking {
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        assertEquals("c never learns b, exactly as in V1", setOf("dev-a", "dev-c"), active("dev-c", groupId))
        say("dev-b", groupId, "hello")
        assertNull(stored("dev-c", groupId, "hello"))
    }

    @Test
    fun `a first-use pin left under a member's id is replaced by the owner's vouch`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        // dev-e connected to c first and claimed b's id: the pin c holds for "dev-b" is e's key.
        vouchingOf("dev-c").tofu("dev-b", fingerprintHex(keyOf("dev-e")))
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        assertEquals(pinOf("dev-b"), vouchingOf("dev-c").pinOf("dev-b"))
        assertEquals(setOf(groupId), vouchingOf("dev-c").groupsOf("dev-b"))
        assertNotNull(row("dev-c", groupId, "dev-b"))
    }

    @Test
    fun `a peer that presents another key than its certificate is not trusted in the group`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val signed = sign("dev-b", groupId, "m-imp", "dev-b", "i am b")

        // The live session at c under the id dev-b holds e's key, not the key the owner certified for b.
        presentedKey["dev-b"] = keyOf("dev-e")
        deliver("dev-b", "dev-c", message(groupId, "m-imp", "dev-b", "i am b", signed))
        assertNull("the id alone is not enough", stored("dev-c", groupId, "i am b"))

        presentedKey.remove("dev-b")
        deliver("dev-b", "dev-c", message(groupId, "m-imp", "dev-b", "i am b", signed))
        assertNotNull("the same frame over the certified key is accepted", stored("dev-c", groupId, "i am b"))
    }

    @Test
    fun `a device that is not in the roster is not trusted in the group even when the owner knows it`() = runBlocking {
        vouchedTrust = true
        unpair("dev-c", "dev-d")
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        deliver("dev-d", "dev-c", message(groupId, "m-out", "dev-d", "let me in", sign("dev-d", groupId, "m-out", "dev-d", "let me in")))

        assertNull(stored("dev-c", groupId, "let me in"))
        assertNull("no pin appears for a device that was never vouched", vouchingOf("dev-c").pinOf("dev-d"))
    }

    @Test
    fun `an owner cert that names another key for a paired member is refused and the pairing stays`() = runBlocking {
        vouchedTrust = true
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val charter = charterOf("dev-a", groupId)
        val swapped = GroupSigning(cryptos.getValue("dev-a")).issueCert(
            groupId, "dev-b", keyOf("dev-e"), "Bo", MemberCert.ROLE_MEMBER, 2L, "op-swap", true, "dev-a",
        )

        deliver("dev-a", "dev-c", GroupWireFrame.Bundle(groupId, "dev-a", "op", charter, listOf(swapped)))

        assertEquals(GroupCanonical.encode(keyOf("dev-b")), row("dev-c", groupId, "dev-b").subjectKey)
        assertEquals(1L, row("dev-c", groupId, "dev-b").membershipVersion)
        assertEquals("the pairing pin is untouched", pinOf("dev-b"), vouchingOf("dev-c").pinOf("dev-b"))
    }

    @Test
    fun `the owner removes a member, every device withdraws the vouch and the member is silenced`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        assertEquals(setOf(groupId), vouchingOf("dev-b").groupsOf("dev-c"))

        val removed = nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        assertTrue("remove must succeed: $removed", removed is FlashResult.Success)
        settle()

        for (id in listOf("dev-a", "dev-b", "dev-c")) {
            assertEquals("$id: c is gone", setOf("dev-a", "dev-b"), active(id, groupId))
            assertEquals("the owner signed the tombstone", "dev-a", row(id, groupId, "dev-c").issuerId)
        }
        assertEquals(emptySet<String>(), vouchingOf("dev-b").groupsOf("dev-c"))
        assertNull("the vouched pin goes with the last vouch", vouchingOf("dev-b").pinOf("dev-c"))
        deliver("dev-c", "dev-b", message(groupId, "m-gone", "dev-c", "still here", sign("dev-c", groupId, "m-gone", "dev-c", "still here")))
        assertNull(stored("dev-b", groupId, "still here"))
    }

    @Test
    fun `only the owner of a v2 group is offered member removal in the conversation state`() = runBlocking {
        vouchedTrust = true
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        nodes.getValue("dev-a").repo.openConversation(groupId)
        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()

        assertTrue("the owner may remove members", nodes.getValue("dev-a").repo.conversationState.value.canRemoveMembers)
        assertFalse("a member may not", nodes.getValue("dev-b").repo.conversationState.value.canRemoveMembers)

        val removed = nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        assertTrue(removed is FlashResult.Success)
        settle()
        assertEquals(
            "the roster the sheet shows drops the removed member",
            setOf("dev-a", "dev-b"),
            nodes.getValue("dev-a").repo.conversationState.value.members.map { it.id }.toSet(),
        )
    }

    @Test
    fun `only the owner can remove a member`() = runBlocking {
        vouchedTrust = true
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        val refused = nodes.getValue("dev-b").repo.removeGroupMember(groupId, "dev-c")

        assertTrue(refused is FlashResult.Failure)
        assertEquals(setOf("dev-a", "dev-b", "dev-c"), active("dev-a", groupId))
        val owner = nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-a")
        assertTrue("the owner cannot remove itself", owner is FlashResult.Failure)
    }

    @Test
    fun `a member who leaves withdraws the vouches it made and the others withdraw theirs for it`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        val left = nodes.getValue("dev-b").repo.leaveGroup(groupId)
        assertTrue(left is FlashResult.Success)
        settle()

        assertEquals("b no longer vouches anyone", emptySet<String>(), vouchingOf("dev-b").groupsOf("dev-c"))
        assertNull(vouchingOf("dev-b").pinOf("dev-c"))
        assertEquals("c no longer vouches b", emptySet<String>(), vouchingOf("dev-c").groupsOf("dev-b"))
        assertNull(vouchingOf("dev-c").pinOf("dev-b"))
    }

    @Test
    fun `a file sent to a group is offered to paired members only`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")

        nodes.getValue("dev-b").repo.sendGroupAttachment(
            groupId, "att-1", "tr-1", "photo.jpg", "image/jpeg", 10L, null, 0L, emptyList(),
        )
        settle()

        val targets = nodes.getValue("dev-b").deliveryDao.rows.keys.filter { it.first == "att-1" }.map { it.second }
        assertEquals("only the paired owner is a recipient, so nothing waits for c forever", listOf("dev-a"), targets)
    }

    @Test
    fun `a group of twenty forms when only the owner is paired with everyone`() = runBlocking {
        vouchedTrust = true
        val others = ('b'..'t').map { "dev-$it" }
        others.forEach { x -> others.filter { it != x }.forEach { y -> distrust(x, y) } }
        node("dev-a")
        others.forEach { node(it) }
        others.forEach { connect("dev-a", it) }
        settle()

        val groupId = createGroup("dev-a", "Everyone", *others.toTypedArray())

        assertEquals(20, active("dev-a", groupId).size)
        for (id in others) {
            assertEquals("$id holds the whole roster", 20, active(id, groupId).size)
            assertEquals("$id vouches every other member it never paired", 18, vouchingOf(id).pinnedDevices().size)
        }
    }

    @Test
    fun `a twenty-first member is refused at creation and a big group needs every member on v2`() = runBlocking {
        vouchedTrust = true
        val tooMany = (('b'..'t').map { "dev-$it" } + "dev-u").toSet()
        node("dev-a")
        val over = nodes.getValue("dev-a").repo.createGroup("Too big", tooMany)
        assertTrue(over is FlashResult.Failure)
        assertTrue(((over as FlashResult.Failure).error as FlashError.Unknown).message.contains("2-${GroupPolicy.MAX_MEMBERS_V2}"))

        level["dev-c"] = 1
        val legacy = nodes.getValue("dev-a").repo.createGroup("Half old", ('b'..'h').map { "dev-$it" }.toSet())
        assertTrue(legacy is FlashResult.Failure)
        assertTrue(
            "the user is told why: ${(legacy as FlashResult.Failure).error}",
            (legacy.error as FlashError.Unknown).message.contains("latest Flash"),
        )
    }

    // ------------------------------------------------------------------------------ V2: SignedGroups alone

    @Test
    fun `twenty members fit in one bundle and a twenty-first is ignored without leaving a pin`() = runBlocking {
        val others = (1..19).map { "m$it" }
        val directory = parties("owner", "recv", *others.toTypedArray())
        val invitees = (others.take(18) + "recv").associateWith { keyOf(directory, it) }
        val created = engine(directory, "owner").create("Twenty", invitees) { it }
        val vouching = vouchingFor(directory, setOf("owner"))
        val receiver = engine(directory, "recv", vouching = vouching, paired = { it == "owner" })

        assertEquals(SignedGroups.BundleOutcome.Applied(created.groupId, joined = true), receiver.onBundle("owner", created.bundle))
        assertEquals(20, directory.getValue("recv").members.members.values.count { it.isActive })
        others.take(18).forEach { assertEquals(setOf(created.groupId), vouching.groupsOf(it)) }

        val added = engine(directory, "owner").addMembers(created.groupId, mapOf("m19" to keyOf(directory, "m19"))) { it }!!
        assertEquals(SignedGroups.BundleOutcome.Ignored("too-many-members"), receiver.onBundle("owner", added.changed))
        assertNull("no pin for a member the roster refused", vouching.pinOf("m19"))
    }

    @Test
    fun `a cert whose key another group vouched differently is dropped and the rest applies`() = runBlocking {
        val directory = parties("owner", "recv", "x", "y")
        val vouching = vouchingFor(directory, setOf("owner"))
        val otherKey = fingerprintHex(TestGroupCrypto().publicKey)
        vouching.vouch("x", otherKey, "g2-other")
        val invitees = mapOf("recv" to keyOf(directory, "recv"), "x" to keyOf(directory, "x"), "y" to keyOf(directory, "y"))
        val created = engine(directory, "owner").create("Team", invitees) { it }
        val receiver = engine(directory, "recv", vouching = vouching, paired = { it == "owner" })

        val outcome = receiver.onBundle("owner", created.bundle)

        assertEquals(SignedGroups.BundleOutcome.Applied(created.groupId, joined = true), outcome)
        assertNull("x is not stored: two owners disagree about its key", directory.getValue("recv").members.members[created.groupId to "x"])
        assertNotNull(directory.getValue("recv").members.members[created.groupId to "y"])
        assertEquals("the first vouch is untouched", otherKey, vouching.pinOf("x"))
        assertEquals(setOf("g2-other"), vouching.groupsOf("x"))
    }

    @Test
    fun `a relay that is not a member cannot leave a pin behind`() = runBlocking {
        val directory = parties("owner", "recv", "x", "stranger", "newbie")
        val vouching = vouchingFor(directory, setOf("owner", "stranger"))
        val created = engine(directory, "owner").create(
            "Team", mapOf("recv" to keyOf(directory, "recv"), "x" to keyOf(directory, "x")),
        ) { it }
        val receiver = engine(directory, "recv", vouching = vouching, paired = { it in setOf("owner", "stranger") })
        assertTrue(receiver.onBundle("owner", created.bundle) is SignedGroups.BundleOutcome.Applied)
        val added = engine(directory, "owner").addMembers(created.groupId, mapOf("newbie" to keyOf(directory, "newbie"))) { it }!!

        val outcome = receiver.onBundle("stranger", added.changed)

        assertEquals(SignedGroups.BundleOutcome.Ignored("sender-not-member"), outcome)
        assertNull(vouching.pinOf("newbie"))
        assertNull(directory.getValue("recv").members.members[created.groupId to "newbie"])
    }

    @Test
    fun `a replayed bundle re-installs the vouches a cleared trust store lost`() = runBlocking {
        val directory = parties("owner", "recv", "x")
        val vouching = vouchingFor(directory, setOf("owner"))
        val created = engine(directory, "owner").create(
            "Team", mapOf("recv" to keyOf(directory, "recv"), "x" to keyOf(directory, "x")),
        ) { it }
        val receiver = engine(directory, "recv", vouching = vouching, paired = { it == "owner" })
        assertTrue(receiver.onBundle("owner", created.bundle) is SignedGroups.BundleOutcome.Applied)
        assertEquals(setOf(created.groupId), vouching.groupsOf("x"))

        vouching.clear()
        val replay = receiver.onBundle("owner", created.bundle)

        assertEquals(SignedGroups.BundleOutcome.Applied(created.groupId, joined = false), replay)
        assertEquals(setOf(created.groupId), vouching.groupsOf("x"))
        assertEquals(fingerprintHex(keyOf(directory, "x")), vouching.pinOf("x"))
    }

    @Test
    fun `a vouched member counts in the group only over the key its certificate names`() = runBlocking {
        val directory = parties("owner", "recv", "x", "stranger")
        val vouching = vouchingFor(directory, setOf("owner"))
        val created = engine(directory, "owner").create(
            "Team", mapOf("recv" to keyOf(directory, "recv"), "x" to keyOf(directory, "x")),
        ) { it }
        val receiver = engine(directory, "recv", vouching = vouching, paired = { it == "owner" })
        receiver.onBundle("owner", created.bundle)

        assertTrue(receiver.isVouchedMember(created.groupId, "x", keyOf(directory, "x")))
        assertFalse("another key under the same id", receiver.isVouchedMember(created.groupId, "x", keyOf(directory, "stranger")))
        assertFalse("no session key (plaintext socket)", receiver.isVouchedMember(created.groupId, "x", null))
        assertFalse("not in the roster", receiver.isVouchedMember(created.groupId, "stranger", keyOf(directory, "stranger")))
        assertFalse("not this group", receiver.isVouchedMember("g2-elsewhere", "x", keyOf(directory, "x")))
    }

    @Test
    fun `without a vouching port every member must still be paired`() = runBlocking {
        val directory = parties("owner", "recv", "x")
        val created = engine(directory, "owner").create(
            "Team", mapOf("recv" to keyOf(directory, "recv"), "x" to keyOf(directory, "x")),
        ) { it }
        val receiver = engine(directory, "recv", paired = { it == "owner" })

        assertTrue(receiver.onBundle("owner", created.bundle) is SignedGroups.BundleOutcome.Applied)

        assertNull("V1: x is unknown to a device that never paired it", directory.getValue("recv").members.members[created.groupId to "x"])
        assertFalse(receiver.isVouchedMember(created.groupId, "x", keyOf(directory, "x")))
    }

    // ------------------------------------------------------------------------------ removal ripple

    /** Takes [id] off the network: no session with anyone, so nothing reaches it and it hears nothing. */
    private fun goOffline(id: String) {
        liveSessions.removeIf { id in it.split("|") }
    }

    @Test
    fun `a member removed while offline learns of it when a member reconnects`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        goOffline("dev-c")

        val removed = nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        assertTrue("remove must succeed: $removed", removed is FlashResult.Success)
        settle()
        assertEquals("c was offline, so nothing reached it", setOf("dev-a", "dev-b", "dev-c"), active("dev-c", groupId))

        // b never sends a roster to an inactive peer, and c's own roster is ignored by b: only the notice tells c.
        connect("dev-b", "dev-c")
        settle()

        assertEquals("c now knows it is out", setOf("dev-a", "dev-b"), active("dev-c", groupId))
        assertEquals("the owner's tombstone, relayed by b", "dev-a", row("dev-c", groupId, "dev-c").issuerId)
        assertEquals("c withdrew the vouch it held for b", emptySet<String>(), vouchingOf("dev-c").groupsOf("dev-b"))
        assertNull(vouchingOf("dev-c").pinOf("dev-b"))
    }

    @Test
    fun `a member who left by choice is not sent a removal notice`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        nodes.getValue("dev-c").repo.leaveGroup(groupId)
        settle()
        outbound.clear()

        connect("dev-b", "dev-c")
        settle()

        assertTrue(
            "a leave is the member's own act, so nothing is sent to them",
            outbound.none { it.from == "dev-b" && it.to == "dev-c" && it.frame is GroupWireFrame.Bundle },
        )
    }

    @Test
    fun `a removed member cannot send, its screen says so and it is out of the calls`() = runBlocking {
        vouchedTrust = true
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val c = nodes.getValue("dev-c").repo
        c.openConversation(groupId)
        settle()
        assertEquals(FlashSelfMembership.Active, c.conversationState.value.selfMembership)
        assertTrue(c.conversationState.value.header.showCallActions)

        nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        settle()

        assertEquals(FlashSelfMembership.Removed, c.conversationState.value.selfMembership)
        assertFalse("no call buttons for a device that is out", c.conversationState.value.header.showCallActions)
        outbound.clear()
        c.sendText("hello?")
        settle()
        assertNull("nothing is stored, so nothing stays PENDING for good", stored("dev-c", groupId, "hello?"))
        assertTrue(outbound.none { it.from == "dev-c" && it.frame is GroupWireFrame.Message })
        assertTrue("the members it left behind still see the group as theirs", nodes.getValue("dev-b").repo.isGroupCallPeer(groupId, "dev-a"))
    }

    @Test
    fun `a member who leaves sees the group as left and cannot send`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val b = nodes.getValue("dev-b").repo
        b.openConversation(groupId)
        b.leaveGroup(groupId)
        settle()

        assertEquals(FlashSelfMembership.Left, b.conversationState.value.selfMembership)
        assertFalse(b.conversationState.value.canRemoveMembers)
        b.sendText("one more thing")
        settle()
        assertNull(stored("dev-b", groupId, "one more thing"))
    }

    @Test
    fun `a removed member is no longer a call peer although it is still paired`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val b = nodes.getValue("dev-b").repo
        assertTrue("an active member is a call peer", b.isGroupCallPeer(groupId, "dev-c"))

        nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        settle()

        assertFalse("the removed member may not ring or join the group's calls", b.isGroupCallPeer(groupId, "dev-c"))
        assertTrue("the plain gate is unchanged, c is still paired", b.isGroupPeerTrusted(groupId, "dev-c"))
        assertTrue(b.isGroupCallPeer(groupId, "dev-a"))
        assertFalse("and the removed device is out of every call of the group", nodes.getValue("dev-c").repo.isGroupCallPeer(groupId, "dev-a"))
        assertFalse("a group nobody here knows has no call peers", b.isGroupCallPeer("g2-" + "0".repeat(32), "dev-a"))
    }

    // ------------------------------------------------------------------------------ call membership (ERROR-088)

    @Test
    fun `a vouched member this device is not paired with is in the call without a session and may be sent to only on the certified key`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val b = nodes.getValue("dev-b").repo

        assertTrue("the owner introduced c, so c is in the call", b.isGroupCallMember(groupId, "dev-c"))
        assertTrue("and a frame may go to a session presenting the certified key", b.isGroupCallPeer(groupId, "dev-c"))

        // c is not connected yet: there is no session key to check
        keyless += "dev-c"
        assertTrue("a member with no session is still a member of the call", b.isGroupCallMember(groupId, "dev-c"))
        assertFalse("but nothing may be sent to a connection that presents no key", b.isGroupCallPeer(groupId, "dev-c"))
        keyless -= "dev-c"

        // somebody else answers on c's address
        presentedKey["dev-c"] = keyOf("dev-e")
        assertTrue("membership is a roster fact, an impostor does not change it", b.isGroupCallMember(groupId, "dev-c"))
        assertFalse("the live-key gate still refuses the impostor", b.isGroupCallPeer(groupId, "dev-c"))
        presentedKey.remove("dev-c")
        assertTrue(b.isGroupCallPeer(groupId, "dev-c"))
    }

    @Test
    fun `only an active roster member is in the call - a paired stranger, a removed member and a member who left are not`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val b = nodes.getValue("dev-b").repo

        assertTrue(b.isGroupCallMember(groupId, "dev-a"))
        assertTrue(b.isGroupCallMember(groupId, "dev-c"))
        assertFalse("paired with b but not on the roster", b.isGroupCallMember(groupId, "dev-d"))
        assertFalse("a group nobody here knows has no call members", b.isGroupCallMember("g2-" + "0".repeat(32), "dev-a"))

        nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        settle()
        assertFalse("the owner removed c", b.isGroupCallMember(groupId, "dev-c"))
        assertFalse("a device that is out of the group is in none of its calls", nodes.getValue("dev-c").repo.isGroupCallMember(groupId, "dev-a"))
    }

    @Test
    fun `a member who left the group is not in its calls`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        val b = nodes.getValue("dev-b").repo
        assertTrue(b.isGroupCallMember(groupId, "dev-c"))

        nodes.getValue("dev-c").repo.leaveGroup(groupId)
        settle()

        assertFalse(b.isGroupCallMember(groupId, "dev-c"))
        assertFalse("and a member that left is in no call itself", nodes.getValue("dev-c").repo.isGroupCallMember(groupId, "dev-a"))
    }

    @Test
    fun `a removed member takes nothing from a member that has not heard of the removal yet`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        goOffline("dev-b")

        nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        settle()
        assertEquals("b is stale: it still lists c", setOf("dev-a", "dev-b", "dev-c"), active("dev-b", groupId))
        assertEquals(setOf("dev-a", "dev-b"), active("dev-c", groupId))
        assertNull("c withdrew its vouch for b", vouchingOf("dev-c").pinOf("dev-b"))

        // b reconnects to c first: its roster and its next message both reach a device that is out.
        connect("dev-b", "dev-c")
        deliver("dev-b", "dev-c", message(groupId, "m-stale", "dev-b", "for members only", sign("dev-b", groupId, "m-stale", "dev-b", "for members only")))

        assertNull("c stores nothing from a group it was removed from", stored("dev-c", groupId, "for members only"))
        assertNull("and b's roster did not bring the pin back", vouchingOf("dev-c").pinOf("dev-b"))

        // Any member that has the tombstone converges b.
        connect("dev-a", "dev-b")
        settle()
        assertEquals("b caught up from the owner", setOf("dev-a", "dev-b"), active("dev-b", groupId))
    }

    @Test
    fun `a removed member can be added again and takes part again`() = runBlocking {
        vouchedTrust = true
        unpair("dev-b", "dev-c")
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c")
        settle()
        assertEquals(emptySet<String>(), vouchingOf("dev-b").groupsOf("dev-c"))

        val readded = nodes.getValue("dev-a").repo.addGroupMembers(groupId, setOf("dev-c"))
        assertTrue("the owner can add the removed member back: $readded", readded is FlashResult.Success)
        settle()

        for (id in listOf("dev-a", "dev-b", "dev-c")) {
            assertEquals("$id: everybody is back", setOf("dev-a", "dev-b", "dev-c"), active(id, groupId))
        }
        assertEquals("the re-add outranks the removal", 3L, row("dev-b", groupId, "dev-c").membershipVersion)
        assertEquals("b vouches c again", setOf(groupId), vouchingOf("dev-b").groupsOf("dev-c"))
        val c = nodes.getValue("dev-c").repo
        c.openConversation(groupId)
        settle()
        assertEquals(FlashSelfMembership.Active, c.conversationState.value.selfMembership)
        say("dev-c", groupId, "I am back")
        assertNotNull("a and b store what c says once c is back", stored("dev-a", groupId, "I am back"))
        assertNotNull(stored("dev-b", groupId, "I am back"))
    }

    // ------------------------------------------------------------------------------ delivery ticks
    // Chat/group sync audit, step 2. The tick of a group message moves when its author learns that each member
    // holds it. Live delivery already told it; a member that caught up later did not.

    /** Pushes every queued retry of [id]'s outbox an hour out, so only what a test sends by hand reaches anyone. */
    private fun freezeOutbox(id: String) {
        val outbox = nodes.getValue(id).outboxDao
        outbox.queue.keys.forEach { key ->
            outbox.queue[key]?.let { outbox.queue[key] = it.copy(nextAttemptAt = System.currentTimeMillis() + 3_600_000L) }
        }
    }

    private fun deliveryState(id: String, messageId: String, member: String): String? =
        nodes.getValue(id).deliveryDao.rows[messageId to member]?.state

    @Test
    fun `a member that catches up from a relay tells the author, whose tick then moves`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        goOffline("dev-b")
        say("dev-a", groupId, "while b was away")
        val original = stored("dev-a", groupId, "while b was away")!!
        assertEquals("c was online and acknowledged", "DELIVERED", deliveryState("dev-a", original.localId, "dev-c"))
        assertNotEquals("b was away", "DELIVERED", deliveryState("dev-a", original.localId, "dev-b"))
        assertNotEquals("so the message is not delivered yet", "DELIVERED", nodes.getValue("dev-a").messageDao.messages.getValue(original.localId).status)

        // b is back with a and with c, but a's own retry has not fired (frozen) and no session-up edge ran.
        freezeOutbox("dev-a")
        liveSessions.add(pairKey("dev-a", "dev-b"))
        liveSessions.add(pairKey("dev-b", "dev-c"))
        val syncId = interceptSyncRequest(requester = "dev-b", holder = "dev-c", groupId = groupId)
        outbound.clear()

        // c relays a's own signed message to b, as an elected holder does.
        val relayed = GroupWireFrame.Message(
            groupId, original.localId, "dev-a", "Ada", original.sentAt, original.text, null, null, 0L, original.groupSig,
        )
        deliver("dev-c", "dev-b", GroupWireFrame.SyncPush(groupId, syncId, "dev-c", relayed))

        assertNotNull("b stored the relayed message", stored("dev-b", groupId, "while b was away"))
        assertTrue(
            "b tells the author itself, not only the relay",
            outbound.any { it.from == "dev-b" && it.to == "dev-a" && (it.frame as? GroupWireFrame.Receipt)?.messageId == original.localId },
        )
        assertEquals("DELIVERED", deliveryState("dev-a", original.localId, "dev-b"))
        assertEquals("every member holds it now", "DELIVERED", nodes.getValue("dev-a").messageDao.messages.getValue(original.localId).status)
        assertNull("so the outbox row is retired", nodes.getValue("dev-a").outboxDao.queue[original.localId])
    }

    @Test
    fun `the author marks a member delivered when that member acknowledges its catch-up push`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        goOffline("dev-b")
        say("dev-a", groupId, "catch up from me")
        val original = stored("dev-a", groupId, "catch up from me")!!
        assertNotEquals("b was away", "DELIVERED", deliveryState("dev-a", original.localId, "dev-b"))

        freezeOutbox("dev-a")
        liveSessions.add(pairKey("dev-a", "dev-b"))
        nodes.getValue("dev-b").repo.sendGroupSyncRequests("dev-a")
        settleLong()

        assertNotNull("b caught up from its author", stored("dev-b", groupId, "catch up from me"))
        assertEquals("the ack names the message, so the author records the delivery", "DELIVERED", deliveryState("dev-a", original.localId, "dev-b"))
        assertEquals("DELIVERED", nodes.getValue("dev-a").messageDao.messages.getValue(original.localId).status)
    }

    // ------------------------------------------------------------------------------ read ticks
    // Chat/group sync audit, step 3. A group message is READ only once every active remote member has read past it.
    // Before this the reader sent a direct receipt to the group id, which is not a device, so nobody ever heard it.

    private fun status(id: String, messageId: String): String =
        nodes.getValue(id).messageDao.messages.getValue(messageId).status

    private fun readFrames(from: String, to: String): List<GroupWireFrame.Read> =
        outbound.filter { it.from == from && it.to == to }.mapNotNull { it.frame as? GroupWireFrame.Read }

    @Test
    fun `a reader tells every other member how far it has read, and only them`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-a", groupId, "read me")
        val sent = stored("dev-a", groupId, "read me")!!
        outbound.clear()

        nodes.getValue("dev-c").repo.openConversation(groupId)
        settle()

        assertEquals("c names the message to the author", listOf(sent.localId), readFrames("dev-c", "dev-a").map { it.upToMessageId })
        assertEquals("and to the other member", listOf(sent.localId), readFrames("dev-c", "dev-b").map { it.upToMessageId })
        assertTrue("never to itself", outbound.none { it.to == "dev-c" && it.frame is GroupWireFrame.Read })
        assertEquals("every Read is stamped with the reader", setOf("dev-c"), outbound.mapNotNull { (it.frame as? GroupWireFrame.Read)?.from }.toSet())
        assertEquals("a kept c's cursor", sent.localId, nodes.getValue("dev-a").readCursorDao.cursors[groupId to "dev-c"]?.upToMessageId)
    }

    @Test
    fun `the author's message turns read only when the last member has read it`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-a", groupId, "read by all")
        val sent = stored("dev-a", groupId, "read by all")!!
        assertEquals("both members acknowledged delivery", "DELIVERED", status("dev-a", sent.localId))

        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()
        assertEquals("b has read it, c has not", "DELIVERED", status("dev-a", sent.localId))
        assertNotNull("a holds b's cursor", nodes.getValue("dev-a").readCursorDao.cursors[groupId to "dev-b"])

        nodes.getValue("dev-c").repo.openConversation(groupId)
        settle()
        assertEquals("c was the last to read", "READ", status("dev-a", sent.localId))
    }

    @Test
    fun `the read tick stops at the member that has read the least`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-a", groupId, "one")
        val one = stored("dev-a", groupId, "one")!!
        nodes.getValue("dev-b").repo.openConversation(groupId)
        nodes.getValue("dev-c").repo.openConversation(groupId)
        settle()
        assertEquals("both have read the first message", "READ", status("dev-a", one.localId))

        // c puts the conversation away; a says more; b (still looking at it) reads on, c does not.
        nodes.getValue("dev-c").repo.closeConversation()
        delay(5)
        say("dev-a", groupId, "two")
        val two = stored("dev-a", groupId, "two")!!
        settle()

        assertEquals("b's cursor moved on", two.localId, nodes.getValue("dev-a").readCursorDao.cursors[groupId to "dev-b"]?.upToMessageId)
        assertEquals("c's did not", one.localId, nodes.getValue("dev-a").readCursorDao.cursors[groupId to "dev-c"]?.upToMessageId)
        assertEquals("so the first message stays read", "READ", status("dev-a", one.localId))
        assertNotEquals("and the second is held back by c", "READ", status("dev-a", two.localId))
    }

    @Test
    fun `a member that left does not hold the read tick back`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-a", groupId, "first")
        val first = stored("dev-a", groupId, "first")!!
        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()
        assertEquals("c has not read it", "DELIVERED", status("dev-a", first.localId))

        assertTrue(nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c") is FlashResult.Success)
        settle()
        say("dev-a", groupId, "second")
        val second = stored("dev-a", groupId, "second")!!

        // b's newest inbound message is now `second`; the roster is {a, b}, so b alone decides.
        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()
        assertEquals("read by every member still in the group", "READ", status("dev-a", second.localId))
        assertEquals("and so is everything before it", "READ", status("dev-a", first.localId))
    }

    @Test
    fun `a read from someone outside the group changes nothing`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        say("dev-a", groupId, "members only")
        val sent = stored("dev-a", groupId, "members only")!!

        deliver("dev-c", "dev-a", GroupWireFrame.Read(groupId, "dev-c", sent.localId, SENT_AT))

        assertNull("no cursor is kept for a stranger", nodes.getValue("dev-a").readCursorDao.cursors[groupId to "dev-c"])
        assertNotEquals("READ", status("dev-a", sent.localId))
    }

    @Test
    fun `a read that names a message this device does not hold moves nothing`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        say("dev-a", groupId, "mine")
        val sent = stored("dev-a", groupId, "mine")!!

        deliver("dev-b", "dev-a", GroupWireFrame.Read(groupId, "dev-b", "no-such-message", SENT_AT))
        assertTrue("an unknown name stores no cursor", nodes.getValue("dev-a").readCursorDao.cursors.isEmpty())
        assertNotEquals("READ", status("dev-a", sent.localId))

        // A message that exists but belongs to another conversation cannot stand in for one of this group.
        val other = "other-conversation-message"
        nodes.getValue("dev-a").messageDao.messages[other] =
            sent.copy(localId = other, conversationId = "dev-z", text = "elsewhere")
        deliver("dev-b", "dev-a", GroupWireFrame.Read(groupId, "dev-b", other, SENT_AT))
        assertTrue("a foreign message stores no cursor either", nodes.getValue("dev-a").readCursorDao.cursors.isEmpty())
        assertNotEquals("READ", status("dev-a", sent.localId))
    }

    @Test
    fun `an older read never moves a cursor backwards`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        say("dev-a", groupId, "one")
        delay(5)
        say("dev-a", groupId, "two")
        val one = stored("dev-a", groupId, "one")!!
        val two = stored("dev-a", groupId, "two")!!
        assertTrue("the two messages are ordered", two.sentAt > one.sentAt)

        deliver("dev-b", "dev-a", GroupWireFrame.Read(groupId, "dev-b", two.localId, SENT_AT))
        deliver("dev-b", "dev-a", GroupWireFrame.Read(groupId, "dev-b", one.localId, SENT_AT))

        assertEquals("the later cursor stands", two.localId, nodes.getValue("dev-a").readCursorDao.cursors[groupId to "dev-b"]?.upToMessageId)
        assertEquals("READ", status("dev-a", two.localId))
    }

    // ------------------------------------------------------------------------------ catch-up media
    // Chat/group sync audit, step 4. A catch-up never re-sends file bytes, so a file row must reach the newcomer
    // as a one-line label, not as an empty bubble or a voice note's raw metadata.

    private fun attachmentRow(id: String, groupId: String, mime: String, name: String, sentAt: Long, text: String = "") =
        MessageEntity(
            localId = id,
            conversationId = groupId,
            senderId = "dev-a",
            senderName = "Ada",
            text = text,
            sentAt = sentAt,
            status = "SENT",
            attachmentTransferId = "transfer-$id",
            attachmentName = name,
            attachmentMime = mime,
            attachmentSize = 2_000_000L,
        )

    @Test
    fun `a legacy catch-up labels files instead of sending empty bubbles`() = runBlocking {
        level["dev-c"] = 1
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Old and new", "dev-b", "dev-c")
        goOffline("dev-c")
        val now = System.currentTimeMillis()
        val author = nodes.getValue("dev-a").messageDao
        author.insert(attachmentRow("att-photo", groupId, "image/jpeg", "holiday.jpg", now - 4))
        author.insert(attachmentRow("att-video", groupId, "video/mp4", "clip.mp4", now - 3))
        author.insert(attachmentRow("att-file", groupId, "application/pdf", "plan.pdf", now - 2))
        author.insert(attachmentRow("att-voice", groupId, "audio/mp4", "note.m4a", now - 1, text = "vmsg:2400:3,9,4"))
        say("dev-a", groupId, "plain text")

        connect("dev-a", "dev-c")
        settleLong()

        val caught = nodes.getValue("dev-c").messageDao.messages
        assertEquals("[Photo] holiday.jpg", caught["att-photo"]?.text)
        assertEquals("[Video] clip.mp4", caught["att-video"]?.text)
        assertEquals("[File] plan.pdf", caught["att-file"]?.text)
        assertEquals("a voice note is labelled, its waveform metadata is not shown", "[Voice message]", caught["att-voice"]?.text)
        assertNotNull("ordinary text still catches up", stored("dev-c", groupId, "plain text"))
        assertTrue(
            "no row the newcomer holds is empty or carries raw metadata",
            caught.values.filter { it.conversationId == groupId }.none { it.text.isBlank() || it.text.startsWith("vmsg:") },
        )
        assertTrue("and none claims an attachment it cannot open", caught.values.none { it.attachmentTransferId != null })
    }

    // ------------------------------------------------------------------------------ rename
    // Chat/group sync audit, step 5. The repository captured the device's name at construction, so a renamed
    // device kept stamping its old name on everything it sent and on the groups it made.

    @Test
    fun `a renamed device uses its new name from then on, and a signed label keeps the name it was issued with`() = runBlocking {
        level["dev-c"] = 1
        mesh("dev-a", "dev-b", "dev-c")
        val legacy = createGroup("dev-a", "Old", "dev-b", "dev-c")
        val v2 = createGroup("dev-a", "New", "dev-b")
        assertEquals("the legacy group starts with the old name", "Ada", row("dev-a", legacy, "dev-a").displayName)

        nodes.getValue("dev-a").repo.updateLocalDisplayName("  Ada Lovelace ")
        settle()

        assertEquals("the device's own legacy row is renamed", "Ada Lovelace", row("dev-a", legacy, "dev-a").displayName)
        assertEquals("a v2 row is a stored cert and stays as the owner signed it", "Ada", row("dev-a", v2, "dev-a").displayName)

        say("dev-a", legacy, "hello again")
        assertEquals("what it sends is stamped with the new name", "Ada Lovelace", stored("dev-b", legacy, "hello again")!!.senderName)

        val later = createGroup("dev-a", "Later", "dev-b")
        assertEquals("a group it creates now signs the new name into its owner cert", "Ada Lovelace", row("dev-b", later, "dev-a").displayName)
    }

    // ------------------------------------------------------------------------------ message info
    // Chat/group sync audit, step 6 (UI-051). The sheet behind the delivery badge: who has read, received or not yet
    // received a message this device sent, kept live from the delivery rows, the roster and the read cursors.

    private fun infoOf(id: String, messageId: String) = nodes.getValue(id).repo.observeMessageInfo(messageId)

    private suspend fun infoWhere(id: String, messageId: String, predicate: (FlashMessageInfoUi) -> Boolean): FlashMessageInfoUi =
        withTimeout(5_000) { infoOf(id, messageId).first { it != null && predicate(it) }!! }

    @Test
    fun `message info puts each recipient where it stands and follows a read`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        say("dev-a", groupId, "who has it")
        val sent = stored("dev-a", groupId, "who has it")!!

        val first = infoOf("dev-a", sent.localId).first()!!
        assertEquals("the message it describes", "who has it", first.preview)
        assertTrue("and when it was sent", first.sentLabel.isNotBlank())
        assertEquals("both members confirmed delivery", setOf("dev-b", "dev-c"), first.deliveredTo.map { it.id }.toSet())
        assertTrue("a delivered recipient carries the confirmation time", first.deliveredTo.all { it.deliveredAtLabel != null })
        assertTrue("nobody has read it yet", first.readBy.isEmpty())
        assertTrue("and nobody is left waiting", first.waiting.isEmpty())

        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()
        val afterRead = infoWhere("dev-a", sent.localId) { it.readBy.size == 1 }

        assertEquals("b moved to read", listOf("dev-b"), afterRead.readBy.map { it.id })
        assertEquals("a read row has no time, only a cursor is stored", null, afterRead.readBy.single().deliveredAtLabel)
        assertEquals("c is still delivered", listOf("dev-c"), afterRead.deliveredTo.map { it.id })
    }

    @Test
    fun `message info names a member that has not been reached, and one that has since left`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Team", "dev-b", "dev-c")
        goOffline("dev-c")
        say("dev-a", groupId, "are you there")
        val sent = stored("dev-a", groupId, "are you there")!!

        val waiting = infoOf("dev-a", sent.localId).first()!!
        assertEquals(listOf("dev-b"), waiting.deliveredTo.map { it.id })
        assertEquals("c has not got it", listOf("dev-c"), waiting.waiting.map { it.id })
        assertEquals("because its device is not reachable", FlashWaitingReason.DeviceNotReached, waiting.waiting.single().waitingReason)

        assertTrue(nodes.getValue("dev-a").repo.removeGroupMember(groupId, "dev-c") is FlashResult.Success)
        settle()
        val removed = infoWhere("dev-a", sent.localId) { info -> info.waiting.any { it.waitingReason == FlashWaitingReason.LeftGroup } }
        assertEquals("a removed member that never got it is marked as no longer in the group", listOf("dev-c"), removed.waiting.map { it.id })
    }

    @Test
    fun `message info is only for a message this device wrote in a group`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        say("dev-a", groupId, "mine")
        val sent = stored("dev-a", groupId, "mine")!!
        settle()

        assertNull("the receiver has no recipient rows for it", infoOf("dev-b", sent.localId).first())
        assertNull("an unknown id", infoOf("dev-a", "no-such-message").first())
        nodes.getValue("dev-a").repo.deleteMessage(sent.localId)
        settle()
        assertNull("a deleted message", infoOf("dev-a", sent.localId).first())
    }

    @Test
    fun `message info is not offered for a direct message`() = runBlocking {
        mesh("dev-a", "dev-b")
        say("dev-a", "dev-b", "just us two")
        val sent = stored("dev-a", "dev-b", "just us two")!!

        assertNull("a direct message has no recipient rows; its status is on the bubble", infoOf("dev-a", sent.localId).first())
    }

    // ------------------------------------------------------------------------------ catch-up banner
    // Chat/group sync audit, step 7 (UI-052). The requester never learns how many messages will come, so the banner only
    // says history is arriving and counts what has landed. It starts on the first NEW message, not on a request.

    private suspend fun syncOf(id: String, predicate: (FlashGroupSyncUi?) -> Boolean): FlashGroupSyncUi? {
        val repo = nodes.getValue(id).repo
        withTimeout(5_000) {
            while (!predicate(repo.conversationState.value.groupSync)) delay(20)
        }
        return repo.conversationState.value.groupSync
    }

    private suspend fun pushHistory(requesterSync: String, holder: String, requester: String, groupId: String, messageId: String, text: String) {
        val frame = message(groupId, messageId, holder, text, sign(holder, groupId, messageId, holder, text))
        deliver(holder, requester, GroupWireFrame.SyncPush(groupId, requesterSync, holder, frame))
    }

    @Test
    fun `the catch-up banner starts on the first new message, counts the arrivals and clears after the quiet period`() = runBlocking {
        assertTrue("the quiet period outlasts a backup holder's push", GroupPolicy.SYNC_QUIET_MS > GroupPolicy.BACKUP_DELAY_MS)
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()
        assertNull("nothing is arriving yet", nodes.getValue("dev-b").repo.conversationState.value.groupSync)

        val syncId = interceptSyncRequest(requester = "dev-b", holder = "dev-a", groupId = groupId)
        assertNull("a request alone shows nothing", nodes.getValue("dev-b").repo.conversationState.value.groupSync)

        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-1", "first")
        assertEquals(FlashGroupSyncUi(1), syncOf("dev-b") { it != null })
        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-2", "second")
        assertEquals("each new message counts", FlashGroupSyncUi(2), syncOf("dev-b") { it?.receivedCount == 2 })
        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-2", "second")
        delay(150)
        assertEquals("a message already stored does not count", FlashGroupSyncUi(2), nodes.getValue("dev-b").repo.conversationState.value.groupSync)

        assertNull("the banner goes away once pushes stop", syncOf("dev-b") { it == null })

        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-2", "second")
        delay(150)
        assertNull("a duplicate never starts a banner", nodes.getValue("dev-b").repo.conversationState.value.groupSync)

        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-3", "third")
        assertEquals("a later burst starts again from one", FlashGroupSyncUi(1), syncOf("dev-b") { it != null })
    }

    @Test
    fun `a newer arrival keeps the banner up past the first arrival's quiet period`() = runBlocking {
        mesh("dev-a", "dev-b")
        val groupId = createGroup("dev-a", "Team", "dev-b")
        nodes.getValue("dev-b").repo.openConversation(groupId)
        settle()
        val syncId = interceptSyncRequest(requester = "dev-b", holder = "dev-a", groupId = groupId)

        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-1", "first")
        syncOf("dev-b") { it != null }
        delay(syncQuietMs * 2 / 3)
        pushHistory(syncId, "dev-a", "dev-b", groupId, "h-2", "second")
        delay(syncQuietMs * 2 / 3)

        // 1.33 quiet periods after the first arrival, but only 0.67 after the last.
        assertEquals("the first timer must not close a banner a newer arrival extended", FlashGroupSyncUi(2), nodes.getValue("dev-b").repo.conversationState.value.groupSync)
        assertNull(syncOf("dev-b") { it == null })
    }

    @Test
    fun `the catch-up banner belongs to its own group only`() = runBlocking {
        mesh("dev-a", "dev-b")
        val first = createGroup("dev-a", "One", "dev-b")
        val second = createGroup("dev-a", "Two", "dev-b")
        nodes.getValue("dev-b").repo.openConversation(first)
        settle()
        val syncId = interceptSyncRequest(requester = "dev-b", holder = "dev-a", groupId = second)

        pushHistory(syncId, "dev-a", "dev-b", second, "h-1", "history of two")
        assertNotNull("the message did land in the other group", stored("dev-b", second, "history of two"))
        delay(200)

        assertNull("the open group shows no banner for another group's history", nodes.getValue("dev-b").repo.conversationState.value.groupSync)
    }

    @Test
    fun `option D continue in new group establishes new owner and preserves history`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val oldGroupId = createGroup("dev-a", "Sprint Planning", "dev-b", "dev-c")
        say("dev-a", oldGroupId, "Welcome to sprint planning")

        val repoA = nodes.getValue("dev-a").repo
        val repoB = nodes.getValue("dev-b").repo
        val repoC = nodes.getValue("dev-c").repo

        repoA.openConversation(oldGroupId)
        repoB.openConversation(oldGroupId)
        settle()

        // Verify conversation states on old group
        val stateA = repoA.conversationState.value
        assertTrue("creator can add members", stateA.canAddMembers)
        assertTrue("creator is group owner", stateA.isGroupOwner)
        assertTrue("creator can remove members", stateA.canRemoveMembers)
        assertFalse("creator cannot continue in new group", stateA.canContinueInNewGroup)

        val stateB = repoB.conversationState.value
        assertFalse("non-owner cannot add members in v2 group", stateB.canAddMembers)
        assertFalse("non-owner is not group owner", stateB.isGroupOwner)
        assertFalse("non-owner cannot remove members", stateB.canRemoveMembers)
        assertTrue("non-owner can continue in new group", stateB.canContinueInNewGroup)

        // Now dev-b continues in new group
        val newGroupResult = repoB.continueInNewGroup(oldGroupId)
        assertTrue("continueInNewGroup must succeed: $newGroupResult", newGroupResult is FlashResult.Success)
        val newGroupId = (newGroupResult as FlashResult.Success).value
        assertNotEquals("new group has distinct id", oldGroupId, newGroupId)

        settle()

        // dev-b opens new group
        repoB.openConversation(newGroupId)
        settle()

        val stateBNew = repoB.conversationState.value
        assertEquals("new group keeps old title", "Sprint Planning", stateBNew.header.title)
        assertTrue("dev-b is owner of new group", stateBNew.isGroupOwner)
        assertTrue("dev-b can add members in new group", stateBNew.canAddMembers)
        assertTrue("dev-b can remove members in new group", stateBNew.canRemoveMembers)
        assertFalse("dev-b cannot continue in new group as owner", stateBNew.canContinueInNewGroup)

        // Verify old group still exists on dev-b with old history
        assertNotNull("old group still in conversationDao", nodes.getValue("dev-b").conversationDao.get(oldGroupId))
        assertNotNull("old messages still preserved", stored("dev-b", oldGroupId, "Welcome to sprint planning"))

        // dev-c receives the new group
        val memberDaoC = nodes.getValue("dev-c").memberDao
        assertNotNull("dev-c is active member in new group", memberDaoC.member(newGroupId, "dev-c"))
        assertTrue("dev-c active in new group", memberDaoC.member(newGroupId, "dev-c")?.isActive == true)
        // old owner dev-a was not invited to the new group
        assertNull("old owner was excluded from new group", memberDaoC.member(newGroupId, "dev-a"))
    }

    // ------------------------------------------------------------------------------ ADR-063: Admins & Successor

    @Test
    fun `owner promotes admin and admin adds and removes member`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c", "dev-d")
        val groupId = createGroup("dev-a", "Admin Team", "dev-b", "dev-c")

        val repoA = nodes.getValue("dev-a").repo
        val repoB = nodes.getValue("dev-b").repo

        repoA.openConversation(groupId)
        repoB.openConversation(groupId)
        settle()

        // dev-a promotes dev-b to admin
        val promoteResult = repoA.promoteAdmin(groupId, "dev-b")
        assertTrue("promote must succeed: $promoteResult", promoteResult is FlashResult.Success)
        settle()

        // Verify dev-b is now admin on both dev-a and dev-b
        val memberBOnA = nodes.getValue("dev-a").memberDao.member(groupId, "dev-b")
        assertEquals("admin", memberBOnA?.role)
        val memberBOnB = nodes.getValue("dev-b").memberDao.member(groupId, "dev-b")
        assertEquals("admin", memberBOnB?.role)

        val stateB = repoB.conversationState.value
        assertTrue("dev-b is admin", stateB.isGroupAdmin)
        assertTrue("dev-b can add members as admin", stateB.canAddMembers)
        assertTrue("dev-b can remove members as admin", stateB.canRemoveMembers)
        assertFalse("dev-b cannot promote admin (owner only)", stateB.canPromoteAdmin)

        // dev-b (as admin) adds dev-d to the group
        val addResult = repoB.addGroupMembers(groupId, setOf("dev-d"))
        assertTrue("add members by admin must succeed: $addResult", addResult is FlashResult.Success)
        settle()

        // dev-d should now have joined and be active
        val memberDOnB = nodes.getValue("dev-b").memberDao.member(groupId, "dev-d")
        assertNotNull("dev-d was added by admin", memberDOnB)
        assertTrue("dev-d is active", memberDOnB?.isActive == true)

        val memberDOnD = nodes.getValue("dev-d").memberDao.member(groupId, "dev-d")
        assertNotNull("dev-d sees self as member", memberDOnD)
        assertTrue("dev-d is active on own device", memberDOnD?.isActive == true)

        // dev-b (as admin) removes dev-c
        val removeResult = repoB.removeGroupMember(groupId, "dev-c")
        assertTrue("remove member by admin must succeed: $removeResult", removeResult is FlashResult.Success)
        settle()

        val memberCOnB = nodes.getValue("dev-b").memberDao.member(groupId, "dev-c")
        assertFalse("dev-c is no longer active", memberCOnB?.isActive == true)

        // dev-b (as admin) cannot remove owner (dev-a)
        val removeOwnerResult = repoB.removeGroupMember(groupId, "dev-a")
        assertTrue("admin cannot remove owner", removeOwnerResult is FlashResult.Failure)
    }

    @Test
    fun `owner leaves group naming a successor admin`() = runBlocking {
        mesh("dev-a", "dev-b", "dev-c")
        val groupId = createGroup("dev-a", "Successor Team", "dev-b", "dev-c")

        val repoA = nodes.getValue("dev-a").repo
        val repoB = nodes.getValue("dev-b").repo

        repoA.openConversation(groupId)
        repoB.openConversation(groupId)
        settle()

        // Owner dev-a leaves group, naming dev-b as successor
        val leaveResult = repoA.leaveGroup(groupId, "dev-b")
        assertTrue("leave with successor must succeed: $leaveResult", leaveResult is FlashResult.Success)
        settle()

        // Verify dev-a is now inactive
        val memberAOnB = nodes.getValue("dev-b").memberDao.member(groupId, "dev-a")
        assertFalse("dev-a is inactive", memberAOnB?.isActive == true)

        // Verify dev-b was promoted to admin and can manage members
        val memberBOnB = nodes.getValue("dev-b").memberDao.member(groupId, "dev-b")
        assertEquals("admin", memberBOnB?.role)

        val stateB = repoB.conversationState.value
        assertTrue("dev-b is now admin", stateB.isGroupAdmin)
        assertTrue("dev-b can add members", stateB.canAddMembers)
    }

    // ------------------------------------------------------------------------------ harness: nodes

    private class Node(
        val id: String,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val outboxDao: InMemoryOutboxDao = InMemoryOutboxDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val deliveryDao: InMemoryGroupDeliveryDao = InMemoryGroupDeliveryDao(),
        val readCursorDao: InMemoryReadCursorDao = InMemoryReadCursorDao(),
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

    /** Neither device is paired with the other (they may still be connected). */
    private fun unpair(x: String, y: String) {
        distrust(x, y)
        distrust(y, x)
    }

    private fun isPairedWith(id: String, peer: String): Boolean =
        peer in names.keys && peer != id && distrusted[id]?.contains(peer) != true

    private fun vouchingOf(id: String): TestVouching = vouchings.getOrPut(id) {
        TestVouching(isPaired = { peer -> isPairedWith(id, peer) }, pairedPin = { peer -> pinOf(peer) })
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
            groupSyncQuietMs = syncQuietMs,
            isTrustedPeer = { peer -> isPairedWith(id, peer) },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
            groupCrypto = cryptos.getValue(id),
            pinnedFingerprint = { peer -> pinOf(peer) },
            peerGroupProtocol = { peer -> level[peer] ?: GroupPolicy.V2_PROTOCOL },
            peerIdentityKey = { peer -> if (peer in keyless) null else presentedKey[peer] ?: cryptos[peer]?.publicKey },
            groupVouching = if (vouchedTrust) vouchingOf(id) else null,
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

    @Test
    fun `swarm announcement statement and chat message signature domain separation`() = runBlocking {
        val dir = parties("dev-a", "dev-b")
        val ada = engine(dir, "dev-a")
        val bo = engine(dir, "dev-b")

        val created = ada.create("SepGroup", mapOf("dev-b" to keyOf(dir, "dev-b"))) { it }
        val gid = created.groupId
        val outcome = bo.onBundle("dev-a", created.bundle)
        assertTrue("bo must accept ada's group bundle", outcome is SignedGroups.BundleOutcome.Applied)

        val rootHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val swarmSig = ada.signSwarmAnnouncement(
            groupId = gid,
            messageId = "m1",
            root = rootHex,
            sizeBytes = 1024L,
            fileName = "test.bin",
            mimeType = "application/octet-stream",
            sentAt = 1000L,
        )
        assertNotNull("swarm announcement signature should be generated", swarmSig)

        val swarmValid = bo.verifySwarmAnnouncement(
            groupId = gid,
            authorId = "dev-a",
            messageId = "m1",
            root = rootHex,
            sizeBytes = 1024L,
            fileName = "test.bin",
            mimeType = "application/octet-stream",
            sentAt = 1000L,
            rootSig = swarmSig!!,
        )
        assertTrue("valid swarm announcement must verify", swarmValid)

        val boParty = dir.getValue("dev-b")
        val boRules = GroupSignatureRules(
            crypto = boParty.crypto,
            localDeviceId = "dev-b",
            isPaired = { _: String -> true },
            pinnedFingerprint = { peer: String -> dir[peer]?.crypto?.publicKey?.let { fingerprintHex(it) } },
        )

        // Swarm announcement signature must NOT verify as a chat message
        val asMsgValid = boRules.verifyMessage(
            authorKey = GroupCanonical.encode(keyOf(dir, "dev-a")),
            groupId = gid,
            messageId = "m1",
            from = "dev-a",
            sentAt = 1000L,
            replyToId = null,
            replyPreview = null,
            text = "test.bin",
            signature = swarmSig,
        )
        assertFalse("swarm announcement signature must NEVER verify as a chat message", asMsgValid)

        val msgSig = ada.signMessage(
            groupId = gid,
            messageId = "m2",
            sentAt = 2000L,
            replyToId = null,
            replyPreview = null,
            text = "hello world",
        )

        val msgValid = boRules.verifyMessage(
            authorKey = GroupCanonical.encode(keyOf(dir, "dev-a")),
            groupId = gid,
            messageId = "m2",
            from = "dev-a",
            sentAt = 2000L,
            replyToId = null,
            replyPreview = null,
            text = "hello world",
            signature = msgSig,
        )
        assertTrue("valid message must verify", msgValid)

        val asSwarmValid = bo.verifySwarmAnnouncement(
            groupId = gid,
            authorId = "dev-a",
            messageId = "m2",
            root = rootHex,
            sizeBytes = 1024L,
            fileName = "test.bin",
            mimeType = "application/octet-stream",
            sentAt = 2000L,
            rootSig = msgSig,
        )
        assertFalse("chat message signature must NEVER verify as a swarm announcement", asSwarmValid)
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

    private fun engine(
        directory: Map<String, Party>,
        id: String,
        budget: VerifyBudget = VerifyBudget(),
        vouching: GroupVouching? = null,
        paired: (String) -> Boolean = { peer -> peer in directory && peer != id },
    ): SignedGroups {
        val party = directory.getValue(id)
        return SignedGroups(
            localDeviceId = id,
            localDisplayName = id,
            crypto = party.crypto,
            conversationDao = party.conversations,
            members = party.members,
            isPaired = paired,
            pinnedFingerprint = { peer -> directory[peer]?.crypto?.publicKey?.let { fingerprintHex(it) } },
            vouching = vouching,
            nowMs = { 1_700_000_000_000L },
            newId = { "id-${counter.incrementAndGet()}" },
            budget = budget,
        )
    }

    /** A trust store for [id] that is paired with [pairedWith] only, every pairing under the device's real key. */
    private fun vouchingFor(directory: Map<String, Party>, pairedWith: Set<String>): TestVouching =
        TestVouching(
            isPaired = { peer -> peer in pairedWith },
            pairedPin = { peer -> directory[peer]?.crypto?.publicKey?.let { fingerprintHex(it) } },
        )

    private fun fingerprintHex(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02X".format(it) }

    private companion object {
        const val SENT_AT = 1_700_000_000_123L
    }
}
