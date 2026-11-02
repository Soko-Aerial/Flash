package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.PieceRange
import com.transfer.flash.core.swarm.model.SwarmFailReason
import com.transfer.flash.core.swarm.model.SwarmLifecycleState

/**
 * Orchestrates piece requesting, arrival, local storage persistence, finalization, and periodic tick sweeps (§4c, §4d, §4h, §4m).
 */
internal class TransferScheduler(
    private val piecePicker: PiecePicker,
    private val sourceSelector: SourceSelector,
    private val requestWindow: RequestWindow,
    private val strikeBook: StrikeBook,
) {
    fun scheduleRequests(
        content: ContentState,
        peers: Map<String, PeerConnectionState>,
        isPeerAllowed: (String, String) -> Boolean,
        nowMs: Long,
        systemSuspended: Boolean,
        networkUp: Boolean,
        commands: MutableList<SwarmCommand>,
    ) {
        if (content.state != SwarmLifecycleState.ACTIVE || content.manifest == null || content.isComplete) return
        if (systemSuspended || !networkUp) return

        val candidatePeers = peers.values.filter { peer ->
            peer.isConnected &&
                peer.hasSw1Feature &&
                isPeerAllowed(content.groupId, peer.peerId) &&
                !strikeBook.isBanned(peer.peerId, content.root)
        }
        if (candidatePeers.isEmpty()) return

        // Everything below is built once per pass instead of once per requested piece (OPT-O08-01): the peer views,
        // the per-peer in-flight sets and the source candidates used to be rebuilt inside the while-loop, which
        // allocated three collections per peer per slot (about 960 objects for a 16-slot pass).
        val inFlightByPeer = HashMap<String, MutableSet<Int>>(candidatePeers.size)
        for (key in content.inFlightRequests.keys) {
            inFlightByPeer.getOrPut(key.first) { HashSet() }.add(key.second)
        }

        val available = ArrayList<PeerPieceAvailability>(candidatePeers.size)
        val candidates = ArrayList<PeerSourceCandidate>(candidatePeers.size)
        for (p in candidatePeers) {
            val pcs = p.contentStates[content.root] ?: continue
            val inFlight = inFlightByPeer.getOrPut(p.peerId) { HashSet() }
            val win = requestWindow.getWindow(p.peerId, content.root)
            candidates.add(
                PeerSourceCandidate(
                    peerId = p.peerId,
                    isOrigin = p.peerId == content.originId,
                    bitfield = pcs.bitfield,
                    isConnected = p.isConnected,
                    isAllowed = isPeerAllowed(content.groupId, p.peerId),
                    hasSw1Feature = p.hasSw1Feature,
                    servingEnabled = pcs.servingEnabled,
                    strikes = strikeBook.getStrikes(p.peerId, content.root),
                    backoffUntilMs = pcs.backoffUntilMs,
                    inFlightCount = inFlight.size,
                    window = win,
                    ewmaSpeedBps = pcs.ewmaSpeedBps,
                    inFlightPieces = inFlight,
                )
            )
            if (pcs.servingEnabled && nowMs >= pcs.backoffUntilMs && inFlight.size < win) {
                available.add(PeerPieceAvailability(p.peerId, pcs.bitfield, inFlight.size, win))
            }
        }
        if (available.isEmpty()) return

        val scan = piecePicker.beginScan(
            localBitfield = content.bitfield,
            availablePeers = available,
            inFlightByPiece = content.inFlightByPiece,
            canDuplicate = { p ->
                val fetcher = content.inFlightByPiece[p]?.firstOrNull()
                val sentAt = if (fetcher != null) content.inFlightRequests[fetcher to p] ?: nowMs else nowMs
                val elapsedMs = nowMs - sentAt
                val pcs = if (fetcher != null) peers[fetcher]?.contentStates?.get(content.root) else null
                val speedBps = pcs?.ewmaSpeedBps ?: 1_000_000.0
                val expectedMs = if (speedBps > 0.0) ((content.pieceSize.toDouble() * 1000.0) / speedBps).toLong() else 500L
                val thresholdMs = maxOf(300L, (expectedMs * 15L) / 10L)
                elapsedMs >= thresholdMs
            },
        )

        while (requestWindow.canRequestBytes(content.pieceSize.toLong())) {
            val pieceToFetch = scan.pickNext() ?: break
            val chosen = sourceSelector.selectSource(pieceToFetch, candidates, nowMs) ?: break

            content.inFlightRequests[chosen.peerId to pieceToFetch] = nowMs
            content.inFlightByPiece.getOrPut(pieceToFetch) { LinkedHashSet() }.add(chosen.peerId)
            val pLen = content.pieceLength(pieceToFetch).toLong()
            requestWindow.addInFlightBytes(pLen)

            commands.add(
                SwarmCommand.Send(
                    peerId = chosen.peerId,
                    frame = SwarmFrame.Request(content.groupId, content.root, listOf(pieceToFetch)),
                )
            )

            // Keep the scan and the per-peer views in step with the request just issued.
            scan.onPeerRequested(chosen.peerId, pieceToFetch)
            val inFlight = inFlightByPeer.getOrPut(chosen.peerId) { HashSet() }
            inFlight.add(pieceToFetch)
            val index = candidates.indexOfFirst { it.peerId == chosen.peerId }
            if (index >= 0) {
                candidates[index] = candidates[index].copy(inFlightCount = inFlight.size, inFlightPieces = inFlight)
            }
        }
    }

    fun handlePieceArrived(
        e: SwarmEvent.PieceArrived,
        content: ContentState,
        peerState: PeerContentState?,
        commands: MutableList<SwarmCommand>,
        onVerifiedPiece: (ContentState) -> Unit,
    ) {
        // A cancelled, paused, failed or finished item must not write again (a late piece used to resurrect a cancelled file).
        if (content.state != SwarmLifecycleState.ACTIVE) return

        val reqKey = e.peerId to e.index
        val reqTime = content.inFlightRequests.remove(reqKey)
        val pieceLen = content.pieceLength(e.index).toLong()

        // Only a piece we asked for releases budget; an unsolicited or duplicate one must not shrink the in-flight count.
        if (reqTime != null) {
            content.inFlightByPiece[e.index]?.remove(e.peerId)
            requestWindow.removeInFlightBytes(pieceLen)
        } else if (content.bitfield.get(e.index)) {
            return
        }

        if (!e.verified) {
            strikeBook.recordStrike(e.peerId, content.root)
            requestWindow.onCongestion(e.peerId, content.root)
            commands.add(SwarmCommand.Log("SWARM: piece ${e.index} hash mismatch from ${e.peerId}"))
            return
        }

        if (reqTime != null && peerState != null) {
            peerState.updateSpeed(e.bytes.size, maxOf(1L, e.nowMs - reqTime))
        }
        requestWindow.onPieceCompleted(e.peerId, content.root)

        // Cancel loser peer in endgame duplicate requests (§4c)
        val otherRequesters = content.inFlightByPiece[e.index]?.filter { it != e.peerId } ?: emptyList()
        if (otherRequesters.isNotEmpty()) {
            for (loser in otherRequesters) {
                content.inFlightRequests.remove(loser to e.index)
                requestWindow.removeInFlightBytes(pieceLen)
                commands.add(
                    SwarmCommand.Send(
                        peerId = loser,
                        frame = SwarmFrame.Unrequest(content.groupId, content.root, listOf(e.index)),
                    )
                )
            }
            content.inFlightByPiece.remove(e.index)
        }

        commands.add(SwarmCommand.WritePiece(content.groupId, content.root, e.index, e.bytes))
        onVerifiedPiece(content)
    }

    fun handlePieceStored(
        e: SwarmEvent.PieceStored,
        content: ContentState,
        peers: Map<String, PeerConnectionState>,
        commands: MutableList<SwarmCommand>,
        onProgress: (ContentState) -> Unit,
    ) {
        if (content.bitfield.get(e.index)) return
        if (content.state == SwarmLifecycleState.CANCELLED || content.state == SwarmLifecycleState.FAILED) return

        content.bitfield.set(e.index, true)
        val pieceLen = content.pieceLength(e.index)
        content.bytesDone = minOf(content.totalSize, content.bytesDone + pieceLen)

        // Sync and persist bits before HAVE (INV-4)
        commands.add(
            SwarmCommand.SyncAndPersistBits(
                groupId = content.groupId,
                root = content.root,
                bits = content.bitfield.toByteArray(),
                bytesDone = content.bytesDone,
                totalSize = content.totalSize,
            )
        )
        content.persistedBits.set(e.index, true)
        content.pendingHavePieces.add(e.index)
        for ((_, peer) in peers) {
            val pcs = peer.getOrCreateContent(content.root, content.totalPieces)
            pcs.pendingHavePieces.add(e.index)
        }

        onProgress(content)

        if (content.isComplete) {
            content.state = SwarmLifecycleState.VERIFYING
            onProgress(content)
            commands.add(SwarmCommand.Finalize(content.groupId, content.root))
        }
    }

    fun handlePieceStoreFailed(
        e: SwarmEvent.PieceStoreFailed,
        content: ContentState,
        commands: MutableList<SwarmCommand>,
        onFailure: (ContentState) -> Unit,
    ) {
        content.storageUnavailable = true
        onFailure(content)
        commands.add(SwarmCommand.Log("SWARM: piece store failed for ${content.root} index ${e.index}: ${e.error}"))
    }

    fun handleFinalizeResult(
        e: SwarmEvent.FinalizeResult,
        content: ContentState,
        peers: Map<String, PeerConnectionState>,
        isPeerAllowed: (String, String) -> Boolean,
        commands: MutableList<SwarmCommand>,
        onStateChanged: (ContentState) -> Unit,
    ) {
        if (e.ok) {
            content.state = SwarmLifecycleState.COMPLETE
            content.waitReason = null
            if (e.finalPath != null) {
                content.finalPath = e.finalPath
                content.identitySize = e.identitySize
                content.identityModifiedMs = e.identityModifiedMs
            }
            onStateChanged(content)
            commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            for ((peerId, peer) in peers) {
                if (peer.isConnected && peer.hasSw1Feature && isPeerAllowed(content.groupId, peerId)) {
                    commands.add(SwarmCommand.Send(peerId, SwarmFrame.HaveAll(content.groupId, content.root)))
                }
            }
        } else {
            content.verifyFailures++
            if (content.verifyFailures == 1 && e.badPieces.isNotEmpty()) {
                for (bad in e.badPieces) {
                    if (bad in 0 until content.totalPieces) {
                        content.bitfield.set(bad, false)
                        content.persistedBits.set(bad, false)
                        val pLen = content.pieceLength(bad)
                        content.bytesDone = maxOf(0L, content.bytesDone - pLen)
                    }
                }
                content.state = SwarmLifecycleState.ACTIVE
                commands.add(
                    SwarmCommand.SyncAndPersistBits(
                        groupId = content.groupId,
                        root = content.root,
                        bits = content.bitfield.toByteArray(),
                        bytesDone = content.bytesDone,
                        totalSize = content.totalSize,
                    )
                )
                onStateChanged(content)
            } else {
                content.state = SwarmLifecycleState.FAILED
                content.failReason = if (e.storageError) SwarmFailReason.STORAGE_FAILED.reasonCode else SwarmFailReason.DAMAGED.reasonCode
                onStateChanged(content)
                commands.add(SwarmCommand.PersistRecord(content.toRecord(e.nowMs)))
            }
        }
    }

    fun flushPendingHaves(
        contents: Collection<ContentState>,
        peers: Map<String, PeerConnectionState>,
        isPeerAllowed: (String, String) -> Boolean,
        nowMs: Long,
        commands: MutableList<SwarmCommand>,
    ) {
        for (content in contents) {
            for ((peerId, peer) in peers) {
                if (!peer.isConnected || !peer.hasSw1Feature || !isPeerAllowed(content.groupId, peerId)) continue
                val pcs = peer.getOrCreateContent(content.root, content.totalPieces)
                if (pcs.pendingHavePieces.isEmpty()) continue

                if (nowMs - pcs.lastHaveSentMs >= 1000L || pcs.pendingHavePieces.size >= 4) {
                    val validPieces = pcs.pendingHavePieces.filter { content.persistedBits.get(it) }.toSet()
                    val ranges = toPieceRanges(validPieces)
                    if (ranges.isNotEmpty()) {
                        commands.add(
                            SwarmCommand.Send(
                                peerId = peerId,
                                frame = SwarmFrame.Have(content.groupId, content.root, ranges),
                            )
                        )
                        pcs.lastHaveSentMs = nowMs
                    }
                    pcs.pendingHavePieces.clear()
                }
            }
        }
    }

    fun checkTimeouts(
        contents: Collection<ContentState>,
        peers: Map<String, PeerConnectionState>,
        nowMs: Long,
    ) {
        for (content in contents) {
            if (content.state != SwarmLifecycleState.ACTIVE) continue

            val timedOut = ArrayList<Pair<String, Int>>()
            for ((key, reqTime) in content.inFlightRequests) {
                val peerId = key.first
                val index = key.second
                val peerState = peers[peerId]?.contentStates?.get(content.root)
                val ewmaSpeed = peerState?.ewmaSpeedBps ?: 1_000_000.0
                val expectedPieceMs = ((content.pieceSize.toDouble() * 1000.0) / maxOf(1.0, ewmaSpeed)).toLong()
                val timeoutMs = maxOf(5_000L, 4L * expectedPieceMs)

                if (nowMs - reqTime > timeoutMs) {
                    timedOut.add(key)
                }
            }

            for ((peerId, index) in timedOut) {
                content.inFlightRequests.remove(peerId to index)
                content.inFlightByPiece[index]?.remove(peerId)
                val pLen = content.pieceLength(index).toLong()
                requestWindow.removeInFlightBytes(pLen)
                requestWindow.onCongestion(peerId, content.root)
                val peerState = peers[peerId]?.contentStates?.get(content.root)
                if (peerState != null) {
                    peerState.backoffUntilMs = nowMs + 5_000L
                }
            }
        }
    }

    private fun toPieceRanges(pieces: Set<Int>): List<PieceRange> {
        if (pieces.isEmpty()) return emptyList()
        val sorted = pieces.sorted()
        val ranges = ArrayList<PieceRange>()
        var start = sorted[0]
        var count = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] == start + count) {
                count++
            } else {
                ranges.add(PieceRange(start, count))
                start = sorted[i]
                count = 1
            }
        }
        ranges.add(PieceRange(start, count))
        return ranges
    }
}
