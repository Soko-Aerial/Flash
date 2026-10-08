package com.transfer.flash.core.messaging.group

/**
 * User-facing sentences for group membership and invite lifecycle (Plan §8.3, Table 8.3).
 *
 * M-01 to M-22 define standard human-readable messages for all group invite, joining,
 * and secret synchronization states.
 */
public object GroupMembershipStatusText {

    /** M-01: Malformed, truncated, or unsupported invite link. */
    public const val INVALID_INVITE: String =
        "This invite is not valid. Ask for a new one."

    /** M-02: User is already an active member of this group. */
    public fun alreadyMember(groupName: String): String =
        "You are already in $groupName."

    /** M-03: No member reachable (hints fail, nobody discovered within 30 s). */
    public fun waitingForMember(groupName: String): String =
        "Waiting for a member of $groupName to be nearby."

    /** M-04: TLS identity mismatch at the inviter device ID (impostor or stale pin). */
    public const val IDENTITY_MISMATCH: String =
        "The device that answered is not the one that shared this invite."

    /** M-05: Proof failed (wrong secret / rate-limited). */
    public const val INVITE_NO_LONGER_VALID: String =
        "This invite is no longer valid."

    /** M-06: Invite from before a secret rotation. */
    public const val INVITE_REPLACED: String =
        "This invite was replaced. Ask for a new one."

    /** M-07: Join request forwarded, waiting for an admin's decision. */
    public fun waitingForAdmin(groupName: String): String =
        "Waiting for an admin of $groupName to approve."

    /** M-08: Admin declined the join request. */
    public fun requestDeclined(groupName: String): String =
        "Your request to join $groupName was declined."

    /** M-09: Group reached maximum capacity. */
    public fun groupFull(groupName: String): String =
        "$groupName is full."

    /** M-10: Peer does not advertise gs1 feature (old build). */
    public fun updateRequired(deviceName: String): String =
        "Update Flash on $deviceName to use invites."

    /** M-11: Peer device ID has a key conflict with an existing pairing. */
    public fun pinConflict(deviceName: String): String =
        "$deviceName is known to this device under a different key."

    /** M-12: Stale secret; receiving new group code via handover. */
    public const val GETTING_NEW_GROUP_CODE: String =
        "Getting the new group code…"

    /** M-17: Secret store was cleared; re-fetching group secret. */
    public const val GETTING_GROUP_CODE: String =
        "Getting the group code…"

    /** M-22: All admins left or unavailable. */
    public fun noAdminAvailable(groupName: String): String =
        "No admin of $groupName is available, so nobody can approve new members."

    /**
     * M-23: A member added this device to a signed group, but this device is not paired with the group's owner and
     * holds no invite, so it cannot accept the group (the charter check, `owner-not-paired`).
     */
    public fun addedButOwnerNotPaired(adderName: String?, groupName: String): String {
        val who = adderName?.trim()?.takeIf { it.isNotEmpty() } ?: "A group member"
        return "$who added you to $groupName, but you are not paired with its owner. Pair with the owner, or ask for an invite link, to join."
    }

    /**
     * M-24: Shown to a member who is not the owner right after adding people. Whether a newcomer can accept the group
     * depends on a pairing this device cannot see, so the sentence says so instead of promising it.
     */
    public fun newcomersNeedOwnerPairing(newcomerNames: List<String>, ownerName: String?): String {
        val who = when (newcomerNames.size) {
            0 -> "They"
            1 -> newcomerNames[0]
            else -> newcomerNames.dropLast(1).joinToString(", ") + " and " + newcomerNames.last()
        }
        val owner = ownerName?.trim()?.takeIf { it.isNotEmpty() } ?: "the group's owner"
        return "Added. $who can only join if also paired with $owner. Otherwise share an invite link."
    }
}
