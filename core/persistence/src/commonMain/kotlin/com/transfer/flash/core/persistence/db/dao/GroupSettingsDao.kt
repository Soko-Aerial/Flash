package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupSettingsEntity

/**
 * Data access object for signed group settings (ADR-074, GM-9).
 */
@Dao
public interface GroupSettingsDao {

    @Upsert
    public suspend fun upsert(entity: GroupSettingsEntity)

    @Query("SELECT * FROM group_settings WHERE groupId = :groupId")
    public suspend fun getByGroupId(groupId: String): GroupSettingsEntity?

    @Query("DELETE FROM group_settings WHERE groupId = :groupId")
    public suspend fun deleteForGroup(groupId: String)

    @Query("SELECT * FROM group_settings")
    public suspend fun getAll(): List<GroupSettingsEntity>
}
