package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupSecretEntity

/**
 * Data access object for group secrets (GM-2).
 */
@Dao
public interface GroupSecretDao {

    @Upsert
    public suspend fun upsert(entity: GroupSecretEntity)

    @Query("SELECT * FROM group_secret WHERE groupId = :groupId AND epoch = :epoch")
    public suspend fun getByGroupAndEpoch(groupId: String, epoch: Long): GroupSecretEntity?

    @Query("SELECT * FROM group_secret WHERE groupId = :groupId ORDER BY epoch DESC LIMIT 1")
    public suspend fun getLatestForGroup(groupId: String): GroupSecretEntity?

    @Query("SELECT * FROM group_secret WHERE groupId = :groupId ORDER BY epoch ASC")
    public suspend fun getAllForGroup(groupId: String): List<GroupSecretEntity>

    @Query("DELETE FROM group_secret WHERE groupId = :groupId")
    public suspend fun deleteForGroup(groupId: String)

    @Query("SELECT * FROM group_secret")
    public suspend fun getAll(): List<GroupSecretEntity>
}
