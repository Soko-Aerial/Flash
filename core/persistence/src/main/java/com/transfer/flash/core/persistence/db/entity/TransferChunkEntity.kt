package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity

/**
 * One chunk of a transfer. Composite PK `(transferId, chunkIndex)`; the leading PK column
 * covers all per-transfer lookups, so no extra index is needed. The set of rows with
 * `done = 1` for a transfer is its resume bit-vector.
 */
@Entity(tableName = "transfer_chunks", primaryKeys = ["transferId", "chunkIndex"])
public data class TransferChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    val done: Boolean = false,
)
