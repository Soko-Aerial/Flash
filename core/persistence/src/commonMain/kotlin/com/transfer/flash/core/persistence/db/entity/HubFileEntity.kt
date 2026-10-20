package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file sent or received through the organisation hub (ADR-215, ADR-216, `docs/plans/HUB-FILES-PLAN.md` P3).
 *
 * The row outlives the process: start-up resumes every transfer from here, never from memory. [fileKey] is the file's
 * secret (`hfk1.` text) and is kept only in this encrypted database: the sender's copy is written when the upload has
 * finished (the engine's plain record is released right after), the recipient's arrives in the sealed hub message.
 *
 * [state] is one of [HubFileStates]; [paused] is separate so a resume knows which state it returns to.
 */
@Entity(
    tableName = "hub_file",
    indices = [
        Index(value = ["direction", "fileId"], unique = true),
        Index(value = ["messageId"]),
    ],
)
public data class HubFileEntity(
    /** The id the Transfers screen, the chat bubble and the transfer repository use. */
    @PrimaryKey val transferId: String,
    /** [HubFileStates.OUT] or [HubFileStates.IN]. */
    val direction: String,
    /** 32 lowercase hex characters, the hub's id for the file. */
    val fileId: String,
    /** The recipient (OUT) or the sender (IN). */
    val peerId: String,
    val hubUrl: String,
    /** Display name only, never a path: the received copy's name is sanitised again before it touches the disk. */
    val fileName: String,
    val mime: String,
    val plainBytes: Long,
    /** OUT: what the host's file opener reads. Null for IN. */
    val sourceUri: String?,
    val fileKey: String?,
    val cipherSha256: String?,
    val cipherChunkBytes: Int,
    val chunkCount: Int,
    val expiresAtMs: Long,
    /** The chat message that carries (OUT, once queued) or announced (IN) this file. */
    val messageId: String?,
    val state: String,
    val paused: Boolean,
    /** The sentence the row shows for its last problem, already worded for people; null when there is none. */
    val errorText: String?,
    val bytesDone: Long,
    /** IN: the received file once it is complete. */
    val localPath: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** The values of [HubFileEntity.direction] and [HubFileEntity.state]. */
public object HubFileStates {
    public const val OUT: String = "OUT"
    public const val IN: String = "IN"

    /** OUT: the upload is running or waiting to run again. */
    public const val UPLOADING: String = "UPLOADING"

    /** OUT: the hub holds the whole file and the ticket is stored; the chat message is not queued yet. */
    public const val READY: String = "READY"

    /** OUT: the ticket message is in the outbox (and possibly delivered). Terminal for the transfer. */
    public const val QUEUED: String = "QUEUED"

    /** IN: the ticket arrived; waiting for the person (or the auto-download setting) to accept. */
    public const val OFFERED: String = "OFFERED"

    /** IN: accepted; the download is running or waiting to run again. */
    public const val DOWNLOADING: String = "DOWNLOADING"

    /** IN: the file is verified and at [HubFileEntity.localPath]. Terminal. */
    public const val DONE: String = "DONE"

    public const val FAILED: String = "FAILED"
    public const val CANCELLED: String = "CANCELLED"
    public const val DECLINED: String = "DECLINED"

    /** Transfer ids of hub files: the prefix plus the file id, so a row, its message and its transfer share one id. */
    public const val TRANSFER_PREFIX_IN: String = "hub-i-"
    public const val TRANSFER_PREFIX_OUT: String = "hub-o-"

    /** States a start-up resumes (unless [HubFileEntity.paused]). */
    public val RUNNING: List<String> = listOf(UPLOADING, READY, DOWNLOADING)
}
