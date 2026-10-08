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

    /**
     * How long after the last catch-up message arrived the "catching up" banner (UI-052) stays up. It must outlast
     * [BACKUP_DELAY_MS], the gap before a backup holder's push, so a two-holder round reads as one burst.
     */
    public const val SYNC_QUIET_MS: Long = 3_000L
    public const val LOW_MAX_PER_SECOND: Int = 5
    public const val LOW_MAX_TOTAL: Int = 100
    public const val DEFAULT_MAX_PER_SECOND: Int = 20
    public const val DEFAULT_MAX_TOTAL: Int = 500
    public const val MAX_COPIES_PER_MESSAGE: Int = 2
    public const val SYNC_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * How long a catch-up still carries a swarm FILE offer: as long as the swarm keeps the content (7 days,
     * `SwarmConfig.retentionMs`). With the 24 h text window a member that was offline for two days never heard of a file
     * that was still fetchable (ERROR-120).
     */
    public const val SWARM_OFFER_SYNC_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    public const val MAX_PENDING_SYNC_MESSAGES: Int = 100

    /** Pages of [MAX_PENDING_SYNC_MESSAGES] rows one catch-up request may read (ERROR-121): bounds the work for a huge history. */
    public const val MAX_CATCH_UP_PAGES: Int = 20

    /**
     * How long a catch-up request this device sent stays answerable. A round finishes in seconds
     * (backup delay 2 s, at most 500 messages at 20 per second); ten minutes tolerates a slow link
     * without leaving a `syncId` open for hours.
     */
    public const val SYNC_REQUEST_TTL_MS: Long = 10L * 60L * 1000L
    public const val MAX_OUTGOING_SYNC_REQUESTS: Int = 256

    // --- v2 groups (ADR-044 V1, docs/group/v1-signed-membership-plan.md) ---

    /** The `proto` of a [GroupCharter]; also the group protocol level a device needs (`gv`). */
    public const val V2_PROTOCOL: Int = 2

    /**
     * Members of a v2 group (ADR-044 V2). Six was bound by "every member paired with every other member";
     * vouched trust removes that need, so the limit is 20. A legacy group stays at [MAX_MEMBERS]: every shipped
     * codec rejects a longer legacy roster. The mesh this implies (19 sessions per device) fits the dial budget
     * and session ceiling of ADR-057, which `core:engine` pins with a test.
     */
    public const val MAX_MEMBERS_V2: Int = 20

    /** Leave/removal certs a bundle may carry on top of the active members. */
    public const val MAX_BUNDLE_TOMBSTONES: Int = 64
    public const val MAX_BUNDLE_CERTS: Int = MAX_MEMBERS_V2 + MAX_BUNDLE_TOMBSTONES
    public const val MAX_REMOVED_IDS_PER_ROTATION: Int = 64
    public const val MAX_LABEL_LENGTH: Int = 80
    public const val CHARTER_NONCE_BYTES: Int = 16

    /** Every id in this namespace is a v2 group id and can only be created by a valid charter. */
    public const val V2_GROUP_ID_PREFIX: String = "g2-"

    /** Signature verifications a single peer may cost this device per [VERIFY_WINDOW_MS]. */
    public const val BUNDLE_VERIFICATIONS_PER_WINDOW: Int = 120
    public const val VERIFY_WINDOW_MS: Long = 60_000L

    public fun isV2GroupId(groupId: String): Boolean = groupId.startsWith(V2_GROUP_ID_PREFIX)

    public fun normalizedName(name: String): String? = name.trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_GROUP_NAME_LENGTH }

    public fun validMemberIds(
        memberIds: Collection<String>,
        localDeviceId: String,
        maxMembers: Int = MAX_MEMBERS,
        minMembers: Int = 2,
    ): Boolean {
        val members = memberIds.toSet()
        return members.size == memberIds.size &&
            members.none { it.isBlank() } &&
            localDeviceId in members &&
            members.size in minMembers..maxMembers
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
