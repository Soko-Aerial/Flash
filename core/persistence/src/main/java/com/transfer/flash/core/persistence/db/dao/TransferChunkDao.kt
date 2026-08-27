package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity

/**
 * Chunk done-set = resume bit-vector. [doneChunks] returns the sorted indexes of completed
 * chunks so a resumed transfer can skip them; [resetStuck] clears the set when the source
 * file identity no longer matches (C1 resume rule).
 */
@Dao
public interface TransferChunkDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertAll(chunks: List<TransferChunkEntity>)

    @Query(
        "UPDATE transfer_chunks SET done = 1 " +
            "WHERE transferId = :transferId AND chunkIndex = :chunkIndex",
    )
    public suspend fun markChunkDone(transferId: String, chunkIndex: Int)

    @Query(
        "SELECT chunkIndex FROM transfer_chunks " +
            "WHERE transferId = :transferId AND done = 1 ORDER BY chunkIndex ASC",
    )
    public suspend fun doneChunks(transferId: String): List<Int>

    /**
     * All completed chunk rows across every transfer, for warming the in-memory receiver
     * done-set at startup so a resumed inbound FILE_START can seed its bit-vector synchronously
     * (#20). Rows are role-scoped by transferId (a device is only ever sender OR receiver for a
     * given id), so send-side rows never mis-seed a receive session.
     */
    @Query("SELECT transferId, chunkIndex FROM transfer_chunks WHERE done = 1")
    public suspend fun allDoneChunks(): List<ChunkIndexRef>

    @Query("UPDATE transfer_chunks SET done = 0 WHERE transferId = :transferId")
    public suspend fun resetStuck(transferId: String)
}

/** Lightweight projection for [TransferChunkDao.allDoneChunks]. */
public data class ChunkIndexRef(
    val transferId: String,
    val chunkIndex: Int,
)
