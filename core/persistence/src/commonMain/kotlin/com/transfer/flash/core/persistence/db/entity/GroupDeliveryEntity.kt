package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Per-recipient state for an outbound group message. The message outbox remains one row per
 * message; these rows decide which member is still due and when all active members reached quorum.
 */
@Entity(
    tableName = "group_deliveries",
    primaryKeys = ["messageId", "memberId"],
    indices = [Index(value = ["memberId", "nextAttemptAt"]), Index(value = ["messageId", "state"])],
)
public data class GroupDeliveryEntity(
    val messageId: String,
    val memberId: String,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val nextAttemptAt: Long,
    val deliveredAt: Long? = null,
)
