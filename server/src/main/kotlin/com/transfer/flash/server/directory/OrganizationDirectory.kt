package com.transfer.flash.server.directory

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Group visibility modes.
 * Per D-E11: [HIDDEN] groups are NEVER listed to non-members; admin-add only.
 */
enum class GroupVisibility {
    LISTED,
    HIDDEN
}

/**
 * Group join modes governing enrollment.
 * - [OPEN_IN_SCOPE]: devices within the group's department scope can join immediately.
 * - [REQUEST_APPROVAL]: devices in scope submit a request requiring group admin approval.
 * - [ADMIN_ONLY]: members must be explicitly added by an administrator.
 */
enum class JoinMode {
    OPEN_IN_SCOPE,
    REQUEST_APPROVAL,
    ADMIN_ONLY
}

/**
 * Role of a member in the organization or department.
 */
enum class MemberRole {
    ORG_ADMIN,
    DEPT_ADMIN,
    MEMBER
}

/**
 * Department entity.
 * Per D-E13: only organization admins may create departments.
 */
data class Department(
    val id: String,
    val name: String,
    val adminDeviceIds: List<String> = emptyList()
)

/**
 * Group charter defining visibility, scope, admins and members.
 */
data class GroupCharter(
    val id: String,
    val name: String,
    val departmentId: String,
    val visibility: GroupVisibility,
    val joinMode: JoinMode,
    val adminDeviceIds: List<String> = emptyList(),
    val memberDeviceIds: List<String> = emptyList()
)

/**
 * Member record registered in a department.
 */
data class DepartmentMember(
    val deviceId: String,
    val departmentId: String,
    val displayName: String,
    val role: MemberRole = MemberRole.MEMBER
)

enum class JoinRequestStatus {
    PENDING,
    APPROVED,
    REJECTED
}

data class JoinRequest(
    val id: String,
    val groupId: String,
    val deviceId: String,
    val departmentId: String?,
    val status: JoinRequestStatus = JoinRequestStatus.PENDING,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed interface JoinRequestResult {
    data class Joined(val group: GroupCharter) : JoinRequestResult
    data class Pending(val request: JoinRequest) : JoinRequestResult
    data class Rejected(val reason: String) : JoinRequestResult
}

/**
 * In-memory thread-safe enterprise organization directory managing departments,
 * groups, memberships, scoped discovery, and join requests.
 *
 * Implements owner decisions:
 * - D-E10: Server-side directory with departments and group discovery.
 * - D-E11: Hidden groups are NEVER listed to strangers; admin-add only.
 * - D-E13: Org admin creates departments; department admins create groups inside theirs.
 */
class OrganizationDirectory {

    private val departments = ConcurrentHashMap<String, Department>()
    private val groups = ConcurrentHashMap<String, GroupCharter>()
    private val members = ConcurrentHashMap<String, DepartmentMember>() // deviceId -> Member
    private val joinRequests = ConcurrentHashMap<String, JoinRequest>() // requestId -> Request

    // --- Department Management (D-E13) ---

    /**
     * Creates a new department.
     * Per D-E13: Only an organization admin can create departments.
     */
    fun createDepartment(
        id: String,
        name: String,
        adminDeviceIds: List<String> = emptyList(),
        callerDeviceId: String? = null,
        isOrgAdmin: Boolean = false
    ): Department {
        if (!isOrgAdmin) {
            throw SecurityException("Only organization admin can create departments (D-E13)")
        }
        require(id.isNotBlank()) { "Department id cannot be blank" }
        require(name.isNotBlank()) { "Department name cannot be blank" }

        val dept = Department(id = id.trim(), name = name.trim(), adminDeviceIds = adminDeviceIds.distinct())
        departments[dept.id] = dept
        return dept
    }

    fun getDepartment(id: String): Department? = departments[id]

    fun listDepartments(): List<Department> = departments.values.toList()

    // --- Group Management (D-E13) ---

    /**
     * Creates a group inside a department.
     * Per D-E13: Department admins create groups inside their department (or org admins).
     */
    fun createGroup(
        id: String,
        name: String,
        departmentId: String,
        visibility: GroupVisibility,
        joinMode: JoinMode,
        adminDeviceIds: List<String> = emptyList(),
        callerDeviceId: String,
        isOrgAdmin: Boolean = false
    ): GroupCharter {
        require(id.isNotBlank()) { "Group id cannot be blank" }
        require(name.isNotBlank()) { "Group name cannot be blank" }

        val dept = departments[departmentId]
            ?: throw IllegalArgumentException("Department '$departmentId' does not exist")

        val isDeptAdmin = dept.adminDeviceIds.contains(callerDeviceId)
        if (!isOrgAdmin && !isDeptAdmin) {
            throw SecurityException(
                "Only department admin or organization admin can create groups inside department '$departmentId' (D-E13)"
            )
        }

        val allAdmins = (adminDeviceIds + callerDeviceId).distinct()
        val allMembers = allAdmins.distinct()

        val group = GroupCharter(
            id = id.trim(),
            name = name.trim(),
            departmentId = departmentId,
            visibility = visibility,
            joinMode = joinMode,
            adminDeviceIds = allAdmins,
            memberDeviceIds = allMembers
        )
        groups[group.id] = group
        return group
    }

    fun getGroup(id: String): GroupCharter? = groups[id]

    fun listGroups(): List<GroupCharter> = groups.values.toList()

    // --- Scoped Directory Query (D-E10, D-E11) ---

    /**
     * Returns only groups visible to the calling device:
     * 1. If user is already an admin or member of the group, it is ALWAYS visible (even if HIDDEN).
     * 2. If user is NOT a member/admin:
     *    - If group is HIDDEN: NEVER returned to non-members (D-E11).
     *    - If group is LISTED: returned only if within the user's scope:
     *      - userDepartment matches group.departmentId, OR
     *      - userDepartment is wildcard "*" (e.g. org admin / auditor scope).
     */
    fun listVisibleGroups(userDepartment: String?, userDeviceId: String): List<GroupCharter> {
        return groups.values.filter { group ->
            val isMemberOrAdmin = group.memberDeviceIds.contains(userDeviceId) ||
                group.adminDeviceIds.contains(userDeviceId)

            if (isMemberOrAdmin) {
                // Existing members always see their own groups, even hidden ones
                true
            } else {
                // Non-members:
                if (group.visibility == GroupVisibility.HIDDEN) {
                    // D-E11: Hidden groups are NEVER listed to non-members
                    false
                } else {
                    // LISTED groups: must match user's department scope
                    userDepartment != null && (userDepartment == "*" || group.departmentId == userDepartment)
                }
            }
        }
    }

    // --- Department Members ---

    fun addDepartmentMember(
        departmentId: String,
        deviceId: String,
        displayName: String,
        role: MemberRole = MemberRole.MEMBER
    ): DepartmentMember {
        require(deviceId.isNotBlank()) { "Device id cannot be blank" }
        require(departmentId.isNotBlank()) { "Department id cannot be blank" }

        val member = DepartmentMember(
            deviceId = deviceId.trim(),
            departmentId = departmentId.trim(),
            displayName = displayName.trim(),
            role = role
        )
        members[deviceId] = member
        return member
    }

    fun removeDepartmentMember(departmentId: String, deviceId: String) {
        val existing = members[deviceId]
        if (existing != null && existing.departmentId == departmentId) {
            members.remove(deviceId)
        }
    }

    fun getDepartmentMember(deviceId: String): DepartmentMember? = members[deviceId]

    fun listDepartmentMembers(departmentId: String): List<DepartmentMember> {
        return members.values.filter { it.departmentId == departmentId }
    }

    // --- Join Requests (D-E11) ---

    /**
     * Attempts to join a group or submits a join approval request.
     * Per D-E11: Hidden groups cannot be joined via request; admin-add only.
     */
    fun requestJoinGroup(
        groupId: String,
        userDeviceId: String,
        userDepartment: String?
    ): JoinRequestResult {
        val group = groups[groupId]
            ?: return JoinRequestResult.Rejected("Group '$groupId' not found")

        // If already a member
        if (group.memberDeviceIds.contains(userDeviceId)) {
            return JoinRequestResult.Joined(group)
        }

        // D-E11: Hidden groups are admin-add only
        if (group.visibility == GroupVisibility.HIDDEN) {
            return JoinRequestResult.Rejected("Hidden groups cannot be joined via request; admin-add only (D-E11)")
        }

        // Admin-only groups
        if (group.joinMode == JoinMode.ADMIN_ONLY) {
            return JoinRequestResult.Rejected("Group '${group.name}' join mode is ADMIN_ONLY")
        }

        // Scope check
        val inScope = userDepartment != null && (userDepartment == "*" || userDepartment == group.departmentId)
        if (!inScope) {
            return JoinRequestResult.Rejected("User is outside department scope '${group.departmentId}'")
        }

        return when (group.joinMode) {
            JoinMode.OPEN_IN_SCOPE -> {
                val updated = group.copy(memberDeviceIds = (group.memberDeviceIds + userDeviceId).distinct())
                groups[groupId] = updated
                JoinRequestResult.Joined(updated)
            }
            JoinMode.REQUEST_APPROVAL -> {
                val req = JoinRequest(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    deviceId = userDeviceId,
                    departmentId = userDepartment,
                    status = JoinRequestStatus.PENDING
                )
                joinRequests[req.id] = req
                JoinRequestResult.Pending(req)
            }
            JoinMode.ADMIN_ONLY -> JoinRequestResult.Rejected("Group join mode is ADMIN_ONLY")
        }
    }

    fun approveJoinRequest(
        requestId: String,
        callerDeviceId: String,
        isOrgAdmin: Boolean = false
    ): GroupCharter {
        val req = joinRequests[requestId]
            ?: throw IllegalArgumentException("Join request '$requestId' not found")
        val group = groups[req.groupId]
            ?: throw IllegalArgumentException("Group '${req.groupId}' not found")
        val dept = departments[group.departmentId]

        val isGroupAdmin = group.adminDeviceIds.contains(callerDeviceId)
        val isDeptAdmin = dept != null && dept.adminDeviceIds.contains(callerDeviceId)
        if (!isOrgAdmin && !isGroupAdmin && !isDeptAdmin) {
            throw SecurityException("Caller is not authorized to approve join requests for group '${group.name}'")
        }

        val updated = group.copy(memberDeviceIds = (group.memberDeviceIds + req.deviceId).distinct())
        groups[group.id] = updated
        joinRequests[requestId] = req.copy(status = JoinRequestStatus.APPROVED)
        return updated
    }

    fun rejectJoinRequest(
        requestId: String,
        callerDeviceId: String,
        isOrgAdmin: Boolean = false
    ) {
        val req = joinRequests[requestId]
            ?: throw IllegalArgumentException("Join request '$requestId' not found")
        val group = groups[req.groupId]
            ?: throw IllegalArgumentException("Group '${req.groupId}' not found")
        val dept = departments[group.departmentId]

        val isGroupAdmin = group.adminDeviceIds.contains(callerDeviceId)
        val isDeptAdmin = dept != null && dept.adminDeviceIds.contains(callerDeviceId)
        if (!isOrgAdmin && !isGroupAdmin && !isDeptAdmin) {
            throw SecurityException("Caller is not authorized to reject join requests for group '${group.name}'")
        }

        joinRequests[requestId] = req.copy(status = JoinRequestStatus.REJECTED)
    }

    fun listPendingJoinRequests(groupId: String): List<JoinRequest> {
        return joinRequests.values.filter { it.groupId == groupId && it.status == JoinRequestStatus.PENDING }
    }

    // --- Admin Add / Remove Member ---

    fun addMemberToGroup(
        groupId: String,
        deviceId: String,
        callerDeviceId: String? = null,
        isOrgAdmin: Boolean = false
    ): GroupCharter {
        val group = groups[groupId]
            ?: throw IllegalArgumentException("Group '$groupId' not found")
        val dept = departments[group.departmentId]

        if (!isOrgAdmin && callerDeviceId != null) {
            val isGroupAdmin = group.adminDeviceIds.contains(callerDeviceId)
            val isDeptAdmin = dept != null && dept.adminDeviceIds.contains(callerDeviceId)
            if (!isGroupAdmin && !isDeptAdmin) {
                throw SecurityException("Caller not authorized to add member to group '${group.name}'")
            }
        }

        val updated = group.copy(memberDeviceIds = (group.memberDeviceIds + deviceId).distinct())
        groups[groupId] = updated
        return updated
    }

    fun removeMemberFromGroup(
        groupId: String,
        deviceId: String,
        callerDeviceId: String? = null,
        isOrgAdmin: Boolean = false
    ): GroupCharter {
        val group = groups[groupId]
            ?: throw IllegalArgumentException("Group '$groupId' not found")
        val dept = departments[group.departmentId]

        if (!isOrgAdmin && callerDeviceId != null) {
            val isGroupAdmin = group.adminDeviceIds.contains(callerDeviceId)
            val isDeptAdmin = dept != null && dept.adminDeviceIds.contains(callerDeviceId)
            if (!isGroupAdmin && !isDeptAdmin) {
                throw SecurityException("Caller not authorized to remove member from group '${group.name}'")
            }
        }

        val updated = group.copy(
            memberDeviceIds = group.memberDeviceIds.filter { it != deviceId },
            adminDeviceIds = group.adminDeviceIds.filter { it != deviceId }
        )
        groups[groupId] = updated
        return updated
    }
}
