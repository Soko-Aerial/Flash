package com.transfer.flash.core.swarm.model

/**
 * Durable record of a swarm content item (§5.2, §5.4).
 */
public data class SwarmContentRecord(
    public val root: ContentRoot,
    public val groupId: String,
    public val messageId: String,
    public val role: SwarmRole,
    public val originId: String,
    public val originKey: String,
    public val fileName: String,
    public val mime: String,
    public val totalSize: Long,
    public val pieceSize: Int,
    public val manifestBytes: ByteArray?,
    public val bits: ByteArray,
    public val bytesDone: Long,
    public val state: SwarmLifecycleState,
    public val waitReason: SwarmWaitReason?,
    public val failReason: String?,
    public val localTransferId: String,
    public val sourceUri: String?,
    public val sourcePersistent: Boolean,
    public val partialKey: String,
    public val finalPath: String?,
    public val identitySize: Long,
    public val identityModifiedMs: Long,
    public val deliveredTo: Set<String>,
    public val createdAtMs: Long,
    public val lastProgressAtMs: Long,
    public val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SwarmContentRecord) return false
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
            ((manifestBytes == null && other.manifestBytes == null) ||
                (manifestBytes != null && other.manifestBytes != null && manifestBytes.contentEquals(other.manifestBytes))) &&
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
        result = 31 * result + (manifestBytes?.contentHashCode() ?: 0)
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
