package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.HubGroupRosterEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity

/**
 * Accessor for the offline organization directory cache (Plan B, ADR-134).
 */
@Dao
public interface OrgDirectoryDao {

    @Upsert
    public suspend fun upsertMembers(members: List<OrgDirectoryMemberEntity>)

    @Query("DELETE FROM org_directory_member WHERE deviceId IN (:deviceIds)")
    public suspend fun deleteMembers(deviceIds: List<String>)

    @Query("SELECT * FROM org_directory_member WHERE deviceId = :deviceId")
    public suspend fun findMemberByDeviceId(deviceId: String): OrgDirectoryMemberEntity?

    @Query("SELECT * FROM org_directory_member WHERE subjectPublicKeyFingerprint = :fingerprint")
    public suspend fun findMemberByFingerprint(fingerprint: String): OrgDirectoryMemberEntity?

    @Query("SELECT * FROM org_directory_member WHERE displayNameFolded LIKE :prefix || '%' OR displayNameFolded LIKE '% ' || :prefix || '%' ORDER BY displayName ASC LIMIT :limit")
    public suspend fun searchMembers(prefix: String, limit: Int): List<OrgDirectoryMemberEntity>

    @Query("SELECT * FROM org_directory_member WHERE departmentId = :departmentId ORDER BY displayName ASC")
    public suspend fun listMembersByDepartment(departmentId: String): List<OrgDirectoryMemberEntity>

    @Query("SELECT * FROM org_directory_member ORDER BY displayName ASC")
    public suspend fun listAllMembers(): List<OrgDirectoryMemberEntity>

    @Upsert
    public suspend fun upsertDepartments(departments: List<OrgDepartmentEntity>)

    @Query("DELETE FROM org_department WHERE id IN (:ids)")
    public suspend fun deleteDepartments(ids: List<String>)

    @Query("SELECT * FROM org_department ORDER BY name ASC")
    public suspend fun listDepartments(): List<OrgDepartmentEntity>

    @Query("SELECT * FROM org_sync_state WHERE id = 1")
    public suspend fun getSyncState(): OrgSyncStateEntity?

    @Upsert
    public suspend fun setSyncState(state: OrgSyncStateEntity)

    @Query("DELETE FROM org_directory_member")
    public suspend fun clearMembers()

    @Query("DELETE FROM org_department")
    public suspend fun clearDepartments()

    @Query("DELETE FROM org_sync_state")
    public suspend fun clearSyncState()

    @Query("SELECT * FROM hub_group_roster")
    public suspend fun allGroupRosters(): List<HubGroupRosterEntity>

    @Query("SELECT * FROM hub_group_roster WHERE groupId = :groupId")
    public suspend fun groupRoster(groupId: String): HubGroupRosterEntity?

    @Upsert
    public suspend fun upsertGroupRosters(rosters: List<HubGroupRosterEntity>)

    @Query("DELETE FROM hub_group_roster")
    public suspend fun clearGroupRosters()

    /** Replaces the whole stored roster set in one transaction (the sync's last page, or nothing). */
    @Transaction
    public suspend fun replaceGroupRosters(rosters: List<HubGroupRosterEntity>) {
        clearGroupRosters()
        if (rosters.isNotEmpty()) upsertGroupRosters(rosters)
    }

    @Transaction
    public suspend fun clearAll() {
        clearGroupRosters()
        clearMembers()
        clearDepartments()
        clearSyncState()
    }

    @Transaction
    public suspend fun applySyncBatch(
        fullResync: Boolean,
        membersToUpsert: List<OrgDirectoryMemberEntity>,
        memberIdsToDelete: List<String>,
        departmentsToUpsert: List<OrgDepartmentEntity>,
        departmentIdsToDelete: List<String>,
        newState: OrgSyncStateEntity,
    ) {
        if (fullResync) {
            clearMembers()
            clearDepartments()
            if (membersToUpsert.isNotEmpty()) {
                upsertMembers(membersToUpsert)
            }
            if (departmentsToUpsert.isNotEmpty()) {
                upsertDepartments(departmentsToUpsert)
            }
        } else {
            if (memberIdsToDelete.isNotEmpty()) {
                deleteMembers(memberIdsToDelete)
            }
            if (departmentIdsToDelete.isNotEmpty()) {
                deleteDepartments(departmentIdsToDelete)
            }
            val filteredMembers = if (membersToUpsert.isEmpty()) {
                emptyList()
            } else {
                membersToUpsert.filter { incoming ->
                    val existing = findMemberByDeviceId(incoming.deviceId)
                    existing == null || incoming.statementSerial >= existing.statementSerial
                }
            }
            if (filteredMembers.isNotEmpty()) {
                upsertMembers(filteredMembers)
            }
            if (departmentsToUpsert.isNotEmpty()) {
                upsertDepartments(departmentsToUpsert)
            }
        }
        setSyncState(newState)
    }

    /** [applySyncBatch] and the replacement of the stored hub group rosters in ONE transaction (Plan B B4). */
    @Transaction
    public suspend fun applySyncBatchWithRosters(
        fullResync: Boolean,
        membersToUpsert: List<OrgDirectoryMemberEntity>,
        memberIdsToDelete: List<String>,
        departmentsToUpsert: List<OrgDepartmentEntity>,
        departmentIdsToDelete: List<String>,
        newState: OrgSyncStateEntity,
        rosters: List<HubGroupRosterEntity>,
    ) {
        applySyncBatch(fullResync, membersToUpsert, memberIdsToDelete, departmentsToUpsert, departmentIdsToDelete, newState)
        replaceGroupRosters(rosters)
    }
}
