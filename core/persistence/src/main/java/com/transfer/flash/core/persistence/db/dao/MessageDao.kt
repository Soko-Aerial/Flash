package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Message history access. Inserts are idempotent (IGNORE on [MessageEntity.localId]).
 * History is keyset-paginated on the composite cursor `(sentAt DESC, localId DESC)` so pages
 * are stable under concurrent inserts and cost an index seek instead of OFFSET.
 */
@Dao
public interface MessageDao {

    /** @return row id of the inserted row, or -1 when a duplicate [localId] was ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(message: MessageEntity): Long

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedAt IS NULL " +
            "ORDER BY sentAt DESC, localId DESC",
    )
    public fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    /**
     * Keyset page strictly before `(cursorSentAt, cursorLocalId)` in descending
     * `(sentAt, localId)` order. For the first page pass `cursorSentAt = Long.MAX_VALUE` and a
     * sentinel string greater than any real localId (e.g. `"\uFFFF"`).
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND deletedAt IS NULL AND " +
            "(sentAt < :cursorSentAt OR (sentAt = :cursorSentAt AND localId < :cursorLocalId)) " +
            "ORDER BY sentAt DESC, localId DESC LIMIT :limit",
    )
    public suspend fun historyBefore(
        conversationId: String,
        cursorSentAt: Long,
        cursorLocalId: String,
        limit: Int,
    ): List<MessageEntity>

    /** Single row by client UUID, or null if absent. Used by the outbox drain to recover the
     *  authoritative conversationId/sentAt/text a message was composed with (the outbox row itself
     *  carries only the payload), so a message is never re-routed to whatever conversation happens
     *  to be active when the drain fires. */
    @Query("SELECT * FROM messages WHERE localId = :localId LIMIT 1")
    public suspend fun getByLocalId(localId: String): MessageEntity?

    /** True when any message row already references [transferId] as an attachment. Used to keep
     *  inbound-attachment ingestion idempotent (a replayed transfer start must not double-insert). */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE attachmentTransferId = :transferId)")
    public suspend fun existsAttachment(transferId: String): Boolean

    @Query("UPDATE messages SET status = :status WHERE localId = :localId")
    public suspend fun updateStatus(localId: String, status: String)

    /**
     * Read-receipt absorption (C6.3): when a peer reports it has read up to [upToMessageId], mark
     * OUR own outbound messages in that thread (`senderId = :selfId`) as READ up to that message's
     * timestamp. Keyed by `sentAt <= (the read message's sentAt)` so it is monotonic and idempotent;
     * already-READ rows are skipped. Inbound rows (the peer's messages) are never touched.
     */
    @Query(
        "UPDATE messages SET status = 'READ' WHERE conversationId = :conversationId " +
            "AND senderId = :selfId AND status != 'READ' AND sentAt <= " +
            "(SELECT sentAt FROM messages WHERE localId = :upToMessageId)",
    )
    public suspend fun markReadUpTo(conversationId: String, selfId: String, upToMessageId: String)

    /** Newest message localId in a conversation (composite cursor head), or null if empty. Used by
     *  the chat-list bulk "mark read" to advance `lastReadCursor` to the latest message. */
    @Query(
        "SELECT localId FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY sentAt DESC, localId DESC LIMIT 1",
    )
    public suspend fun newestLocalId(conversationId: String): String?

    /**
     * Per-conversation unread counts (C6.x badge). A message counts as unread when it is inbound
     * (`senderId != :selfId`), not tombstoned, and newer than the conversation's `lastReadCursor`
     * (a message localId). The cursor is resolved to its `sentAt` via the correlated subquery;
     * a null cursor means nothing has been read yet, so every inbound message counts. Grouped so
     * one observation feeds the whole chat list instead of one flow per row.
     */
    @Query(
        "SELECT m.conversationId AS conversationId, COUNT(*) AS unread FROM messages m " +
            "JOIN conversations c ON c.id = m.conversationId " +
            "WHERE m.senderId != :selfId AND m.deletedAt IS NULL AND (" +
            "c.lastReadCursor IS NULL OR " +
            "m.sentAt > (SELECT sentAt FROM messages WHERE localId = c.lastReadCursor)" +
            ") GROUP BY m.conversationId",
    )
    public fun observeUnreadCounts(selfId: String): Flow<List<ConversationUnread>>

    /**
     * Per-conversation latest-message preview (chat-list preview line + content search). For each
     * conversation this yields the `text` of its newest non-tombstoned message: SQLite's
     * bare-column rule means the `text`/`sentAt` selected alongside `MAX(sentAt)` come from that
     * same newest row. Conversations with no live messages simply produce no row.
     */
    @Query(
        "SELECT conversationId AS conversationId, text AS previewText, MAX(sentAt) AS sentAt " +
            "FROM messages WHERE deletedAt IS NULL GROUP BY conversationId",
    )
    public fun observeLatestPreviews(): Flow<List<ConversationPreview>>

    @Query("UPDATE messages SET editedAt = :editedAt WHERE localId = :localId")
    public suspend fun markEdited(localId: String, editedAt: Long)

    /** Tombstone only — never deletes the row (history pagination must stay stable). */
    @Query("UPDATE messages SET deletedAt = :deletedAt WHERE localId = :localId")
    public suspend fun markDeleted(localId: String, deletedAt: Long)

    /** Hard-delete every message of the given conversations. Used only by the chat-list bulk
     *  delete (the whole thread is going away), not by per-message tombstoning. */
    @Query("DELETE FROM messages WHERE conversationId IN (:ids)")
    public suspend fun deleteByConversations(ids: List<String>)

    /**
     * Full-history content search (UI-search): case-insensitive substring match over message
     * `text` across ALL conversations, newest first. Tombstoned rows are excluded. Voice-meta
     * marker rows (`vmsg:` blobs) never match real queries, so no extra filter is needed.
     */
    @Query(
        "SELECT * FROM messages WHERE deletedAt IS NULL AND text LIKE '%' || :query || '%' " +
            "ORDER BY sentAt DESC, localId DESC LIMIT :limit",
    )
    public suspend fun searchMessages(query: String, limit: Int): List<MessageEntity>
}
