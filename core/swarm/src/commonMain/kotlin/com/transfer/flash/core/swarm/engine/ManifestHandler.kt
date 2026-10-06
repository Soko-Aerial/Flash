package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.SwarmLifecycleState

/**
 * Handles manifest acquisition, reassembly, and validation (§4a, E-15).
 */
internal class ManifestHandler(
    private val strikeBook: StrikeBook,
) {
    fun handleManifestPart(
        e: SwarmEvent.ManifestPartArrived,
        content: ContentState,
        commands: MutableList<SwarmCommand>,
        onManifestReady: (ContentState) -> Unit,
    ) {
        if (content.manifest != null) return

        var assembler = content.manifestAssembler
        if (assembler == null) {
            assembler = ManifestCodec.Reassembler(content.root)
            content.manifestAssembler = assembler
        }

        val decoded = assembler.addFragment(e.part.fragmentIndex, e.part.fragmentCount, e.part.bytes)
        if (decoded == null) {
            content.manifestRequestSentAtMs = e.nowMs
            val nextIdx = e.part.fragmentIndex + 1
            if (nextIdx < e.part.fragmentCount) {
                commands.add(
                    SwarmCommand.Send(
                        peerId = e.peerId,
                        frame = SwarmFrame.ManifestGet(content.groupId, content.root, nextIdx),
                    )
                )
            }
            return
        }

        content.manifest = decoded
        content.manifestAssembler = null
        content.manifestPeerInFlight = null
        commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
        onManifestReady(content)
    }

    fun handleManifestComplete(
        e: SwarmEvent.ManifestComplete,
        content: ContentState,
        commands: MutableList<SwarmCommand>,
        onManifestReady: (ContentState) -> Unit,
        retryFetch: (ContentState) -> Unit,
    ) {
        if (!e.valid) {
            strikeBook.recordStrike(e.peerId, content.root)
            content.manifestAssembler = null
            content.manifestPeerInFlight = null
            retryFetch(content)
            return
        }
        content.manifest = e.manifest
        content.manifestAssembler = null
        content.manifestPeerInFlight = null
        commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
        onManifestReady(content)
    }

    fun maybeFetchManifest(
        content: ContentState,
        peers: Map<String, PeerConnectionState>,
        isPeerAllowed: (String, String) -> Boolean,
        commands: MutableList<SwarmCommand>,
        nowMs: Long = 0L,
    ) {
        if (content.manifest != null || content.manifestPeerInFlight != null) return

        val candidatePeers = peers.values.filter { peer ->
            peer.isConnected &&
                peer.hasSw1Feature &&
                isPeerAllowed(content.groupId, peer.peerId) &&
                !strikeBook.isBanned(peer.peerId, content.root)
        }.sortedBy { strikeBook.getStrikes(it.peerId, content.root) }

        // A peer can only serve the manifest if it has the manifest.
        // Origin always has it; non-origin peers only have it if they hold pieces.
        val withContent = candidatePeers.filter { peer ->
            peer.peerId == content.originId || (peer.contentStates[content.root]?.bitfield?.count() ?: 0) > 0
        }
        val target = withContent.filter { it.peerId != content.originId }.firstOrNull()
            ?: withContent.firstOrNull { it.peerId == content.originId }
            ?: candidatePeers.firstOrNull()
            ?: return

        content.manifestPeerInFlight = target.peerId
        content.manifestRequestSentAtMs = nowMs
        commands.add(
            SwarmCommand.Send(
                peerId = target.peerId,
                frame = SwarmFrame.ManifestGet(content.groupId, content.root, 0),
            )
        )
    }
}
