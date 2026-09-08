package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Aggregated reaction state for one `(messageId, emoji)` pair, denormalized for bubble
 * rendering. Last-write-wins via upsert; [reactorIdsJson] is a JSON array of member IDs.
 */
@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "emoji"],
    indices = [Index(value = ["messageId"])],
)
public data class ReactionEntity(
    val messageId: String,
    val emoji: String,
    val count: Int = 1,
    val selfReacted: Boolean = false,
    val reactorIdsJson: String,
)
