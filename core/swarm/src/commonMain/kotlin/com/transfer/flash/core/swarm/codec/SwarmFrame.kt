package com.transfer.flash.core.swarm.codec

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason

/**
 * State of content held by a peer as advertised in a SUMMARY frame.
 */
public enum class SwarmContentState(public val wireValue: Int) {
    NONE(0),
    PARTIAL(1),
    ALL(2);

    public companion object {
        public fun fromWire(value: Int): SwarmContentState? = when (value) {
            0 -> NONE
            1 -> PARTIAL
            2 -> ALL
            else -> null
        }
    }
}

/**
 * Origin availability status in a SOURCE_STATUS frame.
 */
public enum class SourceState(public val wireValue: Int) {
    LOST(1),
    RESTORED(2);

    public companion object {
        public fun fromWire(value: Int): SourceState? = when (value) {
            1 -> LOST
            2 -> RESTORED
            else -> null
        }
    }
}

/**
 * Origin availability reason in a SOURCE_STATUS frame.
 */
public enum class SourceReason(public val wireValue: Int) {
    NONE(0),
    DELETED(1),
    CHANGED(2),
    PERMISSION(3);

    public companion object {
        public fun fromWire(value: Int): SourceReason? = when (value) {
            0 -> NONE
            1 -> DELETED
            2 -> CHANGED
            3 -> PERMISSION
            else -> null
        }
    }
}

/**
 * Sealed hierarchy of all 12 FSW1 wire frames (ADR-071, SW-3).
 */
public sealed interface SwarmFrame {
    public val typeCode: Int

    public data class Summary(
        public val groupId: String,
        public val tombstones: List<SwarmTombstone>,
        public val entries: List<Entry>,
    ) : SwarmFrame {
        override val typeCode: Int get() = 1

        public data class Entry(
            public val root: ContentRoot,
            public val state: SwarmContentState,
            public val servingEnabled: Boolean,
        )
    }

    public data class ManifestGet(
        public val groupId: String,
        public val root: ContentRoot,
        public val fragmentIndex: Int,
    ) : SwarmFrame {
        override val typeCode: Int get() = 2
    }

    public data class ManifestPart(
        public val groupId: String,
        public val root: ContentRoot,
        public val fragmentIndex: Int,
        public val fragmentCount: Int,
        public val bytes: ByteArray,
    ) : SwarmFrame {
        override val typeCode: Int get() = 3

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ManifestPart) return false
            return groupId == other.groupId &&
                root == other.root &&
                fragmentIndex == other.fragmentIndex &&
                fragmentCount == other.fragmentCount &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + root.hashCode()
            result = 31 * result + fragmentIndex
            result = 31 * result + fragmentCount
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    public data class Have(
        public val groupId: String,
        public val root: ContentRoot,
        public val ranges: List<PieceRange>,
    ) : SwarmFrame {
        override val typeCode: Int get() = 4
    }

    public data class HaveAll(
        public val groupId: String,
        public val root: ContentRoot,
    ) : SwarmFrame {
        override val typeCode: Int get() = 5
    }

    public data class Request(
        public val groupId: String,
        public val root: ContentRoot,
        public val pieces: List<Int>,
    ) : SwarmFrame {
        override val typeCode: Int get() = 6
    }

    public data class Piece(
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        public val bytes: ByteArray,
    ) : SwarmFrame {
        override val typeCode: Int get() = 7

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Piece) return false
            return groupId == other.groupId &&
                root == other.root &&
                index == other.index &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + root.hashCode()
            result = 31 * result + index
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    public data class Reject(
        public val groupId: String,
        public val root: ContentRoot,
        public val reason: SwarmRejectReason,
        public val retryAfterMs: Long,
        public val scopeAll: Boolean,
        public val pieces: List<Int>,
    ) : SwarmFrame {
        override val typeCode: Int get() = 8
    }

    public data class Cancel(
        public val groupId: String,
        public val root: ContentRoot,
        public val originId: String,
        public val messageId: String,
        public val reason: SwarmTombstoneReason,
        public val cancelledAtMs: Long,
        public val signature: ByteArray,
    ) : SwarmFrame {
        init {
            require(groupId.isNotEmpty()) { "groupId must not be empty" }
            require(originId.isNotEmpty()) { "originId must not be empty" }
            require(messageId.isNotEmpty()) { "messageId must not be empty" }
            require(cancelledAtMs > 0) { "cancelledAtMs must be > 0" }
            require(signature.isNotEmpty()) { "signature must not be empty" }
        }

        override val typeCode: Int get() = 9

        public fun toTombstone(): SwarmTombstone = SwarmTombstone(
            groupId = groupId,
            root = root,
            originId = originId,
            messageId = messageId,
            reason = reason,
            cancelledAtMs = cancelledAtMs,
            signature = signature,
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Cancel) return false
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

    public data class CancelAck(
        public val groupId: String,
        public val root: ContentRoot,
        public val messageId: String,
    ) : SwarmFrame {
        override val typeCode: Int get() = 10
    }

    public data class SourceStatus(
        public val groupId: String,
        public val root: ContentRoot,
        public val originId: String,
        public val messageId: String,
        public val status: SourceState,
        public val reason: SourceReason,
        public val atMs: Long,
        public val signature: ByteArray,
    ) : SwarmFrame {
        init {
            require(groupId.isNotEmpty()) { "groupId must not be empty" }
            require(originId.isNotEmpty()) { "originId must not be empty" }
            require(messageId.isNotEmpty()) { "messageId must not be empty" }
            require(atMs > 0) { "atMs must be > 0" }
            require(signature.isNotEmpty()) { "signature must not be empty" }
        }

        override val typeCode: Int get() = 11

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SourceStatus) return false
            return groupId == other.groupId &&
                root == other.root &&
                originId == other.originId &&
                messageId == other.messageId &&
                status == other.status &&
                reason == other.reason &&
                atMs == other.atMs &&
                signature.contentEquals(other.signature)
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + root.hashCode()
            result = 31 * result + originId.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + status.hashCode()
            result = 31 * result + reason.hashCode()
            result = 31 * result + atMs.hashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    public data class Unrequest(
        public val groupId: String,
        public val root: ContentRoot,
        public val pieces: List<Int>,
    ) : SwarmFrame {
        override val typeCode: Int get() = 12
    }
}
