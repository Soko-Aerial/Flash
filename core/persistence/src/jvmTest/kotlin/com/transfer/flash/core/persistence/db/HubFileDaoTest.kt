package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
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
}
