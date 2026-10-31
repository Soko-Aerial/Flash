package com.transfer.flash.core.persistence.db.dao

/**
 * Lightweight projection for directory member listings that avoids loading
 * large statement BLOBs from overflow pages (OPT-O06-08).
 */
public data class OrgMemberSummary(
    val deviceId: String,
    val displayName: String,
    val displayNameFolded: String,
    val departmentId: String,
    val role: String,
    val subjectPublicKeyFingerprint: String,
    val statementSerial: Long,
    val statementIssuedAtMs: Long,
    val statementDelegationSerial: Long,
    val lastSyncedAtMs: Long,
)
