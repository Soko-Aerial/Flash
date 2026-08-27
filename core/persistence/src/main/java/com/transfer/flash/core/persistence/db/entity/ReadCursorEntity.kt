package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Per-conversation per-member read cursor. Monotonicity is enforced by
 * `ReadCursorDao.advanceFurthest` — cursors only move forward in `(upToSentAt, upToMessageId)`
 * order and never regress.
 */
@Entity(
    tableName = "read_cursors",
    primaryKeys = ["conversationId", "memberId"],
    indices = [Index(value = ["conversationId", "upToSentAt"])],
)
public data class ReadCursorEntity(
    val conversationId: String,
    val memberId: String,
    val upToMessageId: String,
    val upToSentAt: Long,
)
