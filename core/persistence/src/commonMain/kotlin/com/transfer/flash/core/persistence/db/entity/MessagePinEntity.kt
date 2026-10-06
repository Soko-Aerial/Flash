package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A message the user pinned in a conversation, on this device only (nothing is sent to the peer or group).
 * Several rows per conversation are allowed; [pinnedAt] orders them, newest first.
 */
@Entity(
    tableName = "message_pins",
    primaryKeys = ["conversationId", "messageId"],
    indices = [Index("conversationId")],
)
public data class MessagePinEntity(
    val conversationId: String,
    val messageId: String,
    val pinnedAt: Long,
)
