package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Transfer-level progress row; per-chunk resume state lives in [TransferChunkEntity]. */
@Entity(tableName = "transfers")
public data class TransferEntity(
    @PrimaryKey val transferId: String,
    val totalBytes: Long,
    val bytesDone: Long = 0L,
    val status: String,
)
