package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity

/**
 * Per-member delivery/read state for a message. PK `(messageId, memberId)` makes inserts
 * idempotent — duplicate ACKs are IGNOREd, first state wins by design (state transitions are
 * monotonic per member; re-inserts of the same stage must not resurrect older states).
 */
@Entity(tableName = "receipts", primaryKeys = ["messageId", "memberId"])
public data class ReceiptEntity(
    val messageId: String,
    val memberId: String,
    val state: String,
)
