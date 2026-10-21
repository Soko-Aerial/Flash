package com.transfer.flash.core.persistence.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Transfer-level progress row; per-chunk resume state lives in [TransferChunkEntity].
 *
 * Since schema 20 (open-items C-09) the row also carries the transfer's identity, so a Failed, Cancelled or interrupted
 * 1:1 transfer is still listed (and can be retried) after the process was killed. A row written before that has an empty
 * [direction] and is never restored.
 */
@Entity(tableName = "transfers")
public data class TransferEntity(
    @PrimaryKey val transferId: String,
    val totalBytes: Long,
    val bytesDone: Long = 0L,
    val status: String,
    val peerDeviceId: String? = null,
    @ColumnInfo(defaultValue = "''") val peerName: String = "",
    @ColumnInfo(defaultValue = "''") val fileName: String = "",
    /** `Sending` or `Receiving` (the domain enum name); empty for a row from before schema 20. */
    @ColumnInfo(defaultValue = "''") val direction: String = "",
    val sourceUri: String? = null,
    val wireFileId: String? = null,
    val localPath: String? = null,
    val errorMessage: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0L,
    /** The person cleared the row from the Transfers list; it is not restored. */
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
)
