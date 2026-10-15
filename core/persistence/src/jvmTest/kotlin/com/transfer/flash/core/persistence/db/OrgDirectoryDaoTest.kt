package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.text.foldForSearch
import com.transfer.flash.core.persistence.db.entity.HubGroupRosterEntity
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class OrgDirectoryDaoTest {

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

    @Test
    fun testMemberUpsertAndQueries() = runBlocking {
        val dao = db.orgDirectoryDao()

        val member1 = OrgDirectoryMemberEntity(
            deviceId = "dev-1",
            displayName = "General Mensah",
            displayNameFolded = foldForSearch("General Mensah"),
            departmentId = "dept-hq",
            role = "admin",
            subjectPublicKeyFingerprint = "FINGERPRINT-MENSAH",
            statementBytes = byteArrayOf(1, 2, 3),
            statementSerial = 10L,
            statementIssuedAtMs = 1000L,
            statementDelegationSerial = 1L,
            lastSyncedAtMs = 1500L,
        )

        val member2 = OrgDirectoryMemberEntity(
            deviceId = "dev-2",
            displayName = "Béatrice Owusu",
            displayNameFolded = foldForSearch("Béatrice Owusu"),
            departmentId = "dept-hq",
            role = "member",
            subjectPublicKeyFingerprint = "FINGERPRINT-BEATRICE",
            statementBytes = byteArrayOf(4, 5, 6),
            statementSerial = 11L,
            statementIssuedAtMs = 1001L,
            statementDelegationSerial = 1L,
            lastSyncedAtMs = 1501L,
        )

        dao.upsertMembers(listOf(member1, member2))

        // Find by deviceId
        val found1 = dao.findMemberByDeviceId("dev-1")
        assertNotNull(found1)
        assertEquals("General Mensah", found1.displayName)
        assertEquals("general mensah", found1.displayNameFolded)

        // Find by fingerprint (Layer 1 handshake lookup)
        val foundByFp = dao.findMemberByFingerprint("FINGERPRINT-BEATRICE")
        assertNotNull(foundByFp)
        assertEquals("dev-2", foundByFp.deviceId)

        val notFoundFp = dao.findMemberByFingerprint("UNKNOWN-FP")
        assertNull(notFoundFp)

        // Search by folded prefix: diacritic insensitive ("beatrice" matches "Béatrice Owusu")
        val searchBeatrice = dao.searchMembers(foldForSearch("beatrice"), limit = 10)
        assertEquals(1, searchBeatrice.size)
        assertEquals("dev-2", searchBeatrice[0].deviceId)

        // Search by second word prefix ("owusu" matches "Béatrice Owusu")
        val searchOwusu = dao.searchMembers(foldForSearch("owusu"), limit = 10)
        assertEquals(1, searchOwusu.size)
        assertEquals("dev-2", searchOwusu[0].deviceId)

        // List by department
        val hqMembers = dao.listMembersByDepartment("dept-hq")
        assertEquals(2, hqMembers.size)

        // Delete member
        dao.deleteMembers(listOf("dev-1"))
        assertNull(dao.findMemberByDeviceId("dev-1"))
        assertEquals(1, dao.listAllMembers().size)
    }

    @Test
    fun testDepartmentUpsertAndQueries() = runBlocking {
        val dao = db.orgDirectoryDao()

        val dept1 = OrgDepartmentEntity(
            id = "dept-a",
            name = "Alpha",
            memberCount = 12,
            headDeviceIdsJson = "[\"dev-head-a\"]",
            hidden = false,
        )
        val dept2 = OrgDepartmentEntity(
            id = "dept-b",
            name = "Bravo",
            memberCount = 4,
            headDeviceIdsJson = "[]",
            hidden = true,
        )

        dao.upsertDepartments(listOf(dept1, dept2))

        val list = dao.listDepartments()
        assertEquals(2, list.size)
        assertEquals("dept-a", list[0].id)
        assertEquals("dept-b", list[1].id)

        dao.deleteDepartments(listOf("dept-a"))
        val remaining = dao.listDepartments()
        assertEquals(1, remaining.size)
        assertEquals("dept-b", remaining[0].id)
    }

    @Test
    fun testSyncStateAndClearAll() = runBlocking {
        val dao = db.orgDirectoryDao()

        val state = OrgSyncStateEntity(
            id = 1,
            orgRootFingerprint = "FP-ORG-TEST",
            directoryEpoch = "epoch-2026",
            lastSyncedVersion = 105L,
            offlineGraceDays = 30,
            lastSyncSuccessMs = 5000L,
            lastSyncAttemptMs = 5100L,
            lastErrorKind = "rate_limited",
        )

        dao.setSyncState(state)

        val loaded = dao.getSyncState()
        assertNotNull(loaded)
        assertEquals("FP-ORG-TEST", loaded.orgRootFingerprint)
        assertEquals("epoch-2026", loaded.directoryEpoch)
        assertEquals(105L, loaded.lastSyncedVersion)
        assertEquals(30, loaded.offlineGraceDays)
        assertEquals("rate_limited", loaded.lastErrorKind)

        // Add dummy member and department
        dao.upsertMembers(listOf(
            OrgDirectoryMemberEntity(
                deviceId = "dev-x",
                displayName = "User X",
                displayNameFolded = "user x",
                departmentId = "dept-x",
                role = "member",
                subjectPublicKeyFingerprint = "FP-X",
                statementBytes = byteArrayOf(9),
                statementSerial = 1L,
                statementIssuedAtMs = 1L,
                statementDelegationSerial = 1L,
                lastSyncedAtMs = 1L,
            )
        ))
        dao.upsertDepartments(listOf(
            OrgDepartmentEntity("dept-x", "Dept X", 1, "[]", false)
        ))

        // Clear all (epoch reset / re-enrolment)
        dao.clearAll()
        assertTrue(dao.listAllMembers().isEmpty())
        assertTrue(dao.listDepartments().isEmpty())
        assertNull(dao.getSyncState())
    }

    @Test
    fun `testApplySyncBatch_staleSerialFiltered`() = runBlocking {
        val dao = db.orgDirectoryDao()
        val member1 = OrgDirectoryMemberEntity(
            deviceId = "dev-1",
            displayName = "General Mensah",
            displayNameFolded = "general mensah",
            departmentId = "dept-hq",
            role = "admin",
            subjectPublicKeyFingerprint = "FINGERPRINT-MENSAH",
            statementBytes = byteArrayOf(1),
            statementSerial = 10L,
            statementIssuedAtMs = 1000L,
            statementDelegationSerial = 1L,
            lastSyncedAtMs = 1500L,
        )
        dao.upsertMembers(listOf(member1))

        // Incoming update has older statementSerial = 9L (stale)
        val staleIncoming = member1.copy(displayName = "Imposter Mensah", statementSerial = 9L)
        dao.applySyncBatch(
            fullResync = false,
            membersToUpsert = listOf(staleIncoming),
            memberIdsToDelete = emptyList(),
            departmentsToUpsert = emptyList(),
            departmentIdsToDelete = emptyList(),
            newState = OrgSyncStateEntity(
                id = 1,
                orgRootFingerprint = "FP",
                directoryEpoch = "epoch",
                lastSyncedVersion = 2L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 2000L,
                lastSyncAttemptMs = 2000L,
            ),
        )

        // Member in DB must NOT have been overwritten
        val current = dao.findMemberByDeviceId("dev-1")
        assertNotNull(current)
        assertEquals("General Mensah", current.displayName)
        assertEquals(10L, current.statementSerial)

        // But incoming update with statementSerial = 11L (newer) must be accepted
        val newerIncoming = member1.copy(displayName = "Promoted Mensah", statementSerial = 11L)
        dao.applySyncBatch(
            fullResync = false,
            membersToUpsert = listOf(newerIncoming),
            memberIdsToDelete = emptyList(),
            departmentsToUpsert = emptyList(),
            departmentIdsToDelete = emptyList(),
            newState = OrgSyncStateEntity(
                id = 1,
                orgRootFingerprint = "FP",
                directoryEpoch = "epoch",
                lastSyncedVersion = 3L,
                offlineGraceDays = 30,
                lastSyncSuccessMs = 3000L,
                lastSyncAttemptMs = 3000L,
            ),
        )
        val updated = dao.findMemberByDeviceId("dev-1")
        assertNotNull(updated)
        assertEquals("Promoted Mensah", updated.displayName)
        assertEquals(11L, updated.statementSerial)
    }

    @Test
    fun hubGroupRostersAreReplacedAsOneSetAndWipedWithTheSnapshot() = runBlocking {
        val dao = db.orgDirectoryDao()
        dao.replaceGroupRosters(listOf(HubGroupRosterEntity("g1", 1L, "{a}", "[]", 9L), HubGroupRosterEntity("g2", 4L, "{b}", "[]", 9L)))
        assertEquals(2, dao.allGroupRosters().size)
        dao.replaceGroupRosters(listOf(HubGroupRosterEntity("g1", 2L, "{c}", "[]", 10L)))
        assertEquals(listOf("g1"), dao.allGroupRosters().map { it.groupId }, "the old set is replaced, not merged")
        assertEquals(2L, dao.groupRoster("g1")?.version)
        assertNull(dao.groupRoster("g2"))
        dao.clearAll()
        assertTrue(dao.allGroupRosters().isEmpty(), "leaving the organisation wipes the rosters too")
    }
}
