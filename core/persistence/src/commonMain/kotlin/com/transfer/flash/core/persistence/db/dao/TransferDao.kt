package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.TransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface TransferDao {

    /** Last-write-wins: progress rows are re-inserted with updated byte counters. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(transfer: TransferEntity)

    @Query("SELECT * FROM transfers WHERE transferId = :transferId")
    public fun observe(transferId: String): Flow<TransferEntity?>

    @Query("UPDATE transfers SET bytesDone = :bytesDone WHERE transferId = :transferId")
    public suspend fun setBytesDone(transferId: String, bytesDone: Long)

    @Query("UPDATE transfers SET status = :status WHERE transferId = :transferId")
    public suspend fun setStatus(transferId: String, status: String)
}
