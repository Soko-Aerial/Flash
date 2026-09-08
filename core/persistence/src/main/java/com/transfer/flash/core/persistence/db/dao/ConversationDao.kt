package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface ConversationDao {

    @Upsert
    public suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, sortOrder DESC")
    public fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    public suspend fun get(id: String): ConversationEntity?

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    public suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    public suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    public suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE conversations SET lastReadCursor = :cursor WHERE id = :id")
    public suspend fun updateLastReadCursor(id: String, cursor: String)

    /** Hard-delete conversations (chat-list bulk delete, UI selection mode). Messages are removed
     *  separately via [MessageDao.deleteByConversations] so both tables stay consistent. */
    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    public suspend fun deleteConversations(ids: List<String>)
}
