package com.transfer.flash.server.directory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OrganizationDirectoryTest {

    @Test
    fun testDepartmentCreationByOrgAdminOnly() {
        val dir = OrganizationDirectory()

        // Org admin can create department
        val dept = dir.createDepartment(
            id = "dept-infantry",
            name = "1st Infantry Battalion",
            adminDeviceIds = listOf("dev-cmd-01"),
            isOrgAdmin = true
        )
        assertEquals("dept-infantry", dept.id)
        assertEquals("1st Infantry Battalion", dept.name)
        assertEquals(listOf("dev-cmd-01"), dept.adminDeviceIds)

        // Non-org-admin cannot create department (D-E13)
        assertFailsWith<SecurityException> {
            dir.createDepartment(
                id = "dept-signals",
                name = "Signals Corps",
                isOrgAdmin = false
            )
        }
    }

    @Test
    fun testGroupCreationByDepartmentAdminOrOrgAdminOnly() {
        val dir = OrganizationDirectory()
        dir.createDepartment(
            id = "dept-eng",
            name = "Engineering",
            adminDeviceIds = listOf("dev-eng-admin"),
            isOrgAdmin = true
        )

        // Department admin can create group inside their department (D-E13)
        val group1 = dir.createGroup(
            id = "grp-bridges",
            name = "Bridge Builders",
            departmentId = "dept-eng",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "dev-eng-admin",
            isOrgAdmin = false
        )
        assertNotNull(group1)
        assertEquals("grp-bridges", group1.id)
        assertTrue(group1.adminDeviceIds.contains("dev-eng-admin"))
        assertTrue(group1.memberDeviceIds.contains("dev-eng-admin"))

        // Org admin can create group inside any department
        val group2 = dir.createGroup(
            id = "grp-hq-eng",
            name = "Engineering HQ",
            departmentId = "dept-eng",
            visibility = GroupVisibility.HIDDEN,
            joinMode = JoinMode.ADMIN_ONLY,
            callerDeviceId = "dev-root-admin",
            isOrgAdmin = true
        )
        assertNotNull(group2)

        // Stranger / unauthorized device cannot create group (D-E13)
        assertFailsWith<SecurityException> {
            dir.createGroup(
                id = "grp-unauthorized",
                name = "Unauthorized Group",
                departmentId = "dept-eng",
                visibility = GroupVisibility.LISTED,
                joinMode = JoinMode.OPEN_IN_SCOPE,
                callerDeviceId = "dev-stranger-99",
                isOrgAdmin = false
            )
        }
    }

    @Test
    fun testVisibilityRulesHiddenGroupsExclusionPerDE11() {
        val dir = OrganizationDirectory()
        dir.createDepartment("dept-intel", "Military Intelligence", listOf("admin-intel"), isOrgAdmin = true)

        // 1. Create a LISTED group
        dir.createGroup(
            id = "grp-public-briefs",
            name = "Public Briefings",
            departmentId = "dept-intel",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "admin-intel"
        )

        // 2. Create a HIDDEN group (D-E11)
        dir.createGroup(
            id = "grp-covert-ops",
            name = "Covert Ops",
            departmentId = "dept-intel",
            visibility = GroupVisibility.HIDDEN,
            joinMode = JoinMode.ADMIN_ONLY,
            callerDeviceId = "admin-intel"
        )

        // Stranger in same department queries visible groups
        val strangerVisible = dir.listVisibleGroups(userDepartment = "dept-intel", userDeviceId = "stranger-01")

        // Hidden group MUST NEVER be listed to non-members (D-E11)
        assertEquals(1, strangerVisible.size)
        assertEquals("grp-public-briefs", strangerVisible[0].id)
        assertFalse(strangerVisible.any { it.id == "grp-covert-ops" })

        // When group member or admin queries, hidden group IS visible to them
        val adminVisible = dir.listVisibleGroups(userDepartment = "dept-intel", userDeviceId = "admin-intel")
        assertEquals(2, adminVisible.size)
        assertTrue(adminVisible.any { it.id == "grp-covert-ops" })
    }

    @Test
    fun testDepartmentScopingRules() {
        val dir = OrganizationDirectory()
        dir.createDepartment("dept-medical", "Medical Corps", listOf("med-admin"), isOrgAdmin = true)
        dir.createDepartment("dept-armor", "Armored Division", listOf("armor-admin"), isOrgAdmin = true)

        dir.createGroup(
            id = "grp-med-1",
            name = "Field Hospital 1",
            departmentId = "dept-medical",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "med-admin"
        )

        dir.createGroup(
            id = "grp-tank-1",
            name = "Tank Platoon 1",
            departmentId = "dept-armor",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "armor-admin"
        )

        // Medical user only sees Medical groups, not Armor
        val medVisible = dir.listVisibleGroups(userDepartment = "dept-medical", userDeviceId = "nurse-bob")
        assertEquals(1, medVisible.size)
        assertEquals("grp-med-1", medVisible[0].id)

        // Armor user only sees Armor groups
        val armorVisible = dir.listVisibleGroups(userDepartment = "dept-armor", userDeviceId = "driver-dan")
        assertEquals(1, armorVisible.size)
        assertEquals("grp-tank-1", armorVisible[0].id)

        // User with no department assigned sees nothing (unless already a member)
        val unassignedVisible = dir.listVisibleGroups(userDepartment = null, userDeviceId = "recruit-new")
        assertEquals(0, unassignedVisible.size)

        // Wildcard auditor sees all listed groups
        val auditorVisible = dir.listVisibleGroups(userDepartment = "*", userDeviceId = "gen-auditor")
        assertEquals(2, auditorVisible.size)
    }

    @Test
    fun testJoinRequestRules() {
        val dir = OrganizationDirectory()
        dir.createDepartment("dept-air", "Air Wing", listOf("air-admin"), isOrgAdmin = true)

        dir.createGroup(
            id = "grp-flight-ready",
            name = "Flight Readiness",
            departmentId = "dept-air",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE,
            callerDeviceId = "air-admin"
        )

        dir.createGroup(
            id = "grp-squadron-x",
            name = "Squadron X",
            departmentId = "dept-air",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.REQUEST_APPROVAL,
            callerDeviceId = "air-admin"
        )

        dir.createGroup(
            id = "grp-command-only",
            name = "Air Command",
            departmentId = "dept-air",
            visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.ADMIN_ONLY,
            callerDeviceId = "air-admin"
        )

        dir.createGroup(
            id = "grp-classified",
            name = "Black Ops Flight",
            departmentId = "dept-air",
            visibility = GroupVisibility.HIDDEN,
            joinMode = JoinMode.ADMIN_ONLY,
            callerDeviceId = "air-admin"
        )

        // 1. OPEN_IN_SCOPE: user in scope joins immediately
        val join1 = dir.requestJoinGroup("grp-flight-ready", "pilot-1", "dept-air")
        assertIs<JoinRequestResult.Joined>(join1)
        val updatedG1 = dir.getGroup("grp-flight-ready")
        assertTrue(updatedG1!!.memberDeviceIds.contains("pilot-1"))

        // User out of scope is rejected from OPEN_IN_SCOPE
        val joinOutOfScope = dir.requestJoinGroup("grp-flight-ready", "medic-1", "dept-medical")
        assertIs<JoinRequestResult.Rejected>(joinOutOfScope)

        // 2. REQUEST_APPROVAL: user in scope creates pending request
        val join2 = dir.requestJoinGroup("grp-squadron-x", "pilot-2", "dept-air")
        assertIs<JoinRequestResult.Pending>(join2)
        val pendingList = dir.listPendingJoinRequests("grp-squadron-x")
        assertEquals(1, pendingList.size)
        assertEquals("pilot-2", pendingList[0].deviceId)

        // Admin approves request
        val approvedGroup = dir.approveJoinRequest(pendingList[0].id, callerDeviceId = "air-admin")
        assertTrue(approvedGroup.memberDeviceIds.contains("pilot-2"))
        assertEquals(0, dir.listPendingJoinRequests("grp-squadron-x").size)

        // 3. ADMIN_ONLY: cannot join via request
        val joinAdminOnly = dir.requestJoinGroup("grp-command-only", "pilot-3", "dept-air")
        assertIs<JoinRequestResult.Rejected>(joinAdminOnly)

        // 4. HIDDEN: cannot join via request (D-E11)
        val joinHidden = dir.requestJoinGroup("grp-classified", "pilot-4", "dept-air")
        assertIs<JoinRequestResult.Rejected>(joinHidden)
        assertTrue(joinHidden.reason.contains("D-E11"))
    }

    @Test
    fun testAdminAddAndRemoveDirectly() {
        val dir = OrganizationDirectory()
        dir.createDepartment("dept-nav", "Naval Fleet", listOf("admiral-01"), isOrgAdmin = true)

        dir.createGroup(
            id = "grp-sub-01",
            name = "Submarine Unit",
            departmentId = "dept-nav",
            visibility = GroupVisibility.HIDDEN,
            joinMode = JoinMode.ADMIN_ONLY,
            callerDeviceId = "admiral-01"
        )

        // Admin adds member directly
        val afterAdd = dir.addMemberToGroup("grp-sub-01", "sailor-jack", callerDeviceId = "admiral-01")
        assertTrue(afterAdd.memberDeviceIds.contains("sailor-jack"))

        // Now sailor-jack can see the hidden group!
        val jackVisible = dir.listVisibleGroups("dept-nav", "sailor-jack")
        assertEquals(1, jackVisible.size)
        assertEquals("grp-sub-01", jackVisible[0].id)

        // Admin removes member
        val afterRemove = dir.removeMemberFromGroup("grp-sub-01", "sailor-jack", callerDeviceId = "admiral-01")
        assertFalse(afterRemove.memberDeviceIds.contains("sailor-jack"))

        // Sailor-jack no longer sees the hidden group
        val jackAfterRemoval = dir.listVisibleGroups("dept-nav", "sailor-jack")
        assertEquals(0, jackAfterRemoval.size)
    }

    @Test
    fun testDepartmentMembersManagement() {
        val dir = OrganizationDirectory()
        dir.createDepartment("dept-hq", "Headquarters", listOf("hq-admin"), isOrgAdmin = true)

        dir.addDepartmentMember("dept-hq", "dev-hq-1", "General Mensah", MemberRole.ORG_ADMIN)
        dir.addDepartmentMember("dept-hq", "dev-hq-2", "Major Addo", MemberRole.MEMBER)

        val members = dir.listDepartmentMembers("dept-hq")
        assertEquals(2, members.size)
        assertTrue(members.any { it.deviceId == "dev-hq-1" && it.role == MemberRole.ORG_ADMIN })

        dir.removeDepartmentMember("dept-hq", "dev-hq-2")
        val remaining = dir.listDepartmentMembers("dept-hq")
        assertEquals(1, remaining.size)
        assertEquals("dev-hq-1", remaining[0].deviceId)
    }
}
