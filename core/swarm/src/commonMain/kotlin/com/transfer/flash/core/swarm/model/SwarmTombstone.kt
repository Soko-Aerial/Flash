package com.transfer.flash.core.swarm.model

/**
 * Reason for an origin-issued swarm cancellation.
 */
public enum class SwarmTombstoneReason(public val wireValue: Int) {
    USER(1),
    DELETED(2);

    public companion object {
        public fun fromWire(value: Int): SwarmTombstoneReason? = when (value) {
            1 -> USER
            2 -> DELETED
            else -> null
        }
    }
}

/**
 * Signed record proving the origin cancelled an announcement (INV-5, INV-6).
 * Cancels the specific announcement (groupId, messageId).
 */
public data class SwarmTombstone(
    public val groupId: String,
    public val root: ContentRoot,
    public val originId: String,
    public val messageId: String,
    public val reason: SwarmTombstoneReason,
    public val cancelledAtMs: Long,
    public val signature: ByteArray,
) {
    init {
        require(groupId.isNotEmpty()) { "groupId must not be empty" }
        require(originId.isNotEmpty()) { "originId must not be empty" }
        require(messageId.isNotEmpty()) { "messageId must not be empty" }
        require(cancelledAtMs > 0) { "cancelledAtMs must be > 0" }
        require(signature.isNotEmpty()) { "signature must not be empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SwarmTombstone) return false
        return groupId == other.groupId &&
            root == other.root &&
            originId == other.originId &&
            messageId == other.messageId &&
            reason == other.reason &&
            cancelledAtMs == other.cancelledAtMs &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + root.hashCode()
        result = 31 * result + originId.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + reason.hashCode()
        result = 31 * result + cancelledAtMs.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
