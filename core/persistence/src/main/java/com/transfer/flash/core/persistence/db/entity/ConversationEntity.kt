package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A conversation. List ordering is `pinned DESC, sortOrder DESC` — [sortOrder] is the
 * recency/activity rank maintained by the repository layer (C6), not by SQL triggers.
 */
@Entity(tableName = "conversations")
public data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Long = 0L,
    val lastReadCursor: String? = null,
)
