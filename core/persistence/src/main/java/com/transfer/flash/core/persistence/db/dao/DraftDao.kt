package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface DraftDao {

    @Upsert
    public suspend fun upsert(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE conversationId = :conversationId")
    public fun observeDraft(conversationId: String): Flow<DraftEntity?>

    @Query("DELETE FROM drafts WHERE conversationId = :conversationId")
    public suspend fun clear(conversationId: String)
}
