package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SourceReason
import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.swarm.model.SwarmWaitReason

/**
 * Commands emitted by [SwarmEngine] to be executed by the driver or host (§5.2).
 * Pure data: no I/O, no coroutines.
 */
public sealed interface SwarmCommand {
    /** Sends one encoded FSW1 frame to a connected peer. */
    public data class Send(
        public val peerId: String,
        public val frame: SwarmFrame,
    ) : SwarmCommand

    /** Asks the host connection planner for a session with a peer. */
    public data class RequestSession(
        public val peerId: String,
    ) : SwarmCommand

    /** Requests driver to read and verify piece [index] from local storage to serve [forPeerId]. */
    public data class ReadPiece(
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        public val forPeerId: String,
    ) : SwarmCommand

    /** Requests driver to write and verify piece [index] into local partial storage. */
    public data class WritePiece(
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        public val bytes: ByteArray,
    ) : SwarmCommand {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WritePiece) return false
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

    /** Requests driver to sync storage and durably persist bitfield progress (INV-4). */
    public data class SyncAndPersistBits(
        public val groupId: String,
        public val root: ContentRoot,
        public val bits: ByteArray,
        public val bytesDone: Long,
    ) : SwarmCommand {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SyncAndPersistBits) return false
            return groupId == other.groupId &&
                root == other.root &&
                bits.contentEquals(other.bits) &&
                bytesDone == other.bytesDone
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + root.hashCode()
            result = 31 * result + bits.contentHashCode()
            result = 31 * result + bytesDone.hashCode()
            return result
        }
    }

    /** Requests driver to finalize destination and check whole-file hash (ADR-068). */
    public data class Finalize(
        public val groupId: String,
        public val root: ContentRoot,
    ) : SwarmCommand

    /** Requests driver to delete partial file upon cancel or failure. */
    public data class DeletePartial(
        public val groupId: String,
        public val root: ContentRoot,
    ) : SwarmCommand

    /** Requests driver to persist or update content record in database. */
    public data class PersistRecord(
        public val record: SwarmContentRecord,
    ) : SwarmCommand

    /** Requests driver to cryptographically sign a tombstone statement as origin. */
    public data class SignTombstone(
        public val groupId: String,
        public val root: ContentRoot,
        public val originId: String,
        public val messageId: String,
        public val reason: SwarmTombstoneReason,
        public val cancelledAtMs: Long,
    ) : SwarmCommand

    /**
     * Requests driver to sign a [SwarmFrame.SourceStatus] statement as origin (ERROR-109). The driver answers with
     * [SwarmEvent.SourceStatusSigned]; nothing is sent until then, so a device that cannot sign says nothing.
     */
    public data class SignSourceStatus(
        public val groupId: String,
        public val root: ContentRoot,
        public val originId: String,
        public val messageId: String,
        public val status: SourceState,
        public val reason: SourceReason,
        public val atMs: Long,
    ) : SwarmCommand

    /** Requests driver to persist a verified tombstone in database. */
    public data class PersistTombstone(
        public val tombstone: SwarmTombstone,
    ) : SwarmCommand

    /** Updates UI state row via bridge. */
    public data class PublishRow(
        public val groupId: String,
        public val root: ContentRoot,
        public val state: SwarmLifecycleState,
        public val waitReason: SwarmWaitReason?,
        public val bytesDone: Long,
        public val totalSize: Long,
        public val failReason: String? = null,
    ) : SwarmCommand

    /** Structured debug log line. */
    public data class Log(
        public val line: String,
    ) : SwarmCommand
}
