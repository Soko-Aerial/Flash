package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface GroupMemberDao {
    @Upsert
    public suspend fun upsert(member: GroupMemberEntity)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY joinedAt, deviceId")
    public fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isActive = 1 ORDER BY joinedAt, deviceId")
    public suspend fun activeMembers(groupId: String): List<GroupMemberEntity>

    /** Every row of the group, leave tombstones included (ADR-044 V1a: `State` carries tombstones). */
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY joinedAt, deviceId")
    public suspend fun allMembers(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND deviceId = :deviceId LIMIT 1")
    public suspend fun member(groupId: String, deviceId: String): GroupMemberEntity?

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId AND isActive = 1")
    public suspend fun activeCount(groupId: String): Int

    /** F3: the groups this device is an active member of — the SyncRequest fan-out set. */
    @Query(
        "SELECT DISTINCT groupId FROM group_members " +
            "WHERE deviceId = :deviceId AND isActive = 1",
    )
    public suspend fun activeGroupIdsFor(deviceId: String): List<String>

    /**
     * Update display name for a device across every legacy group it belongs to (device-name change).
     * A v2 row (`certSig` set) is skipped: its name is the owner-signed label, and rewriting it would
     * make the stored cert fail verification when it is relayed to another member.
     */
    @Query("UPDATE group_members SET displayName = :newName WHERE deviceId = :deviceId AND certSig IS NULL")
    public suspend fun updateMemberDisplayName(deviceId: String, newName: String)
}
