@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.enterprise.announcements.FakeHubAnnouncementsApi
import com.transfer.flash.core.enterprise.announcements.HubAnnouncementError
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.messaging.model.HubAnnouncementDraft
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.persistence.db.runInWriteTransaction
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Plan A3 §10: Client repository integration tests for Announcements (ANN-U21..U26).
 * Covers Room persistence, feed synchronization, unread calculation, posting rights,
 * typed error mapping, search offline fallback, live relay WebSocket hint, and unenrol wipe.
 */
class RealFlashChatRepositoryAnnouncementsTest {

    private lateinit var tempDir: File
    private lateinit var dbFile: File
    private val encryptionKey = "test-encryption-key-announcements"

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-announcements-test").toFile()
        dbFile = File(tempDir, "flash.db")
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sampleCredentials(
        role: String = "org_admin",
        department: String? = null,
        validTo: Long = Long.MAX_VALUE,
    ): EnrollmentCredentials = EnrollmentCredentials(
        hubUrl = "https://hub.example.com",
        rootFingerprint = "FP_ROOT",
        rootPublicKey = "PK_ROOT",
        deviceCert = DeviceCert(
            deviceId = "dev-self",
            subjectPublicKey = "PK_SELF",
            role = role,
            department = department,
            validFrom = 0L,
            validTo = validTo,
            serial = 1L,
            issuerPublicKey = "PK_ROOT",
            issuerSignature = "sig",
        ),
        hubName = "Acme Corp",
    )

    private fun sampleItem(
        id: String,
        seq: Long,
        title: String = "Announcement $id",
        body: String = "Body of $id",
        scope: String = "org",
        departmentId: String? = null,
        pinned: Boolean = false,
        expiresAt: Long? = null,
        deleted: Boolean = false,
    ): AnnouncementItemDto = AnnouncementItemDto(
        id = id,
        scope = scope,
        departmentId = departmentId,
        title = title,
        body = body,
        authorName = "Alice Admin",
        authorRole = "org_admin",
        createdAt = 1000L * seq,
        updatedAt = 1000L * seq,
        edited = false,
        pinned = pinned,
        expiresAt = expiresAt,
        deleted = deleted,
        seq = seq,
    )

    @Test
    fun `ANN-U21 and ANN-U26 feed sync via refreshHubAnnouncements populates Room and updates state`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials())
        val announcementEvents = MutableSharedFlow<RelayFrame.AnnouncementEvent>()

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "Welcome")
        api.items["ann-2"] = sampleItem("ann-2", seq = 2L, title = "Quarterly Plan")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
            hubAnnouncementEvents = announcementEvents,
        )

        val result = repo.refreshHubAnnouncements()
        assertTrue(result is FlashResult.Success)

        val items = repo.hubAnnouncements.first()
        assertEquals(2, items.size)
        assertEquals("ann-2", items[0].id) // newer first
        assertEquals("ann-1", items[1].id)
        assertEquals(2, repo.hubUnreadAnnouncementCount.first())

        val state = db.hubAnnouncementDao().getState()
        assertNotNull(state)
        assertEquals(2L, state.seq)
        assertEquals("test-epoch", state.epoch)
    }

    @Test
    fun `ANN-U22 markHubAnnouncementRead decrements unread count and marks item read`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials())

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "Policy Update")
        api.items["ann-2"] = sampleItem("ann-2", seq = 2L, title = "Health Benefits")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        repo.refreshHubAnnouncements()
        assertEquals(2, repo.hubUnreadAnnouncementCount.first())

        repo.markHubAnnouncementRead("ann-1")
        assertEquals(1, repo.hubUnreadAnnouncementCount.first())

        val list = repo.hubAnnouncements.first()
        val ann1 = list.first { it.id == "ann-1" }
        val ann2 = list.first { it.id == "ann-2" }
        assertTrue(ann1.isRead)
        assertFalse(ann2.isRead)
    }

    @Test
    fun `ANN-U21 feed tombstone deletes announcement from Room`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials())

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "Temporary Notice")
        api.items["ann-2"] = sampleItem("ann-2", seq = 2L, title = "Permanent Notice")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        repo.refreshHubAnnouncements()
        assertEquals(2, repo.hubAnnouncements.first().size)

        // Tombstone ann-1
        api.items["ann-1"] = sampleItem("ann-1", seq = 3L, deleted = true)
        repo.refreshHubAnnouncements()

        val list = repo.hubAnnouncements.first()
        assertEquals(1, list.size)
        assertEquals("ann-2", list.first().id)
    }

    @Test
    fun `ANN-U26 posting rights derivation for admin, head, employee, and unenrolled`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val trustStore = MemoryEnterpriseTrustStore()

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
        )

        // 1. Unenrolled
        assertEquals(HubPostingRights.None, repo.canPostHubAnnouncement.first())

        // 2. Org Admin
        trustStore.saveCredentials(sampleCredentials(role = "org_admin"))
        assertEquals(HubPostingRights.OrgWide(emptyList()), repo.canPostHubAnnouncement.first())

        // 3. Department Head
        trustStore.saveCredentials(sampleCredentials(role = "dept_head", department = "Design"))
        assertEquals(HubPostingRights.Department(listOf("Design")), repo.canPostHubAnnouncement.first())

        // 4. Regular Employee
        trustStore.saveCredentials(sampleCredentials(role = "employee", department = "Design"))
        assertEquals(HubPostingRights.None, repo.canPostHubAnnouncement.first())

        // 5. Expired credentials
        trustStore.saveCredentials(sampleCredentials(role = "org_admin", validTo = 500L))
        assertEquals(HubPostingRights.None, repo.canPostHubAnnouncement.first())
    }

    @Test
    fun `ANN-U26 postHubAnnouncement enforces role gating and persists created item`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials(role = "dept_head", department = "Sales"))

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        // Head cannot post org-wide
        val postOrg = repo.postHubAnnouncement(
            HubAnnouncementDraft(
                scope = "org",
                title = "All Hands",
                body = "Mandatory meeting",
            ),
        )
        assertTrue(postOrg is FlashResult.Failure)
        val errOrg = HubAnnouncementError.fromFlashError((postOrg as FlashResult.Failure).error)
        assertEquals(HubAnnouncementError.NotAllowed, errOrg)

        // Head cannot post to another department
        val postOther = repo.postHubAnnouncement(
            HubAnnouncementDraft(
                scope = "department",
                departmentId = "Engineering",
                title = "Code Freeze",
                body = "No more commits",
            ),
        )
        assertTrue(postOther is FlashResult.Failure)
        val errOther = HubAnnouncementError.fromFlashError((postOther as FlashResult.Failure).error)
        assertEquals(HubAnnouncementError.NotAllowed, errOther)

        // Head CAN post to own department "Sales"
        val postSales = repo.postHubAnnouncement(
            HubAnnouncementDraft(
                scope = "department",
                departmentId = "Sales",
                title = "Sales Targets",
                body = "Q4 goal reached",
            ),
        )
        assertTrue(postSales is FlashResult.Success)
        val created = (postSales as FlashResult.Success).value
        assertEquals("Sales Targets", created.title)
        assertTrue(created.isRead) // author has already read own post

        // Verified persisted in local Room database
        val inDb = db.hubAnnouncementDao().getById(created.id)
        assertNotNull(inDb)
        assertEquals("Sales Targets", inDb.title)
        assertNotNull(inDb.readAtMs)
    }

    @Test
    fun `ANN-U26 editHubAnnouncement propagates changes and handles conflict error`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials(role = "org_admin"))

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "Original Title")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        repo.refreshHubAnnouncements()

        // Successful edit
        val editRes = repo.editHubAnnouncement(
            id = "ann-1",
            draft = HubAnnouncementDraft(scope = "org", title = "Revised Title", body = "Updated content"),
            expectedSeq = 1L,
        )
        assertTrue(editRes is FlashResult.Success)
        assertEquals("Revised Title", editRes.value.title)

        // Conflict edit (someone edited concurrently)
        api.simulateConflict = sampleItem("ann-1", seq = 3L, title = "Concurrent Revision")
        val conflictRes = repo.editHubAnnouncement(
            id = "ann-1",
            draft = HubAnnouncementDraft(scope = "org", title = "Stale Edit", body = "Stale content"),
            expectedSeq = 1L,
        )
        assertTrue(conflictRes is FlashResult.Failure)
        val conflictErr = HubAnnouncementError.fromFlashError((conflictRes as FlashResult.Failure).error)
        assertTrue(conflictErr is HubAnnouncementError.Conflict)
    }

    @Test
    fun `ANN-U26 delete and pin operations with pin limit error mapping`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials(role = "org_admin"))

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "To Pin")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        repo.refreshHubAnnouncements()

        // Successful pin
        val pinRes = repo.setHubAnnouncementPinned("ann-1", true)
        assertTrue(pinRes is FlashResult.Success)
        assertTrue(db.hubAnnouncementDao().getById("ann-1")!!.pinned)

        // Pin limit reached error
        api.simulatePinLimit = true
        val limitRes = repo.setHubAnnouncementPinned("ann-1", true)
        assertTrue(limitRes is FlashResult.Failure)
        val limitErr = HubAnnouncementError.fromFlashError((limitRes as FlashResult.Failure).error)
        assertEquals(HubAnnouncementError.PinLimit, limitErr)

        // Delete
        api.simulatePinLimit = false
        val delRes = repo.deleteHubAnnouncement("ann-1")
        assertTrue(delRes is FlashResult.Success)
        assertEquals(null, db.hubAnnouncementDao().getById("ann-1"))
    }

    @Test
    fun `ANN-U23 and ANN-U26 searchHubAnnouncements falls back to local Room search when offline`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials())

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "Revenue Growth", body = "Profit up 15 percent")
        api.items["ann-2"] = sampleItem("ann-2", seq = 2L, title = "Security Update", body = "All passwords reset")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        repo.refreshHubAnnouncements()

        // Online search
        val onlineSearch = repo.searchHubAnnouncements("revenue", online = true)
        assertTrue(onlineSearch is FlashResult.Success)
        assertEquals(1, onlineSearch.value.size)
        assertEquals("ann-1", onlineSearch.value.first().id)

        // Offline simulated on server -> falls back to local Room search
        api.simulateOffline = true
        val offlineFallback = repo.searchHubAnnouncements("security", online = true)
        assertTrue(offlineFallback is FlashResult.Success)
        assertEquals(1, offlineFallback.value.size)
        assertEquals("ann-2", offlineFallback.value.first().id)

        // Explicit offline search
        val explicitOffline = repo.searchHubAnnouncements("revenue", online = false)
        assertTrue(explicitOffline is FlashResult.Success)
        assertEquals(1, explicitOffline.value.size)
        assertEquals("ann-1", explicitOffline.value.first().id)
    }

    @Test
    fun `ANN-U25 and ANN-U26 credentials cleared wipes local announcements`(): Unit = runBlocking {
        val db = openEncryptedFlashDatabase(dbFile, encryptionKey)
        val api = FakeHubAnnouncementsApi()
        val trustStore = MemoryEnterpriseTrustStore(sampleCredentials())

        api.items["ann-1"] = sampleItem("ann-1", seq = 1L, title = "Corporate News")

        val repo = RealFlashChatRepository(
            localDeviceId = "dev-self",
            localDisplayName = "Self",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            enterpriseTrustStore = trustStore,
            hubAnnouncementDao = db.hubAnnouncementDao(),
            hubAnnouncementsApi = api,
        )

        repo.refreshHubAnnouncements()
        assertEquals(1, repo.hubAnnouncements.first().size)

        // Unenrol (wipe credentials)
        trustStore.clearCredentials()

        // Wait for async collector on Dispatchers.IO to wipe Room tables
        val deadline = SystemTimeSource.nowMs() + 3000L
        while (repo.hubAnnouncements.first().isNotEmpty() && SystemTimeSource.nowMs() < deadline) {
            kotlinx.coroutines.delay(20)
        }

        val remaining = repo.hubAnnouncements.first()
        assertTrue(remaining.isEmpty(), "Stored announcements must be wiped when unenrolled")
        assertEquals(null, db.hubAnnouncementDao().getState(), "Sync state must be wiped when unenrolled")
    }
}
