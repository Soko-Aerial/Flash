package com.transfer.flash.core.persistence.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Signed record proving the origin cancelled a swarm content announcement (§5.4, SW-6).
 * Keyed by (groupId, messageId) per ADR-072 rule 5, with root indexed.
 */
@Entity(
    tableName = "swarm_tombstone",
    primaryKeys = ["groupId", "messageId"],
    indices = [
        Index(value = ["root"]),
        Index(value = ["groupId"]),
    ],
)
public data class SwarmTombstoneEntity(
    public val groupId: String,
    public val messageId: String,
    public val root: String,
    public val originId: String,
    public val reason: String,
    public val cancelledAtMs: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    public val signature: ByteArray,
    public val receivedAtMs: Long,
    public val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SwarmTombstoneEntity) return false
        return groupId == other.groupId &&
            messageId == other.messageId &&
            root == other.root &&
            originId == other.originId &&
            reason == other.reason &&
            cancelledAtMs == other.cancelledAtMs &&
            signature.contentEquals(other.signature) &&
            receivedAtMs == other.receivedAtMs &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + root.hashCode()
        result = 31 * result + originId.hashCode()
        result = 31 * result + reason.hashCode()
        result = 31 * result + cancelledAtMs.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + receivedAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }
}
