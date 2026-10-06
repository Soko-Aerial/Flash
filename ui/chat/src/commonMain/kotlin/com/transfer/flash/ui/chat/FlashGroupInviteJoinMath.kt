package com.transfer.flash.ui.chat

import com.transfer.flash.core.security.group.GroupInvite
import com.transfer.flash.core.security.group.GroupInviteCodec

/**
 * Pure math and formatting helpers for Group Invite & Join Flow (UI-054).
 * Unit-testable without instrumentation.
 */
public object FlashGroupInviteJoinMath {

    private val INVITE_URI_REGEX = Regex("""flash://g/1/[A-Za-z0-9_-]+""")

    /** Safely decodes an invite link into a [GroupInvite], or null if malformed or untrusted. */
    public fun parseInviteUrl(raw: String): GroupInvite? =
        GroupInviteCodec.decode(raw.trim())

    /** True if [raw] begins with or contains a valid Flash group invite URI. */
    public fun isInviteUrl(raw: String): Boolean =
        INVITE_URI_REGEX.containsMatchIn(raw.trim())

    /** Extracts the first `flash://g/1/...` URI found in message text, or null. */
    public fun extractInviteUrl(text: String): String? =
        INVITE_URI_REGEX.find(text)?.value

    /** Formats the inviter display label. */
    public fun inviterLabel(inviterName: String?): String =
        if (!inviterName.isNullOrBlank()) {
            "Invited by $inviterName"
        } else {
            "Invited by a group member"
        }

    /** Title for join confirmation dialog. */
    public fun joinConfirmationTitle(groupName: String): String =
        "Join ${groupName.ifBlank { "Group" }}?"

    /** Friendly message for join confirmation dialog. */
    public fun joinConfirmationMessage(groupName: String, inviterName: String?): String {
        val safeGroup = groupName.ifBlank { "this group" }
        return if (!inviterName.isNullOrBlank()) {
            "You were invited by $inviterName to join $safeGroup."
        } else {
            "You were invited to join $safeGroup."
        }
    }

    /**
     * The line under a join request: when it came, and a warning when the approving device is not paired with the
     * requester (its name is then whatever it chose to call itself).
     */
    public fun joinRequestSubtitle(requestedAtMs: Long, nowMs: Long, isKnownDevice: Boolean): String {
        val whenText = formatRequestTime(requestedAtMs, nowMs)
        return if (isKnownDevice) {
            "Requested to join · $whenText"
        } else {
            "Not paired with you, name chosen by the device · $whenText"
        }
    }

    /** Formats a relative timestamp for join requests. */
    public fun formatRequestTime(requestedAtMs: Long, nowMs: Long): String {
        val diffMs = (nowMs - requestedAtMs).coerceAtLeast(0L)
        val diffSec = diffMs / 1000L
        val diffMin = diffSec / 60L
        val diffHours = diffMin / 60L
        val diffDays = diffHours / 24L

        return when {
            diffMin < 1L -> "Just now"
            diffMin < 60L -> "${diffMin}m ago"
            diffHours < 24L -> "${diffHours}h ago"
            diffDays == 1L -> "Yesterday"
            else -> "${diffDays}d ago"
        }
    }
}
