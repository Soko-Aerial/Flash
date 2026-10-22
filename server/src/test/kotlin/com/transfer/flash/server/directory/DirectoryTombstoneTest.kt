package com.transfer.flash.server.directory

import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SRVHUB-06 / SRVHUB-07 (DIR-02, DIR-03, ADR-222): a removal is told only to those who could see the entity, and an entity that
 * comes back is not deleted again by every peer.
 */
class DirectoryTombstoneTest {

    private val admin = DirectoryActor("root", null, MemberRole.ORG_ADMIN)
    private val outsider = DirectoryActor("alice", "dept-a", MemberRole.MEMBER)

    private fun directory(): OrganizationDirectory {
        val dir = OrganizationDirectory.open(ServerDatabase.inMemory())
        dir.createDepartment("dept-a", "Alpha", emptyList(), isOrgAdmin = true)
        dir.createDepartment("dept-b", "Beta", emptyList(), hidden = true, actor = admin)
        dir.addDepartmentMember("dept-a", "alice", "Alice")
        dir.addDepartmentMember("dept-b", "bob", "Bob")
        dir.addDepartmentMember("dept-b", "carol", "Carol")
        dir.addDepartmentMember("dept-a", "dan", "Dan")
        return dir
    }

    private fun OrganizationDirectory.removedFor(since: Long, actor: DirectoryActor) =
        getSyncChanges(since = since, cursor = null, limit = 50, actor = actor)

    @Test
    fun theRemovalOfAHiddenDepartmentsMemberIsNotToldToOutsiders() {
        val dir = directory()
        val since = dir.currentVersion
        dir.removeDepartmentMember("dept-b", "carol")
        dir.removeDevice("bob")
        dir.removeDepartmentMember("dept-a", "dan")

        assertEquals(listOf("dan"), dir.removedFor(since, outsider).removedDeviceIds, "only the visible department's removal reaches an outsider")
        assertEquals(setOf("carol", "bob", "dan"), dir.removedFor(since, admin).removedDeviceIds.toSet(), "an org admin sees all")
        assertEquals(setOf("carol", "bob", "dan"), dir.removedFor(since, DirectoryActor("zed", "dept-b", MemberRole.MEMBER)).removedDeviceIds.toSet(), "the department's own members see them")
    }

    @Test
    fun aDepartmentAdminOfTheHiddenDepartmentSeesItsRemovals() {
        val dir = directory()
        dir.updateDepartment("dept-b", null, listOf("head"), null, admin)
        val since = dir.currentVersion
        dir.removeDepartmentMember("dept-b", "carol")
        assertEquals(listOf("carol"), dir.removedFor(since, DirectoryActor("head", "dept-a", MemberRole.DEPT_ADMIN)).removedDeviceIds)
    }

    @Test
    fun aDeletedHiddenDepartmentIsNotToldToOutsidersEither() {
        val dir = directory()
        dir.removeDepartmentMember("dept-b", "bob")
        dir.removeDepartmentMember("dept-b", "carol")
        val since = dir.currentVersion
        dir.deleteDepartment("dept-b", admin)

        assertFalse("dept-b" in dir.removedFor(since, outsider).removedDepartmentIds)
        assertTrue("dept-b" in dir.removedFor(since, admin).removedDepartmentIds)
    }

    @Test
    fun aDeletedVisibleDepartmentIsStillToldToEveryone() {
        val dir = directory()
        dir.createDepartment("dept-c", "Gamma", emptyList(), isOrgAdmin = true)
        val since = dir.currentVersion
        dir.deleteDepartment("dept-c", admin)
        assertEquals(listOf("dept-c"), dir.removedFor(since, outsider).removedDepartmentIds)
    }

    @Test
    fun theDepartmentOfARemovalSurvivesARestart() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        dir.createDepartment("dept-b", "Beta", emptyList(), hidden = true, actor = admin)
        dir.addDepartmentMember("dept-b", "carol", "Carol")
        val since = dir.currentVersion
        dir.removeDepartmentMember("dept-b", "carol")
        dir.deleteDepartment("dept-b", admin)

        val reopened = OrganizationDirectory.open(db)
        val seen = reopened.removedFor(since, outsider)
        assertEquals(emptyList(), seen.removedDeviceIds)
        assertEquals(emptyList(), seen.removedDepartmentIds)
        assertEquals(listOf("carol"), reopened.removedFor(since, admin).removedDeviceIds)
    }

    @Test
    fun aRemovedDeviceThatIsRegisteredAgainIsListedAndNotRemoved() {
        val dir = directory()
        val since = dir.currentVersion
        dir.removeDevice("dan")
        assertEquals(listOf("dan"), dir.removedFor(since, outsider).removedDeviceIds)

        dir.addDepartmentMember("dept-a", "dan", "Dan")
        val after = dir.removedFor(since, outsider)
        assertFalse("dan" in after.removedDeviceIds, "a device that came back is not deleted again by every peer")
        assertTrue("dan" in after.members.map { it.deviceId } || dir.getDepartmentMember("dan") != null)
        assertFalse(dir.tombstones.containsKey("member" to "dan"))
    }

    @Test
    fun aClearedTombstoneStaysClearedAfterARestart() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        dir.createDepartment("dept-a", "Alpha", emptyList(), isOrgAdmin = true)
        dir.addDepartmentMember("dept-a", "dan", "Dan")
        val since = dir.currentVersion
        dir.removeDevice("dan")
        dir.addDepartmentMember("dept-a", "dan", "Dan")

        val reopened = OrganizationDirectory.open(db)
        assertFalse("dan" in reopened.removedFor(since, admin).removedDeviceIds)
    }

    @Test
    fun aDepartmentCreatedAgainIsNotToldAsRemoved() {
        val dir = directory()
        dir.createDepartment("dept-c", "Gamma", emptyList(), isOrgAdmin = true)
        val since = dir.currentVersion
        dir.deleteDepartment("dept-c", admin)
        dir.createDepartment("dept-c", "Gamma again", emptyList(), isOrgAdmin = true)

        val seen = dir.removedFor(since, outsider)
        assertFalse("dept-c" in seen.removedDepartmentIds)
        assertTrue("dept-c" in seen.departments.map { it.id })
    }

    @Test
    fun aStaleTombstoneIsIgnoredEvenIfNothingClearedIt() {
        // Belt and braces: a tombstone row older than the entity's current version (for example one restored from an old backup).
        val dir = directory()
        val since = dir.currentVersion
        dir.removeDepartmentMember("dept-a", "dan")
        dir.addDepartmentMember("dept-a", "dan", "Dan")
        dir.tombstones["member" to "dan"] = DirectoryTombstone("member", "dan", since + 1, 1L)
        assertFalse("dan" in dir.removedFor(since, outsider).removedDeviceIds)
    }

    @Test
    fun migration4AddsTheDepartmentColumnsAndKeepsOldTombstonesVisible() {
        val db = ServerDatabase.inMemory()
        Migrator(db).apply { register(OrganizationDirectory.COMPONENT, OrganizationDirectory.MIGRATIONS.take(3)); migrate() }
        db.withConnection {
            it.createStatement().use { st ->
                st.execute("INSERT INTO directory_tombstone(entity_type, entity_id, deleted_at_version, deleted_at_ms) VALUES('member', 'old', 5, 1)")
            }
        }
        Migrator(db).apply { register(OrganizationDirectory.COMPONENT, OrganizationDirectory.MIGRATIONS); migrate() }
        assertEquals(4, Migrator(db).currentVersion(OrganizationDirectory.COMPONENT))
        val old = OrganizationDirectory(db).tombstones["member" to "old"]!!
        assertEquals("", old.departmentId)
        assertFalse(old.departmentHidden)
    }
}
