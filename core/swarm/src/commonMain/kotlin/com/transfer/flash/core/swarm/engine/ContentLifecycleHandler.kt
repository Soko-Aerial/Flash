package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SourceReason
import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason

/**
 * Handles content registration, user acceptance, pause/resume, cancellations, and tombstones (§4a, §4j, INV-5, INV-6, INV-10).
 */
internal class ContentLifecycleHandler(
    private val localDeviceId: String,
    private val config: SwarmConfig,
    private val strikeBook: StrikeBook,
) {
    fun handleAnnounced(
        e: SwarmEvent.Announced,
        contents: MutableMap<Pair<String, ContentRoot>, ContentState>,
        tombstones: Map<Pair<String, String>, SwarmTombstone>,
        commands: MutableList<SwarmCommand>,
        onContentCreated: (ContentState) -> Unit,
    ) {
        val tombstoneKey = e.groupId to e.messageId
        if (tombstoneKey in tombstones) {
            commands.add(SwarmCommand.Log("SWARM: announced content already tombstoned: ${e.root}"))
            return
        }

        val key = e.groupId to e.root
        if (key in contents) return

        val role = if (e.isOrigin || e.originId == localDeviceId) SwarmRole.ORIGIN else SwarmRole.RECEIVER
        val autoAccept = e.autoAccept ?: config.autoAcceptIncoming
        val initialState = when {
            role == SwarmRole.ORIGIN -> SwarmLifecycleState.ACTIVE
            autoAccept -> SwarmLifecycleState.ACTIVE
            else -> SwarmLifecycleState.OFFERED
        }

        val content = ContentState(
            groupId = e.groupId,
            root = e.root,
            messageId = e.messageId,
            role = role,
            originId = e.originId,
            originKey = e.originKey,
            fileName = e.fileName,
            mime = e.mime,
            totalSize = e.totalSize,
            pieceSize = e.pieceSize,
            manifest = e.manifest,
            localTransferId = e.messageId,
            sourceUri = e.localUri,
            partialKey = "part_${e.groupId}_${e.root.hex}",
            createdAtMs = e.sentAtMs,
            expiresAtMs = e.expiresAtMs,
            initialState = initialState,
        )
        if (role == SwarmRole.ORIGIN) {
            for (i in 0 until content.totalPieces) {
                content.bitfield.set(i, true)
                content.persistedBits.set(i, true)
            }
            content.bytesDone = content.totalSize
        }
        contents[key] = content

        commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
        onContentCreated(content)
    }

    /**
     * Rebuilds a persisted content item (R4). Never trusts `bytesDone` or a transient VERIFYING state:
     * progress is recomputed from the persisted bits, and a complete receiver is finalized again.
     */
    fun handleRestored(
        e: SwarmEvent.Restored,
        contents: MutableMap<Pair<String, ContentRoot>, ContentState>,
        tombstones: Map<Pair<String, String>, SwarmTombstone>,
        commands: MutableList<SwarmCommand>,
        onRestored: (ContentState) -> Unit,
    ) {
        val r = e.record
        val key = r.groupId to r.root
        if (key in contents) return

        val manifest = r.manifestBytes?.let { ManifestCodec.decode(it, r.root) }
        val restoredState = when (r.state) {
            SwarmLifecycleState.VERIFYING -> SwarmLifecycleState.ACTIVE
            else -> r.state
        }
        val content = ContentState(
            groupId = r.groupId,
            root = r.root,
            messageId = r.messageId,
            role = r.role,
            originId = r.originId,
            originKey = r.originKey,
            fileName = r.fileName,
            mime = r.mime,
            totalSize = r.totalSize,
            pieceSize = r.pieceSize,
            manifest = manifest,
            localTransferId = r.localTransferId,
            sourceUri = r.sourceUri,
            partialKey = r.partialKey,
            createdAtMs = r.createdAtMs,
            expiresAtMs = r.expiresAtMs,
            initialState = restoredState,
        )
        content.failReason = r.failReason
        content.finalPath = r.finalPath
        content.identitySize = r.identitySize
        content.identityModifiedMs = r.identityModifiedMs
        content.deliveredTo.addAll(r.deliveredTo)

        if (r.role == SwarmRole.ORIGIN && r.bits.size == (content.totalPieces + 7) / 8) {
            // ERROR-109: an origin starts with every bit set, so a persisted bit that is clear is a piece whose source could
            // not be read. Honouring it keeps a restart from claiming the whole file again.
            val persisted = Bitfield.fromByteArray(content.totalPieces, r.bits)
            for (i in 0 until content.totalPieces) {
                if (!persisted.get(i)) {
                    content.bitfield.set(i, false)
                    content.persistedBits.set(i, false)
                    content.bytesDone -= content.pieceLength(i)
                    content.originSourceLost = true
                }
            }
        }
        if (r.role == SwarmRole.RECEIVER) {
            val persisted = if (r.state == SwarmLifecycleState.COMPLETE) {
                Bitfield(content.totalPieces).also { for (i in 0 until content.totalPieces) it.set(i, true) }
            } else {
                Bitfield.fromByteArray(content.totalPieces, r.bits)
            }
            var done = 0L
            for (i in persisted.setIndices()) {
                content.bitfield.set(i, true)
                content.persistedBits.set(i, true)
                done += content.pieceLength(i)
            }
            content.bytesDone = done
        }
        contents[key] = content

        var changed = restoredState != r.state
        val tombstoned = (r.groupId to r.messageId) in tombstones
        if (tombstoned && content.state != SwarmLifecycleState.COMPLETE && content.state != SwarmLifecycleState.CANCELLED) {
            content.state = SwarmLifecycleState.CANCELLED
            commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
            changed = true
        } else if (content.role == SwarmRole.RECEIVER &&
            content.state == SwarmLifecycleState.ACTIVE &&
            content.manifest != null &&
            content.isComplete
        ) {
            // The last piece was persisted but finalization never finished.
            content.state = SwarmLifecycleState.VERIFYING
            commands.add(SwarmCommand.Finalize(content.groupId, content.root))
            changed = true
        }
        if (changed) commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
        if (content.role == SwarmRole.ORIGIN && content.originSourceLost && content.state == SwarmLifecycleState.ACTIVE) {
            commands.add(
                SwarmCommand.SignSourceStatus(
                    groupId = content.groupId,
                    root = content.root,
                    originId = content.originId,
                    messageId = content.messageId,
                    status = SourceState.LOST,
                    reason = SourceReason.NONE,
                    atMs = e.nowMs,
                )
            )
        }
        commands.add(
            SwarmCommand.Log(
                "restored root=${r.root.hex.take(8)} group=${r.groupId} role=${r.role} state=${content.state} " +
                    "pieces=${content.piecesDone}/${content.totalPieces}"
            )
        )
        onRestored(content)
    }

    fun handleAccepted(
        e: SwarmEvent.Accepted,
        content: ContentState,
        commands: MutableList<SwarmCommand>,
        onAccepted: (ContentState) -> Unit,
    ) {
        if (content.state == SwarmLifecycleState.OFFERED) {
            content.state = SwarmLifecycleState.ACTIVE
            commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            onAccepted(content)
        }
    }

    fun handleLocalPause(
        e: SwarmEvent.LocalPause,
        content: ContentState,
        cancelInFlight: (ContentState) -> Unit,
        commands: MutableList<SwarmCommand>,
        onStateChanged: (ContentState) -> Unit,
    ) {
        if (content.state == SwarmLifecycleState.ACTIVE) {
            content.state = SwarmLifecycleState.PAUSED_BY_USER
            cancelInFlight(content)
            commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            onStateChanged(content)
        }
    }

    fun handleLocalResume(
        e: SwarmEvent.LocalResume,
        content: ContentState,
        commands: MutableList<SwarmCommand>,
        onResumed: (ContentState) -> Unit,
    ) {
        if (content.state == SwarmLifecycleState.PAUSED_BY_USER) {
            content.state = SwarmLifecycleState.ACTIVE
            commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            onResumed(content)
        } else if (content.state == SwarmLifecycleState.ACTIVE && content.storageUnavailable) {
            content.storageUnavailable = false
            commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            onResumed(content)
        }
    }

    fun handleLocalCancel(
        e: SwarmEvent.LocalCancel,
        content: ContentState,
        cancelInFlight: (ContentState) -> Unit,
        commands: MutableList<SwarmCommand>,
        onCancelled: (ContentState) -> Unit,
    ) {
        if (e.asOrigin) {
            commands.add(
                SwarmCommand.SignTombstone(
                    groupId = content.groupId,
                    root = content.root,
                    originId = content.originId,
                    messageId = content.messageId,
                    reason = e.reason,
                    cancelledAtMs = e.nowMs,
                )
            )
        } else {
            content.state = SwarmLifecycleState.CANCELLED
            cancelInFlight(content)
            commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
            commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            onCancelled(content)
        }
    }

    fun handleCancelArrived(
        e: SwarmEvent.CancelArrived,
        peers: Map<String, PeerConnectionState>,
        isPeerAllowed: (String, String) -> Boolean,
        applyTombstone: (SwarmTombstone) -> Unit,
        commands: MutableList<SwarmCommand>,
    ) {
        if (!e.signatureValid) {
            strikeBook.recordStrike(e.peerId, e.frame.root)
            commands.add(SwarmCommand.Log("SWARM: invalid cancel signature from ${e.peerId} for ${e.frame.root}"))
            return
        }

        val tombstone = e.frame.toTombstone()
        applyTombstone(tombstone)

        commands.add(
            SwarmCommand.Send(
                peerId = e.peerId,
                frame = SwarmFrame.CancelAck(e.frame.groupId, e.frame.root, e.frame.messageId),
            )
        )

        for ((peerId, peer) in peers) {
            if (peerId != e.peerId && peer.isConnected && peer.hasSw1Feature && isPeerAllowed(e.frame.groupId, peerId)) {
                commands.add(SwarmCommand.Send(peerId, e.frame))
            }
        }
    }
}
