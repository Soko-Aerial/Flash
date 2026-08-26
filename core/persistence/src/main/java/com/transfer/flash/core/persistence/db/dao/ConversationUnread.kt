package com.transfer.flash.core.persistence.db.dao

/**
 * Per-conversation unread projection (C6.x unread badge). Room maps the `conversationId`/`unread`
 * columns of the grouped count query in [MessageDao.observeUnreadCounts] onto this class by name.
 */
data class ConversationUnread(
    val conversationId: String,
    val unread: Int,
)
