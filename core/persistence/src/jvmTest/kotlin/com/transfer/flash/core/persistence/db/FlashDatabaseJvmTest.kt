package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.MessagePinEntity
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.persistence.db.entity.SwarmTombstoneEntity
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [FlashDatabase] actually OPENS and WORKS on the desktop `jvm()` target (Phase 09B-1).
 *
 * This suite is the point of 09B-1. Relocating 24 entity/DAO files to `commonMain` only proves they
 * compile without `android.jar`; CONVENTIONS.md R3.1 is explicit that "an `actual` that is only
 * compiled is not verified". What is unverified without this file is the whole generated tier: that
 * Room's KSP processor ran for `kspJvm`, that it emitted `actual object FlashDatabaseConstructor`,
 * that the `FlashDatabase_Impl` it wrote targets the multiplatform `androidx.sqlite` driver
 * interfaces rather than the Android Support ones, and that its `InvalidationTracker` works off-Android.
 *
 * Two details are load-bearing and must not be "simplified":
 *
 * 1. **`factory = FlashDatabaseConstructor::initialize` is passed explicitly.** `Room`'s jvm
 *    `inMemoryDatabaseBuilder` declares `factory` with a DEFAULT of
 *    `{ findAndInstantiateDatabaseImpl(T::class.java) }` — i.e. reflection. Omitting the argument
 *    would make every test here pass even if `@ConstructedBy` did nothing and no `actual` object
 *    existed, because Room would find `FlashDatabase_Impl` by name instead. Passing the constructor
 *    reference is what routes the open through the generated-constructor seam under test.
 * 2. **`runBlocking`, not `runTest`.** These are real I/O against a real SQLite build; `runTest`'s
 *    virtual clock would make the real-time `withTimeout` waits in
 *    [flow re-emits after a write, proving InvalidationTracker runs on jvm] expire instantly.
 *
 * **The driver is [BundledSQLiteDriver], which is UNENCRYPTED.** PHASE-09B permits it here and
 * nowhere else, and only in memory: `inMemoryDatabaseBuilder` passes `name = null`, so no file path
 * — and no `":memory:"` string literal — is involved at all. Putting this driver in `jvmMain`, or
 * giving it a path, is "B without C" under D5's charter and is forbidden. The encrypted desktop
 * driver is 09B-2's problem and needs a human decision first.
 */
class FlashDatabaseJvmTest {

    private var open: FlashDatabase? = null

    @AfterTest
    fun closeDatabase() {
        open?.close()
        open = null
    }

    private fun openDatabase(): FlashDatabase =
        Room.inMemoryDatabaseBuilder<FlashDatabase>(factory = FlashDatabaseConstructor::initialize)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { open = it }

    @Test
    fun `trusted peer round-trips through the generated jvm _Impl`() = runBlocking {
        val dao = openDatabase().trustedPeerDao()

        assertFalse(dao.isPinned(DEVICE_ID, FINGERPRINT), "empty db should not report a pin")

        val rowId = dao.insert(peer(DEVICE_ID))
        assertTrue(rowId > 0L, "insert should return a real rowid, got $rowId")
        assertTrue(dao.isPinned(DEVICE_ID, FINGERPRINT), "pin should be visible after insert")

        val stored = dao.observeAll().first().single()
        assertEquals(DEVICE_ID, stored.deviceId)
        assertEquals("peer-$DEVICE_ID", stored.name)
        assertEquals(FINGERPRINT, stored.fingerprintHex)
        assertEquals(TRUSTED_AT, stored.trustedAt)

        dao.revoke(DEVICE_ID)
        assertFalse(dao.isPinned(DEVICE_ID, FINGERPRINT), "pin should be gone after revoke")
        assertTrue(dao.observeAll().first().isEmpty(), "table should be empty after revoke")
    }

    @Test
    fun `IGNORE conflict strategy returns minus one on a duplicate primary key`() = runBlocking {
        // OnConflictStrategy.IGNORE is compiled into the generated _Impl as `INSERT OR IGNORE`, so
        // this checks the jvm code generator honours the annotation, not just that SQL runs.
        val dao = openDatabase().trustedPeerDao()
        assertTrue(dao.insert(peer(DEVICE_ID)) > 0L)
        assertEquals(-1L, dao.insert(peer(DEVICE_ID).copy(name = "should-not-win")))
        assertEquals("peer-$DEVICE_ID", dao.observeAll().first().single().name)
    }

    @Test
    fun `all eleven tables exist on the jvm target`() = runBlocking {
        // One read per @Dao. A missing table makes SQLite raise, so these calls are existence
        // probes; the returned emptiness is secondary. Five DAOs have no argument-free read, hence
        // the "absent" keys. Reads were chosen over writes so nothing here depends on entity shape.
        val db = openDatabase()
        assertTrue(db.conversationDao().observeAll().first().isEmpty(), "conversations")
        assertNull(db.messageDao().getByLocalId("absent"), "messages")
        assertEquals(0, db.receiptDao().countForMessage("absent"), "receipts")
        assertTrue(db.outboxDao().dueForDelivery(now = 0L, limit = 1).isEmpty(), "outbox")
        assertNull(db.transferDao().observe("absent").first(), "transfers")
        assertTrue(db.transferChunkDao().allDoneChunks().isEmpty(), "transfer chunks")
        assertTrue(db.recentSearchDao().observeRecent(limit = 1).first().isEmpty(), "recent searches")
        assertTrue(db.trustedPeerDao().observeAll().first().isEmpty(), "trusted peers")
        assertNull(db.reactionDao().get("absent", "+1"), "reactions")
        assertNull(db.draftDao().observeDraft("absent").first(), "drafts")
        assertNull(db.readCursorDao().get("absent", "absent"), "read cursors")
    }

    @Test
    fun `clearing a conversation read cursor re-emits all inbound messages as unread`() = runBlocking {
        val db = openDatabase()
        val conversationDao = db.conversationDao()
        val messageDao = db.messageDao()
        conversationDao.upsert(
            com.transfer.flash.core.persistence.db.entity.ConversationEntity(
                id = "conversation-unread",
                title = "Unread",
                isGroup = false,
                lastReadCursor = "inbound-new",
            ),
        )
        fun message(id: String, senderId: String, sentAt: Long) =
            com.transfer.flash.core.persistence.db.entity.MessageEntity(
                localId = id,
                conversationId = "conversation-unread",
                senderId = senderId,
                senderName = senderId,
                text = id,
                sentAt = sentAt,
                status = "DELIVERED",
            )
        messageDao.insert(message("inbound-old", "peer", 1L))
        messageDao.insert(message("self", "self", 2L))
        messageDao.insert(message("inbound-new", "peer", 3L))
        assertTrue(messageDao.observeUnreadCounts("self").first().isEmpty())

        conversationDao.clearLastReadCursor("conversation-unread")

        assertNull(conversationDao.get("conversation-unread")!!.lastReadCursor)
        assertEquals(2, messageDao.observeUnreadCounts("self").first().single().unread)
    }

    @Test
    fun `group delivery aggregate re-emits and stays scoped to outbound conversation messages`() = runBlocking {
        val db = openDatabase()
        db.conversationDao().upsert(
            com.transfer.flash.core.persistence.db.entity.ConversationEntity(
                id = "group-1",
                title = "Group",
                isGroup = true,
            ),
        )
        db.messageDao().insert(
            com.transfer.flash.core.persistence.db.entity.MessageEntity(
                localId = "outbound",
                conversationId = "group-1",
                senderId = "self",
                senderName = "Me",
                text = "hello",
                sentAt = 1L,
                status = "SENT",
            ),
        )
        val dao = db.groupDeliveryDao()
        dao.insertAll(
            listOf(
                GroupDeliveryEntity("outbound", "peer-a", nextAttemptAt = 0L),
                GroupDeliveryEntity("outbound", "peer-b", nextAttemptAt = 0L),
            ),
        )
        val initial = dao.observeDeliveryCounts("group-1", "self").first().single()
        assertEquals(0, initial.deliveredTo)
        assertEquals(2, initial.deliveredTotal)

        dao.markDelivered("outbound", "peer-a", deliveredAt = 2L)
        val updated = dao.observeDeliveryCounts("group-1", "self").first().single()
        assertEquals(1, updated.deliveredTo)
        assertEquals(2, updated.deliveredTotal)
        assertTrue(dao.observeDeliveryCounts("group-1", "someone-else").first().isEmpty())
    }

    @Test
    fun `flow re-emits after a write, proving InvalidationTracker runs on jvm`() = runBlocking {
        // Room's Flow queries are driven by InvalidationTracker, which on Android leans on the
        // Support stack. If it silently no-ops on jvm, every observe* DAO function returns a Flow
        // that emits once and then goes dead — the kind of failure that compiles, passes a
        // round-trip test, and breaks the desktop UI. So: subscribe FIRST, assert the initial
        // emission is empty (that is what proves the subscription predates the write), then write
        // and require a SECOND emission. Awaiting `isNotEmpty()` without the empty assertion first
        // would pass on the initial emission alone and prove nothing.
        val dao = openDatabase().trustedPeerDao()
        val emissions = Channel<List<TrustedPeerEntity>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            dao.observeAll().collect { emissions.send(it) }
        }
        try {
            assertTrue(
                withTimeout(AWAIT_MS) { emissions.receive() }.isEmpty(),
                "first emission should be the empty table, i.e. we subscribed before the insert",
            )
            dao.insert(peer(DEVICE_ID))
            val afterWrite = withTimeout(AWAIT_MS) { emissions.receive() }
            assertEquals(listOf(DEVICE_ID), afterWrite.map { it.deviceId })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `message pins are ordered newest first, scoped to one conversation and removable`() = runBlocking {
        val dao = openDatabase().messagePinDao()
        assertTrue(dao.observePinnedIds("c1").first().isEmpty())

        dao.upsert(MessagePinEntity("c1", "m1", pinnedAt = 10L))
        dao.upsert(MessagePinEntity("c1", "m2", pinnedAt = 20L))
        dao.upsert(MessagePinEntity("c2", "m9", pinnedAt = 30L))
        assertEquals(listOf("m2", "m1"), dao.observePinnedIds("c1").first())
        assertEquals(listOf("m9"), dao.observePinnedIds("c2").first())

        // Pinning again refreshes the row instead of duplicating it.
        dao.upsert(MessagePinEntity("c1", "m1", pinnedAt = 40L))
        assertEquals(listOf("m1", "m2"), dao.observePinnedIds("c1").first())

        dao.unpin("c1", "m1")
        assertEquals(listOf("m2"), dao.observePinnedIds("c1").first())

        dao.clearConversation("c1")
        assertTrue(dao.observePinnedIds("c1").first().isEmpty())
        assertEquals(listOf("m9"), dao.observePinnedIds("c2").first())
    }

    @Test
    fun `swarmDao content round trip bit updates and queries`() = runBlocking {
        val dao = openDatabase().swarmDao()
        val content = SwarmContentEntity(
            root = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            groupId = "g2-crew",
            messageId = "m-123",
            role = "ORIGIN",
            originId = "alice",
            originKey = "key-alice",
            fileName = "movie.mp4",
            mime = "video/mp4",
            totalSize = 1048576L,
            pieceSize = 65536,
            manifest = byteArrayOf(1, 2, 3),
            bits = byteArrayOf(0b00000000),
            bytesDone = 0L,
            state = "ACTIVE",
            waitReason = null,
            failReason = null,
            localTransferId = "tx-local-1",
            sourceUri = "content://media/1",
            sourcePersistent = true,
            partialKey = "partial-1",
            finalPath = null,
            identitySize = 1048576L,
            identityModifiedMs = 1700000000000L,
            deliveredTo = "bob,charlie",
            createdAtMs = 1700000000000L,
            lastProgressAtMs = 1700000000000L,
            expiresAtMs = 1700000000000L + 86400000L,
        )

        dao.upsertContent(content)

        val byKey = dao.getContent(content.root, content.groupId)
        assertEquals(content, byKey)

        val byTransferId = dao.getContentByTransferId("tx-local-1")
        assertEquals(content, byTransferId)

        val byMessageId = dao.getContentByMessageId("g2-crew", "m-123")
        assertEquals(content, byMessageId)

        val all = dao.loadAllContent()
        assertEquals(1, all.size)
        assertEquals(content, all.first())

        val groupContent = dao.loadContentForGroup("g2-crew")
        assertEquals(1, groupContent.size)

        // Update bits
        val newBits = byteArrayOf(0b11111111.toByte())
        dao.updateBits(content.root, content.groupId, newBits, 65536L, 1700000005000L)
        val afterBits = dao.getContent(content.root, content.groupId)!!
        assertTrue(newBits.contentEquals(afterBits.bits))
        assertEquals(65536L, afterBits.bytesDone)
        assertEquals(1700000005000L, afterBits.lastProgressAtMs)

        // Update state
        dao.updateState(content.root, content.groupId, "PAUSED_BY_USER", "WAITING_FOR_USER", null, 1700000010000L)
        val afterState = dao.getContent(content.root, content.groupId)!!
        assertEquals("PAUSED_BY_USER", afterState.state)
        assertEquals("WAITING_FOR_USER", afterState.waitReason)

        // Finalize content
        dao.finalizeContent(content.root, content.groupId, "/path/to/movie.mp4", 1048576L, 1700000020000L, "COMPLETE", 1700000020000L)
        val afterFinal = dao.getContent(content.root, content.groupId)!!
        assertEquals("COMPLETE", afterFinal.state)
        assertEquals("/path/to/movie.mp4", afterFinal.finalPath)
        assertNull(afterFinal.waitReason)

        // Delete content
        dao.deleteContent(content.root, content.groupId)
        assertNull(dao.getContent(content.root, content.groupId))
    }

    @Test
    fun `swarmDao tombstone round trip and purge expired`() = runBlocking {
        val dao = openDatabase().swarmDao()
        val tombstone = SwarmTombstoneEntity(
            groupId = "g2-crew",
            messageId = "m-123",
            root = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            originId = "alice",
            reason = "USER",
            cancelledAtMs = 1700000000000L,
            signature = byteArrayOf(9, 8, 7),
            receivedAtMs = 1700000001000L,
            expiresAtMs = 1700000050000L,
        )

        dao.upsertTombstone(tombstone)

        val fetched = dao.getTombstone("g2-crew", "m-123")
        assertEquals(tombstone, fetched)

        val byGroup = dao.getTombstonesForGroup("g2-crew")
        assertEquals(1, byGroup.size)

        val byContent = dao.getTombstonesForContent(tombstone.root, "g2-crew")
        assertEquals(1, byContent.size)

        val all = dao.loadAllTombstones()
        assertEquals(1, all.size)

        // Insert expired content
        val expiredContent = SwarmContentEntity(
            root = "root-expired",
            groupId = "g2-crew",
            messageId = "m-expired",
            role = "ORIGIN",
            originId = "alice",
            originKey = "key-alice",
            fileName = "old.bin",
            mime = "application/octet-stream",
            totalSize = 100L,
            pieceSize = 100,
            manifest = null,
            bits = byteArrayOf(0),
            bytesDone = 0L,
            state = "CANCELLED",
            waitReason = null,
            failReason = null,
            localTransferId = "tx-old",
            sourceUri = null,
            sourcePersistent = false,
            partialKey = "pk-old",
            finalPath = null,
            identitySize = 0L,
            identityModifiedMs = 0L,
            deliveredTo = "",
            createdAtMs = 1000L,
            lastProgressAtMs = 1000L,
            expiresAtMs = 1700000010000L,
        )
        dao.upsertContent(expiredContent)

        // Purge before expiry time: nothing purged
        assertEquals(0, dao.purgeExpiredContent(1700000005000L))
        assertEquals(0, dao.purgeExpiredTombstones(1700000005000L))

        // Purge after expiry time
        assertEquals(1, dao.purgeExpiredContent(1700000020000L))
        assertNull(dao.getContent("root-expired", "g2-crew"))

        assertEquals(1, dao.purgeExpiredTombstones(1700000060000L))
        assertNull(dao.getTombstone("g2-crew", "m-123"))
    }

    private fun peer(deviceId: String) = TrustedPeerEntity(
        deviceId = deviceId,
        name = "peer-$deviceId",
        fingerprintHex = FINGERPRINT,
        trustedAt = TRUSTED_AT,
    )

    private companion object {
        const val DEVICE_ID = "jvm-peer-1"
        const val FINGERPRINT = "0A1B2C3D4E5F"
        const val TRUSTED_AT = 1_700_000_000_000L

        /** Real milliseconds — see the class KDoc on why this suite uses `runBlocking`. */
        const val AWAIT_MS = 15_000L
    }
}
