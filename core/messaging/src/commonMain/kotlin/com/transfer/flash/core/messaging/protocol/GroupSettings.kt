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
 * @property historyCeiling How far back a new member may catch up (ADR-100). The default [GroupHistoryCeiling.D30] is
 *   not signed or sent at all, so settings that never set it keep their signature and golden vectors.
 * @property historyCeilingSig ADR-105: the signer's separate signature over `flash-gsethc-v1` (this object's group, version,
 *   opId, signer and the ceiling name). Empty for the default ceiling. [sig] never covers the ceiling, so a build that does
 *   not know the ceiling still verifies the rest of the object.
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
    val historyCeiling: GroupHistoryCeiling = GroupHistoryCeiling.DEFAULT,
    val historyCeilingSig: String = "",
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
 *
 * ADR-105: the same operation (equal version and opId) can reach a device twice, once complete and once with the ceiling
 * left off (an older build re-encodes the stored object without the field it does not know). The copy that carries the
 * signed ceiling wins, so a stripped copy that arrived first never hides it. Callers hand in only verified objects.
 */
public fun settingsWins(
    candidate: GroupSettings,
    existing: GroupSettings?,
): Boolean {
    if (existing == null) return true
    if (candidate.version > existing.version) return true
    if (candidate.version < existing.version) return false
    if (candidate.opId == existing.opId) {
        return candidate.historyCeiling != GroupHistoryCeiling.DEFAULT && existing.historyCeiling == GroupHistoryCeiling.DEFAULT
    }
    return candidate.opId < existing.opId
}

