package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.MessagePinEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface MessagePinDao {

    @Upsert
    public suspend fun upsert(pin: MessagePinEntity)

    /** Message ids pinned in [conversationId], newest pin first. */
    @Query("SELECT messageId FROM message_pins WHERE conversationId = :conversationId ORDER BY pinnedAt DESC, messageId")
    public fun observePinnedIds(conversationId: String): Flow<List<String>>

    @Query("DELETE FROM message_pins WHERE conversationId = :conversationId AND messageId = :messageId")
    public suspend fun unpin(conversationId: String, messageId: String)

    @Query("DELETE FROM message_pins WHERE conversationId = :conversationId")
    public suspend fun clearConversation(conversationId: String)
}
