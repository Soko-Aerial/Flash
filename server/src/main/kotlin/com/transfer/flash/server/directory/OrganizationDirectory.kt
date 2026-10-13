package com.transfer.flash.server.directory

import com.transfer.flash.server.observability.PresenceView
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.decodeList
import com.transfer.flash.server.storage.encodeList
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.longOrNull
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.update
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
 * Group charter defining visibility, scope, admins and members. An EMPTY [departmentId] is an organisation-wide group
 * (created by an organisation admin only).
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
 * Member record registered in a department (an empty [departmentId] is a departmentless member).
 */
data class DepartmentMember(
    val deviceId: String,
    val departmentId: String,
    val displayName: String,
    val role: MemberRole = MemberRole.MEMBER,
    /** The device's P-256 identity key (SPKI Base64), shown to authenticated in-scope callers so peers can verify it. */
    val subjectPublicKey: String? = null,
)

enum class JoinRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,

    /**
     * An admin rejected the request AND the requesting device has been told (ADR-124): its next `join-request` was
     * answered `REJECTED`. After that a new request starts a new pending row. Without this step a device that polls
     * its pending join would never see the decision, because the repeat request created a fresh PENDING row.
     */
    REJECTED_SEEN,
}

data class JoinRequest(
    val id: String,
    val groupId: String,
    val deviceId: String,
    val departmentId: String?,
    val status: JoinRequestStatus = JoinRequestStatus.PENDING,
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String? = null,
    val decidedBy: String? = null,
    val decidedAtMs: Long? = null,
)

sealed interface JoinRequestResult {
    data class Joined(val group: GroupCharter) : JoinRequestResult
    data class Pending(val request: JoinRequest) : JoinRequestResult
    data class Rejected(val reason: String) : JoinRequestResult
}

/** The id (department / group) already exists: creating it again would overwrite somebody else's work (REL-10). */
class DirectoryConflictException(message: String) : IllegalStateException(message)

/** An id the request names does not exist (or is not visible to the caller). */
class DirectoryNotFoundException(message: String) : IllegalArgumentException(message)

/**
 * Who is asking, as established by the hub from the authenticated certificate (never from request data): the device,
 * its department (from the certificate the hub signed) and its role.
 */
data class DirectoryActor(
    val deviceId: String,
    val departmentId: String? = null,
    val role: MemberRole = MemberRole.MEMBER,
) {
    val isOrgAdmin: Boolean get() = role == MemberRole.ORG_ADMIN

    companion object {
        /** An organisation administrator acting through the admin console (no device). */
        val CONSOLE = DirectoryActor(deviceId = "console", role = MemberRole.ORG_ADMIN)
    }
}

/**
 * Thread-safe enterprise organization directory: departments, groups, memberships, scoped discovery and join
 * requests (D-E10, D-E11, D-E13), persisted in SQLite when a [ServerDatabase] is given (component `directory`).
 *
 * - One read/write lock guards the in-memory index; every mutation writes the database FIRST (one transaction) and only
 *   then swaps the index, so a failed write changes nothing and two admins can not lose each other's updates.
 * - Creating a department or group whose id exists fails with [DirectoryConflictException] (no silent overwrite).
 * - Authorisation is by [DirectoryActor] (or the legacy `callerDeviceId`/`isOrgAdmin` pair): a null/absent caller is
 *   allowed ONLY together with `isOrgAdmin = true`.
 * - Visibility: HIDDEN groups are invisible to non-members; organisation admins see everything; department admins see
 *   their department; everybody sees LISTED groups of their own department and the LISTED, open-to-join
 *   organisation-wide (blank department) groups, so a departmentless member is not blind (REL-10).
 *
 * Blocking JDBC: the routes call it from request handlers (short statements, SQLite WAL); keep calls small.
 */
class OrganizationDirectory(
    private val db: ServerDatabase? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Live connection state for `online` flags; the relay's pool satisfies it. */
    @Volatile var presence: PresenceView = NoPresence,
) {

    private val lock = ReentrantReadWriteLock()
    private val departments = LinkedHashMap<String, Department>()
    private val groups = LinkedHashMap<String, GroupCharter>()
    private val members = LinkedHashMap<String, DepartmentMember>()
    private val joinRequests = LinkedHashMap<String, JoinRequest>()

    /** (groupId, deviceId) pairs an invite pre-authorised: the device joins that group without approval. */
    private val preAuthorized = HashSet<Pair<String, String>>()

    init {
        if (db != null) load(db)
    }

    // ------------------------------------------------------------------------------------------------ departments

    /**
     * Creates a new department. Per D-E13: only an organization admin can create departments.
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
        return createDepartment(id, name, adminDeviceIds, DirectoryActor(callerDeviceId ?: DirectoryActor.CONSOLE.deviceId, role = MemberRole.ORG_ADMIN))
    }

    fun createDepartment(id: String, name: String, adminDeviceIds: List<String>, actor: DirectoryActor): Department {
        if (!actor.isOrgAdmin) throw SecurityException("Only organization admin can create departments (D-E13)")
        requireId(id, "Department id")
        requireName(name, "Department name")
        val dept = Department(id = id.trim(), name = name.trim(), adminDeviceIds = adminDeviceIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        return lock.write {
            if (departments.containsKey(dept.id)) throw DirectoryConflictException("Department '${dept.id}' already exists")
            persist { c -> insertDepartment(c, dept) }
            departments[dept.id] = dept
            dept
        }
    }

    fun getDepartment(id: String): Department? = lock.read { departments[id] }

    fun listDepartments(): List<Department> = lock.read { departments.values.toList() }

    /** Renames a department and/or replaces its admin list. Organisation admin only. */
    fun updateDepartment(id: String, name: String?, adminDeviceIds: List<String>?, actor: DirectoryActor): Department {
        if (!actor.isOrgAdmin) throw SecurityException("Only organization admin can change departments")
        return lock.write {
            val existing = departments[id] ?: throw DirectoryNotFoundException("Department '$id' not found")
            if (name != null) requireName(name, "Department name")
            val updated = existing.copy(
                name = name?.trim() ?: existing.name,
                adminDeviceIds = adminDeviceIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: existing.adminDeviceIds,
            )
            persist { c -> c.update("UPDATE dir_departments SET name = ?, admin_ids = ? WHERE id = ?", updated.name, encodeList(updated.adminDeviceIds), id) }
            departments[id] = updated
            updated
        }
    }

    /** Deletes an empty department (no groups, no members). Organisation admin only. */
    fun deleteDepartment(id: String, actor: DirectoryActor) {
        if (!actor.isOrgAdmin) throw SecurityException("Only organization admin can delete departments")
        lock.write {
            if (!departments.containsKey(id)) throw DirectoryNotFoundException("Department '$id' not found")
            if (groups.values.any { it.departmentId == id } || members.values.any { it.departmentId == id }) {
                throw DirectoryConflictException("Department '$id' still has groups or members")
            }
            persist { c -> c.update("DELETE FROM dir_departments WHERE id = ?", id) }
            departments.remove(id)
        }
    }

    // ------------------------------------------------------------------------------------------------ groups

    /**
     * Creates a group inside a department. Per D-E13: department admins create groups inside their department (or
     * org admins). An empty [departmentId] makes an organisation-wide group (org admin only).
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
    ): GroupCharter = createGroup(
        id, name, departmentId, visibility, joinMode, adminDeviceIds,
        DirectoryActor(callerDeviceId, role = if (isOrgAdmin) MemberRole.ORG_ADMIN else MemberRole.MEMBER),
    )

    fun createGroup(
        id: String,
        name: String,
        departmentId: String,
        visibility: GroupVisibility,
        joinMode: JoinMode,
        adminDeviceIds: List<String>,
        actor: DirectoryActor,
    ): GroupCharter {
        requireId(id, "Group id")
        requireName(name, "Group name")
        return lock.write {
            if (departmentId.isBlank()) {
                if (!actor.isOrgAdmin) throw SecurityException("Only organization admin can create organisation-wide groups")
            } else {
                val dept = departments[departmentId]
                    ?: throw IllegalArgumentException("Department '$departmentId' does not exist")
                if (!actor.isOrgAdmin && !isDepartmentAdmin(dept, actor)) {
                    throw SecurityException(
                        "Only department admin or organization admin can create groups inside department '$departmentId' (D-E13)"
                    )
                }
            }
            if (groups.containsKey(id.trim())) throw DirectoryConflictException("Group '${id.trim()}' already exists")

            val allAdmins = (adminDeviceIds.map { it.trim() }.filter { it.isNotEmpty() } + actor.deviceId)
                .filter { it != DirectoryActor.CONSOLE.deviceId }
                .distinct()
            val group = GroupCharter(
                id = id.trim(),
                name = name.trim(),
                departmentId = departmentId.trim(),
                visibility = visibility,
                joinMode = joinMode,
                adminDeviceIds = allAdmins,
                memberDeviceIds = allAdmins,
            )
            persist { c ->
                insertGroup(c, group, clock())
                group.memberDeviceIds.forEach { m -> insertGroupMember(c, group.id, m) }
            }
            groups[group.id] = group
            group
        }
    }

    fun getGroup(id: String): GroupCharter? = lock.read { groups[id] }

    fun listGroups(): List<GroupCharter> = lock.read { groups.values.toList() }

    /** Changes name / visibility / join mode. Group admin, department admin or organisation admin. */
    fun updateGroup(id: String, name: String?, visibility: GroupVisibility?, joinMode: JoinMode?, actor: DirectoryActor): GroupCharter =
        lock.write {
            val group = groups[id] ?: throw DirectoryNotFoundException("Group '$id' not found")
            requireManager(group, actor, "change")
            if (name != null) requireName(name, "Group name")
            val updated = group.copy(
                name = name?.trim() ?: group.name,
                visibility = visibility ?: group.visibility,
                joinMode = joinMode ?: group.joinMode,
            )
            persist { c ->
                c.update(
                    "UPDATE dir_groups SET name = ?, visibility = ?, join_mode = ? WHERE id = ?",
                    updated.name, updated.visibility.name, updated.joinMode.name, id,
                )
            }
            groups[id] = updated
            updated
        }

    fun deleteGroup(id: String, actor: DirectoryActor) {
        lock.write {
            val group = groups[id] ?: throw DirectoryNotFoundException("Group '$id' not found")
            requireManager(group, actor, "delete")
            persist { c ->
                c.update("DELETE FROM dir_group_members WHERE group_id = ?", id)
                c.update("DELETE FROM dir_join_requests WHERE group_id = ?", id)
                c.update("DELETE FROM dir_preauth WHERE group_id = ?", id)
                c.update("DELETE FROM dir_groups WHERE id = ?", id)
            }
            groups.remove(id)
            joinRequests.values.removeIf { it.groupId == id }
            preAuthorized.removeIf { it.first == id }
        }
    }

    /** Replaces a group's admin list (organisation or department admin only; a group admin may not crown others). */
    fun setGroupAdmins(id: String, adminDeviceIds: List<String>, actor: DirectoryActor): GroupCharter = lock.write {
        val group = groups[id] ?: throw DirectoryNotFoundException("Group '$id' not found")
        val dept = departments[group.departmentId]
        if (!actor.isOrgAdmin && !(dept != null && isDepartmentAdmin(dept, actor))) {
            throw SecurityException("Only department admin or organization admin can change group admins")
        }
        val admins = adminDeviceIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val newMembers = (group.memberDeviceIds + admins).distinct()
        val updated = group.copy(adminDeviceIds = admins, memberDeviceIds = newMembers)
        persist { c ->
            c.update("UPDATE dir_groups SET admin_ids = ? WHERE id = ?", encodeList(admins), id)
            (newMembers - group.memberDeviceIds.toSet()).forEach { insertGroupMember(c, id, it) }
        }
        groups[id] = updated
        updated
    }

    // ------------------------------------------------------------------------------------------------ visibility

    /**
     * Legacy signature kept for the existing callers and tests: `*` means an organisation-admin / auditor scope.
     * Routes use [listVisibleGroups] with a [DirectoryActor] derived from the authenticated certificate.
     */
    fun listVisibleGroups(userDepartment: String?, userDeviceId: String): List<GroupCharter> = lock.read {
        groups.values.filter { group ->
            when {
                isMemberOrAdminOf(group, userDeviceId) -> true
                group.visibility == GroupVisibility.HIDDEN -> false
                else -> inScope(group, userDepartment)
            }
        }
    }

    /** The groups [actor] may see (rules in the class comment). */
    fun listVisibleGroups(actor: DirectoryActor): List<GroupCharter> = lock.read {
        groups.values.filter { canSee(it, actor) }
    }

    fun canSee(group: GroupCharter, actor: DirectoryActor): Boolean = lock.read {
        when {
            actor.isOrgAdmin -> true
            isMemberOrAdminOf(group, actor.deviceId) -> true
            departments[group.departmentId]?.let { isDepartmentAdmin(it, actor) } == true -> true
            group.visibility == GroupVisibility.HIDDEN -> false
            else -> inScope(group, actor.departmentId)
        }
    }

    /** True when [actor] may read the member list of [group] (members and managers only, never strangers). */
    fun canSeeMembers(group: GroupCharter, actor: DirectoryActor): Boolean = lock.read {
        actor.isOrgAdmin || isMemberOrAdminOf(group, actor.deviceId) ||
            departments[group.departmentId]?.let { isDepartmentAdmin(it, actor) } == true
    }

    fun canManage(group: GroupCharter, actor: DirectoryActor): Boolean = lock.read {
        actor.isOrgAdmin || group.adminDeviceIds.contains(actor.deviceId) ||
            departments[group.departmentId]?.let { isDepartmentAdmin(it, actor) } == true
    }

    // ------------------------------------------------------------------------------------------------ members

    fun addDepartmentMember(
        departmentId: String,
        deviceId: String,
        displayName: String,
        role: MemberRole = MemberRole.MEMBER,
        subjectPublicKey: String? = null,
    ): DepartmentMember {
        require(deviceId.isNotBlank()) { "Device id cannot be blank" }
        require(departmentId.isNotBlank()) { "Department id cannot be blank" }
        return registerMember(
            DepartmentMember(
                deviceId = deviceId.trim(),
                departmentId = departmentId.trim(),
                displayName = displayName.trim(),
                role = role,
                subjectPublicKey = subjectPublicKey,
            ),
        )
    }

    /** Inserts or updates a member record; [DepartmentMember.departmentId] may be blank (departmentless). */
    fun registerMember(member: DepartmentMember): DepartmentMember = lock.write {
        val stored = member.copy(
            deviceId = member.deviceId.trim(),
            departmentId = member.departmentId.trim(),
            displayName = member.displayName.trim().take(128),
        )
        persist { c ->
            c.update(
                "INSERT INTO dir_members(device_id, department_id, display_name, role, subject_public_key) " +
                    "VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET department_id = excluded.department_id, " +
                    "display_name = excluded.display_name, role = excluded.role, subject_public_key = excluded.subject_public_key",
                stored.deviceId, stored.departmentId, stored.displayName, stored.role.name, stored.subjectPublicKey,
            )
        }
        members[stored.deviceId] = stored
        stored
    }

    fun removeDepartmentMember(departmentId: String, deviceId: String) {
        lock.write {
            val existing = members[deviceId]
            if (existing != null && existing.departmentId == departmentId) {
                persist { c -> c.update("DELETE FROM dir_members WHERE device_id = ?", deviceId) }
                members.remove(deviceId)
            }
        }
    }

    fun getDepartmentMember(deviceId: String): DepartmentMember? = lock.read { members[deviceId] }

    fun listDepartmentMembers(departmentId: String): List<DepartmentMember> =
        lock.read { members.values.filter { it.departmentId == departmentId } }

    fun listAllMembers(): List<DepartmentMember> = lock.read { members.values.toList() }

    fun memberCount(departmentId: String): Int = lock.read { members.values.count { it.departmentId == departmentId } }

    fun groupCount(departmentId: String): Int = lock.read { groups.values.count { it.departmentId == departmentId } }

    fun pendingCount(groupId: String): Int =
        lock.read { joinRequests.values.count { it.groupId == groupId && it.status == JoinRequestStatus.PENDING } }

    /** Count of currently connected members of [group] according to [presence]. */
    fun onlineCount(group: GroupCharter): Int = group.memberDeviceIds.count { presence.isOnline(it) }

    fun isOnline(deviceId: String): Boolean = presence.isOnline(deviceId)

    /**
     * Removes every trace of [deviceId] (it was revoked or left): its member record, its group memberships and admin
     * roles, its pending requests and pre-authorisations. Idempotent.
     */
    fun removeDevice(deviceId: String) {
        lock.write {
            persist { c ->
                c.update("DELETE FROM dir_members WHERE device_id = ?", deviceId)
                c.update("DELETE FROM dir_group_members WHERE device_id = ?", deviceId)
                c.update("DELETE FROM dir_join_requests WHERE device_id = ? AND status = 'PENDING'", deviceId)
                c.update("DELETE FROM dir_preauth WHERE device_id = ?", deviceId)
                for (g in groups.values.filter { deviceId in it.adminDeviceIds }) {
                    c.update("UPDATE dir_groups SET admin_ids = ? WHERE id = ?", encodeList(g.adminDeviceIds - deviceId), g.id)
                }
                for (d in departments.values.filter { deviceId in it.adminDeviceIds }) {
                    c.update("UPDATE dir_departments SET admin_ids = ? WHERE id = ?", encodeList(d.adminDeviceIds - deviceId), d.id)
                }
            }
            members.remove(deviceId)
            for ((id, g) in groups.entries.toList()) {
                if (deviceId in g.memberDeviceIds || deviceId in g.adminDeviceIds) {
                    groups[id] = g.copy(
                        memberDeviceIds = g.memberDeviceIds - deviceId,
                        adminDeviceIds = g.adminDeviceIds - deviceId,
                    )
                }
            }
            for ((id, d) in departments.entries.toList()) {
                if (deviceId in d.adminDeviceIds) departments[id] = d.copy(adminDeviceIds = d.adminDeviceIds - deviceId)
            }
            joinRequests.values.removeIf { it.deviceId == deviceId && it.status == JoinRequestStatus.PENDING }
            preAuthorized.removeIf { it.second == deviceId }
        }
    }

    /**
     * Applies an invite's scope after the device enrolled: the member record, [autoJoinGroups] (the device is added)
     * and [allowedGroups] (the device may join those without approval later). Unknown groups are skipped (logged by the
     * caller). Idempotent, so a startup reconciliation can replay it.
     */
    fun applyEnrollment(
        member: DepartmentMember,
        autoJoinGroups: Set<String>,
        allowedGroups: Set<String>,
    ) {
        lock.write {
            registerMember(member)
            for (groupId in autoJoinGroups) {
                val group = groups[groupId] ?: continue
                if (member.deviceId !in group.memberDeviceIds) {
                    persist { c -> insertGroupMember(c, groupId, member.deviceId) }
                    groups[groupId] = group.copy(memberDeviceIds = group.memberDeviceIds + member.deviceId)
                }
            }
            for (groupId in allowedGroups) {
                if (groups.containsKey(groupId) && preAuthorized.add(groupId to member.deviceId)) {
                    persist { c -> c.update("INSERT OR IGNORE INTO dir_preauth(group_id, device_id) VALUES(?,?)", groupId, member.deviceId) }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ join requests

    /**
     * Attempts to join a group or submits a join approval request.
     * Per D-E11: Hidden groups cannot be joined via request; admin-add only (a hidden group is reported exactly like a
     * missing one, so the reply does not reveal that it exists).
     */
    fun requestJoinGroup(
        groupId: String,
        userDeviceId: String,
        userDepartment: String?,
        message: String? = null,
    ): JoinRequestResult = lock.write {
        val group = groups[groupId]
            ?: return@write JoinRequestResult.Rejected(JOIN_NOT_AVAILABLE)

        if (group.memberDeviceIds.contains(userDeviceId)) {
            return@write JoinRequestResult.Joined(group)
        }

        if ((groupId to userDeviceId) in preAuthorized) {
            return@write JoinRequestResult.Joined(addMemberLocked(group, userDeviceId))
        }

        // An admin declined an earlier request of this device: say so ONCE (the client polls its pending join), then
        // let a later request start fresh. The request row stays as history.
        val declined = joinRequests.values.filter {
            it.groupId == groupId && it.deviceId == userDeviceId && it.status == JoinRequestStatus.REJECTED
        }
        if (declined.isNotEmpty()) {
            for (request in declined) {
                val seen = request.copy(status = JoinRequestStatus.REJECTED_SEEN)
                persist { c -> updateJoinRequest(c, seen) }
                joinRequests[request.id] = seen
            }
            return@write JoinRequestResult.Rejected("An administrator declined this request.")
        }

        // D-E11 / REV-06: a hidden group and a group outside the caller's scope answer exactly like a missing one: same status, same
        // body, no name, no department. Only a group the caller could list may say why it cannot be joined.
        if (group.visibility == GroupVisibility.HIDDEN || !inScope(group, userDepartment)) {
            return@write JoinRequestResult.Rejected(JOIN_NOT_AVAILABLE)
        }

        if (group.joinMode == JoinMode.ADMIN_ONLY) {
            return@write JoinRequestResult.Rejected("This group only accepts members added by an administrator.")
        }

        when (group.joinMode) {
            JoinMode.OPEN_IN_SCOPE -> JoinRequestResult.Joined(addMemberLocked(group, userDeviceId))
            JoinMode.REQUEST_APPROVAL -> {
                val already = joinRequests.values.firstOrNull {
                    it.groupId == groupId && it.deviceId == userDeviceId && it.status == JoinRequestStatus.PENDING
                }
                if (already != null) return@write JoinRequestResult.Pending(already)
                val req = JoinRequest(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    deviceId = userDeviceId,
                    departmentId = userDepartment,
                    status = JoinRequestStatus.PENDING,
                    timestampMs = clock(),
                    message = message?.take(256),
                )
                persist { c -> insertJoinRequest(c, req) }
                joinRequests[req.id] = req
                JoinRequestResult.Pending(req)
            }
            JoinMode.ADMIN_ONLY -> JoinRequestResult.Rejected("This group only accepts members added by an administrator.")
        }
    }

    fun approveJoinRequest(
        requestId: String,
        callerDeviceId: String,
        isOrgAdmin: Boolean = false
    ): GroupCharter = approveJoinRequest(
        requestId,
        DirectoryActor(callerDeviceId, role = if (isOrgAdmin) MemberRole.ORG_ADMIN else MemberRole.MEMBER),
    )

    fun approveJoinRequest(requestId: String, actor: DirectoryActor): GroupCharter = lock.write {
        val req = joinRequests[requestId] ?: throw DirectoryNotFoundException("Join request '$requestId' not found")
        val group = groups[req.groupId] ?: throw DirectoryNotFoundException("Group '${req.groupId}' not found")
        requireManager(group, actor, "approve join requests for")
        if (req.status != JoinRequestStatus.PENDING) throw IllegalStateException("Join request '$requestId' was already decided")
        val updated = addMemberLocked(group, req.deviceId)
        val decided = req.copy(status = JoinRequestStatus.APPROVED, decidedBy = actor.deviceId, decidedAtMs = clock())
        persist { c -> updateJoinRequest(c, decided) }
        joinRequests[requestId] = decided
        updated
    }

    fun rejectJoinRequest(
        requestId: String,
        callerDeviceId: String,
        isOrgAdmin: Boolean = false
    ) = rejectJoinRequest(
        requestId,
        DirectoryActor(callerDeviceId, role = if (isOrgAdmin) MemberRole.ORG_ADMIN else MemberRole.MEMBER),
    )

    fun rejectJoinRequest(requestId: String, actor: DirectoryActor) {
        lock.write {
            val req = joinRequests[requestId] ?: throw DirectoryNotFoundException("Join request '$requestId' not found")
            val group = groups[req.groupId] ?: throw DirectoryNotFoundException("Group '${req.groupId}' not found")
            requireManager(group, actor, "reject join requests for")
            if (req.status != JoinRequestStatus.PENDING) throw IllegalStateException("Join request '$requestId' was already decided")
            val decided = req.copy(status = JoinRequestStatus.REJECTED, decidedBy = actor.deviceId, decidedAtMs = clock())
            persist { c -> updateJoinRequest(c, decided) }
            joinRequests[requestId] = decided
        }
    }

    fun listPendingJoinRequests(groupId: String): List<JoinRequest> = lock.read {
        joinRequests.values.filter { it.groupId == groupId && it.status == JoinRequestStatus.PENDING }
    }

    fun getJoinRequest(requestId: String): JoinRequest? = lock.read { joinRequests[requestId] }

    /** All pending requests across groups [actor] manages (the admin console's inbox). */
    fun listPendingFor(actor: DirectoryActor): List<JoinRequest> = lock.read {
        joinRequests.values.filter { r ->
            r.status == JoinRequestStatus.PENDING && groups[r.groupId]?.let { canManage(it, actor) } == true
        }
    }

    // ------------------------------------------------------------------------------------------------ admin add / remove

    fun addMemberToGroup(
        groupId: String,
        deviceId: String,
        callerDeviceId: String? = null,
        isOrgAdmin: Boolean = false
    ): GroupCharter = addMemberToGroup(groupId, deviceId, actorOf(callerDeviceId, isOrgAdmin, "add member to group"))

    fun addMemberToGroup(groupId: String, deviceId: String, actor: DirectoryActor): GroupCharter = lock.write {
        val group = groups[groupId] ?: throw DirectoryNotFoundException("Group '$groupId' not found")
        requireManager(group, actor, "add member to")
        addMemberLocked(group, deviceId.trim())
    }

    fun removeMemberFromGroup(
        groupId: String,
        deviceId: String,
        callerDeviceId: String? = null,
        isOrgAdmin: Boolean = false
    ): GroupCharter = removeMemberFromGroup(groupId, deviceId, actorOf(callerDeviceId, isOrgAdmin, "remove member from group"))

    fun removeMemberFromGroup(groupId: String, deviceId: String, actor: DirectoryActor): GroupCharter = lock.write {
        val group = groups[groupId] ?: throw DirectoryNotFoundException("Group '$groupId' not found")
        requireManager(group, actor, "remove member from")
        val updated = group.copy(
            memberDeviceIds = group.memberDeviceIds.filter { it != deviceId },
            adminDeviceIds = group.adminDeviceIds.filter { it != deviceId }
        )
        persist { c ->
            c.update("DELETE FROM dir_group_members WHERE group_id = ? AND device_id = ?", groupId, deviceId)
            c.update("UPDATE dir_groups SET admin_ids = ? WHERE id = ?", encodeList(updated.adminDeviceIds), groupId)
        }
        groups[groupId] = updated
        updated
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** Callers of the legacy API: a missing caller is only allowed together with the explicit org-admin flag. */
    private fun actorOf(callerDeviceId: String?, isOrgAdmin: Boolean, what: String): DirectoryActor {
        if (isOrgAdmin) {
            return DirectoryActor(callerDeviceId ?: DirectoryActor.CONSOLE.deviceId, role = MemberRole.ORG_ADMIN)
        }
        if (callerDeviceId == null) throw SecurityException("Caller not authorized to $what: no caller identity")
        return DirectoryActor(callerDeviceId, role = MemberRole.MEMBER)
    }

    private fun requireManager(group: GroupCharter, actor: DirectoryActor, verb: String) {
        if (!canManage(group, actor)) {
            throw SecurityException("Caller is not authorized to $verb group '${group.name}'")
        }
    }

    private fun isDepartmentAdmin(dept: Department, actor: DirectoryActor): Boolean =
        dept.adminDeviceIds.contains(actor.deviceId) ||
            (actor.role == MemberRole.DEPT_ADMIN && actor.departmentId == dept.id)

    private fun isMemberOrAdminOf(group: GroupCharter, deviceId: String): Boolean =
        group.memberDeviceIds.contains(deviceId) || group.adminDeviceIds.contains(deviceId)

    /**
     * Scope rule for a non-member: same department, or an organisation-wide group (blank department) that anyone may
     * join openly. `*` is the legacy auditor wildcard.
     */
    private fun inScope(group: GroupCharter, userDepartment: String?): Boolean {
        if (userDepartment == "*") return true
        if (group.departmentId.isBlank()) return group.joinMode == JoinMode.OPEN_IN_SCOPE
        return !userDepartment.isNullOrBlank() && userDepartment == group.departmentId
    }

    private fun addMemberLocked(group: GroupCharter, deviceId: String): GroupCharter {
        if (deviceId in group.memberDeviceIds) return group
        persist { c -> insertGroupMember(c, group.id, deviceId) }
        val updated = group.copy(memberDeviceIds = group.memberDeviceIds + deviceId)
        groups[group.id] = updated
        return updated
    }

    private fun requireId(id: String, what: String) {
        require(id.isNotBlank()) { "$what cannot be blank" }
        require(id.trim().length <= 128 && id.none { it.code < 0x20 || it.code == 0x7F }) { "$what is too long or contains control characters" }
    }

    private fun requireName(name: String, what: String) {
        require(name.isNotBlank()) { "$what cannot be blank" }
        require(name.trim().length <= 128 && name.none { it.code < 0x20 || it.code == 0x7F }) { "$what is too long or contains control characters" }
    }

    /** Runs [block] against the database in one transaction when persistence is on. */
    private fun persist(block: (Connection) -> Unit) {
        db?.immediate { conn -> block(conn) }
    }

    private fun insertDepartment(c: Connection, d: Department) {
        c.update("INSERT INTO dir_departments(id, name, admin_ids) VALUES(?,?,?)", d.id, d.name, encodeList(d.adminDeviceIds))
    }

    private fun insertGroup(c: Connection, g: GroupCharter, now: Long) {
        c.update(
            "INSERT INTO dir_groups(id, name, department_id, visibility, join_mode, admin_ids, created_at) VALUES(?,?,?,?,?,?,?)",
            g.id, g.name, g.departmentId, g.visibility.name, g.joinMode.name, encodeList(g.adminDeviceIds), now,
        )
    }

    private fun insertGroupMember(c: Connection, groupId: String, deviceId: String) {
        c.update("INSERT OR IGNORE INTO dir_group_members(group_id, device_id) VALUES(?,?)", groupId, deviceId)
    }

    private fun insertJoinRequest(c: Connection, r: JoinRequest) {
        c.update(
            "INSERT INTO dir_join_requests(id, group_id, device_id, department_id, status, requested_at, message, decided_by, decided_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            r.id, r.groupId, r.deviceId, r.departmentId, r.status.name, r.timestampMs, r.message, r.decidedBy, r.decidedAtMs,
        )
    }

    private fun updateJoinRequest(c: Connection, r: JoinRequest) {
        c.update("UPDATE dir_join_requests SET status = ?, decided_by = ?, decided_at = ? WHERE id = ?", r.status.name, r.decidedBy, r.decidedAtMs, r.id)
    }

    private fun load(database: ServerDatabase) {
        database.withConnection { c ->
            c.query("SELECT * FROM dir_departments ORDER BY rowid") { rs ->
                Department(rs.getString("id"), rs.getString("name"), decodeList(rs.getString("admin_ids")))
            }.forEach { departments[it.id] = it }
            val memberRows = c.query("SELECT group_id, device_id FROM dir_group_members ORDER BY rowid") { it.getString(1) to it.getString(2) }
                .groupBy({ it.first }, { it.second })
            c.query("SELECT * FROM dir_groups ORDER BY created_at, rowid") { rs ->
                val id = rs.getString("id")
                GroupCharter(
                    id = id,
                    name = rs.getString("name"),
                    departmentId = rs.getString("department_id"),
                    visibility = GroupVisibility.valueOf(rs.getString("visibility")),
                    joinMode = JoinMode.valueOf(rs.getString("join_mode")),
                    adminDeviceIds = decodeList(rs.getString("admin_ids")),
                    memberDeviceIds = memberRows[id].orEmpty(),
                )
            }.forEach { groups[it.id] = it }
            c.query("SELECT * FROM dir_members ORDER BY rowid") { rs ->
                DepartmentMember(
                    deviceId = rs.getString("device_id"),
                    departmentId = rs.getString("department_id"),
                    displayName = rs.getString("display_name"),
                    role = MemberRole.valueOf(rs.getString("role")),
                    subjectPublicKey = rs.getString("subject_public_key"),
                )
            }.forEach { members[it.deviceId] = it }
            c.query("SELECT * FROM dir_join_requests ORDER BY requested_at, rowid") { rs ->
                JoinRequest(
                    id = rs.getString("id"),
                    groupId = rs.getString("group_id"),
                    deviceId = rs.getString("device_id"),
                    departmentId = rs.getString("department_id"),
                    status = JoinRequestStatus.valueOf(rs.getString("status")),
                    timestampMs = rs.getLong("requested_at"),
                    message = rs.getString("message"),
                    decidedBy = rs.getString("decided_by"),
                    decidedAtMs = rs.longOrNull("decided_at"),
                )
            }.forEach { joinRequests[it.id] = it }
            c.query("SELECT group_id, device_id FROM dir_preauth") { it.getString(1) to it.getString(2) }.forEach { preAuthorized += it }
        }
    }

    /** Presence view of a hub with no relay attached (tests, the console before the relay starts). */
    object NoPresence : PresenceView {
        override fun isOnline(deviceId: String): Boolean = false
        override fun onlineDeviceIds(): Set<String> = emptySet()
    }

    companion object {
        const val COMPONENT: String = "directory"

        /** The one answer for a join request that names a missing, hidden or out-of-scope group (REV-06): it must not tell them apart. */
        const val JOIN_NOT_AVAILABLE: String = "This group is not available to join."

        val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "directory: departments, groups, members, join requests, pre-authorisations") { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE TABLE IF NOT EXISTS dir_departments(id TEXT PRIMARY KEY, name TEXT NOT NULL, admin_ids TEXT NOT NULL DEFAULT '')")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS dir_groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, department_id TEXT NOT NULL DEFAULT '', " +
                            "visibility TEXT NOT NULL, join_mode TEXT NOT NULL, admin_ids TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS dir_group_members(group_id TEXT NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY(group_id, device_id))",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_dir_group_members_device ON dir_group_members(device_id)")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS dir_members(device_id TEXT PRIMARY KEY, department_id TEXT NOT NULL DEFAULT '', " +
                            "display_name TEXT NOT NULL DEFAULT '', role TEXT NOT NULL, subject_public_key TEXT)",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS dir_join_requests(id TEXT PRIMARY KEY, group_id TEXT NOT NULL, device_id TEXT NOT NULL, " +
                            "department_id TEXT, status TEXT NOT NULL, requested_at INTEGER NOT NULL, message TEXT, decided_by TEXT, decided_at INTEGER)",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_dir_join_group ON dir_join_requests(group_id, status)")
                    st.execute("CREATE TABLE IF NOT EXISTS dir_preauth(group_id TEXT NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY(group_id, device_id))")
                }
            },
        )

        /** Applies the directory schema and loads the persisted directory. */
        fun open(db: ServerDatabase, presence: PresenceView = NoPresence, clock: () -> Long = System::currentTimeMillis): OrganizationDirectory {
            Migrator(db).apply { register(COMPONENT, MIGRATIONS) }.migrate()
            return OrganizationDirectory(db, clock, presence)
        }
    }
}
