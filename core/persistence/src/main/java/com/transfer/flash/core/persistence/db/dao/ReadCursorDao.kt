package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read cursors only ever move forward.
 *
 * [advanceFurthest] is implemented as a transactional read-compare-write rather than a SQL
 * CASE upsert because:
 *  1. the monotonicity rule ("newer wins in (upToSentAt, upToMessageId) order") is expressed
 *     once, explicitly, in Kotlin where it can be unit-tested directly (C1.8);
 *  2. SQLite allows a single writer at a time, and Room runs the whole method inside one
 *     transaction on the write connection, so concurrent advances serialize;
 *  3. seeding via IGNORE-insert inside the same transaction makes the absent-row case race-free.
 */
@Dao
public interface ReadCursorDao {

    @Query(
        "SELECT * FROM read_cursors " +
            "WHERE conversationId = :conversationId AND memberId = :memberId",
    )
    public suspend fun get(conversationId: String, memberId: String): ReadCursorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertSeed(cursor: ReadCursorEntity)

    @Query(
        "UPDATE read_cursors SET upToMessageId = :upToMessageId, upToSentAt = :upToSentAt " +
            "WHERE conversationId = :conversationId AND memberId = :memberId",
    )
    public suspend fun updateCursor(
        conversationId: String,
        memberId: String,
        upToMessageId: String,
        upToSentAt: Long,
    )

    /**
     * Advances the member's cursor to `(upToMessageId, upToSentAt)` only if that position is
     * strictly further than the stored one; older or equal positions are no-ops.
     */
    @Transaction
    public suspend fun advanceFurthest(
        conversationId: String,
        memberId: String,
        upToMessageId: String,
        upToSentAt: Long,
    ) {
        insertSeed(ReadCursorEntity(conversationId, memberId, upToMessageId, upToSentAt))
        val current = get(conversationId, memberId) ?: return
        val candidateFurther =
            upToSentAt > current.upToSentAt ||
                (upToSentAt == current.upToSentAt && upToMessageId > current.upToMessageId)
        if (candidateFurther) {
            updateCursor(conversationId, memberId, upToMessageId, upToSentAt)
        }
    }

    @Query("SELECT * FROM read_cursors WHERE conversationId = :conversationId ORDER BY memberId")
    public fun observeCursors(conversationId: String): Flow<List<ReadCursorEntity>>
}
