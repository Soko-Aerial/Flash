package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupPreferencesEntity

/**
 * Data access object for device-local group preferences (ADR-074, GM-9).
 */
@Dao
public interface GroupPreferencesDao {

    @Upsert
    public suspend fun upsert(entity: GroupPreferencesEntity)

    @Query("SELECT * FROM group_preferences WHERE groupId = :groupId")
    public suspend fun getByGroupId(groupId: String): GroupPreferencesEntity?

    @Query("DELETE FROM group_preferences WHERE groupId = :groupId")
    public suspend fun deleteForGroup(groupId: String)

    @Query("SELECT * FROM group_preferences")
    public suspend fun getAll(): List<GroupPreferencesEntity>
}
