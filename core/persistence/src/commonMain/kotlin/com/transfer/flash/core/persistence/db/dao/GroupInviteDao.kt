package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupInviteEntity

/**
 * Data access object for group invites (GM-2).
 */
@Dao
public interface GroupInviteDao {

    @Upsert
    public suspend fun upsert(entity: GroupInviteEntity)

    @Query("SELECT * FROM group_invite WHERE groupId = :groupId")
    public suspend fun getByGroupId(groupId: String): GroupInviteEntity?

    @Query("SELECT * FROM group_invite WHERE state = :state ORDER BY acceptedAtMs DESC")
    public suspend fun getByState(state: String): List<GroupInviteEntity>

    @Query("UPDATE group_invite SET state = :state WHERE groupId = :groupId")
    public suspend fun updateState(groupId: String, state: String)

    @Query("DELETE FROM group_invite WHERE groupId = :groupId")
    public suspend fun delete(groupId: String)

    @Query("SELECT * FROM group_invite")
    public suspend fun getAll(): List<GroupInviteEntity>
}
