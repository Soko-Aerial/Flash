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

    /**
     * v2 membership (ADR-044 V1): the group's signed charter plus any subset of its signed member
     * certs. It replaces `Create`/`Add`/`Leave`/`State` for a v2 group and is self-authenticating,
     * so any member may relay it: create is the charter with every cert, an add or removal is the
     * changed certs, a leave is the leaver's own tombstone, and the reconcile on every session-up is
     * the charter with every cert including tombstones. [operationId] only tags the frame.
     * `docs/group/v1-signed-membership-plan.md` D4.
     */
    public data class Bundle(
        override val groupId: String,
        override val from: String,
        val operationId: String,
        val charter: GroupCharter,
        val certs: List<MemberCert>,
        val rotation: GroupRotation? = null,
        val settings: GroupSettings? = null,
    ) : GroupWireFrame {
        init {
            require(charter.groupId == groupId && certs.all { it.groupId == groupId }) {
                "a bundle carries one group"
            }
            if (rotation != null) {
                require(rotation.groupId == groupId) { "rotation notice must match bundle group id" }
            }
            if (settings != null) {
                require(settings.groupId == groupId) { "settings must match bundle group id" }
            }
        }
    }

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

    /**
     * The file a catch-up [Message] stands for (ERROR-108): enough for a member that was offline at send time to join the
     * swarm. [rootSig] is the author's signature over (group, message, root, size, name, type, sentAt), so a relaying
     * member cannot swap the file; the receiver verifies it against the author's key before it believes any field here.
     */
    public data class SwarmOffer(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val root: String,
        val pieceSize: Int,
        val rootSig: String,
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
        /**
         * v2 groups: base64 signature by [from] over the canonical message bytes (plan D3). Null
         * for a legacy group. For a message inside a [SyncPush] this is what makes the relayed
         * copy verifiable, and [from] is then the explicit author, not the pusher.
         */
        val signature: String? = null,
        /** A catch-up copy of a swarm file message carries its offer here (ERROR-108); null for text and live frames. */
        val swarmOffer: SwarmOffer? = null,
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

    /** Author-requested tombstone for one group message. */
    public data class DeleteForEveryone(
        override val groupId: String,
        val messageId: String,
        override val from: String,
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
        val signature: String? = null,
        val root: String? = null,
        val pieceSize: Int? = null,
        val swarm: Int? = null,
        val rootSig: String? = null,
    ) : GroupWireFrame

    /**
     * Group membership proof wire frames (GM-3, ADR-073, protocol "Group membership v1").
     * Prefix FLASH_GMEM.
     */
    public data class GsHello(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
        val nonce: ByteArray,
    ) : GroupWireFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GsHello) return false
            return groupId == other.groupId && from == other.from && epoch == other.epoch && nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int =
            ((groupId.hashCode() * 31 + from.hashCode()) * 31 + epoch.hashCode()) * 31 + nonce.contentHashCode()

        override fun toString(): String =
            "GsHello(groupId='$groupId', from='$from', epoch=$epoch, nonce=[${nonce.size}B])"
    }

    public data class GsChallenge(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
        val nonce: ByteArray,
        val mac: ByteArray,
    ) : GroupWireFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GsChallenge) return false
            return groupId == other.groupId && from == other.from && epoch == other.epoch &&
                nonce.contentEquals(other.nonce) && mac.contentEquals(other.mac)
        }

        override fun hashCode(): Int =
            (((groupId.hashCode() * 31 + from.hashCode()) * 31 + epoch.hashCode()) * 31 + nonce.contentHashCode()) * 31 + mac.contentHashCode()

        override fun toString(): String =
            "GsChallenge(groupId='$groupId', from='$from', epoch=$epoch, nonce=[${nonce.size}B], mac=[${mac.size}B])"
    }

    public data class GsProof(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
        val mac: ByteArray,
    ) : GroupWireFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GsProof) return false
            return groupId == other.groupId && from == other.from && epoch == other.epoch && mac.contentEquals(other.mac)
        }

        override fun hashCode(): Int =
            ((groupId.hashCode() * 31 + from.hashCode()) * 31 + epoch.hashCode()) * 31 + mac.contentHashCode()

        override fun toString(): String =
            "GsProof(groupId='$groupId', from='$from', epoch=$epoch, mac=[${mac.size}B])"
    }

    public data class GsResult(
        override val groupId: String,
        override val from: String,
        val ok: Boolean,
        val reason: String,
    ) : GroupWireFrame

    /**
     * Join request sent by an invitee after proving group secret knowledge (GM-4).
     */
    public data class GsJoinRequest(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
        val subjectId: String,
        val subjectKey: String,
        val label: String,
        val requestedAtMs: Long,
        val signature: String,
    ) : GroupWireFrame

    /**
     * Join decision sent by an admin or forwarded to the joiner (GM-4).
     */
    public data class GsJoinDecision(
        override val groupId: String,
        override val from: String,
        val subjectId: String,
        val approved: Boolean,
        val reason: String,
        val decidedBy: String,
        val decidedAtMs: Long,
        val signature: String,
    ) : GroupWireFrame

    /**
     * Roster preview sent to the joiner after a valid join request (GM-4).
     */
    public data class GsRosterPreview(
        override val groupId: String,
        override val from: String,
        val charter: GroupCharter,
        val memberCount: Int,
        val memberNames: List<String>,
    ) : GroupWireFrame

    /**
     * Stale epoch response sending the latest rotation notice to an active member (GM-6).
     */
    public data class GsStale(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
        val rotation: GroupRotation,
    ) : GroupWireFrame

    /**
     * Secret request sent by a member who lacks the secret for [epoch] (GM-6).
     */
    public data class GsSecretRequest(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
    ) : GroupWireFrame

    /**
     * Secret handover frame carrying the 32-byte group secret over live TLS (GM-6).
     *
     * Security:
     * - [toString] is redacted and NEVER leaks secret bytes in logs (GINV-1).
     * - [equals] and [hashCode] use constant-time / array comparison.
     */
    public data class GsSecret(
        override val groupId: String,
        override val from: String,
        val epoch: Long,
        val secret: ByteArray,
    ) : GroupWireFrame {
        init {
            require(secret.size == 32) { "Group secret must be exactly 32 bytes" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GsSecret) return false
            return groupId == other.groupId && from == other.from && epoch == other.epoch && secret.contentEquals(other.secret)
        }

        override fun hashCode(): Int =
            ((groupId.hashCode() * 31 + from.hashCode()) * 31 + epoch.hashCode()) * 31 + secret.contentHashCode()

        override fun toString(): String =
            "GsSecret(groupId='$groupId', from='$from', epoch=$epoch, secret=[${secret.size}B])"
    }
}

public enum class GroupSyncTier { LOW, MEDIUM, HIGH }
