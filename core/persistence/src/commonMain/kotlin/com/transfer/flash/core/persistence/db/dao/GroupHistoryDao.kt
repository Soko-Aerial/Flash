package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupHistoryStateEntity
import com.transfer.flash.core.persistence.db.entity.GroupSyncWatermarkEntity

/**
 * Device-local group history state and catch-up watermarks (ADR-100).
 */
@Dao
public interface GroupHistoryDao {

    @Upsert
    public suspend fun upsertState(entity: GroupHistoryStateEntity)

    @Query("SELECT * FROM group_history_state WHERE groupId = :groupId")
    public suspend fun state(groupId: String): GroupHistoryStateEntity?

    @Query("DELETE FROM group_history_state WHERE groupId = :groupId")
    public suspend fun deleteState(groupId: String)

    @Upsert
    public suspend fun upsertWatermark(entity: GroupSyncWatermarkEntity)

    @Query("SELECT * FROM group_sync_watermark WHERE groupId = :groupId AND holderId = :holderId")
    public suspend fun watermark(groupId: String, holderId: String): GroupSyncWatermarkEntity?

    @Query("SELECT * FROM group_sync_watermark WHERE groupId = :groupId")
    public suspend fun watermarks(groupId: String): List<GroupSyncWatermarkEntity>

    @Query("DELETE FROM group_sync_watermark WHERE groupId = :groupId")
    public suspend fun deleteWatermarks(groupId: String)
}
