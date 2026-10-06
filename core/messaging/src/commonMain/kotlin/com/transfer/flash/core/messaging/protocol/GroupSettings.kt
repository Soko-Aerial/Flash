package com.transfer.flash.core.messaging.protocol

/**
 * Signed group settings shared across the group (ADR-074, GM-9).
 *
 * Signed by an admin (owner or co-owner/admin) under canonical domain `"flash-gset-v1"`.
 * Carried in roster bundles; highest valid [version] wins, with tie-break on smaller [opId].
 *
 * @property groupId The group this settings object belongs to.
 * @property version Monotonic version number (grows by 1 per change).
 * @property joinPolicy "APPROVE" or "OPEN".
 * @property inviteSharers "ALL" or "ADMINS".
 * @property maxMembers Maximum allowed active members in the group (2..20).
 * @property swarmServing Whether swarm serving is enabled for this group.
 * @property membersMayAdd Whether non-admin members may add new members.
 * @property opId Operation ID (16 hex chars or UUID) for tie-breaking.
 * @property signerId Device ID of the admin who signed this settings object.
 * @property sig Base64 signature by [signerId] over canonical settings bytes.
 */
public data class GroupSettings(
    val groupId: String,
    val version: Long,
    val joinPolicy: String = POLICY_APPROVE,
    val inviteSharers: String = SHARERS_ALL,
    val maxMembers: Int = GroupPolicy.MAX_MEMBERS_V2,
    val swarmServing: Boolean = true,
    val membersMayAdd: Boolean = false,
    val opId: String,
    val signerId: String,
    val sig: String,
) {
    public companion object {
        public const val POLICY_APPROVE: String = "APPROVE"
        public const val POLICY_OPEN: String = "OPEN"
        public const val SHARERS_ALL: String = "ALL"
        public const val SHARERS_ADMINS: String = "ADMINS"

        /**
         * Default settings for a group before any custom settings have been signed or stored (ADR-074 rule 3).
         */
        public fun defaults(groupId: String): GroupSettings = GroupSettings(
            groupId = groupId,
            version = 0L,
            joinPolicy = POLICY_APPROVE,
            inviteSharers = SHARERS_ALL,
            maxMembers = GroupPolicy.MAX_MEMBERS_V2,
            swarmServing = true,
            membersMayAdd = false,
            opId = "",
            signerId = "",
            sig = "",
        )
    }
}

/**
 * Returns true if [candidate] settings object should overwrite [existing] settings (ADR-074, GM-9).
 *
 * The highest valid version wins; on a tie, the smaller [GroupSettings.opId] (lexicographically).
 */
public fun settingsWins(
    candidate: GroupSettings,
    existing: GroupSettings?,
): Boolean {
    if (existing == null) return true
    if (candidate.version > existing.version) return true
    if (candidate.version < existing.version) return false
    return candidate.opId < existing.opId
}

