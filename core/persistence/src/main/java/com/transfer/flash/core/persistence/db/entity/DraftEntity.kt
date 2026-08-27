package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Unsent composer text per conversation. One row per conversation; last-write-wins. */
@Entity(tableName = "drafts")
public data class DraftEntity(
    @PrimaryKey val conversationId: String,
    val text: String,
    val updatedAt: Long,
)
