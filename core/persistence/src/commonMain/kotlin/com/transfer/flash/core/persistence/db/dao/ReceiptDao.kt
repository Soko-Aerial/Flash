package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import kotlinx.coroutines.flow.Flow

/** Receipt inserts are idempotent per `(messageId, memberId)`; first state wins. */
@Dao
public interface ReceiptDao {

    /** @return row id of the inserted row, or -1 when a duplicate receipt was ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(receipt: ReceiptEntity): Long

    @Query("SELECT * FROM receipts WHERE messageId = :messageId ORDER BY memberId")
    public fun observeForMessage(messageId: String): Flow<List<ReceiptEntity>>

    @Query("SELECT COUNT(*) FROM receipts WHERE messageId = :messageId")
    public suspend fun countForMessage(messageId: String): Int
}
