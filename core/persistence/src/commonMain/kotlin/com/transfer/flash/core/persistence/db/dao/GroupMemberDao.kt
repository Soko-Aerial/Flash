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

    /** Update display name for a device across every group it belongs to (device-name change). */
    @Query("UPDATE group_members SET displayName = :newName WHERE deviceId = :deviceId")
    public suspend fun updateMemberDisplayName(deviceId: String, newName: String)
}
