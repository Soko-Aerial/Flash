package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pending outbound frame. Delivery claims rows with `nextAttemptAt <= now`; [attempts] is
 * incremented via a single atomic SQL UPDATE so concurrent claimers observe monotonically
 * increasing counts. Removal from the outbox is the commit point of delivery.
 */
@Entity(tableName = "outbox")
public data class OutboxEntity(
    @PrimaryKey val localId: String,
    val attempts: Int = 0,
    val nextAttemptAt: Long,
    val payloadJson: String,
    val createdAt: Long,
)
