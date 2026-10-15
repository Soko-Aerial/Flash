package com.transfer.flash.server.announcements

import com.transfer.flash.server.storage.ServerDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementStoreTest {

    private lateinit var db: ServerDatabase
    private var testClockMs: Long = 1_000_000L
    private lateinit var store: AnnouncementStore

    @BeforeTest
    fun setUp() {
        db = ServerDatabase.inMemory()
        store = AnnouncementStore.open(db) { testClockMs }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun migrationAppliesAndInitializesEpochAndSeq() {
        val epoch = store.currentEpoch()
        assertTrue(epoch.isNotBlank())
        assertEquals(0L, store.currentSeq())
    }

    @Test
    fun createAnnouncementAndIdempotency() {
        val item1 = store.createAnnouncement(
            clientRequestId = "req-1",
            scope = "org",
            departmentId = null,
            title = "First All Hands",
            body = "Welcome everyone to our new announcements board.",
            authorKind = "device",
            authorRef = "dev-admin",
            authorName = "Alice Admin",
            authorRole = "admin",
            pinned = false,
        )

        assertEquals("First All Hands", item1.title)
        assertEquals(1L, item1.seq)
        assertEquals(1L, store.currentSeq())

        // Replay same clientRequestId within 10 minutes -> returns same item with same ID and seq
        val item2 = store.createAnnouncement(
            clientRequestId = "req-1",
            scope = "org",
            departmentId = null,
            title = "Different Title Should Be Ignored",
            body = "Different Body",
            authorKind = "device",
            authorRef = "dev-admin",
            authorName = "Alice Admin",
            authorRole = "admin",
        )

        assertEquals(item1.id, item2.id)
        assertEquals(item1.title, item2.title)
        assertEquals(item1.seq, item2.seq)
        assertEquals(1L, store.currentSeq())
    }

    @Test
    fun optimisticConcurrencyOnUpdate() {
        val created = store.createAnnouncement(
            clientRequestId = "req-c",
            scope = "org",
            departmentId = null,
            title = "Original Title",
            body = "Original Body",
            authorKind = "console",
            authorRef = "admin1",
            authorName = "Admin One",
            authorRole = "admin",
        )
        val initialSeq = created.seq

        // Concurrent update with wrong expectedSeq fails
        val ex = assertFailsWith<AnnouncementConflictException> {
            store.updateAnnouncement(
                id = created.id,
                expectedSeq = initialSeq + 999L,
                title = "Conflicting Update",
                body = "Should fail",
                actorRole = "admin",
            )
        }
        assertEquals(created.id, ex.currentItem.id)

        // Matching expectedSeq succeeds and bumps seq
        val updated = store.updateAnnouncement(
            id = created.id,
            expectedSeq = initialSeq,
            title = "Updated Title",
            body = "Updated Body",
            actorRole = "admin",
        )

        assertEquals("Updated Title", updated.title)
        assertTrue(updated.edited)
        assertTrue(updated.seq > initialSeq)
    }

    @Test
    fun pinLimitsEnforcedPerScope() {
        // Pin 3 company-wide announcements
        val p1 = store.createAnnouncement("r1", "org", null, "Pin 1", "Body 1", "device", "a", "A", "admin", pinned = true)
        val p2 = store.createAnnouncement("r2", "org", null, "Pin 2", "Body 2", "device", "a", "A", "admin", pinned = true)
        val p3 = store.createAnnouncement("r3", "org", null, "Pin 3", "Body 3", "device", "a", "A", "admin", pinned = true)

        // 4th pinned company announcement must throw AnnouncementPinLimitException
        assertFailsWith<AnnouncementPinLimitException> {
            store.createAnnouncement("r4", "org", null, "Pin 4", "Body 4", "device", "a", "A", "admin", pinned = true)
        }

        // But department scope can still have its own pins (independent scope)
        val deptPin1 = store.createAnnouncement("r-d1", "department", "dept-eng", "Dept Pin 1", "Body", "device", "a", "A", "admin", pinned = true)
        assertTrue(deptPin1.pinned)

        // Unpin p1 allows pinning another
        store.setPinned(p1.id, pinned = false, actorRole = "admin")
        val p4 = store.createAnnouncement("r4-retry", "org", null, "Pin 4", "Body 4", "device", "a", "A", "admin", pinned = true)
        assertTrue(p4.pinned)
    }

    @Test
    fun visibilityFilterInSearchAndFeed() {
        // Org announcement
        val orgAnn = store.createAnnouncement("o1", "org", null, "Company Picnic", "Details about picnic", "device", "a", "A", "admin")
        // Dept announcement in engineering
        val engAnn = store.createAnnouncement("e1", "department", "dept-eng", "Secret Architecture Project", "Quantum compiler plans", "device", "h", "Head", "dept_admin")

        // 1. Single item get: outsider from dept-marketing cannot read eng announcement
        val outsiderView = store.getAnnouncement(engAnn.id, callerDeptId = "dept-marketing", isOrgAdmin = false)
        assertNull(outsiderView)

        // Colleague in dept-eng can read it
        val engMemberView = store.getAnnouncement(engAnn.id, callerDeptId = "dept-eng", isOrgAdmin = false)
        assertNotNull(engMemberView)
        assertEquals(engAnn.id, engMemberView.id)

        // Org admin can read it
        val adminView = store.getAnnouncement(engAnn.id, callerDeptId = null, isOrgAdmin = true)
        assertNotNull(adminView)

        // 2. Initial changes feed (since=0): outsider feed only contains org announcement
        val outsiderFeed = store.getChangesFeed(sinceSeq = 0L, limit = 50, callerDeptId = "dept-marketing", isOrgAdmin = false)
        assertEquals(1, outsiderFeed.items.size)
        assertEquals(orgAnn.id, outsiderFeed.items[0].id)

        // Eng member feed contains both
        val engFeed = store.getChangesFeed(sinceSeq = 0L, limit = 50, callerDeptId = "dept-eng", isOrgAdmin = false)
        assertEquals(2, engFeed.items.size)

        // 3. Search: outsider searching for "Quantum" gets 0 rows
        val outsiderSearch = store.searchAnnouncements(query = "Quantum", scope = null, departmentId = null, before = null, callerDeptId = "dept-marketing", isOrgAdmin = false)
        assertEquals(0, outsiderSearch.items.size)

        // Eng member searching for "Quantum" finds it
        val engSearch = store.searchAnnouncements(query = "Quantum", scope = null, departmentId = null, before = null, callerDeptId = "dept-eng", isOrgAdmin = false)
        assertEquals(1, engSearch.items.size)
        assertEquals(engAnn.id, engSearch.items[0].id)
    }

    @Test
    fun ftsSearchOperatorsDoNotThrowAndMatchWords() {
        store.createAnnouncement("s1", "org", null, "Alpha Beta", "Testing search functionality with keywords", "device", "a", "A", "admin")

        // Malformed or hostile query operators: quotes, asterisks, NEAR, etc.
        val search1 = store.searchAnnouncements(query = "\"Alpha* NEAR Beta-", scope = null, departmentId = null, before = null, callerDeptId = null, isOrgAdmin = true)
        // Must not crash with SQLite syntax error, and should match
        assertTrue(search1.items.isNotEmpty())

        val search2 = store.searchAnnouncements(query = "keywords", scope = null, departmentId = null, before = null, callerDeptId = null, isOrgAdmin = true)
        assertEquals(1, search2.items.size)
    }

    @Test
    fun softDeleteLeavesTombstoneInDeltaFeed() {
        val created = store.createAnnouncement("d1", "org", null, "To Be Deleted", "Will be deleted", "device", "a", "A", "admin")
        val createSeq = created.seq

        val deleted = store.deleteAnnouncement(created.id, actorRole = "admin")
        assertTrue(deleted.deleted)
        assertEquals("", deleted.title)
        assertEquals("", deleted.body)

        // getAnnouncement returns null
        assertNull(store.getAnnouncement(created.id, callerDeptId = null, isOrgAdmin = true))

        // Initial sync feed (since=0) ignores tombstones
        val initialFeed = store.getChangesFeed(sinceSeq = 0L, limit = 10, callerDeptId = null, isOrgAdmin = true)
        assertEquals(0, initialFeed.items.size)

        // Delta sync feed (since=createSeq) includes the tombstone
        val deltaFeed = store.getChangesFeed(sinceSeq = createSeq, limit = 10, callerDeptId = null, isOrgAdmin = true)
        assertEquals(1, deltaFeed.items.size)
        assertTrue(deltaFeed.items[0].deleted)
        assertEquals(created.id, deltaFeed.items[0].id)
    }

    @Test
    fun expiryFiltersOutExpiredAnnouncements() {
        val expiresAt = testClockMs + 5000L
        val ann = store.createAnnouncement("exp1", "org", null, "Expiring Soon", "Body", "device", "a", "A", "admin", expiresAtMs = expiresAt)

        // Before expiry: visible
        assertNotNull(store.getAnnouncement(ann.id, callerDeptId = null, isOrgAdmin = false))

        // Advance clock past expiry
        testClockMs += 10000L

        // After expiry: regular member sees null
        assertNull(store.getAnnouncement(ann.id, callerDeptId = null, isOrgAdmin = false))
        val feed = store.getChangesFeed(sinceSeq = 0L, limit = 10, callerDeptId = null, isOrgAdmin = false)
        assertEquals(0, feed.items.size)
    }

    @Test
    fun authorPermissionsMatrix() {
        val headAuthored = store.createAnnouncement(
            "h1", "department", "dept-eng", "Head Post", "Body",
            "device", "head-dev", "Bob Head", "dept_admin"
        )
        val adminAuthored = store.createAnnouncement(
            "a1", "department", "dept-eng", "Admin Post in Eng", "Body",
            "device", "admin-dev", "Alice Admin", "admin"
        )

        // Another head in dept-eng CAN edit headAuthored
        val updatedByColleagueHead = store.updateAnnouncement(
            id = headAuthored.id,
            expectedSeq = headAuthored.seq,
            title = "Edited By Colleague Head",
            body = "Updated body",
            actorRole = "dept_admin",
            actorDeptId = "dept-eng",
            actorRef = "head-2",
        )
        assertEquals("Edited By Colleague Head", updatedByColleagueHead.title)

        // Head CANNOT edit adminAuthored in their department
        assertFailsWith<AnnouncementForbiddenException> {
            store.updateAnnouncement(
                id = adminAuthored.id,
                expectedSeq = adminAuthored.seq,
                title = "Head Trying to Edit Admin Post",
                body = "Should fail",
                actorRole = "dept_admin",
                actorDeptId = "dept-eng",
                actorRef = "head-2",
            )
        }

        // Head of marketing CANNOT edit eng head post
        assertFailsWith<AnnouncementForbiddenException> {
            store.updateAnnouncement(
                id = headAuthored.id,
                expectedSeq = updatedByColleagueHead.seq,
                title = "Cross Dept Edit Attempt",
                body = "Should fail",
                actorRole = "dept_admin",
                actorDeptId = "dept-marketing",
                actorRef = "head-mkt",
            )
        }
    }

    @Test
    fun previewComputesAccurateCountsAndHonestyNotice() {
        val preview = store.previewAnnouncement(
            scope = "department",
            departmentId = "dept-eng",
            title = "New SDK Released",
            body = "Check https://flash.org/sdk for downloads.",
            departmentName = "Engineering",
            estimatedRecipients = 42,
        )

        assertEquals("New SDK Released", preview.title)
        assertEquals(listOf("https://flash.org/sdk"), preview.links)
        assertEquals("Department: Engineering", preview.audienceScope)
        assertEquals(42, preview.estimatedRecipients)
        assertTrue(preview.honestyNotice.contains("plain text"))
    }
}
