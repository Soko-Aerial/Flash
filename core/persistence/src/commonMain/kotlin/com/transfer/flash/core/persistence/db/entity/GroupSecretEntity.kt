package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Storage for a group's shared secret at an epoch (ADR-044, ADR-073, GM-2).
 *
 * Encrypted at rest: Flash database is encrypted with SQLCipher on both Android and Desktop,
 * so [secretWrapped] bytes are protected by SQLCipher page-level encryption.
 *
 * [source] is one of `CREATED`, `INVITE`, `HANDOVER`, `ROTATED`.
 * [commit] is the 64-character lowercase hex string of the SHA-256 rotation commitment.
 */
@Entity(
    tableName = "group_secret",
    primaryKeys = ["groupId", "epoch"],
    indices = [Index(value = ["groupId"])],
)
public data class GroupSecretEntity(
    val groupId: String,
    val epoch: Long,
    val secretWrapped: ByteArray,
    val commit: String,
    val source: String,
    val receivedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupSecretEntity) return false
        return groupId == other.groupId &&
            epoch == other.epoch &&
            secretWrapped.contentEquals(other.secretWrapped) &&
            commit == other.commit &&
            source == other.source &&
            receivedAtMs == other.receivedAtMs
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + secretWrapped.contentHashCode()
        result = 31 * result + commit.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }

    override fun toString(): String {
        return "GroupSecretEntity(groupId=$groupId, epoch=$epoch, commit=$commit, source=$source, receivedAtMs=$receivedAtMs)"
    }
}
