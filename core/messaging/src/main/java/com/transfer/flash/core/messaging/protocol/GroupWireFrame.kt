package com.transfer.flash.core.messaging.protocol

/**
 * Group protocol frames. All identifiers are stable UUID strings at the boundary; the codec rejects
 * malformed or oversized values before they reach persistence. Phase 1 carries [keyEpoch] as zero
 * while group payload encryption remains deferred to Phase 3.
 */
public sealed interface GroupWireFrame : ChatWireFrame {
    public val groupId: String
    public val from: String

    public sealed interface Membership : GroupWireFrame {
        public val operationId: String
        public val membershipVersion: Long
    }

    public data class Create(
        override val groupId: String,
        override val from: String,
        override val operationId: String,
        override val membershipVersion: Long,
        val name: String,
        val memberIds: List<String>,
    ) : Membership

    public data class Add(
        override val groupId: String,
        override val from: String,
        override val operationId: String,
        override val membershipVersion: Long,
        val memberIds: List<String>,
    ) : Membership

    public data class Leave(
        override val groupId: String,
        override val from: String,
        override val operationId: String,
        override val membershipVersion: Long,
        val memberId: String,
    ) : Membership

    /**
     * Full-roster bootstrap (F2): the complete group state a late joiner needs to materialize
     * the conversation — name, creator, and every member with its own versioned membership
     * record. Sent by an adder to NEWLY added members (who have no local record to validate
     * a mere Add against), and accepted by existing members as idempotent reconciliation.
     * The versioned merge preserves leave tombstones: a state entry never resurrects a member
     * whose tombstone is strictly newer.
     */
    public data class State(
        override val groupId: String,
        override val from: String,
        override val operationId: String,
        override val membershipVersion: Long,
        val name: String,
        val creatorId: String,
        val members: List<RosterEntry>,
    ) : Membership

    /** One member's versioned state inside a [State] roster. */
    public data class RosterEntry(
        val deviceId: String,
        val displayName: String,
        val role: String,
        val joinedAt: Long,
        val membershipVersion: Long,
        val operationId: String,
        val isActive: Boolean,
    )

    public data class Message(
        override val groupId: String,
        val messageId: String,
        override val from: String,
        val senderName: String,
        val sentAt: Long,
        val text: String,
        val replyToId: String? = null,
        val replyToPreview: String? = null,
        val keyEpoch: Long = 0L,
    ) : GroupWireFrame

    public data class Receipt(
        override val groupId: String,
        val messageId: String,
        override val from: String,
        val deliveredAt: Long,
        val keyEpoch: Long = 0L,
    ) : GroupWireFrame

    public data class Read(
        override val groupId: String,
        override val from: String,
        val upToMessageId: String,
        val readAt: Long,
        val keyEpoch: Long = 0L,
    ) : GroupWireFrame

    public sealed interface Sync : GroupWireFrame {
        public val syncId: String
        public val keyEpoch: Long
    }

    public data class SyncRequest(
        override val groupId: String,
        override val syncId: String,
        override val from: String,
        val sinceSentAt: Long,
        val sinceMessageId: String,
        val tier: GroupSyncTier,
        val maxPerSecond: Int,
        val maxTotal: Int,
        override val keyEpoch: Long = 0L,
    ) : Sync

    public data class SyncClaim(
        override val groupId: String,
        override val syncId: String,
        override val from: String,
        val messageIds: List<String>,
        val tier: GroupSyncTier = GroupSyncTier.MEDIUM,
        override val keyEpoch: Long = 0L,
    ) : Sync

    public data class SyncPush(
        override val groupId: String,
        override val syncId: String,
        override val from: String,
        val message: Message,
        override val keyEpoch: Long = 0L,
    ) : Sync

    public data class SyncAck(
        override val groupId: String,
        override val syncId: String,
        override val from: String,
        val messageIds: List<String>,
        val hasMore: Boolean,
        override val keyEpoch: Long = 0L,
    ) : Sync

    /**
     * F4: announces an inbound group media transfer BEFORE its binary stream, so the receiver
     * can thread the offer/row into the GROUP conversation instead of a sender-keyed 1:1
     * thread (`ChunkFrame.FileStart` carries no conversation context). [messageId] is the
     * sender's chat-row id — the receiver mints its own row under the same id, so a re-pull
     * dedups. Text framing is unknown-key tolerant; old peers drop the unknown prefix.
     */
    public data class GroupMedia(
        override val groupId: String,
        val messageId: String,
        val transferId: String,
        val wireFileId: String,
        override val from: String,
        val senderName: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sentAt: Long,
    ) : GroupWireFrame
}

public enum class GroupSyncTier { LOW, MEDIUM, HIGH }
