package com.transfer.flash.core.persistence.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Durable record of a swarm content item (§5.4, SW-6).
 * Composite primary key: (root, groupId).
 */
@Entity(
    tableName = "swarm_content",
    primaryKeys = ["root", "groupId"],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["messageId"]),
        Index(value = ["state"]),
        Index(value = ["localTransferId"]),
    ],
)
public data class SwarmContentEntity(
    public val root: String,
    public val groupId: String,
    public val messageId: String,
    public val role: String,
    public val originId: String,
    public val originKey: String,
    public val fileName: String,
    public val mime: String,
    public val totalSize: Long,
    public val pieceSize: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    public val manifest: ByteArray?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    public val bits: ByteArray,
    public val bytesDone: Long,
    public val state: String,
    public val waitReason: String?,
    public val failReason: String?,
    public val localTransferId: String,
    public val sourceUri: String?,
    public val sourcePersistent: Boolean,
    public val partialKey: String,
    public val finalPath: String?,
    public val identitySize: Long,
    public val identityModifiedMs: Long,
    public val deliveredTo: String,
    public val createdAtMs: Long,
    public val lastProgressAtMs: Long,
    public val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SwarmContentEntity) return false
        return root == other.root &&
            groupId == other.groupId &&
            messageId == other.messageId &&
            role == other.role &&
            originId == other.originId &&
            originKey == other.originKey &&
            fileName == other.fileName &&
            mime == other.mime &&
            totalSize == other.totalSize &&
            pieceSize == other.pieceSize &&
            ((manifest == null && other.manifest == null) ||
                (manifest != null && other.manifest != null && manifest.contentEquals(other.manifest))) &&
            bits.contentEquals(other.bits) &&
            bytesDone == other.bytesDone &&
            state == other.state &&
            waitReason == other.waitReason &&
            failReason == other.failReason &&
            localTransferId == other.localTransferId &&
            sourceUri == other.sourceUri &&
            sourcePersistent == other.sourcePersistent &&
            partialKey == other.partialKey &&
            finalPath == other.finalPath &&
            identitySize == other.identitySize &&
            identityModifiedMs == other.identityModifiedMs &&
            deliveredTo == other.deliveredTo &&
            createdAtMs == other.createdAtMs &&
            lastProgressAtMs == other.lastProgressAtMs &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = root.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + originId.hashCode()
        result = 31 * result + originKey.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + totalSize.hashCode()
        result = 31 * result + pieceSize
        result = 31 * result + (manifest?.contentHashCode() ?: 0)
        result = 31 * result + bits.contentHashCode()
        result = 31 * result + bytesDone.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + (waitReason?.hashCode() ?: 0)
        result = 31 * result + (failReason?.hashCode() ?: 0)
        result = 31 * result + localTransferId.hashCode()
        result = 31 * result + (sourceUri?.hashCode() ?: 0)
        result = 31 * result + sourcePersistent.hashCode()
        result = 31 * result + partialKey.hashCode()
        result = 31 * result + (finalPath?.hashCode() ?: 0)
        result = 31 * result + identitySize.hashCode()
        result = 31 * result + identityModifiedMs.hashCode()
        result = 31 * result + deliveredTo.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + lastProgressAtMs.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }
}
