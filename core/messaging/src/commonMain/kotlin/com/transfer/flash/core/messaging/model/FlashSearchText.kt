package com.transfer.flash.core.messaging.model

/**
 * Pure text helpers shared by the repository (building search hits from Room rows) and the chat UI
 * (in-chat search, shared-content filtering).
 *
 * Everything here is allocation-light and side-effect free so it can run per keystroke on the main
 * thread for the small in-memory lists, and on the IO dispatcher for database results.
 */
public object FlashSearchText {

    /** Case-insensitive "does [text] contain [query]"; null-safe and blank-query safe. */
    public fun contains(text: String?, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || text.isNullOrEmpty()) return false
        return text.contains(q, ignoreCase = true)
    }

    /**
     * A single-line extract of [text] centred on the first occurrence of [query].
     *
     * Returns the whole (whitespace-collapsed) text when it already fits in [maxChars]; otherwise
     * keeps roughly half the budget on each side of the match and marks each trimmed edge with an
     * ellipsis, the same shape Telegram and WhatsApp use in result rows. A query that is not in the
     * text yields a leading extract rather than an empty string, so a caller can always render
     * something.
     */
    public fun snippet(text: String, query: String, maxChars: Int = 140): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return ""
        if (collapsed.length <= maxChars) return collapsed
        val q = query.trim()
        val index = if (q.isEmpty()) -1 else collapsed.indexOf(q, ignoreCase = true)
        if (index < 0) return collapsed.take(maxChars - 1).trimEnd() + "…"
        val budget = (maxChars - q.length).coerceAtLeast(24)
        val before = budget / 2
        val start = (index - before).coerceAtLeast(0)
        val end = (start + maxChars).coerceAtMost(collapsed.length)
        val adjustedStart = (end - maxChars).coerceAtLeast(0)
        val body = collapsed.substring(adjustedStart, end).trim()
        val prefix = if (adjustedStart > 0) "…" else ""
        val suffix = if (end < collapsed.length) "…" else ""
        return prefix + body + suffix
    }

    private val URL_REGEX = Regex("""(?:https?|flash)://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** First link in [text] (http, https or a `flash://` invite), trailing punctuation trimmed. */
    public fun firstUrl(text: String?): String? {
        val match = text?.let { URL_REGEX.find(it) } ?: return null
        return match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
    }

    /** True when [query] occurs inside a link in [text] — the Links scope only shows link matches. */
    public fun matchesLink(text: String?, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || text.isNullOrEmpty()) return false
        val url = firstUrl(text) ?: return false
        return url.contains(q, ignoreCase = true)
    }

    /** Images and videos: shown under the Media scope, rendered with the media grammar. */
    public fun isMediaMime(mimeType: String?): Boolean =
        mimeType?.startsWith("image/", ignoreCase = true) == true ||
            mimeType?.startsWith("video/", ignoreCase = true) == true
}
