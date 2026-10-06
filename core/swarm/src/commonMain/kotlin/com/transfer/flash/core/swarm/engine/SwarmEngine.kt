package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmFailReason
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmTombstone

/**
 * Sans-IO Swarm State Machine (§5.2, SW-4).
 * Pure state machine: no I/O, no system clock, no coroutines.
 * Deterministic: randomness derived strictly from [seed] (INV-11).
 */
public class SwarmEngine(
    public val config: SwarmConfig,
    public val localDeviceId: String,
    seed: Long,
) {
    private val random = SeededRandom(seed)
    public val strikeBook: StrikeBook = StrikeBook()
    private val requestWindow = RequestWindow(budgetBytes = config.profile.inFlightByteBudget)
    private val piecePicker = PiecePicker(random)
    private val sourceSelector = SourceSelector(random)
    private val servePolicy = ServePolicy()

    private val contents = LinkedHashMap<Pair<String, ContentRoot>, ContentState>()
    private val peers = LinkedHashMap<String, PeerConnectionState>()
    private val tombstones = LinkedHashMap<Pair<String, String>, SwarmTombstone>()

    private var networkUp: Boolean = true
    private var systemSuspended: Boolean = false
    private var callActive: Boolean = false
    private var localServingEnabled: Boolean = config.servingEnabled
    private var freeStorageBytes: Long = Long.MAX_VALUE

    private val manifestHandler = ManifestHandler(strikeBook)
    private val serveHandler = ServeHandler(servePolicy, requestWindow, config)
    private val transferScheduler = TransferScheduler(piecePicker, sourceSelector, requestWindow, strikeBook)
    private val contentLifecycleHandler = ContentLifecycleHandler(localDeviceId, config, strikeBook)
    private val peerHandler = PeerHandler(peers, contents, tombstones) { tb, cmds -> applyTombstone(tb, cmds) }

    public fun handle(event: SwarmEvent): List<SwarmCommand> {
        val commands = ArrayList<SwarmCommand>()
        when (event) {
            is SwarmEvent.Announced -> {
                contentLifecycleHandler.handleAnnounced(event, contents, tombstones, commands) { content ->
                    publishRow(content, commands)
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        updateWaitReason(content)
                        publishRow(content, commands)
                        if (content.role == SwarmRole.RECEIVER) {
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands)
                            transferScheduler.scheduleRequests(
                                content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, content.groupId, localServingEnabled, systemSuspended, commands)
                            }
                        }
                    }
                }
            }
            is SwarmEvent.TombstonesRestored -> {
                for (tb in event.tombstones) tombstones.putIfAbsent(tb.groupId to tb.messageId, tb)
            }
            is SwarmEvent.Restored -> {
                contentLifecycleHandler.handleRestored(event, contents, tombstones, commands) { content ->
                    publishRow(content, commands)
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        updateWaitReason(content)
                        publishRow(content, commands)
                        if (content.role == SwarmRole.RECEIVER) {
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands, event.nowMs)
                            transferScheduler.scheduleRequests(
                                content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                    if (content.state != SwarmLifecycleState.CANCELLED) {
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, content.groupId, localServingEnabled, systemSuspended, commands)
                                if (!content.isComplete && content.piecesDone > 0) {
                                    commands.add(
                                        SwarmCommand.Send(
                                            peerId,
                                            SwarmFrame.Have(content.groupId, content.root, content.persistedBits.toRanges()),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is SwarmEvent.Accepted -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleAccepted(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        manifestHandler.maybeFetchManifest(it, peers, ::isPeerAllowed, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(it.groupId, peerId)) {
                                peerHandler.sendSummary(peerId, it.groupId, localServingEnabled, systemSuspended, commands)
                            }
                        }
                    }
                }
            }
            is SwarmEvent.ManifestPartArrived -> {
                contents[event.part.groupId to event.part.root]?.let { content ->
                    manifestHandler.handleManifestPart(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.ManifestComplete -> {
                contents[event.groupId to event.root]?.let { content ->
                    manifestHandler.handleManifestComplete(
                        e = event,
                        content = content,
                        commands = commands,
                        onManifestReady = {
                            updateWaitReason(it)
                            publishRow(it, commands)
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        },
                        retryFetch = { manifestHandler.maybeFetchManifest(it, peers, ::isPeerAllowed, commands) }
                    )
                }
            }
            is SwarmEvent.PieceArrived -> {
                contents[event.groupId to event.root]?.let { content ->
                    val peerState = peers[event.peerId]?.contentStates?.get(content.root)
                    transferScheduler.handlePieceArrived(event, content, peerState, commands) {
                        // Driver writes piece
                    }
                }
            }
            is SwarmEvent.PieceStored -> {
                contents[event.groupId to event.root]?.let { content ->
                    transferScheduler.handlePieceStored(event, content, peers, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        if (!it.isComplete) {
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                }
            }
            is SwarmEvent.PieceStoreFailed -> {
                contents[event.groupId to event.root]?.let { content ->
                    transferScheduler.handlePieceStoreFailed(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.PieceRead -> {
                contents[event.groupId to event.root]?.let { content ->
                    val isTombstoned = (content.groupId to content.messageId) in tombstones ||
                        isTombstoned(content.groupId, content.root) ||
                        content.state == SwarmLifecycleState.CANCELLED
                    val isAllowed = isPeerAllowed(content.groupId, event.peerId)
                    serveHandler.handlePieceRead(event, content, isTombstoned, isAllowed, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.FinalizeResult -> {
                contents[event.groupId to event.root]?.let { content ->
                    transferScheduler.handleFinalizeResult(
                        event, content, peers, ::isPeerAllowed, commands
                    ) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        if (it.state == SwarmLifecycleState.ACTIVE) {
                            transferScheduler.scheduleRequests(
                                it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                }
            }
            is SwarmEvent.SourceStatusSigned -> {
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    // ERROR-109: only while the source is still lost; a late signature must not announce a loss that ended.
                    if (content.role == SwarmRole.ORIGIN && content.originSourceLost) {
                        content.signedSourceLost = event.frame
                        for ((peerId, peer) in peers) {
                            if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                                commands.add(SwarmCommand.Send(peerId = peerId, frame = event.frame))
                            }
                        }
                    }
                }
            }
            is SwarmEvent.SourceStatusArrived -> {
                if (!event.signatureValid) {
                    strikeBook.recordStrike(event.peerId, event.frame.root)
                    return commands
                }
                val target = contents[event.frame.groupId to event.frame.root]
                // ERROR-109: only the content's own origin can say its source is lost or back (a valid signature from any
                // other member is still not the origin), and an older statement than the one applied is a replay.
                if (target != null &&
                    (target.role != SwarmRole.RECEIVER ||
                        event.frame.originId != target.originId ||
                        event.frame.atMs < target.sourceStatusAtMs)
                ) {
                    return commands
                }
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    content.sourceStatusAtMs = event.frame.atMs
                    content.originSourceLost = event.frame.status == SourceState.LOST
                    updateWaitReason(content)
                    publishRow(content, commands)
                    transferScheduler.scheduleRequests(
                        content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                    )
                }
            }
            is SwarmEvent.PeerUp -> {
                peerHandler.handlePeerUp(event, localServingEnabled, systemSuspended, commands) {
                    recheckAllContents(event.nowMs, commands)
                }
            }
            is SwarmEvent.PeerDown -> {
                peerHandler.handlePeerDown(event, ::cancelPeerInFlight) {
                    recheckAllContents(event.nowMs, commands)
                }
            }
            is SwarmEvent.SummaryArrived -> {
                // ERROR-110: INV-3, a device outside the group exchanges nothing with us, tombstones included.
                if (!isPeerAllowed(event.frame.groupId, event.peerId)) return commands
                peerHandler.handleSummaryArrived(event, localServingEnabled, systemSuspended, commands) { gid ->
                    for (content in contents.values.filter { it.groupId == gid }) {
                        if (content.state == SwarmLifecycleState.ACTIVE) {
                            updateWaitReason(content)
                            publishRow(content, commands)
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands)
                            transferScheduler.scheduleRequests(
                                content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                            )
                        }
                    }
                }
            }
            is SwarmEvent.HaveArrived -> {
                if (!isPeerAllowed(event.frame.groupId, event.peerId)) return commands
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    peerHandler.handleHaveArrived(event, content) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.HaveAllArrived -> {
                if (!isPeerAllowed(event.frame.groupId, event.peerId)) return commands
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    peerHandler.handleHaveAllArrived(event, content) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.RequestArrived -> {
                val content = contents[event.frame.groupId to event.frame.root]
                val isTombstoned = (event.frame.groupId to (content?.messageId ?: "")) in tombstones ||
                    isTombstoned(event.frame.groupId, event.frame.root) ||
                    content?.state == SwarmLifecycleState.CANCELLED
                serveHandler.handleRequestArrived(
                    e = event,
                    content = content,
                    isTombstoned = isTombstoned,
                    localServingEnabled = localServingEnabled,
                    systemSuspended = systemSuspended,
                    callActive = callActive,
                    countPeerCopies = { idx -> countPeerCopies(content, idx, requesterPeerId = event.peerId) },
                    countPendingServes = { idx -> countPendingServes(content, idx) },
                    commands = commands,
                )
            }
            is SwarmEvent.UnrequestArrived -> {
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    serveHandler.handleUnrequestArrived(event, content)
                }
            }
            is SwarmEvent.RejectArrived -> {
                contents[event.frame.groupId to event.frame.root]?.let { content ->
                    val peerState = peers[event.peerId]?.contentStates?.get(content.root)
                    serveHandler.handleRejectArrived(event, content, peerState)
                    transferScheduler.scheduleRequests(
                        content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                    )
                }
            }
            is SwarmEvent.CancelArrived -> {
                contentLifecycleHandler.handleCancelArrived(
                    event, peers, ::isPeerAllowed, { tb -> applyTombstone(tb, commands) }, commands
                )
            }
            is SwarmEvent.CancelAckArrived -> { /* Log or no-op */ }
            is SwarmEvent.LocalCancel -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleLocalCancel(event, content, ::cancelInFlight, commands) {
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.LocalPause -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleLocalPause(event, content, ::cancelInFlight, commands) {
                        publishRow(it, commands)
                    }
                }
            }
            is SwarmEvent.LocalResume -> {
                contents[event.groupId to event.root]?.let { content ->
                    contentLifecycleHandler.handleLocalResume(event, content, commands) {
                        updateWaitReason(it)
                        publishRow(it, commands)
                        manifestHandler.maybeFetchManifest(it, peers, ::isPeerAllowed, commands)
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.TombstoneSigned -> {
                applyTombstone(event.tombstone, commands)
                for ((peerId, peer) in peers) {
                    if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(event.tombstone.groupId, peerId)) {
                        commands.add(
                            SwarmCommand.Send(
                                peerId = peerId,
                                frame = SwarmFrame.Cancel(
                                    groupId = event.tombstone.groupId,
                                    root = event.tombstone.root,
                                    originId = event.tombstone.originId,
                                    messageId = event.tombstone.messageId,
                                    reason = event.tombstone.reason,
                                    cancelledAtMs = event.tombstone.cancelledAtMs,
                                    signature = event.tombstone.signature,
                                ),
                            )
                        )
                    }
                }
            }
            is SwarmEvent.MembershipChanged -> {
                peerHandler.handleMembershipChanged(
                    event, localServingEnabled, systemSuspended, ::cancelPeerInFlight, ::cancelInFlight, commands
                ) {
                    updateWaitReason(it)
                    publishRow(it, commands)
                    if (it.state == SwarmLifecycleState.ACTIVE) {
                        transferScheduler.scheduleRequests(
                            it, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
            is SwarmEvent.NetworkUp -> {
                networkUp = true
                recheckAllContents(event.nowMs, commands)
            }
            is SwarmEvent.NetworkDown -> {
                networkUp = false
                for (content in contents.values) {
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        cancelInFlight(content)
                        updateWaitReason(content)
                        publishRow(content, commands)
                    }
                }
            }
            is SwarmEvent.SpaceChanged -> {
                freeStorageBytes = event.freeBytes
                recheckAllContents(event.nowMs, commands)
            }
            is SwarmEvent.SystemSuspend -> {
                systemSuspended = true
                for (content in contents.values) {
                    if (content.state == SwarmLifecycleState.ACTIVE) {
                        cancelInFlight(content)
                        updateWaitReason(content)
                        publishRow(content, commands)
                    }
                }
            }
            is SwarmEvent.SystemResume -> {
                systemSuspended = false
                recheckAllContents(event.nowMs, commands)
            }
            is SwarmEvent.ServingEnabled -> {
                localServingEnabled = event.enabled
            }
            is SwarmEvent.CallActive -> {
                callActive = event.active
                requestWindow.setCallActive(event.active)
            }
            is SwarmEvent.Tick -> {
                transferScheduler.checkTimeouts(contents.values, peers, event.nowMs)
                transferScheduler.flushPendingHaves(contents.values, peers, ::isPeerAllowed, event.nowMs, commands)

                for (content in contents.values) {
                    if (content.state != SwarmLifecycleState.COMPLETE &&
                        content.state != SwarmLifecycleState.CANCELLED &&
                        content.state != SwarmLifecycleState.FAILED &&
                        event.nowMs >= content.expiresAtMs
                    ) {
                        content.state = SwarmLifecycleState.FAILED
                        content.failReason = SwarmFailReason.EXPIRED.reasonCode
                        cancelInFlight(content)
                        commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
                        publishRow(content, commands)
                        commands.add(SwarmCommand.PersistRecord(content.toRecord(event.nowMs)))
                    } else if (content.state == SwarmLifecycleState.ACTIVE) {
                        updateWaitReason(content)
                        if (content.role == SwarmRole.RECEIVER && content.manifest == null) {
                            if (content.manifestPeerInFlight != null && event.nowMs - content.manifestRequestSentAtMs > 5_000L) {
                                content.manifestPeerInFlight = null
                                content.manifestAssembler = null
                            }
                            manifestHandler.maybeFetchManifest(content, peers, ::isPeerAllowed, commands, event.nowMs)
                        }
                        transferScheduler.scheduleRequests(
                            content, peers, ::isPeerAllowed, event.nowMs, systemSuspended, networkUp, commands
                        )
                    }
                }
            }
        }
        return commands
    }

    public fun snapshot(): SwarmSnapshot {
        val map = LinkedHashMap<Pair<String, ContentRoot>, ContentSnapshot>()
        for ((key, c) in contents) {
            val (onlineHolders, distCopies) = computeHolderStats(c)
            map[key] = ContentSnapshot(
                groupId = c.groupId,
                root = c.root,
                state = c.state,
                waitReason = c.waitReason,
                bytesDone = c.bytesDone,
                totalBytes = c.totalSize,
                piecesDone = c.piecesDone,
                totalPieces = c.totalPieces,
                holdersOnline = onlineHolders,
                distributedCopies = distCopies,
                canGoOffline = c.role == SwarmRole.ORIGIN && distCopies >= 1,
                deliveredTo = c.deliveredTo.toSet(),
            )
        }
        return SwarmSnapshot(map)
    }

    public fun debugDump(): String {
        val sb = StringBuilder()
        for ((key, c) in contents) {
            sb.appendLine("Content ${c.groupId}:${c.root.hex}: state=${c.state}, wait=${c.waitReason}, inFlight=${c.inFlightRequests.entries.map { "${it.key.first.take(6)}:${it.key.second}@${it.value}" }}, bits=${c.bitfield.count()}/${c.totalPieces}")
        }
        for ((peerId, p) in peers) {
            sb.appendLine("Peer ${peerId.take(6)}: isConnected=${p.isConnected}, sw1=${p.hasSw1Feature}")
            for ((root, pcs) in p.contentStates) {
                sb.appendLine("  pcs ${root.hex.take(8)}: bitsCount=${pcs.bitfield.count()}, servingEnabled=${pcs.servingEnabled}, backoff=${pcs.backoffUntilMs}, win=${requestWindow.getWindow(peerId, root)}, inFlightBytes=${requestWindow.currentInFlightBytes}")
            }
        }
        return sb.toString()
    }

    private fun cancelInFlight(content: ContentState) {
        for ((key, _) in content.inFlightRequests) {
            val pLen = content.pieceLength(key.second).toLong()
            requestWindow.removeInFlightBytes(pLen)
        }
        content.inFlightRequests.clear()
        content.inFlightByPiece.clear()
    }

    private fun cancelPeerInFlight(content: ContentState, peerId: String) {
        val toRemove = ArrayList<Int>()
        for ((key, _) in content.inFlightRequests) {
            if (key.first == peerId) toRemove.add(key.second)
        }
        for (p in toRemove) {
            content.inFlightRequests.remove(peerId to p)
            content.inFlightByPiece[p]?.remove(peerId)
            val pLen = content.pieceLength(p).toLong()
            requestWindow.removeInFlightBytes(pLen)
        }
    }

    private fun isPeerAllowed(groupId: String, peerId: String): Boolean {
        return peers[peerId]?.allowedGroups?.get(groupId) ?: true
    }

    private fun updateWaitReason(content: ContentState) {
        val (onlineHolders, _) = computeHolderStats(content)
        val isOriginConn = peers[content.originId]?.isConnected == true

        val offlineCandidatePeers = peers.values.filter {
            !it.isConnected && it.peerId != localDeviceId && isPeerAllowed(content.groupId, it.peerId)
        }
        var hasOfflineHolders = false
        for (p in offlineCandidatePeers) {
            val pcs = p.contentStates[content.root]
            if (pcs != null && !pcs.bitfield.isEmpty()) {
                for (i in 0 until content.totalPieces) {
                    if (!content.bitfield.get(i) && pcs.bitfield.get(i)) {
                        hasOfflineHolders = true
                        break
                    }
                }
            }
            if (hasOfflineHolders) break
        }

        content.waitReason = WaitClassifier.classify(
            WaitClassificationContext(
                systemSuspended = systemSuspended,
                networkUp = networkUp,
                freeBytes = freeStorageBytes,
                remainingBytes = content.remainingBytes,
                storageUnavailable = content.storageUnavailable,
                isOrigin = content.role == SwarmRole.ORIGIN,
                isOriginConnected = isOriginConn,
                isOriginSourceLost = content.originSourceLost,
                hasConnectedHoldersForMissing = onlineHolders > 0,
                hasDisconnectedHoldersForMissing = hasOfflineHolders,
            )
        )
    }

    private fun publishRow(content: ContentState, commands: MutableList<SwarmCommand>) {
        commands.add(
            SwarmCommand.PublishRow(
                groupId = content.groupId,
                root = content.root,
                state = content.state,
                waitReason = content.waitReason,
                bytesDone = content.bytesDone,
                totalSize = content.totalSize,
                failReason = content.failReason,
            )
        )
    }

    private fun applyTombstone(tombstone: SwarmTombstone, commands: MutableList<SwarmCommand>) {
        val key = tombstone.groupId to tombstone.messageId
        if (key in tombstones) return
        tombstones[key] = tombstone
        commands.add(SwarmCommand.PersistTombstone(tombstone))

        val content = contents[tombstone.groupId to tombstone.root] ?: return
        if (content.messageId == tombstone.messageId) {
            content.activeRequesters.clear()
            content.outstandingServeBytes.clear()
            if (content.state == SwarmLifecycleState.COMPLETE) {
                // Keep file, stop serving
            } else {
                content.state = SwarmLifecycleState.CANCELLED
                cancelInFlight(content)
                commands.add(SwarmCommand.DeletePartial(content.groupId, content.root))
                commands.add(SwarmCommand.PersistRecord(content.toRecord(tombstone.cancelledAtMs)))
                publishRow(content, commands)
            }
        }
    }

    private fun isTombstoned(groupId: String, root: ContentRoot): Boolean {
        return tombstones.values.any { it.groupId == groupId && it.root == root }
    }

    private fun recheckAllContents(nowMs: Long, commands: MutableList<SwarmCommand>) {
        for (content in contents.values) {
            if (content.state == SwarmLifecycleState.ACTIVE) {
                updateWaitReason(content)
                publishRow(content, commands)
                transferScheduler.scheduleRequests(
                    content, peers, ::isPeerAllowed, nowMs, systemSuspended, networkUp, commands
                )
            }
        }
    }

    private fun computeHolderStats(content: ContentState): Pair<Int, Int> {
        val candidatePeers = peers.values.filter {
            it.isConnected && it.peerId != localDeviceId && isPeerAllowed(content.groupId, it.peerId)
        }
        var onlineHolders = 0
        for (p in candidatePeers) {
            val pcs = p.contentStates[content.root]
            if (pcs != null && !pcs.bitfield.isEmpty()) {
                onlineHolders++
            }
        }

        var minCopies = Int.MAX_VALUE
        for (i in 0 until content.totalPieces) {
            var copiesForI = 0
            for (p in candidatePeers) {
                val pcs = p.contentStates[content.root]
                if (pcs != null && pcs.bitfield.get(i)) {
                    copiesForI++
                }
            }
            if (copiesForI < minCopies) {
                minCopies = copiesForI
            }
        }
        val distCopies = if (minCopies == Int.MAX_VALUE) 0 else minCopies
        return onlineHolders to distCopies
    }

    private fun countPeerCopies(content: ContentState?, index: Int, requesterPeerId: String? = null): Int {
        if (content == null) return 0
        var count = 0
        for ((peerId, peer) in peers) {
            if (peerId != localDeviceId && (requesterPeerId == null || peerId != requesterPeerId) && peer.isConnected && peer.hasSw1Feature) {
                if (!isPeerAllowed(content.groupId, peerId)) continue
                if (strikeBook.isBanned(peerId, content.root)) continue
                val pcs = peer.contentStates[content.root]
                if (pcs != null && !pcs.servingEnabled) continue

                val fromHave = pcs != null && pcs.bitfield.get(index)
                val fromOrigin = content.role == SwarmRole.ORIGIN && content.originServedPiecesByPeer[peerId]?.contains(index) == true
                if (fromHave || fromOrigin) {
                    count++
                }
            }
        }
        return count
    }

    private fun countPendingServes(content: ContentState?, index: Int): Int {
        return content?.pendingServesByPiece?.get(index) ?: 0
    }
}
