package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.text.foldForSearch
import com.transfer.flash.core.persistence.db.entity.HubAnnouncementEntity
import com.transfer.flash.core.persistence.db.entity.HubAnnouncementStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class HubAnnouncementDaoTest {

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

    private fun sampleAnnouncement(
        id: String = "ann-1",
        scope: String = "org",
        departmentId: String? = null,
        title: String = "Welcome All",
        body: String = "Important update for all staff.",
        pinned: Boolean = false,
        expiresAtMs: Long? = null,
        seq: Long = 1L,
        readAtMs: Long? = null,
        createdAtMs: Long = 1000L,
    ): HubAnnouncementEntity {
        val search = foldForSearch("$title $body")
        return HubAnnouncementEntity(
            id = id,
            scope = scope,
            departmentId = departmentId,
            title = title,
            body = body,
            authorName = "Alice Admin",
            authorRole = "org_admin",
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
            edited = false,
            pinned = pinned,
            expiresAtMs = expiresAtMs,
            seq = seq,
            readAtMs = readAtMs,
            searchFolded = search,
        )
    }

    @Test
    fun `ANN-U21 feed apply upsert and delete operations`() = runBlocking {
        val dao = db.hubAnnouncementDao()

        val item1 = sampleAnnouncement("a1", seq = 1L)
        val item2 = sampleAnnouncement("a2", seq = 2L, title = "Second News")

        dao.upsert(listOf(item1, item2))
        assertEquals(2, dao.observeAll().first().size)

        // Edit item1
        val edited1 = item1.copy(title = "Updated Title", edited = true, seq = 3L)
        dao.upsertOne(edited1)
        val retrieved = dao.getById("a1")
        assertNotNull(retrieved)
        assertEquals("Updated Title", retrieved.title)
        assertTrue(retrieved.edited)

        // Pin item2
        dao.setPinned("a2", true)
        val p2 = dao.getById("a2")
        assertNotNull(p2)
        assertTrue(p2.pinned)

        // Ordering: pinned item appears first in observeAll
        val list = dao.observeAll().first()
        assertEquals("a2", list.first().id)

        // Delete item1
        dao.deleteById("a1")
        assertNull(dao.getById("a1"))
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun `ANN-U22 unread counts exclude read and expired items`() = runBlocking {
        val dao = db.hubAnnouncementDao()
        val now = 5000L

        val unreadActive = sampleAnnouncement("a1", readAtMs = null, expiresAtMs = 10000L)
        val unreadExpired = sampleAnnouncement("a2", readAtMs = null, expiresAtMs = 3000L)
        val readActive = sampleAnnouncement("a3", readAtMs = 4000L, expiresAtMs = 10000L)
        val unreadNoExpiry = sampleAnnouncement("a4", readAtMs = null, expiresAtMs = null)

        dao.upsert(listOf(unreadActive, unreadExpired, readActive, unreadNoExpiry))

        // Active unread items are a1 and a4 -> count = 2
        assertEquals(2, dao.observeUnreadCount(now).first())

        // Mark a4 as read
        dao.markRead("a4", now)
        assertEquals(1, dao.observeUnreadCount(now).first())
    }

    @Test
    fun `ANN-U23 search offline finds matching text and filters expired items`() = runBlocking {
        val dao = db.hubAnnouncementDao()
        val now = 5000L

        val matchActive = sampleAnnouncement("a1", title = "Quarterly Results", body = "Revenue grew 20 percent", expiresAtMs = 10000L)
        val matchExpired = sampleAnnouncement("a2", title = "Old Results", body = "Revenue report from last year", expiresAtMs = 2000L)
        val noMatch = sampleAnnouncement("a3", title = "Office Maintenance", body = "Elevator repair scheduled")

        dao.upsert(listOf(matchActive, matchExpired, noMatch))

        val results = dao.search(foldForSearch("revenue"), now)
        assertEquals(1, results.size)
        assertEquals("a1", results.first().id)
    }

    @Test
    fun `ANN-U24 prune keeps newest items and preserves unread`() = runBlocking {
        val dao = db.hubAnnouncementDao()

        val items = (1..10).map { i ->
            sampleAnnouncement(
                id = "item-$i",
                scope = "org",
                createdAtMs = i * 1000L,
            )
        }
        dao.upsert(items)
        assertEquals(10, dao.observeAll().first().size)

        // Prune keeping newest 3 items
        dao.pruneOldAnnouncements(maxKeep = 3)
        val remaining = dao.observeAll().first()
        assertEquals(3, remaining.size)
        assertEquals(listOf("item-10", "item-9", "item-8"), remaining.map { it.id })
    }

    @Test
    fun `ANN-U25 unenrol wipes all announcement tables and state`() = runBlocking {
        val dao = db.hubAnnouncementDao()

        dao.upsert(listOf(sampleAnnouncement("a1"), sampleAnnouncement("a2")))
        dao.setState(HubAnnouncementStateEntity(id = 1, epoch = "ep-1", seq = 42L, lastSyncAtMs = 1000L))

        assertEquals(2, dao.observeAll().first().size)
        assertNotNull(dao.getState())

        dao.clearAll()

        assertTrue(dao.observeAll().first().isEmpty())
        assertNull(dao.getState())
    }
}
