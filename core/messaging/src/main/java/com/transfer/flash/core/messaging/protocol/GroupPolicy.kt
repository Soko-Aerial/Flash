package com.transfer.flash.core.messaging.protocol

/** Pure limits and deterministic rules shared by group repository and wire validation. */
public object GroupPolicy {
    /** Creator plus five other trusted peers. */
    public const val MAX_MEMBERS: Int = 6
    public const val MAX_REMOTE_MEMBERS: Int = MAX_MEMBERS - 1
    public const val MAX_GROUP_NAME_LENGTH: Int = 80
    public const val MAX_MESSAGE_TEXT_LENGTH: Int = 16_384
    public const val CLAIM_WINDOW_MS: Long = 300L
    public const val LOW_CLAIM_WINDOW_MS: Long = 500L
    public const val BACKUP_DELAY_MS: Long = 2_000L
    public const val LOW_MAX_PER_SECOND: Int = 5
    public const val LOW_MAX_TOTAL: Int = 100
    public const val DEFAULT_MAX_PER_SECOND: Int = 20
    public const val DEFAULT_MAX_TOTAL: Int = 500
    public const val MAX_COPIES_PER_MESSAGE: Int = 2
    public const val SYNC_TTL_MS: Long = 24L * 60L * 60L * 1000L
    public const val MAX_PENDING_SYNC_MESSAGES: Int = 100

    public fun normalizedName(name: String): String? = name.trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_GROUP_NAME_LENGTH }

    public fun validMemberIds(memberIds: Collection<String>, localDeviceId: String): Boolean {
        val members = memberIds.toSet()
        return members.size == memberIds.size &&
            members.none { it.isBlank() } &&
            localDeviceId in members &&
            members.size in 2..MAX_MEMBERS
    }

    public fun syncLimits(tier: GroupSyncTier): Pair<Int, Int> = when (tier) {
        GroupSyncTier.LOW -> LOW_MAX_PER_SECOND to LOW_MAX_TOTAL
        GroupSyncTier.MEDIUM, GroupSyncTier.HIGH -> DEFAULT_MAX_PER_SECOND to DEFAULT_MAX_TOTAL
    }
}

/** A cursor ordering that cannot lose messages whose clocks collide. */
public data class GroupSyncCursor(val sentAt: Long, val messageId: String) : Comparable<GroupSyncCursor> {
    override fun compareTo(other: GroupSyncCursor): Int =
        sentAt.compareTo(other.sentAt).takeIf { it != 0 } ?: messageId.compareTo(other.messageId)
}

/** Versioned membership conflict rule: leave tombstones beat stale adds, newer re-adds reactivate. */
public data class GroupMembershipVersion(val version: Long, val operationId: String) : Comparable<GroupMembershipVersion> {
    override fun compareTo(other: GroupMembershipVersion): Int =
        version.compareTo(other.version).takeIf { it != 0 } ?: operationId.compareTo(other.operationId)
}

public fun membershipUpdateWins(
    candidate: GroupMembershipVersion,
    existing: GroupMembershipVersion?,
): Boolean = existing == null || candidate > existing
