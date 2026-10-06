package com.transfer.flash.ui.chat

public object FlashPinnedMessageMath {
    /**
     * Header title for the pinned message banner.
     */
    public fun senderHeader(senderName: String, isMine: Boolean, position: Int = 0, total: Int = 1): String {
        val who = if (isMine) "You" else senderName.ifBlank { "Peer" }
        val title = if (total > 1) "Pinned message ${position + 1} of $total" else "Pinned message"
        return "$title • $who"
    }

    /** Keeps the banner's shown pin inside `0 until total` when pins are removed (or a pinned message leaves the window). */
    public fun clampIndex(index: Int, total: Int): Int = if (total <= 0) 0 else index.coerceIn(0, total - 1)

    /** The pin the banner shows after a tap on it: the next one, wrapping around, so every pin is reachable. */
    public fun nextIndex(index: Int, total: Int): Int = if (total <= 1) 0 else (index + 1) % total

    /** The toast for a pin change. A pin is local: the peer or group is never told, and the wording says so. */
    public fun toastText(pinned: Boolean): String = if (pinned) "Pinned on this device" else "Unpinned"

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

    /** [isPinned] for a conversation that can hold several pins. */
    public fun isPinnedIn(messageId: String, pinnedMessageIds: Collection<String>): Boolean = messageId in pinnedMessageIds
}
