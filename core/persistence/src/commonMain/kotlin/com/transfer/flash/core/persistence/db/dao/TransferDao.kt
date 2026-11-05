package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.transfer.flash.core.persistence.db.entity.TransferChunkEntity
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

    @Insert(entity = TransferChunkEntity::class, onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertChunks(chunks: List<TransferChunkEntity>)

    /**
     * Records chunk completions and byte progress within a single atomic SQLite transaction (OPT-O06-07).
     * Halves transaction commits and WAL headers on active file transfer hot paths.
     */
    @Transaction
    public suspend fun recordProgress(
        transferId: String,
        chunks: List<TransferChunkEntity>,
        bytesDone: Long,
    ) {
        if (chunks.isNotEmpty()) {
            insertChunks(chunks)
        }
        setBytesDone(transferId, bytesDone)
    }

    @Query("UPDATE transfers SET status = :status WHERE transferId = :transferId")
    public suspend fun setStatus(transferId: String, status: String)

    /** The source identity stored for a resume check (ADR-352), or null for a row written before schema 23. */
    @Query(
        "SELECT sourceSizeBytes AS sizeBytes, sourceLastModifiedMs AS lastModifiedMs, " +
            "sourcePrefixSha256 AS prefixSha256Hex FROM transfers WHERE transferId = :transferId",
    )
    public suspend fun sourceIdentity(transferId: String): TransferSourceIdentity?

    @Query(
        "UPDATE transfers SET sourceSizeBytes = :sizeBytes, sourceLastModifiedMs = :lastModifiedMs, " +
            "sourcePrefixSha256 = :prefixSha256Hex WHERE transferId = :transferId",
    )
    public suspend fun setSourceIdentity(
        transferId: String,
        sizeBytes: Long?,
        lastModifiedMs: Long?,
        prefixSha256Hex: String?,
    ): Int

    /** Writes the identity of an existing row (C-09). Returns 0 when the row does not exist yet. */
    @Query(
        "UPDATE transfers SET totalBytes = :totalBytes, bytesDone = :bytesDone, status = :status, peerDeviceId = :peerDeviceId, " +
            "peerName = :peerName, fileName = :fileName, direction = :direction, sourceUri = :sourceUri, " +
            "wireFileId = :wireFileId, localPath = :localPath, errorMessage = :errorMessage, updatedAtMs = :updatedAtMs " +
            "WHERE transferId = :transferId",
    )
    public suspend fun updateIdentity(
        transferId: String,
        totalBytes: Long,
        bytesDone: Long,
        status: String,
        peerDeviceId: String?,
        peerName: String,
        fileName: String,
        direction: String,
        sourceUri: String?,
        wireFileId: String?,
        localPath: String?,
        errorMessage: String?,
        updatedAtMs: Long,
    ): Int

    /** Rows to list again after a restart: identity known, not dismissed, touched since [sinceMs]. */
    @Query("SELECT * FROM transfers WHERE direction != '' AND dismissed = 0 AND updatedAtMs >= :sinceMs ORDER BY updatedAtMs DESC")
    public suspend fun restorable(sinceMs: Long): List<TransferEntity>

    @Query("UPDATE transfers SET dismissed = 1 WHERE transferId IN (:ids)")
    public suspend fun dismiss(ids: List<String>)

    /** The history limit (C-05): rows nobody touched since [beforeMs] go. Returns how many. */
    @Query("DELETE FROM transfers WHERE updatedAtMs > 0 AND updatedAtMs < :beforeMs")
    public suspend fun deleteBefore(beforeMs: Long): Int

    /** A fresh insert of an existing id restarts its progress without touching its identity columns. */
    @Query("UPDATE transfers SET totalBytes = :totalBytes, bytesDone = 0, status = :status WHERE transferId = :transferId")
    public suspend fun resetProgress(transferId: String, totalBytes: Long, status: String): Int
}

/** Projection for [TransferDao.sourceIdentity]; the aliases match the adapter's [com.transfer.flash.core.transfer.SourceIdentity]. */
public data class TransferSourceIdentity(
    public val sizeBytes: Long?,
    public val lastModifiedMs: Long?,
    public val prefixSha256Hex: String?,
)
