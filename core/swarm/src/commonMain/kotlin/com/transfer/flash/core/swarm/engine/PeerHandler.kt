package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SwarmContentState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmFailReason
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmTombstone

/**
 * Handles peer connectivity, capabilities, HAVE/HAVE_ALL updates, SUMMARY exchanges, and membership changes (§4b, §4g, §4k).
 */
internal class PeerHandler(
    private val peers: MutableMap<String, PeerConnectionState>,
    private val contents: MutableMap<Pair<String, ContentRoot>, ContentState>,
    private val tombstones: MutableMap<Pair<String, String>, SwarmTombstone>,
    private val applyTombstone: (SwarmTombstone, MutableList<SwarmCommand>) -> Unit,
) {
    fun handlePeerUp(
        e: SwarmEvent.PeerUp,
        localServingEnabled: Boolean,
        systemSuspended: Boolean,
        commands: MutableList<SwarmCommand>,
        onPeerChanged: () -> Unit,
    ) {
        val peer = peers.getOrPut(e.peerId) { PeerConnectionState(e.peerId, e.features) }
        peer.isConnected = true
        peer.features = e.features

        val sharedGroups = (contents.values.map { it.groupId } + tombstones.values.map { it.groupId }).distinct()
        for (gid in sharedGroups) peer.allowedGroups[gid] = gid !in e.deniedGroups
        for (gid in e.deniedGroups) peer.allowedGroups[gid] = false

        if (peer.hasSw1Feature) {
            for (gid in sharedGroups) {
                if (peer.allowedGroups[gid] == false) continue
                sendSummary(e.peerId, gid, localServingEnabled, systemSuspended, commands)
            }
            for (content in contents.values) {
                if (peer.allowedGroups[content.groupId] == false) continue
                if (!content.isComplete && content.piecesDone > 0) {
                    val ranges = content.persistedBits.toRanges()
                    if (ranges.isNotEmpty()) {
                        commands.add(
                            SwarmCommand.Send(
                                peerId = e.peerId,
                                frame = SwarmFrame.Have(content.groupId, content.root, ranges),
                            )
                        )
                        val pcs = peer.getOrCreateContent(content.root, content.totalPieces)
                        pcs.lastHaveSentMs = e.nowMs
                        pcs.pendingHavePieces.clear()
                    }
                }
            }
        }
        onPeerChanged()
    }

    fun handlePeerDown(
        e: SwarmEvent.PeerDown,
        cancelPeerInFlight: (ContentState, String) -> Unit,
        onPeerChanged: () -> Unit,
    ) {
        val peer = peers[e.peerId] ?: return
        peer.isConnected = false

        for (content in contents.values) {
            cancelPeerInFlight(content, e.peerId)
            content.activeRequesters.remove(e.peerId)
            content.outstandingServeBytes.remove(e.peerId)
            content.originServedPiecesByPeer.remove(e.peerId)
            content.elsewhereRejections.keys.removeAll { it.first == e.peerId }
        }
        onPeerChanged()
    }

    fun handleSummaryArrived(
        e: SwarmEvent.SummaryArrived,
        commands: MutableList<SwarmCommand>,
        onSummaryApplied: (String) -> Unit,
    ) {
        // 1. Tombstones first (INV-5)
        for (tb in e.frame.tombstones) {
            applyTombstone(tb, commands)
        }

        // 2. Entries
        val peer = peers.getOrPut(e.peerId) { PeerConnectionState(e.peerId, emptySet()) }
        for (entry in e.frame.entries) {
            val content = contents[e.frame.groupId to entry.root] ?: continue
            val pcs = peer.getOrCreateContent(entry.root, content.totalPieces)
            pcs.servingEnabled = entry.servingEnabled
            when (entry.state) {
                SwarmContentState.ALL -> {
                    for (i in 0 until content.totalPieces) pcs.bitfield.set(i, true)
                }
                SwarmContentState.NONE -> {
                    for (i in 0 until content.totalPieces) pcs.bitfield.set(i, false)
                }
                SwarmContentState.PARTIAL -> { /* Populated via HAVE frames */ }
            }
        }
        onSummaryApplied(e.frame.groupId)
    }

    fun handleHaveArrived(
        e: SwarmEvent.HaveArrived,
        content: ContentState,
        onHaveApplied: (ContentState) -> Unit,
    ) {
        val peer = peers.getOrPut(e.peerId) { PeerConnectionState(e.peerId, emptySet()) }
        val pcs = peer.getOrCreateContent(e.frame.root, content.totalPieces)

        for (range in e.frame.ranges) {
            for (i in range.start until (range.start + range.count)) {
                if (i in 0 until content.totalPieces) {
                    pcs.bitfield.set(i, true)
                }
            }
        }
        onHaveApplied(content)
    }

    fun handleHaveAllArrived(
        e: SwarmEvent.HaveAllArrived,
        content: ContentState,
        onHaveAllApplied: (ContentState) -> Unit,
    ) {
        val peer = peers.getOrPut(e.peerId) { PeerConnectionState(e.peerId, emptySet()) }
        val pcs = peer.getOrCreateContent(e.frame.root, content.totalPieces)

        for (i in 0 until content.totalPieces) {
            pcs.bitfield.set(i, true)
        }

        if (content.role == SwarmRole.ORIGIN) {
            content.deliveredTo.add(e.peerId)
        }
        onHaveAllApplied(content)
    }

    fun handleMembershipChanged(
        e: SwarmEvent.MembershipChanged,
        localServingEnabled: Boolean,
        systemSuspended: Boolean,
        cancelPeerInFlight: (ContentState, String) -> Unit,
        cancelAllInFlight: (ContentState) -> Unit,
        commands: MutableList<SwarmCommand>,
        onContentModified: (ContentState) -> Unit,
    ) {
        if (!e.localActive) {
            for (content in contents.values.filter { it.groupId == e.groupId }) {
                // A removed member keeps what it already received: finished, cancelled and failed rows stay as they are.
                if (content.state == SwarmLifecycleState.COMPLETE ||
                    content.state == SwarmLifecycleState.CANCELLED ||
                    content.state == SwarmLifecycleState.FAILED
                ) {
                    continue
                }
                content.state = SwarmLifecycleState.FAILED
                content.failReason = SwarmFailReason.NOT_MEMBER.reasonCode
                cancelAllInFlight(content)
                commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
                commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
                onContentModified(content)
            }
            return
        }

        for ((peerId, peer) in peers) {
            val allowed = peerId in e.allowedPeers
            peer.allowedGroups[e.groupId] = allowed
            if (!allowed) {
                for (content in contents.values.filter { it.groupId == e.groupId }) {
                    cancelPeerInFlight(content, peerId)
                    content.activeRequesters.remove(peerId)
                    content.outstandingServeBytes.remove(peerId)
                }
            } else if (peer.isConnected && peer.hasSw1Feature) {
                sendSummary(peerId, e.groupId, localServingEnabled, systemSuspended, commands)
            }
        }

        for (content in contents.values.filter { it.groupId == e.groupId }) {
            onContentModified(content)
        }
    }

    fun sendSummary(
        peerId: String,
        groupId: String,
        localServingEnabled: Boolean,
        systemSuspended: Boolean,
        commands: MutableList<SwarmCommand>,
    ) {
        val groupTombstones = tombstones.values.filter { it.groupId == groupId }
        val entries = ArrayList<SwarmFrame.Summary.Entry>()

        for (content in contents.values.filter { it.groupId == groupId }) {
            val state = when {
                content.isComplete -> SwarmContentState.ALL
                content.piecesDone > 0 -> SwarmContentState.PARTIAL
                else -> SwarmContentState.NONE
            }
            entries.add(
                SwarmFrame.Summary.Entry(
                    root = content.root,
                    state = state,
                    servingEnabled = localServingEnabled && !systemSuspended,
                )
            )
        }

        commands.add(
            SwarmCommand.Send(
                peerId = peerId,
                frame = SwarmFrame.Summary(
                    groupId = groupId,
                    tombstones = groupTombstones,
                    entries = entries,
                ),
            )
        )
    }
}
