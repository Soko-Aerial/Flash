package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason

/**
 * Inbound events fed into [SwarmEngine] (§5.2).
 * Every event carries [nowMs] for pure deterministic time processing without system clock calls.
 */
public sealed interface SwarmEvent {
    public val nowMs: Long

    // --- Content Lifecycle Events ---

    public data class Announced(
        public val groupId: String,
        public val messageId: String,
        public val originId: String,
        public val originKey: String,
        public val root: ContentRoot,
        public val totalSize: Long,
        public val pieceSize: Int,
        public val fileName: String,
        public val mime: String,
        public val sentAtMs: Long,
        public val expiresAtMs: Long,
        public val isOrigin: Boolean,
        public val localUri: String? = null,
        public val autoAccept: Boolean? = null,
        public val manifest: SwarmManifest? = null,
        override val nowMs: Long,
    ) : SwarmEvent

    /**
     * Rebuilds one persisted content item after a process restart (R4, E-22, E-29).
     * Only the bits that were persisted after `fsync` count (INV-4).
     */
    public data class Restored(
        public val record: SwarmContentRecord,
        override val nowMs: Long,
    ) : SwarmEvent

    /** Loads persisted tombstones into the engine without re-persisting or re-broadcasting them (INV-5). */
    public data class TombstonesRestored(
        public val tombstones: List<SwarmTombstone>,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class Accepted(
        public val groupId: String,
        public val root: ContentRoot,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class ManifestPartArrived(
        public val peerId: String,
        public val part: SwarmFrame.ManifestPart,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class ManifestComplete(
        public val peerId: String,
        public val groupId: String,
        public val root: ContentRoot,
        public val manifest: SwarmManifest,
        public val valid: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class PieceArrived(
        public val peerId: String,
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        public val bytes: ByteArray,
        public val verified: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PieceArrived) return false
            return peerId == other.peerId &&
                groupId == other.groupId &&
                root == other.root &&
                index == other.index &&
                bytes.contentEquals(other.bytes) &&
                verified == other.verified &&
                nowMs == other.nowMs
        }

        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + groupId.hashCode()
            result = 31 * result + root.hashCode()
            result = 31 * result + index
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + verified.hashCode()
            result = 31 * result + nowMs.hashCode()
            return result
        }
    }

    public data class PieceStored(
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class PieceStoreFailed(
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        public val error: String,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class PieceRead(
        public val peerId: String,
        public val groupId: String,
        public val root: ContentRoot,
        public val index: Int,
        public val bytes: ByteArray?,
        public val status: PieceReadStatus,
        override val nowMs: Long,
    ) : SwarmEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PieceRead) return false
            return peerId == other.peerId &&
                groupId == other.groupId &&
                root == other.root &&
                index == other.index &&
                ((bytes == null && other.bytes == null) ||
                    (bytes != null && other.bytes != null && bytes.contentEquals(other.bytes))) &&
                status == other.status &&
                nowMs == other.nowMs
        }

        override fun hashCode(): Int {
            var result = peerId.hashCode()
            result = 31 * result + groupId.hashCode()
            result = 31 * result + root.hashCode()
            result = 31 * result + index
            result = 31 * result + (bytes?.contentHashCode() ?: 0)
            result = 31 * result + status.hashCode()
            result = 31 * result + nowMs.hashCode()
            return result
        }
    }

    public data class FinalizeResult(
        public val groupId: String,
        public val root: ContentRoot,
        public val ok: Boolean,
        public val badPieces: List<Int> = emptyList(),
        public val finalPath: String? = null,
        public val identitySize: Long? = null,
        public val identityModifiedMs: Long? = null,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class SourceStatusArrived(
        public val peerId: String,
        public val frame: SwarmFrame.SourceStatus,
        public val signatureValid: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent

    // --- Peer Connection & Frame Events ---

    public data class PeerUp(
        public val peerId: String,
        public val features: Set<String>,
        override val nowMs: Long,
        /** Groups the host's gate says this peer may NOT exchange content for; nothing is sent for them. */
        public val deniedGroups: Set<String> = emptySet(),
    ) : SwarmEvent

    public data class PeerDown(
        public val peerId: String,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class SummaryArrived(
        public val peerId: String,
        public val frame: SwarmFrame.Summary,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class HaveArrived(
        public val peerId: String,
        public val frame: SwarmFrame.Have,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class HaveAllArrived(
        public val peerId: String,
        public val frame: SwarmFrame.HaveAll,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class RequestArrived(
        public val peerId: String,
        public val frame: SwarmFrame.Request,
        public val allowed: Boolean,
        override val nowMs: Long,
        /** False when the group's serve switches (signed setting or local preference) forbid serving. */
        public val serveAllowed: Boolean = true,
    ) : SwarmEvent

    public data class UnrequestArrived(
        public val peerId: String,
        public val frame: SwarmFrame.Unrequest,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class RejectArrived(
        public val peerId: String,
        public val frame: SwarmFrame.Reject,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class CancelArrived(
        public val peerId: String,
        public val frame: SwarmFrame.Cancel,
        public val signatureValid: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class CancelAckArrived(
        public val peerId: String,
        public val frame: SwarmFrame.CancelAck,
        override val nowMs: Long,
    ) : SwarmEvent

    // --- Local Actions ---

    public data class LocalCancel(
        public val groupId: String,
        public val root: ContentRoot,
        public val asOrigin: Boolean,
        public val reason: SwarmTombstoneReason = SwarmTombstoneReason.USER,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class LocalPause(
        public val groupId: String,
        public val root: ContentRoot,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class LocalResume(
        public val groupId: String,
        public val root: ContentRoot,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class TombstoneSigned(
        public val tombstone: SwarmTombstone,
        override val nowMs: Long,
    ) : SwarmEvent

    // --- Environment Events ---

    public data class MembershipChanged(
        public val groupId: String,
        public val allowedPeers: Set<String>,
        public val localActive: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class NetworkUp(
        override val nowMs: Long,
    ) : SwarmEvent

    public data class NetworkDown(
        override val nowMs: Long,
    ) : SwarmEvent

    public data class SpaceChanged(
        public val freeBytes: Long,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class SystemSuspend(
        override val nowMs: Long,
    ) : SwarmEvent

    public data class SystemResume(
        override val nowMs: Long,
    ) : SwarmEvent

    public data class ServingEnabled(
        public val enabled: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class CallActive(
        public val active: Boolean,
        override val nowMs: Long,
    ) : SwarmEvent

    public data class Tick(
        override val nowMs: Long,
    ) : SwarmEvent
}
