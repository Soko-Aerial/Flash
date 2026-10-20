package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The conditional updates of [com.transfer.flash.core.persistence.db.dao.HubFileDao] that settle races (ADR-216). */
class HubFileDaoTest {

    private lateinit var db: FlashDatabase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FlashDatabase>(factory = FlashDatabaseConstructor::initialize)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(
        transferId: String = "hub-o-1",
        direction: String = HubFileStates.OUT,
        fileId: String = "0123456789abcdef0123456789abcdef",
        state: String = HubFileStates.UPLOADING,
        createdAtMs: Long = 10L,
    ) = HubFileEntity(
        transferId = transferId,
        direction = direction,
        fileId = fileId,
        peerId = "peer-1",
        hubUrl = "https://hub.example",
        fileName = "report.pdf",
        mime = "application/pdf",
        plainBytes = 1234L,
        sourceUri = if (direction == HubFileStates.OUT) "content://doc/1" else null,
        fileKey = null,
        cipherSha256 = null,
        cipherChunkBytes = 0,
        chunkCount = 0,
        expiresAtMs = 0L,
        messageId = null,
        state = state,
        paused = false,
        errorText = null,
        bytesDone = 0L,
        localPath = null,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )

    @Test
    fun `an upload that finishes stores its ticket once and a cancel that won is not undone`() = runBlocking {
        val dao = db.hubFileDao()
        dao.insert(row())
        assertEquals(1, dao.markUploaded("hub-o-1", "hfk1.k", "a".repeat(64), 1_048_576, 1, 99L, 20L))
        val ready = dao.get("hub-o-1")!!
        assertEquals(HubFileStates.READY, ready.state)
        assertEquals(1234L, ready.bytesDone)
        assertEquals("hfk1.k", ready.fileKey)
        // A second completion (a run finishing twice) changes nothing.
        assertEquals(0, dao.markUploaded("hub-o-1", "hfk1.other", "b".repeat(64), 1_048_576, 1, 99L, 30L))
        assertEquals("hfk1.k", dao.get("hub-o-1")!!.fileKey)

        dao.insert(row(transferId = "hub-o-2", fileId = "f".repeat(32)))
        assertEquals(1, dao.moveState("hub-o-2", HubFileStates.UPLOADING, HubFileStates.CANCELLED, null, 40L))
        assertEquals(0, dao.markUploaded("hub-o-2", "hfk1.k", "a".repeat(64), 1_048_576, 1, 99L, 50L))
        assertEquals(HubFileStates.CANCELLED, dao.get("hub-o-2")!!.state)
        assertNull(dao.get("hub-o-2")!!.fileKey, "a cancelled upload must not gain a key")
    }

    @Test
    fun `the ticket message is queued exactly once`() = runBlocking {
        val dao = db.hubFileDao()
        dao.insert(row())
        assertEquals(0, dao.markQueued("hub-o-1", "m-1", 20L), "an upload still running cannot be queued")
        dao.markUploaded("hub-o-1", "hfk1.k", "a".repeat(64), 1_048_576, 1, 99L, 20L)
        assertEquals(1, dao.markQueued("hub-o-1", "m-1", 30L))
        assertEquals(0, dao.markQueued("hub-o-1", "m-2", 40L))
        assertEquals("m-1", dao.get("hub-o-1")!!.messageId)
    }

    @Test
    fun `a replayed ticket for the same received file id is ignored`() = runBlocking {
        val dao = db.hubFileDao()
        assertTrue(dao.insert(row(transferId = "hub-i-a", direction = HubFileStates.IN, state = HubFileStates.OFFERED)) > 0)
        assertEquals(-1L, dao.insert(row(transferId = "hub-i-b", direction = HubFileStates.IN, state = HubFileStates.OFFERED)))
        assertEquals(-1L, dao.insert(row(transferId = "hub-i-a", direction = HubFileStates.IN, fileId = "e".repeat(32))))
        // The same file id may exist once per direction (a file sent to oneself through the hub).
        assertTrue(dao.insert(row(transferId = "hub-o-a", direction = HubFileStates.OUT)) > 0)
        assertEquals("hub-i-a", dao.byFileId(HubFileStates.IN, "0123456789abcdef0123456789abcdef")!!.transferId)
    }

    @Test
    fun `only a running download can be marked done and runnable skips paused rows`() = runBlocking {
        val dao = db.hubFileDao()
        dao.insert(row(transferId = "hub-i-1", direction = HubFileStates.IN, state = HubFileStates.OFFERED))
        assertEquals(0, dao.markDownloaded("hub-i-1", "/x/report.pdf", 20L))
        dao.moveState("hub-i-1", HubFileStates.OFFERED, HubFileStates.DOWNLOADING, null, 20L)
        dao.insert(row(transferId = "hub-i-2", direction = HubFileStates.IN, fileId = "d".repeat(32), state = HubFileStates.DOWNLOADING))
        dao.setPaused("hub-i-2", true, 25L)
        assertEquals(listOf("hub-i-1"), dao.runnable(HubFileStates.RUNNING).map { it.transferId })
        assertEquals(1, dao.markDownloaded("hub-i-1", "/x/report.pdf", 30L))
        val done = dao.get("hub-i-1")!!
        assertEquals(HubFileStates.DONE, done.state)
        assertEquals("/x/report.pdf", done.localPath)
        assertEquals(1234L, done.bytesDone)
    }

    @Test
    fun `recent rows come newest first and are bounded`() = runBlocking {
        val dao = db.hubFileDao()
        dao.insert(row(transferId = "old", fileId = "1".repeat(32), createdAtMs = 1L))
        dao.insert(row(transferId = "new", fileId = "2".repeat(32), createdAtMs = 2L))
        dao.insert(row(transferId = "mid", fileId = "3".repeat(32), createdAtMs = 3L))
        assertEquals(listOf("mid", "new"), dao.observeRecent(2).first().map { it.transferId })
    }

    @Test
    fun `HUBFILE-P4-U01 the group of a file survives a round trip and one message finds its row`() = runBlocking {
        val dao = db.hubFileDao()
        dao.insert(row(transferId = "hub-o-g", fileId = "a".repeat(32)).copy(groupId = "g2-team", recipients = "m1,m2", messageId = "m-g"))
        dao.insert(row(transferId = "hub-o-p", fileId = "b".repeat(32)).copy(messageId = "m-p"))
        assertEquals("g2-team", dao.get("hub-o-g")!!.groupId)
        assertEquals("m1,m2", dao.get("hub-o-g")!!.recipients)
        assertNull(dao.get("hub-o-p")!!.recipients)
        assertNull(dao.get("hub-o-p")!!.groupId, "a one-to-one file has no group")
        assertEquals("hub-o-g", dao.byMessageId("m-g")!!.transferId)
        assertNull(dao.byMessageId("nobody"))
    }

    @Test
    fun `HUBFILE-P4-U02 prune removes only finished rows past the age and files that left the hub`() = runBlocking {
        val dao = db.hubFileDao()
        fun r(id: String, state: String, updated: Long, expires: Long, dir: String = HubFileStates.IN) =
            row(transferId = "t-$id", direction = dir, fileId = id.padEnd(32, '0'), state = state)
                .copy(updatedAtMs = updated, expiresAtMs = expires)
        dao.insert(r("a1", HubFileStates.DONE, 100L, 0L)) // finished and old: goes
        dao.insert(r("a2", HubFileStates.DONE, 900L, 0L)) // finished and recent: stays
        dao.insert(r("a3", HubFileStates.FAILED, 100L, 0L)) // goes
        dao.insert(r("a4", HubFileStates.CANCELLED, 100L, 0L)) // goes
        dao.insert(r("a5", HubFileStates.DECLINED, 100L, 0L)) // goes
        dao.insert(r("b1", HubFileStates.OFFERED, 100L, 500L)) // the file left the hub: goes
        dao.insert(r("b2", HubFileStates.OFFERED, 100L, 5_000L)) // still downloadable: stays
        dao.insert(r("b3", HubFileStates.OFFERED, 100L, 0L)) // no expiry known: stays
        dao.insert(r("c1", HubFileStates.QUEUED, 100L, 500L, HubFileStates.OUT)) // the ticket can never work: goes
        dao.insert(r("c2", HubFileStates.QUEUED, 100L, 5_000L, HubFileStates.OUT)) // the ticket is still useful: stays
        dao.insert(r("d1", HubFileStates.DOWNLOADING, 100L, 500L)) // running: never pruned
        dao.insert(r("d2", HubFileStates.UPLOADING, 100L, 500L, HubFileStates.OUT)) // running: never pruned
        assertEquals(6, dao.prune(terminalBeforeMs = 500L, expiredBeforeMs = 1_000L))
        assertEquals(
            setOf("t-a2", "t-b2", "t-b3", "t-c2", "t-d1", "t-d2"),
            dao.observeRecent(100).first().map { it.transferId }.toSet(),
        )
        assertEquals(0, dao.prune(terminalBeforeMs = 500L, expiredBeforeMs = 1_000L), "pruning twice removes nothing more")
    }

    @Test
    fun `HUBFILE-P4-U05 a finished row that a visible message still shows is kept until the message is gone`() = runBlocking {
        val dao = db.hubFileDao()
        val messages = db.messageDao()
        fun m(id: String, deleted: Long? = null) = MessageEntity(
            localId = id, conversationId = "peer-1", senderId = "peer-1", senderName = null, text = "", sentAt = 1L,
            status = "DELIVERED", deletedAt = deleted,
        )
        fun r(id: String) = row(transferId = "t-$id", direction = HubFileStates.IN, fileId = id.padEnd(32, '0'), state = HubFileStates.DONE)
            .copy(updatedAtMs = 100L, messageId = "m-$id")
        messages.insert(m("m-shown"))
        messages.insert(m("m-deleted", deleted = 5L))
        dao.insert(r("shown"))
        dao.insert(r("deleted"))
        dao.insert(r("nomessage"))
        dao.insert(row(transferId = "t-unlinked", direction = HubFileStates.OUT, fileId = "u".padEnd(32, '0'), state = HubFileStates.FAILED).copy(updatedAtMs = 100L))
        assertEquals(3, dao.prune(terminalBeforeMs = 500L, expiredBeforeMs = 1_000L))
        assertEquals(listOf("t-shown"), dao.observeRecent(100).first().map { it.transferId })
    }

    @Test
    fun `HUBFILE-P4-U03 a ticket fills in only the bare row of the same author in the same group`() = runBlocking {
        val messages = db.messageDao()
        fun m(id: String, conv: String = "g2-team", sender: String = "alice", transfer: String? = null, deleted: Long? = null) =
            MessageEntity(
                localId = id, conversationId = conv, senderId = sender, senderName = null, text = "", sentAt = 1L,
                status = "DELIVERED", deletedAt = deleted, attachmentTransferId = transfer,
            )
        messages.insert(m("bare"))
        messages.insert(m("other-author", sender = "mallory"))
        messages.insert(m("other-group", conv = "g2-else"))
        messages.insert(m("has-file", transfer = "swarm-1"))
        messages.insert(m("deleted", deleted = 5L))
        suspend fun up(id: String) = messages.attachHubFileToBare(id, "alice", "g2-team", "hub-i-x", "a.pdf", "application/pdf", 9L)
        assertEquals(1, up("bare"))
        val row = messages.getByLocalId("bare")!!
        assertEquals("hub-i-x", row.attachmentTransferId)
        assertEquals("a.pdf", row.attachmentName)
        assertEquals(9L, row.attachmentSize)
        assertEquals(0, up("bare"), "a second ticket finds a row that already has its file")
        assertEquals(0, up("other-author"), "another author's row is never rewritten")
        assertEquals(0, up("other-group"), "another group's row is never rewritten")
        assertEquals(0, up("has-file"), "a row that already carries a file is not swapped")
        assertEquals(0, up("deleted"), "a deleted message is not revived")
        assertEquals(0, up("missing"))
        assertEquals("swarm-1", messages.getByLocalId("has-file")!!.attachmentTransferId)
    }
}
