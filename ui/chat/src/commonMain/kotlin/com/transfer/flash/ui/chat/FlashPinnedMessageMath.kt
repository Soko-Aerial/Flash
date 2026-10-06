package com.transfer.flash.ui.chat

public object FlashPinnedMessageMath {
    /**
     * Header title for the pinned message banner.
     */
    public fun senderHeader(senderName: String, isMine: Boolean): String {
        return if (isMine) "Pinned message • You" else "Pinned message • ${senderName.ifBlank { "Peer" }}"
    }

    /**
     * Clean single-line preview snippet capped at maxChars.
     */
    public fun previewSnippet(text: String, fallback: String = "Attachment", maxChars: Int = 80): String {
        val trimmed = text.trim().replace('\n', ' ')
        val result = if (trimmed.isBlank()) fallback else trimmed
        return if (result.length > maxChars) result.take(maxChars) + "…" else result
    }

    /**
     * Checks whether the given message id is currently pinned.
     */
    public fun isPinned(messageId: String, pinnedMessageId: String?): Boolean {
        return pinnedMessageId != null && messageId == pinnedMessageId
    }
}
