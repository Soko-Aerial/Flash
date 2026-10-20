package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Files sent and received through the organisation hub (ADR-216). Every state change two actors could race on is a
 * conditional `UPDATE ... WHERE state = ...` whose row count says whether this caller won.
 */
@Dao
public interface HubFileDao {

    /** IGNORE: a replayed ticket message, or a second claim of the same id, leaves the first row untouched (-1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(row: HubFileEntity): Long

    @Query("SELECT * FROM hub_file WHERE transferId = :transferId")
    public suspend fun get(transferId: String): HubFileEntity?

    @Query("SELECT * FROM hub_file WHERE direction = :direction AND fileId = :fileId")
    public suspend fun byFileId(direction: String, fileId: String): HubFileEntity?

    /** The rows the Transfers screen and the bubbles show, newest first. */
    @Query("SELECT * FROM hub_file ORDER BY createdAtMs DESC LIMIT :limit")
    public fun observeRecent(limit: Int): Flow<List<HubFileEntity>>

    @Query("SELECT * FROM hub_file WHERE state IN (:states) AND paused = 0")
    public suspend fun runnable(states: List<String>): List<HubFileEntity>

    @Query(
        "UPDATE hub_file SET state = :to, errorText = :errorText, updatedAtMs = :nowMs " +
            "WHERE transferId = :transferId AND state = :from",
    )
    public suspend fun moveState(transferId: String, from: String, to: String, errorText: String?, nowMs: Long): Int

    @Query("UPDATE hub_file SET errorText = :errorText, updatedAtMs = :nowMs WHERE transferId = :transferId")
    public suspend fun setErrorText(transferId: String, errorText: String?, nowMs: Long)

    @Query("UPDATE hub_file SET paused = :paused, updatedAtMs = :nowMs WHERE transferId = :transferId")
    public suspend fun setPaused(transferId: String, paused: Boolean, nowMs: Long)

    @Query("UPDATE hub_file SET bytesDone = :bytesDone, updatedAtMs = :nowMs WHERE transferId = :transferId")
    public suspend fun setProgress(transferId: String, bytesDone: Long, nowMs: Long)

    /** The upload finished: store the ticket. Only an UPLOADING row moves, so a cancel that won stays CANCELLED. */
    @Query(
        "UPDATE hub_file SET state = 'READY', fileKey = :fileKey, cipherSha256 = :cipherSha256, " +
            "cipherChunkBytes = :cipherChunkBytes, chunkCount = :chunkCount, expiresAtMs = :expiresAtMs, " +
            "bytesDone = plainBytes, errorText = NULL, updatedAtMs = :nowMs " +
            "WHERE transferId = :transferId AND state = 'UPLOADING'",
    )
    public suspend fun markUploaded(
        transferId: String,
        fileKey: String,
        cipherSha256: String,
        cipherChunkBytes: Int,
        chunkCount: Int,
        expiresAtMs: Long,
        nowMs: Long,
    ): Int

    /**
     * Links an outgoing row to the chat message that shows it. The message and the upload are started together and either
     * may finish first, so the link is stored as soon as the message exists and only the first link counts.
     */
    @Query("UPDATE hub_file SET messageId = :messageId, updatedAtMs = :nowMs WHERE transferId = :transferId AND messageId IS NULL")
    public suspend fun attachMessage(transferId: String, messageId: String, nowMs: Long): Int

    /** The ticket message went into the outbox: exactly once per row. */
    @Query(
        "UPDATE hub_file SET state = 'QUEUED', messageId = :messageId, updatedAtMs = :nowMs " +
            "WHERE transferId = :transferId AND state = 'READY'",
    )
    public suspend fun markQueued(transferId: String, messageId: String, nowMs: Long): Int

    /** The download finished and the file is in place. */
    @Query(
        "UPDATE hub_file SET state = 'DONE', localPath = :localPath, bytesDone = plainBytes, errorText = NULL, " +
            "paused = 0, updatedAtMs = :nowMs WHERE transferId = :transferId AND state = 'DOWNLOADING'",
    )
    public suspend fun markDownloaded(transferId: String, localPath: String, nowMs: Long): Int

    @Query("DELETE FROM hub_file WHERE transferId = :transferId")
    public suspend fun delete(transferId: String)

    /** The row that shows chat message [messageId], either direction (one message carries at most one hub file). */
    @Query("SELECT * FROM hub_file WHERE messageId = :messageId LIMIT 1")
    public suspend fun byMessageId(messageId: String): HubFileEntity?

    /**
     * Removes rows nothing will use again (HUB-FILES P4): a finished row older than [terminalBeforeMs], and a row whose file
     * left the hub before [expiredBeforeMs] (its ticket or download can never work). A row that is running, paused or
     * waiting on its first byte is never touched, a ticket still queued for delivery is kept until its file expired, and a
     * row that a message still shows is kept whatever its state: the bubble finds its file (or its failure) through it.
     * @return rows removed.
     */
    @Query(
        "DELETE FROM hub_file WHERE " +
            "((state IN ('DONE', 'FAILED', 'CANCELLED', 'DECLINED') AND updatedAtMs < :terminalBeforeMs) OR " +
            "(state IN ('OFFERED', 'READY', 'QUEUED') AND expiresAtMs > 0 AND expiresAtMs < :expiredBeforeMs)) AND " +
            "(messageId IS NULL OR messageId NOT IN (SELECT localId FROM messages WHERE deletedAt IS NULL))",
    )
    public suspend fun prune(terminalBeforeMs: Long, expiredBeforeMs: Long): Int
}
