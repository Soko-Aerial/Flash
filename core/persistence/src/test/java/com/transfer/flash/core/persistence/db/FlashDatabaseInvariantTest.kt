package com.transfer.flash.core.persistence.db

import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity
import com.transfer.flash.core.persistence.db.entity.TransferEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * C1.8 DAO-level concurrency/dedup invariants. These encode the guarantees the C6 engine will
 * rely on, before the engine exists. In-memory Room WITHOUT the SQLCipher factory — the native
 * sqlcipher .so is unavailable on the JVM; Robolectric runs the framework SQLite driver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashDatabaseInvariantTest {

    private lateinit var db: FlashDatabase

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = FlashDatabaseOpener.openInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun message(
        localId: String,
        conversationId: String,
        sentAt: Long,
        status: String = "SENT",
    ) = MessageEntity(
        localId = localId,
        conversationId = conversationId,
        senderId = "sender-$localId",
        senderName = null,
        text = "text of $localId",
        sentAt = sentAt,
        status = status,
    )

    private suspend fun conversation(id: String): ConversationEntity {
        val entity =
            ConversationEntity(
                id = id,
                title = "Conversation $id",
                isGroup = false,
            )
        db.conversationDao().upsert(entity)
        return entity
    }

    /** Greater than any real localId used here — sentinel for "before everything". */
    private val startSentinelLocalId = "\uFFFF"

    // ---------------------------------------------------------------- tests

    @Test
    fun duplicateMessageLocalIdInsertIsIgnored() = runTest {
        conversation("conv-1")
        val dao = db.messageDao()

        val firstInsert = dao.insert(message("m-1", "conv-1", sentAt = 1_000L))
        val duplicateInsert = dao.insert(message("m-1", "conv-1", sentAt = 1_000L))

        assertTrue(firstInsert != -1L)
        assertEquals(-1L, duplicateInsert)
        assertEquals(1, dao.observeConversation("conv-1").first().size)
    }

    @Test
    fun duplicateReceiptInsertIsIgnored() = runTest {
        conversation("conv-1")
        db.messageDao().insert(message("m-1", "conv-1", sentAt = 1_000L))
        val dao = db.receiptDao()

        assertTrue(dao.insert(ReceiptEntity("m-1", "member-a", "DELIVERED")) != -1L)
        assertEquals(-1L, dao.insert(ReceiptEntity("m-1", "member-a", "READ")))

        val receipts = dao.observeForMessage("m-1").first()
        assertEquals(1, receipts.size)
        // First state wins; a replayed older ACK must not overwrite newer state.
        assertEquals("DELIVERED", receipts.single().state)
    }

    @Test
    fun outboxDrainClaimAndDeleteSemantics() = runTest {
        val dao = db.outboxDao()
        val item =
            OutboxEntity(
                localId = "outbox-1",
                nextAttemptAt = 100L,
                payloadJson = """{"type":"MSG"}""",
                createdAt = 0L,
            )

        // Enqueue on empty table succeeds; duplicate enqueue is IGNOREd.
        assertTrue(dao.enqueue(item) != -1L)
        assertEquals(-1L, dao.enqueue(item))
        assertEquals(1, dao.observeCount().first())

        // Claim window before nextAttemptAt: nothing due yet.
        assertTrue(dao.dueForDelivery(now = 99L, limit = 10).isEmpty())

        // Claim at/after due time.
        val claimed = dao.dueForDelivery(now = 100L, limit = 10)
        assertEquals(listOf("outbox-1"), claimed.map { it.localId })

        // Two claimers each increment once -> attempts == 2, row still claimable until deleted.
        dao.incrementAttempts("outbox-1")
        dao.incrementAttempts("outbox-1")
        assertEquals(2, dao.dueForDelivery(now = 200L, limit = 10).single().attempts)

        // Delivery commit: delete removes the row; re-claiming yields nothing (no double send).
        dao.delete("outbox-1")
        assertTrue(dao.dueForDelivery(now = 300L, limit = 10).isEmpty())
        assertEquals(0, dao.observeCount().first())
    }

    @Test
    fun readCursorAdvanceFurthestIsMonotonic() = runTest {
        val dao = db.readCursorDao()

        dao.advanceFurthest("conv-1", "member-a", upToMessageId = "msg-5", upToSentAt = 500L)
        assertEquals(
            ReadCursorEntity("conv-1", "member-a", "msg-5", 500L),
            dao.get("conv-1", "member-a"),
        )

        // Older cursor must NOT move the cursor back.
        dao.advanceFurthest("conv-1", "member-a", upToMessageId = "msg-2", upToSentAt = 200L)
        assertEquals(
            ReadCursorEntity("conv-1", "member-a", "msg-5", 500L),
            dao.get("conv-1", "member-a"),
        )

        // Equal sentAt with lexicographically smaller messageId: no regression either.
        dao.advanceFurthest("conv-1", "member-a", upToMessageId = "msg-3", upToSentAt = 500L)
        assertEquals(
            ReadCursorEntity("conv-1", "member-a", "msg-5", 500L),
            dao.get("conv-1", "member-a"),
        )

        // Newer position advances.
        dao.advanceFurthest("conv-1", "member-a", upToMessageId = "msg-9", upToSentAt = 900L)
        assertEquals(
            ReadCursorEntity("conv-1", "member-a", "msg-9", 900L),
            dao.get("conv-1", "member-a"),
        )

        // Cursors are per-member and independent.
        dao.advanceFurthest("conv-1", "member-b", upToMessageId = "msg-4", upToSentAt = 400L)
        val cursors = dao.observeCursors("conv-1").first()
        assertEquals(2, cursors.size)
        assertEquals("msg-9", cursors.first { it.memberId == "member-a" }.upToMessageId)
        assertEquals("msg-4", cursors.first { it.memberId == "member-b" }.upToMessageId)
    }

    @Test
    fun keysetPaginationWalksHistoryWithoutDupOrGapAndScopesByConversation() = runTest {
        conversation("conv-A")
        conversation("conv-B")

        // Deliberate sentAt collisions within each conversation so the localId tiebreaker in
        // the composite keyset cursor is actually exercised.
        val baseSentAt = 1_700_000_000_000L
        val messagesA = (0 until 30).map { i ->
            message(
                localId = "a-%03d".format(i),
                conversationId = "conv-A",
                sentAt = baseSentAt + (i % 3),
            )
        }
        val messagesB = (0 until 20).map { i ->
            message(
                localId = "b-%03d".format(i),
                conversationId = "conv-B",
                sentAt = baseSentAt + (i % 3),
            )
        }
        (messagesA + messagesB).forEach { db.messageDao().insert(it) }

        val pageSize = 7
        val walked = mutableListOf<MessageEntity>()

        var cursorSentAt = Long.MAX_VALUE
        var cursorLocalId = startSentinelLocalId
        while (true) {
            val page =
                db.messageDao().historyBefore(
                    conversationId = "conv-A",
                    cursorSentAt = cursorSentAt,
                    cursorLocalId = cursorLocalId,
                    limit = pageSize,
                )
            assertTrue(page.size <= pageSize)
            if (page.isEmpty()) break
            walked += page
            val last = page.last()
            cursorSentAt = last.sentAt
            cursorLocalId = last.localId
            assertTrue(walked.size <= messagesA.size) // guard against an infinite loop
        }

        // No dup, no gap: exactly the conv-A rows, newest first.
        assertEquals(messagesA.size, walked.size)
        assertEquals(walked.size, walked.distinctBy { it.localId }.size)
        assertEquals(messagesA.map { it.localId }.toSet(), walked.map { it.localId }.toSet())
        assertTrue(walked.all { it.conversationId == "conv-A" })

        // Strictly descending composite order across page boundaries (no boundary loss).
        walked.zipWithNext().forEach { (prev, next) ->
            val strictlyDescending =
                next.sentAt < prev.sentAt ||
                    (next.sentAt == prev.sentAt && next.localId < prev.localId)
            assertTrue(
                "(${next.sentAt}, ${next.localId}) must precede (${prev.sentAt}, ${prev.localId})",
                strictlyDescending,
            )
        }

        // Conversation scoping: full walk for conv-B too, disjoint from A.
        val walkedB = mutableListOf<MessageEntity>()
        var csB = Long.MAX_VALUE
        var clB = startSentinelLocalId
        while (true) {
            val page = db.messageDao().historyBefore("conv-B", csB, clB, pageSize)
            if (page.isEmpty()) break
            walkedB += page
            csB = page.last().sentAt
            clB = page.last().localId
        }
        assertEquals(messagesB.size, walkedB.size)
        assertEquals(messagesB.map { it.localId }.toSet(), walkedB.map { it.localId }.toSet())
        assertTrue((walked.map { it.localId } intersect walkedB.map { it.localId }).isEmpty())
    }

    @Test
    fun chunkDoneSetRoundTripForResumeBitVector() = runTest {
        val transferId = "transfer-1"
        db.transferDao().insert(
            TransferEntity(
                transferId = transferId,
                totalBytes = 8L * 1024 * 1024,
                bytesDone = 0L,
                status = "TRANSFERRING",
            ),
        )
        db.transferChunkDao().insertAll((0 until 8).map { index ->
            TransferChunkEntity(transferId = transferId, chunkIndex = index, done = false)
        })

        assertTrue(db.transferChunkDao().doneChunks(transferId).isEmpty())

        // Resume vector: chunks 0, 2 and 3 completed.
        listOf(0, 2, 3).forEach { db.transferChunkDao().markChunkDone(transferId, it) }
        assertEquals(listOf(0, 2, 3), db.transferChunkDao().doneChunks(transferId))

        // markChunkDone is idempotent.
        db.transferChunkDao().markChunkDone(transferId, 2)
        assertEquals(listOf(0, 2, 3), db.transferChunkDao().doneChunks(transferId))

        // Done-set is scoped per transfer.
        db.transferDao().insert(
            TransferEntity(transferId = "transfer-2", totalBytes = 1L, status = "OFFERED"),
        )
        db.transferChunkDao().insertAll(
            listOf(TransferChunkEntity("transfer-2", 0)),
        )
        db.transferChunkDao().markChunkDone("transfer-2", 0)
        assertEquals(listOf(0), db.transferChunkDao().doneChunks("transfer-2"))
        assertEquals(listOf(0, 2, 3), db.transferChunkDao().doneChunks(transferId))

        // resetStuck clears only the target transfer's done-set.
        db.transferChunkDao().resetStuck(transferId)
        assertTrue(db.transferChunkDao().doneChunks(transferId).isEmpty())
        assertEquals(listOf(0), db.transferChunkDao().doneChunks("transfer-2"))

        // Rows survive the reset (structure intact, flags cleared).
        db.transferChunkDao().markChunkDone(transferId, 7)
        assertEquals(listOf(7), db.transferChunkDao().doneChunks(transferId))
    }

    @Test
    fun draftUpsertLastWriteWinsAndClearRemovesRow() = runTest {
        val dao = db.draftDao()
        dao.upsert(DraftEntity("conv-1", text = "hello", updatedAt = 100L))
        dao.upsert(DraftEntity("conv-1", text = "hello world", updatedAt = 200L))

        assertEquals("hello world", dao.observeDraft("conv-1").first()?.text)

        dao.clear("conv-1")
        assertNull(dao.observeDraft("conv-1").first())
    }

    // ------------------------------------------------------------------ group tables (v4)

    private fun member(
        groupId: String,
        deviceId: String,
        version: Long,
        op: String,
        active: Boolean = true,
    ) = GroupMemberEntity(
        groupId = groupId,
        deviceId = deviceId,
        displayName = "Member $deviceId",
        role = if (deviceId == "creator") "owner" else "member",
        joinedAt = 1_000L,
        membershipVersion = version,
        operationId = op,
        isActive = active,
    )

    @Test
    fun groupMemberUpsertReplacesTheWholeRowAndActiveFilterApplies() = runTest {
        val dao = db.groupMemberDao()
        dao.upsert(member("g1", "creator", version = 100L, op = "op-create"))
        dao.upsert(member("g1", "peer-a", version = 100L, op = "op-create"))

        assertEquals(2, dao.activeCount("g1"))

        // Leave = tombstone via upsert with isActive=false; active queries no longer see it,
        // but the row survives so a stale add can be version-compared against it.
        dao.upsert(member("g1", "peer-a", version = 200L, op = "op-leave", active = false))
        assertEquals(1, dao.activeCount("g1"))
        assertEquals(false, dao.member("g1", "peer-a")!!.isActive)
        assertEquals(2, dao.observeMembers("g1").first().size)
    }

    @Test
    fun groupDeliveryStateTransitionsAreMonotonicAndScoped() = runTest {
        val dao = db.groupDeliveryDao()
        dao.insertAll(
            listOf(
                GroupDeliveryEntity("m1", "peer-a", nextAttemptAt = 10L),
                GroupDeliveryEntity("m1", "peer-b", nextAttemptAt = 10L),
                GroupDeliveryEntity("m2", "peer-a", nextAttemptAt = 10L),
            ),
        )
        assertEquals(2, dao.pendingForMessage("m1").size)

        // First receipt flips only that member.
        assertEquals(1, dao.markDelivered("m1", "peer-a", deliveredAt = 50L))
        assertEquals(1, dao.pendingForMessage("m1").size)
        assertEquals(1, dao.deliveredCount("m1"))

        // Replayed receipt is a no-op (idempotent).
        assertEquals(0, dao.markDelivered("m1", "peer-a", deliveredAt = 60L))

        // Retry reschedule cannot resurrect a delivered row.
        dao.reschedule("m1", "peer-a", state = "PENDING", nextAttemptAt = 70L)
        assertEquals(1, dao.deliveredCount("m1"))
        assertEquals(1, dao.pendingForMessage("m1").size)

        // makePendingDueForMember touches only that member's undelivered rows.
        dao.makePendingDueForMember("peer-b", now = 100L)
        val peerB = dao.pendingForMessage("m1").single()
        assertEquals("peer-b", peerB.memberId)
        assertEquals(100L, peerB.nextAttemptAt)
        assertEquals(0, peerB.attempts)

        // Scoped by message: m2 untouched.
        assertEquals(1, dao.pendingForMessage("m2").size)
    }

    @Test
    fun conversationProvenanceColumnsRoundTrip() = runTest {
        val dao = db.conversationDao()
        dao.upsert(
            ConversationEntity(
                id = "group-1",
                title = "Team",
                isGroup = true,
                sortOrder = 5L,
                groupCreatedBy = "creator",
                groupCreatedAt = 42L,
            ),
        )
        val loaded = dao.get("group-1")
        assertEquals("creator", loaded!!.groupCreatedBy)
        assertEquals(42L, loaded.groupCreatedAt)
        assertTrue(loaded.isGroup)

        // Direct rows keep null provenance.
        dao.upsert(ConversationEntity(id = "peer-1", title = "Alex", isGroup = false))
        assertNull(dao.get("peer-1")!!.groupCreatedBy)
    }
}
