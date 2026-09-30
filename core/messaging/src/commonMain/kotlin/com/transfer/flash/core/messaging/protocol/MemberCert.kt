package com.transfer.flash.core.messaging.protocol

/**
 * One signed statement about one member of a v2 group (ADR-044 V1, plan D2).
 *
 * Issued by the owner (add, remove, relabel) or by the [subjectId] itself for its own leave
 * (`active = false`, `issuerId == subjectId`). Superseded per subject by a strictly greater
 * `(seq, opId)`, the rule the legacy roster already used, now over a counter nobody can set to
 * "the largest clock value ever".
 */
public data class MemberCert(
    val groupId: String,
    val subjectId: String,
    /** Subject identity public key, X.509 SPKI, base64. */
    val subjectKey: String,
    /** The owner's name for this member. The name shown in a v2 group. */
    val label: String,
    /** [ROLE_OWNER] or [ROLE_MEMBER]. */
    val role: String,
    val seq: Long,
    val opId: String,
    val active: Boolean,
    val issuerId: String,
    /** Issuer signature over the canonical cert bytes, base64. */
    val sig: String,
) {
    public companion object {
        public const val ROLE_OWNER: String = "owner"
        public const val ROLE_MEMBER: String = "member"
    }
}
