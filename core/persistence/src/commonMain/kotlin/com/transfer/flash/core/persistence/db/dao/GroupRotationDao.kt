package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupRotationEntity

/**
 * Data access object for group rotation notices (GM-6).
 */
@Dao
public interface GroupRotationDao {

    @Upsert
    public suspend fun upsert(entity: GroupRotationEntity)

    @Query("SELECT * FROM group_rotation WHERE groupId = :groupId AND newEpoch = :newEpoch")
    public suspend fun getByGroupAndEpoch(groupId: String, newEpoch: Long): GroupRotationEntity?

    @Query("SELECT * FROM group_rotation WHERE groupId = :groupId ORDER BY newEpoch DESC, rotationId ASC LIMIT 1")
    public suspend fun getLatestForGroup(groupId: String): GroupRotationEntity?

    @Query("SELECT * FROM group_rotation WHERE groupId = :groupId ORDER BY newEpoch ASC")
    public suspend fun getAllForGroup(groupId: String): List<GroupRotationEntity>

    @Query("DELETE FROM group_rotation WHERE groupId = :groupId")
    public suspend fun deleteForGroup(groupId: String)

    @Query("SELECT * FROM group_rotation")
    public suspend fun getAll(): List<GroupRotationEntity>
}
