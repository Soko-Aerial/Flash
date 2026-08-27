package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

/** Aggregated reaction rows are last-write-wins per `(messageId, emoji)`. */
@Dao
public interface ReactionDao {

    @Upsert
    public suspend fun upsert(reaction: ReactionEntity)

    @Query("SELECT * FROM reactions WHERE messageId = :messageId ORDER BY emoji")
    public fun observeForMessage(messageId: String): Flow<List<ReactionEntity>>

    /**
     * All reaction rows for messages in one conversation, joined via the messages table (#7). Drives
     * the per-bubble reaction chips in the conversation flow without an N+1 per-message observe.
     */
    @Query(
        "SELECT r.* FROM reactions r INNER JOIN messages m ON r.messageId = m.localId " +
            "WHERE m.conversationId = :conversationId ORDER BY r.messageId, r.emoji",
    )
    public fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>>

    /** Single aggregated row for a `(messageId, emoji)` pair, or null if no one has reacted. */
    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND emoji = :emoji")
    public suspend fun get(messageId: String, emoji: String): ReactionEntity?

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND emoji = :emoji")
    public suspend fun remove(messageId: String, emoji: String)
}
