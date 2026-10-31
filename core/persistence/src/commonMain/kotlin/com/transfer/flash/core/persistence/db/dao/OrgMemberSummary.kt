package com.transfer.flash.core.persistence.db.dao

import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity

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

public fun OrgDirectoryMemberEntity.toSummary(): OrgMemberSummary =
    OrgMemberSummary(
        deviceId = deviceId,
        displayName = displayName,
        displayNameFolded = displayNameFolded,
        departmentId = departmentId,
        role = role,
        subjectPublicKeyFingerprint = subjectPublicKeyFingerprint,
        statementSerial = statementSerial,
        statementIssuedAtMs = statementIssuedAtMs,
        statementDelegationSerial = statementDelegationSerial,
        lastSyncedAtMs = lastSyncedAtMs,
    )
