package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted organization directory member statement (Plan B, ADR-134).
 * Holds the cryptographically verified [statementBytes] issued by the organization authority.
 */
@Entity(
    tableName = "org_directory_member",
    indices = [
        Index("subjectPublicKeyFingerprint"),
        Index("displayNameFolded"),
        Index("departmentId"),
    ],
)
public data class OrgDirectoryMemberEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val displayNameFolded: String,
    val departmentId: String,
    val role: String,
    val subjectPublicKeyFingerprint: String,
    val statementBytes: ByteArray,
    val statementSerial: Long,
    val statementIssuedAtMs: Long,
    val statementDelegationSerial: Long,
    val lastSyncedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrgDirectoryMemberEntity) return false
        return deviceId == other.deviceId &&
            displayName == other.displayName &&
            displayNameFolded == other.displayNameFolded &&
            departmentId == other.departmentId &&
            role == other.role &&
            subjectPublicKeyFingerprint == other.subjectPublicKeyFingerprint &&
            statementBytes.contentEquals(other.statementBytes) &&
            statementSerial == other.statementSerial &&
            statementIssuedAtMs == other.statementIssuedAtMs &&
            statementDelegationSerial == other.statementDelegationSerial &&
            lastSyncedAtMs == other.lastSyncedAtMs
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + displayNameFolded.hashCode()
        result = 31 * result + departmentId.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + subjectPublicKeyFingerprint.hashCode()
        result = 31 * result + statementBytes.contentHashCode()
        result = 31 * result + statementSerial.hashCode()
        result = 31 * result + statementIssuedAtMs.hashCode()
        result = 31 * result + statementDelegationSerial.hashCode()
        result = 31 * result + lastSyncedAtMs.hashCode()
        return result
    }

    override fun toString(): String {
        return "OrgDirectoryMemberEntity(deviceId=$deviceId, displayName=$displayName, departmentId=$departmentId, role=$role, statementSerial=$statementSerial)"
    }
}
