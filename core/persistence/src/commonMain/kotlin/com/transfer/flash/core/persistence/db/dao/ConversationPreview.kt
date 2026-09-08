package com.transfer.flash.core.persistence.db.dao

/**
 * Per-conversation latest-message projection. Room maps the `conversationId`/`previewText`/`sentAt`
 * columns of the grouped query in [MessageDao.observeLatestPreviews] onto this class by name. Drives
 * the chat-list preview line and makes conversations searchable by their most recent message text.
 */
public data class ConversationPreview(
    val conversationId: String,
    val previewText: String,
    val sentAt: Long,
)
