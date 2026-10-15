package com.transfer.flash.server.directory

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.rest.DepartmentSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectoryMemberSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.GroupRosterDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.HubDirectoryValues
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
import java.util.Base64
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
 * [hidden] (Plan A2, option C): when true, outsiders (devices that are neither
 * members/admins of this department nor org admins) see neither the department
 * nor its people, counts, groups or keys. Listing-only: known ids can still
 * message and LAN device names are unchanged.
 */
data class Department(
    val id: String,
    val name: String,
    val adminDeviceIds: List<String> = emptyList(),
    val version: Long = 0L,
    val hidden: Boolean = false,
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
    val memberDeviceIds: List<String> = emptyList(),
    val version: Long = 0L,
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
    val version: Long = 0L,
)

/** The sync cursor did not parse; the route answers 400 instead of restarting from the beginning. */
class InvalidDirectoryCursorException : IllegalArgumentException("The directory sync cursor is not valid.")

/** Tombstone tracking deletion of an entity for incremental directory sync. */
data class DirectoryTombstone(
    val entityType: String,
    val entityId: String,
    val deletedAtVersion: Long,
    val deletedAtMs: Long,
)

/** Interface for signing [OrgMemberStatement]s when members are added or updated. */
fun interface OrgMemberStatementSigner {
    fun sign(
        deviceId: String,
        subjectPublicKey: String,
        departmentId: String,
        role: String,
        nowMs: Long,
        serial: Long,
    ): Pair<OrgMemberStatement, Long>? // statement to delegationSerial
}

/** Signs a [GroupRosterStatement] with the hub's delegate key (Plan B Phase B4); null when the hub has no active delegation. */
fun interface GroupRosterSigner {
    fun sign(unsigned: GroupRosterStatement): GroupRosterStatement?
}

/**
 * Scoping and visibility rules shared between REST directory endpoints and directory sync (Plan B, ADR-134;
 * hidden departments: Plan A2, ADR-137). Every directory read goes through here; no route re-implements the rules.
 *
 * Hidden is listing-only: it never stops a message between two devices that already know each other's ids, it never
 * hides LAN device names, and the hub operator / org admins always see everything.
 */
object DirectoryVisibility {
    /** Ships ON (owner D9): identity + sealing keys are org-visible for non-hidden departments. */
    const val DIRECTORY_KEYS_ORG_WIDE: Boolean = true

    fun canSeeDepartment(department: Department, actor: DirectoryActor): Boolean {
        if (actor.isOrgAdmin) return true
        // Insiders (own department or an administered department) always see it, hidden or not.
        if (department.id == actor.departmentId || actor.deviceId in department.adminDeviceIds) return true
        // Option C: non-hidden departments are visible org-wide to every authenticated device.
        // Routes always authenticate first, so a blank device id never reaches here.
        if (!department.hidden) return actor.deviceId.isNotBlank()
        return false
    }

    fun canSeeMember(member: DepartmentMember, actor: DirectoryActor, directory: OrganizationDirectory): Boolean {
        if (actor.isOrgAdmin) return true
        if (member.deviceId == actor.deviceId) return true
        return canListMember(actor, member, directory)
    }

    /** Org-wide people listing predicate (A2 §3.1): hidden-department rows are never delivered to outsiders. */
    fun canListMember(actor: DirectoryActor, member: DepartmentMember, directory: OrganizationDirectory): Boolean {
        if (actor.isOrgAdmin) return true
        if (member.deviceId == actor.deviceId) return true
        val deptId = member.departmentId
        if (deptId.isBlank()) return true
        if (deptId == actor.departmentId) return true
        val dept = directory.getDepartment(deptId)
        if (dept != null && actor.deviceId in dept.adminDeviceIds) return true
        // A known department admin (device) sees members of the department they administer even when their own
        // certificate names another department.
        if (dept == null) return false
        if (dept.hidden) return false
        return true
    }

    /** Single lookup by known id (A2 §3.3.2): any authenticated org member who knows the id may resolve it. */
    fun canLookupMember(actor: DirectoryActor, deviceId: String): Boolean = actor.deviceId.isNotBlank() && deviceId.isNotBlank()

    /** Group predicate with the hidden-department filter (A2 §3.1): outsiders lose groups of hidden departments. */
    fun canSeeGroup(group: GroupCharter, actor: DirectoryActor, directory: OrganizationDirectory): Boolean {
        if (actor.isOrgAdmin) return true
        if (group.memberDeviceIds.contains(actor.deviceId) || group.adminDeviceIds.contains(actor.deviceId)) return true
        val deptId = group.departmentId
        if (deptId.isBlank()) return true
        val dept = directory.getDepartment(deptId)
        if (dept != null && actor.deviceId in dept.adminDeviceIds) return true
        // Members of the department itself are insiders even when they are not group members.
        if (deptId == actor.departmentId) return true
        if (dept != null && dept.hidden) return false
        if (dept == null) return true
        return true
    }

    /** Keys (identity + sealing) follow D9: org-wide for non-hidden departments, insiders-only for hidden ones. */
    fun keysVisible(actor: DirectoryActor, member: DepartmentMember, directory: OrganizationDirectory): Boolean {
        if (actor.isOrgAdmin) return true
        if (member.deviceId == actor.deviceId) return true
        val deptId = member.departmentId
        if (deptId.isBlank()) return true
        if (deptId == actor.departmentId) return true
        val dept = directory.getDepartment(deptId)
        if (dept != null && actor.deviceId in dept.adminDeviceIds) return true
        if (dept != null && dept.hidden) return false
        return DIRECTORY_KEYS_ORG_WIDE
    }

    /** Presence (online dots): hidden departments show dots to insiders only; otherwise org-visible (v1, no per-dept switch). */
    fun presenceVisible(actor: DirectoryActor, member: DepartmentMember, directory: OrganizationDirectory): Boolean {
        if (actor.isOrgAdmin) return true
        if (member.deviceId == actor.deviceId) return true
        val deptId = member.departmentId
        if (deptId.isBlank()) return true
        if (deptId == actor.departmentId) return true
        val dept = directory.getDepartment(deptId)
        if (dept != null && actor.deviceId in dept.adminDeviceIds) return true
        if (dept != null && dept.hidden) return false
        return true
    }
}

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

    var currentVersion: Long = 0L
        private set
    var epochId: String = UUID.randomUUID().toString()
        private set
    var statementSigner: OrgMemberStatementSigner? = null
    var rosterSigner: GroupRosterSigner? = null

    /** Groups of the newest sync that were too large for an offline roster (more than [GroupRosterStatement.MAX_MEMBERS]). */
    @Volatile var rostersSkippedTooLarge: Int = 0

    val tombstones = LinkedHashMap<Pair<String, String>, DirectoryTombstone>()
    private val memberStatements = LinkedHashMap<String, OrgMemberStatement>()

    private fun bumpVersionLocked(nowMs: Long = clock()): Long {
        currentVersion += 1
        persist { c ->
            c.update(
                "UPDATE directory_state SET current_version = ?, last_bumped_at_ms = ? WHERE id = 1",
                currentVersion,
                nowMs,
            )
        }
        return currentVersion
    }

    private fun recordTombstoneLocked(type: String, id: String, version: Long, nowMs: Long = clock()) {
        persist { c ->
            c.update(
                "INSERT INTO directory_tombstone(entity_type, entity_id, deleted_at_version, deleted_at_ms) " +
                    "VALUES(?,?,?,?) ON CONFLICT(entity_type, entity_id) DO UPDATE SET deleted_at_version = excluded.deleted_at_version, deleted_at_ms = excluded.deleted_at_ms",
                type, id, version, nowMs,
            )
        }
        tombstones[type to id] = DirectoryTombstone(type, id, version, nowMs)
    }

    /**
     * Highest tombstone version that was pruned. A client whose `since` is below it can no longer be told what was
     * deleted, so it gets a full resync (plan B1.2, verifier defect 8). Persisted as a reserved tombstone row
     * (`_pruned`) so it survives a restart without a schema migration.
     */
    var prunedThroughVersion: Long = 0L
        private set

    /** Deletes tombstones older than [retentionMs] (default 90 days) and raises [prunedThroughVersion]. Returns how many. */
    fun pruneTombstones(retentionMs: Long = TOMBSTONE_RETENTION_MS): Int = lock.write {
        val cutoff = clock() - retentionMs
        val old = tombstones.values.filter { it.entityType != PRUNE_MARK && it.deletedAtMs < cutoff }
        if (old.isEmpty()) return@write 0
        val watermark = maxOf(prunedThroughVersion, old.maxOf { it.deletedAtVersion })
        persist { c ->
            c.update("DELETE FROM directory_tombstone WHERE entity_type <> ? AND deleted_at_ms < ?", PRUNE_MARK, cutoff)
        }
        old.forEach { tombstones.remove(it.entityType to it.entityId) }
        prunedThroughVersion = watermark
        recordTombstoneLocked(PRUNE_MARK, "watermark", watermark)
        old.size
    }

    fun resetEpoch(newEpoch: String = UUID.randomUUID().toString()) = lock.write {
        epochId = newEpoch
        persist { c ->
            c.update("UPDATE directory_state SET epoch_id = ?, last_bumped_at_ms = ? WHERE id = 1", epochId, clock())
        }
    }

    /** Members of the last sync page that had no signed statement and were left out (the route reports it). */
    @Volatile
    var unsignedOnLastPage: Int = 0
        private set

    fun getMemberStatement(deviceId: String): OrgMemberStatement? = lock.read { memberStatements[deviceId] }

    fun ensureStatementsSigned() = lock.write {
        val signer = statementSigner ?: return@write
        val now = clock()
        for (member in members.values.toList()) {
            if (member.subjectPublicKey.isNullOrBlank()) continue
            if (memberStatements.containsKey(member.deviceId)) continue
            val roleStr = when (member.role) {
                MemberRole.ORG_ADMIN -> HubRoles.ADMIN
                MemberRole.DEPT_ADMIN -> HubRoles.DEPT_ADMIN
                MemberRole.MEMBER -> HubRoles.MEMBER
            }
            val res = signer.sign(
                deviceId = member.deviceId,
                subjectPublicKey = member.subjectPublicKey,
                departmentId = member.departmentId,
                role = roleStr,
                nowMs = now,
                serial = currentVersion + 1,
            )
            if (res != null) {
                val (statement, delegationSerial) = res
                val ver = bumpVersionLocked(now)
                memberStatements[member.deviceId] = statement
                persist { c ->
                    c.update("UPDATE dir_members SET version = ? WHERE device_id = ?", ver, member.deviceId)
                    insertMemberStatement(c, statement, delegationSerial, ver)
                }
                members[member.deviceId] = member.copy(version = ver)
            }
        }
    }

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
            val newVer = bumpVersionLocked()
            val deptWithVer = dept.copy(version = newVer)
            persist { c -> insertDepartment(c, deptWithVer) }
            departments[dept.id] = deptWithVer
            deptWithVer
        }
    }

    /** Creates a department with an explicit hidden flag (Plan A2; org admin only). */
    fun createDepartment(id: String, name: String, adminDeviceIds: List<String>, hidden: Boolean, actor: DirectoryActor): Department {
        if (!actor.isOrgAdmin) throw SecurityException("Only organization admin can create departments (D-E13)")
        requireId(id, "Department id")
        requireName(name, "Department name")
        val dept = Department(id = id.trim(), name = name.trim(), adminDeviceIds = adminDeviceIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct(), hidden = hidden)
        return lock.write {
            if (departments.containsKey(dept.id)) throw DirectoryConflictException("Department '${dept.id}' already exists")
            val newVer = bumpVersionLocked()
            val deptWithVer = dept.copy(version = newVer)
            persist { c -> insertDepartment(c, deptWithVer) }
            departments[dept.id] = deptWithVer
            if (hidden) resetEpoch()
            deptWithVer
        }
    }

    fun getDepartment(id: String): Department? = lock.read { departments[id] }

    fun listDepartments(): List<Department> = lock.read { departments.values.toList() }

    /** Renames a department and/or replaces its admin list. Organisation admin only. */
    fun updateDepartment(id: String, name: String?, adminDeviceIds: List<String>?, actor: DirectoryActor): Department =
        updateDepartment(id, name, adminDeviceIds, null, actor)

    /** Full update including the A2 hidden flag; any change of hidden bumps the directory epoch (forces full resync). */
    fun updateDepartment(id: String, name: String?, adminDeviceIds: List<String>?, hidden: Boolean?, actor: DirectoryActor): Department {
        if (!actor.isOrgAdmin) throw SecurityException("Only organization admin can change departments")
        return lock.write {
            val existing = departments[id] ?: throw DirectoryNotFoundException("Department '$id' not found")
            if (name != null) requireName(name, "Department name")
            val hiddenChanged = hidden != null && hidden != existing.hidden
            val newVer = bumpVersionLocked()
            val updated = existing.copy(
                name = name?.trim() ?: existing.name,
                adminDeviceIds = adminDeviceIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: existing.adminDeviceIds,
                version = newVer,
                hidden = hidden ?: existing.hidden,
            )
            persist { c -> c.update("UPDATE dir_departments SET name = ?, admin_ids = ?, hidden = ?, version = ? WHERE id = ?", updated.name, encodeList(updated.adminDeviceIds), if (updated.hidden) 1 else 0, newVer, id) }
            departments[id] = updated
            if (hiddenChanged) resetEpoch()
            updated
        }
    }

    /** Flips only the hidden flag (console toggle); bumps the epoch so every client resyncs fully. */
    fun setDepartmentHidden(id: String, hidden: Boolean, actor: DirectoryActor): Department =
        updateDepartment(id, null, null, hidden, actor)

    /** Removes [deviceId] from the admin list of [departmentId] (member moved away; idempotent). */
    fun removeFromAdmins(departmentId: String, deviceId: String) {
        lock.write {
            val dept = departments[departmentId] ?: return@write
            if (deviceId !in dept.adminDeviceIds) return@write
            val v = bumpVersionLocked()
            val updated = dept.copy(adminDeviceIds = dept.adminDeviceIds - deviceId, version = v)
            persist { c -> c.update("UPDATE dir_departments SET admin_ids = ?, version = ? WHERE id = ?", encodeList(updated.adminDeviceIds), v, departmentId) }
            departments[departmentId] = updated
        }
    }

    /**
     * Adds [deviceId] to the admin list of [departmentId] (head enrolment, Plan A1 §3.8; idempotent).
     * No-op when the department does not exist (the enrolment validation already refused unknown departments).
     */
    fun addDepartmentAdmin(departmentId: String, deviceId: String) {
        if (departmentId.isBlank() || deviceId.isBlank()) return
        lock.write {
            val dept = departments[departmentId] ?: return@write
            if (deviceId in dept.adminDeviceIds) return@write
            val v = bumpVersionLocked()
            val updated = dept.copy(adminDeviceIds = dept.adminDeviceIds + deviceId, version = v)
            persist { c -> c.update("UPDATE dir_departments SET admin_ids = ?, version = ? WHERE id = ?", encodeList(updated.adminDeviceIds), v, departmentId) }
            departments[departmentId] = updated
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
            val v = bumpVersionLocked()
            recordTombstoneLocked("department", id, v)
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
            val newVer = bumpVersionLocked()
            val group = GroupCharter(
                id = id.trim(),
                name = name.trim(),
                departmentId = departmentId.trim(),
                visibility = visibility,
                joinMode = joinMode,
                adminDeviceIds = allAdmins,
                memberDeviceIds = allAdmins,
                version = newVer,
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
            val newVer = bumpVersionLocked()
            val updated = group.copy(
                name = name?.trim() ?: group.name,
                visibility = visibility ?: group.visibility,
                joinMode = joinMode ?: group.joinMode,
                version = newVer,
            )
            persist { c ->
                c.update(
                    "UPDATE dir_groups SET name = ?, visibility = ?, join_mode = ?, version = ? WHERE id = ?",
                    updated.name, updated.visibility.name, updated.joinMode.name, newVer, id,
                )
            }
            groups[id] = updated
            updated
        }

    fun deleteGroup(id: String, actor: DirectoryActor) {
        lock.write {
            val group = groups[id] ?: throw DirectoryNotFoundException("Group '$id' not found")
            requireManager(group, actor, "delete")
            val v = bumpVersionLocked()
            recordTombstoneLocked("group", id, v)
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
        val newVer = bumpVersionLocked()
        val updated = group.copy(adminDeviceIds = admins, memberDeviceIds = newMembers, version = newVer)
        persist { c ->
            c.update("UPDATE dir_groups SET admin_ids = ?, version = ? WHERE id = ?", encodeList(admins), newVer, id)
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

    /** A2 helper: groups of hidden departments are listed only to insiders/members/admins. */
    fun listVisibleGroupsWithPrivacy(actor: DirectoryActor): List<GroupCharter> = lock.read {
        groups.values.filter { g -> canSee(g, actor) && DirectoryVisibility.canSeeGroup(g, actor, this) }
    }

    /**
     * Org-wide people search for `GET /directory/people` (A2 §3.3.1): every member
     * [DirectoryVisibility.canListMember] allows, filtered by [query] (case-insensitive
     * contains on displayName, `%`/`_` escaped by the caller contract) and optional
     * [departmentId], ordered by `(displayNameFolded, deviceId)`.
     */
    fun searchPeople(actor: DirectoryActor, query: String, departmentId: String?, limit: Int): List<DepartmentMember> = lock.read {
        val q = query.trim().lowercase()
        members.values
            .filter { DirectoryVisibility.canListMember(actor, it, this) }
            .filter { departmentId.isNullOrBlank() || it.departmentId == departmentId }
            .filter { q.isEmpty() || it.displayName.lowercase().contains(q) }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.deviceId }))
            .take(limit.coerceIn(1, 500))
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
        val newVer = bumpVersionLocked()
        val stored = member.copy(
            deviceId = member.deviceId.trim(),
            departmentId = member.departmentId.trim(),
            displayName = member.displayName.trim().take(128),
            version = newVer,
        )
        persist { c ->
            c.update(
                "INSERT INTO dir_members(device_id, department_id, display_name, role, subject_public_key, version) " +
                    "VALUES(?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET department_id = excluded.department_id, " +
                    "display_name = excluded.display_name, role = excluded.role, subject_public_key = excluded.subject_public_key, version = excluded.version",
                stored.deviceId, stored.departmentId, stored.displayName, stored.role.name, stored.subjectPublicKey, newVer,
            )
        }
        members[stored.deviceId] = stored

        if (statementSigner != null && !stored.subjectPublicKey.isNullOrBlank()) {
            val roleStr = when (stored.role) {
                MemberRole.ORG_ADMIN -> HubRoles.ADMIN
                MemberRole.DEPT_ADMIN -> HubRoles.DEPT_ADMIN
                MemberRole.MEMBER -> HubRoles.MEMBER
            }
            val res = statementSigner?.sign(
                deviceId = stored.deviceId,
                subjectPublicKey = stored.subjectPublicKey,
                departmentId = stored.departmentId,
                role = roleStr,
                nowMs = clock(),
                serial = newVer,
            )
            if (res != null) {
                val (statement, delegationSerial) = res
                memberStatements[stored.deviceId] = statement
                persist { c -> insertMemberStatement(c, statement, delegationSerial, newVer) }
            }
        }

        stored
    }

    fun removeDepartmentMember(departmentId: String, deviceId: String) {
        lock.write {
            val existing = members[deviceId]
            if (existing != null && existing.departmentId == departmentId) {
                val v = bumpVersionLocked()
                recordTombstoneLocked("member", deviceId, v)
                persist { c ->
                    c.update("DELETE FROM dir_members WHERE device_id = ?", deviceId)
                    c.update("DELETE FROM org_member_statement WHERE device_id = ?", deviceId)
                }
                members.remove(deviceId)
                memberStatements.remove(deviceId)
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
            val v = bumpVersionLocked()
            recordTombstoneLocked("member", deviceId, v)
            persist { c ->
                c.update("DELETE FROM dir_members WHERE device_id = ?", deviceId)
                c.update("DELETE FROM org_member_statement WHERE device_id = ?", deviceId)
                c.update("DELETE FROM dir_group_members WHERE device_id = ?", deviceId)
                c.update("DELETE FROM dir_join_requests WHERE device_id = ? AND status = 'PENDING'", deviceId)
                c.update("DELETE FROM dir_preauth WHERE device_id = ?", deviceId)
                for (g in groups.values.filter { deviceId in it.adminDeviceIds }) {
                    c.update("UPDATE dir_groups SET admin_ids = ?, version = ? WHERE id = ?", encodeList(g.adminDeviceIds - deviceId), v, g.id)
                }
                for (d in departments.values.filter { deviceId in it.adminDeviceIds }) {
                    c.update("UPDATE dir_departments SET admin_ids = ?, version = ? WHERE id = ?", encodeList(d.adminDeviceIds - deviceId), v, d.id)
                }
            }
            members.remove(deviceId)
            memberStatements.remove(deviceId)
            for ((id, g) in groups.entries.toList()) {
                if (deviceId in g.memberDeviceIds || deviceId in g.adminDeviceIds) {
                    groups[id] = g.copy(
                        memberDeviceIds = g.memberDeviceIds - deviceId,
                        adminDeviceIds = g.adminDeviceIds - deviceId,
                        version = v,
                    )
                }
            }
            for ((id, d) in departments.entries.toList()) {
                if (deviceId in d.adminDeviceIds) departments[id] = d.copy(adminDeviceIds = d.adminDeviceIds - deviceId, version = v)
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
        val v = bumpVersionLocked()
        val updated = group.copy(
            memberDeviceIds = group.memberDeviceIds.filter { it != deviceId },
            adminDeviceIds = group.adminDeviceIds.filter { it != deviceId },
            version = v,
        )
        persist { c ->
            c.update("DELETE FROM dir_group_members WHERE group_id = ? AND device_id = ?", groupId, deviceId)
            c.update("UPDATE dir_groups SET admin_ids = ?, version = ? WHERE id = ?", encodeList(updated.adminDeviceIds), v, groupId)
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
        val v = bumpVersionLocked()
        val updated = group.copy(memberDeviceIds = group.memberDeviceIds + deviceId, version = v)
        persist { c ->
            insertGroupMember(c, group.id, deviceId)
            c.update("UPDATE dir_groups SET version = ? WHERE id = ?", v, group.id)
        }
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
        c.update("INSERT INTO dir_departments(id, name, admin_ids, hidden, version) VALUES(?,?,?,?,?)", d.id, d.name, encodeList(d.adminDeviceIds), if (d.hidden) 1 else 0, d.version)
    }

    private fun insertGroup(c: Connection, g: GroupCharter, now: Long) {
        c.update(
            "INSERT INTO dir_groups(id, name, department_id, visibility, join_mode, admin_ids, created_at, version) VALUES(?,?,?,?,?,?,?,?)",
            g.id, g.name, g.departmentId, g.visibility.name, g.joinMode.name, encodeList(g.adminDeviceIds), now, g.version,
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

    private fun insertMemberStatement(c: Connection, s: OrgMemberStatement, delegationSerial: Long, version: Long) {
        c.update(
            "INSERT INTO org_member_statement(device_id, statement_serial, issued_at_ms, statement_bytes, signature_bytes, delegation_serial, version) " +
                "VALUES(?,?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET " +
                "statement_serial = excluded.statement_serial, issued_at_ms = excluded.issued_at_ms, " +
                "statement_bytes = excluded.statement_bytes, signature_bytes = excluded.signature_bytes, " +
                "delegation_serial = excluded.delegation_serial, version = excluded.version",
            s.deviceId, s.statementSerial, s.issuedAtMs, s.canonicalBytes(), s.issuerSignature, delegationSerial, version,
        )
    }

    /**
     * Queries incremental or full directory synchronization changes for [actor] (Plan B, ADR-134).
     */
    fun getSyncChanges(
        since: Long,
        cursor: String?,
        limit: Int,
        actor: DirectoryActor,
        activeDelegationChain: List<DelegationCert> = emptyList(),
        offlineGraceDays: Int = 30,
        requestedEpoch: String? = null,
    ): DirectorySyncDto = lock.read {
        val now = clock()
        val isFullResync = since > currentVersion || (requestedEpoch != null && requestedEpoch != epochId) ||
            (since in 1 until prunedThroughVersion)
        val effectiveSince = if (isFullResync) 0L else since.coerceAtLeast(0L)

        // Filter departments visible to actor
        val visibleDepts = departments.values.filter { DirectoryVisibility.canSeeDepartment(it, actor) }
        val changedDepts = visibleDepts.filter { it.version > effectiveSince }.map {
            DepartmentSyncDto(
                id = it.id,
                name = it.name,
                headDeviceIds = it.adminDeviceIds,
                memberCount = members.values.count { m -> m.departmentId == it.id },
                hidden = it.hidden,
            )
        }
        val removedDepts = if (isFullResync) emptyList() else tombstones.values
            .filter { it.entityType == "department" && it.deletedAtVersion > effectiveSince }
            .map { it.entityId }

        // Filter members visible to actor (A2: hidden-department rows never reach outsiders).
        val visibleMembers = members.values.filter { DirectoryVisibility.canListMember(actor, it, this) }
        val allChangedMembers = visibleMembers
            .filter { it.version > effectiveSince }
            .sortedWith(compareBy({ it.version }, { it.deviceId }))

        // Cursor: base64url("$version_$deviceId"). A cursor that does not parse is a client bug, never a silent restart.
        val cursorPair: Pair<Long, String>? = cursor?.let { raw ->
            runCatching {
                val decoded = String(Base64.getUrlDecoder().decode(raw))
                val parts = decoded.split("_", limit = 2)
                parts[0].toLong() to parts[1].also { require(it.isNotEmpty()) }
            }.getOrElse { throw InvalidDirectoryCursorException() }
        }

        val pagedMembers = if (cursorPair != null) {
            allChangedMembers.filter { m ->
                m.version > cursorPair.first || (m.version == cursorPair.first && m.deviceId > cursorPair.second)
            }
        } else {
            allChangedMembers
        }

        val pageSize = limit.coerceIn(1, 1000)
        val membersOnPage = pagedMembers.take(pageSize)
        val hasMore = pagedMembers.size > pageSize
        val nextCursor = if (hasMore && membersOnPage.isNotEmpty()) {
            val last = membersOnPage.last()
            Base64.getUrlEncoder().withoutPadding().encodeToString("${last.version}_${last.deviceId}".toByteArray())
        } else null

        unsignedOnLastPage = membersOnPage.count { memberStatements[it.deviceId] == null }
        val memberDtos = membersOnPage.mapNotNull { m ->
            val statement = memberStatements[m.deviceId]
            if (statement != null) {
                DirectoryMemberSyncDto(
                    deviceId = m.deviceId,
                    displayName = m.displayName,
                    departmentId = m.departmentId,
                    role = when (m.role) {
                        MemberRole.ORG_ADMIN -> HubDirectoryValues.ROLE_ORG_ADMIN
                        MemberRole.DEPT_ADMIN -> HubDirectoryValues.ROLE_DEPT_ADMIN
                        MemberRole.MEMBER -> HubDirectoryValues.ROLE_MEMBER
                    },
                    statement = statement,
                    online = isOnline(m.deviceId),
                )
            } else null
        }

        val removedMembers = if (isFullResync) emptyList() else tombstones.values
            .filter { it.entityType == "member" && it.deletedAtVersion > effectiveSince }
            .map { it.entityId }

        DirectorySyncDto(
            directoryEpoch = epochId,
            version = currentVersion,
            fromVersion = since,
            fullResync = isFullResync,
            nextCursor = nextCursor,
            serverTimeMs = now,
            offlineGraceDays = offlineGraceDays,
            delegationChain = activeDelegationChain,
            members = memberDtos,
            removedDeviceIds = if (cursorPair == null) removedMembers else emptyList(),
            departments = if (cursorPair == null) changedDepts else emptyList(),
            removedDepartmentIds = if (cursorPair == null) removedDepts else emptyList(),
            // B4: every sync that ends (no next page) carries the FULL current set of the caller's group rosters, freshly signed.
            // The client replaces its stored set, so a group the device left simply is not in it (removedGroupIds stays empty).
            groupRosters = if (nextCursor == null) signedRostersFor(actor, now, offlineGraceDays) else emptyList(),
            removedGroupIds = emptyList(),
        )
    }

    /**
     * The signed roster statements of the groups [actor] belongs to (member or admin). A group above
     * [GroupRosterStatement.MAX_MEMBERS] gets none (plan section 10.2: "not supported offline"). Needs [rosterSigner].
     */
    private fun signedRostersFor(actor: DirectoryActor, nowMs: Long, offlineGraceDays: Int): List<GroupRosterDto> {
        val signer = rosterSigner ?: return emptyList()
        val ttlMs = offlineGraceDays.coerceIn(1, 365) * 86_400_000L
        var skipped = 0
        val out = groups.values
            .filter { actor.deviceId in it.memberDeviceIds || actor.deviceId in it.adminDeviceIds }
            .sortedBy { it.id }
            .take(MAX_ROSTERS_PER_SYNC)
            .mapNotNull { g ->
                val members = (g.memberDeviceIds + g.adminDeviceIds).distinct().sorted()
                if (members.size > GroupRosterStatement.MAX_MEMBERS) {
                    skipped++
                    return@mapNotNull null
                }
                val unsigned = GroupRosterStatement(
                    groupId = g.id,
                    groupName = g.name,
                    departmentId = g.departmentId,
                    version = g.version,
                    memberDeviceIds = members,
                    adminDeviceIds = g.adminDeviceIds.distinct().sorted(),
                    issuedAtMs = nowMs,
                    validUntilMs = nowMs + ttlMs,
                )
                val signed = signer.sign(unsigned) ?: return@mapNotNull null
                GroupRosterDto(groupId = g.id, version = g.version, statement = HubRestCodec.encode(signed), validUntilMs = signed.validUntilMs)
            }
        rostersSkippedTooLarge = skipped
        return out
    }

    private fun load(database: ServerDatabase) {
        database.withConnection { c ->
            val hasHidden = runCatching {
                c.query("SELECT hidden FROM dir_departments LIMIT 1") { rs -> rs.getInt(1) }
                true
            }.getOrDefault(false)
            c.query("SELECT * FROM dir_departments ORDER BY rowid") { rs ->
                Department(
                    rs.getString("id"),
                    rs.getString("name"),
                    decodeList(rs.getString("admin_ids")),
                    rs.getLong("version"),
                    hidden = if (hasHidden) runCatching { rs.getInt("hidden") }.getOrDefault(0) != 0 else false,
                )
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
                    version = rs.getLong("version"),
                )
            }.forEach { groups[it.id] = it }
            c.query("SELECT * FROM dir_members ORDER BY rowid") { rs ->
                DepartmentMember(
                    deviceId = rs.getString("device_id"),
                    departmentId = rs.getString("department_id"),
                    displayName = rs.getString("display_name"),
                    role = MemberRole.valueOf(rs.getString("role")),
                    subjectPublicKey = rs.getString("subject_public_key"),
                    version = rs.getLong("version"),
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

            // Load directory_state
            c.query("SELECT current_version, epoch_id FROM directory_state WHERE id = 1") { rs ->
                rs.getLong("current_version") to rs.getString("epoch_id")
            }.firstOrNull().let { state ->
                if (state != null) {
                    currentVersion = state.first
                    epochId = state.second
                } else {
                    currentVersion = 0L
                    epochId = UUID.randomUUID().toString()
                    c.update(
                        "INSERT OR REPLACE INTO directory_state(id, current_version, epoch_id, last_bumped_at_ms) VALUES(1, ?, ?, ?)",
                        currentVersion, epochId, clock(),
                    )
                }
            }

            // Load directory_tombstone
            c.query("SELECT entity_type, entity_id, deleted_at_version, deleted_at_ms FROM directory_tombstone") { rs ->
                DirectoryTombstone(
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    deletedAtVersion = rs.getLong("deleted_at_version"),
                    deletedAtMs = rs.getLong("deleted_at_ms"),
                )
            }.forEach {
                tombstones[it.entityType to it.entityId] = it
                if (it.entityType == PRUNE_MARK) prunedThroughVersion = it.deletedAtVersion
            }

            // Load org_member_statement
            c.query("SELECT device_id, statement_serial, issued_at_ms, signature_bytes FROM org_member_statement") { rs ->
                val devId = rs.getString("device_id")
                val member = members[devId]
                if (member != null && !member.subjectPublicKey.isNullOrBlank()) {
                    OrgMemberStatement(
                        deviceId = devId,
                        subjectPublicKey = member.subjectPublicKey,
                        departmentId = member.departmentId,
                        role = when (member.role) {
                            MemberRole.ORG_ADMIN -> HubRoles.ADMIN
                            MemberRole.DEPT_ADMIN -> HubRoles.DEPT_ADMIN
                            MemberRole.MEMBER -> HubRoles.MEMBER
                        },
                        issuedAtMs = rs.getLong("issued_at_ms"),
                        statementSerial = rs.getLong("statement_serial"),
                        issuerSignature = rs.getString("signature_bytes"),
                    )
                } else null
            }.filterNotNull().forEach { memberStatements[it.deviceId] = it }
        }
    }

    /** Presence view of a hub with no relay attached (tests, the console before the relay starts). */
    object NoPresence : PresenceView {
        override fun isOnline(deviceId: String): Boolean = false
        override fun onlineDeviceIds(): Set<String> = emptySet()
    }

    companion object {
        const val COMPONENT: String = "directory"

        /** A device belongs to few hub groups; a sync carries at most this many rosters (bounds the response and the signing work). */
        const val MAX_ROSTERS_PER_SYNC: Int = 256

        /** Tombstones older than this are pruned; a client further behind gets a full resync (90 days). */
        const val TOMBSTONE_RETENTION_MS: Long = 90L * 24 * 60 * 60 * 1000

        /** Reserved tombstone type that stores [prunedThroughVersion]; never reported to clients. */
        const val PRUNE_MARK: String = "_pruned"

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
            Migration(2, "directory: versioning, member statements, tombstones, and state for offline org trust") { conn ->
                conn.createStatement().use { st ->
                    st.execute("ALTER TABLE dir_departments ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
                    st.execute("ALTER TABLE dir_groups ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
                    st.execute("ALTER TABLE dir_members ADD COLUMN version INTEGER NOT NULL DEFAULT 0")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS org_member_statement(" +
                            "device_id TEXT PRIMARY KEY, " +
                            "statement_serial INTEGER NOT NULL, " +
                            "issued_at_ms INTEGER NOT NULL, " +
                            "statement_bytes BLOB, " +
                            "signature_bytes TEXT NOT NULL, " +
                            "delegation_serial INTEGER NOT NULL, " +
                            "version INTEGER NOT NULL DEFAULT 0" +
                        ")",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS directory_state(" +
                            "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                            "current_version INTEGER NOT NULL DEFAULT 0, " +
                            "epoch_id TEXT NOT NULL, " +
                            "last_bumped_at_ms INTEGER NOT NULL" +
                        ")",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS directory_tombstone(" +
                            "entity_type TEXT NOT NULL, " +
                            "entity_id TEXT NOT NULL, " +
                            "deleted_at_version INTEGER NOT NULL, " +
                            "deleted_at_ms INTEGER NOT NULL, " +
                            "PRIMARY KEY(entity_type, entity_id)" +
                        ")",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_directory_tombstone_ver ON directory_tombstone(deleted_at_version)")
                }
            },
            Migration(3, "directory: hidden departments for option C privacy (Plan A2)") { conn ->
                conn.createStatement().use { st ->
                    st.execute("ALTER TABLE dir_departments ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_dir_members_dept_name ON dir_members(department_id, display_name)")
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
