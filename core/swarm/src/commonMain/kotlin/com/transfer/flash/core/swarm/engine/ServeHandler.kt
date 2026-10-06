package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.codec.SourceReason
import com.transfer.flash.core.swarm.codec.SourceState
import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.PieceReadStatus
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRejectReason
import com.transfer.flash.core.swarm.model.SwarmRole

/**
 * Handles inbound piece requests, piece reads for serving, and rejections (§4f, INV-1, INV-7, INV-8).
 */
internal class ServeHandler(
    private val servePolicy: ServePolicy,
    private val requestWindow: RequestWindow,
    private val config: SwarmConfig,
) {
    fun handleRequestArrived(
        e: SwarmEvent.RequestArrived,
        content: ContentState?,
        isTombstoned: Boolean,
        localServingEnabled: Boolean,
        systemSuspended: Boolean,
        callActive: Boolean,
        countPeerCopies: (Int) -> Int,
        countPendingServes: (Int) -> Int,
        commands: MutableList<SwarmCommand>,
    ) {
        val eval = servePolicy.evaluateRequest(
            peerId = e.peerId,
            requestedPieces = e.frame.pieces,
            pieceSize = content?.pieceSize ?: 65536,
            isAllowed = e.allowed,
            contentKnown = content != null,
            isTombstoned = isTombstoned,
            servingEnabled = localServingEnabled && !systemSuspended && e.serveAllowed,
            isOrigin = content?.role == SwarmRole.ORIGIN,
            isCallActive = callActive,
            profile = config.profile,
            activeRequesters = content?.activeRequesters ?: emptySet(),
            currentOutstandingBytes = content?.outstandingServeBytes?.get(e.peerId) ?: 0L,
            localHoldsPiece = { idx -> content?.bitfield?.get(idx) == true },
            copiesCount = countPeerCopies,
            pendingCount = countPendingServes,
            hasBackedOffElsewhere = { idx -> (content?.elsewhereRejections?.get(e.peerId to idx) ?: 0) >= 1 },
        )

        when (eval) {
            is ServeEvaluation.Rejected -> {
                if (content != null && eval.reason == SwarmRejectReason.ELSEWHERE) {
                    for (p in eval.pieces) {
                        content.elsewhereRejections[e.peerId to p] = (content.elsewhereRejections[e.peerId to p] ?: 0) + 1
                    }
                }
                commands.add(
                    SwarmCommand.Send(
                        peerId = e.peerId,
                        frame = SwarmFrame.Reject(
                            groupId = e.frame.groupId,
                            root = e.frame.root,
                            reason = eval.reason,
                            retryAfterMs = eval.retryAfterMs,
                            scopeAll = eval.scopeAll,
                            pieces = eval.pieces,
                        ),
                    )
                )
            }
            is ServeEvaluation.Accepted -> {
                if (content != null) {
                    if (eval.piecesToServe.isNotEmpty()) {
                        content.activeRequesters.add(e.peerId)
                    }
                    for (p in eval.piecesToServe) {
                        content.elsewhereRejections.remove(e.peerId to p)
                        val pLen = content.pieceLength(p).toLong()
                        val curr = content.outstandingServeBytes[e.peerId] ?: 0L
                        content.outstandingServeBytes[e.peerId] = curr + pLen
                        content.pendingServesByPiece[p] = (content.pendingServesByPiece[p] ?: 0) + 1
                        if (content.role == SwarmRole.ORIGIN) {
                            content.originServedPiecesByPeer.getOrPut(e.peerId) { LinkedHashSet() }.add(p)
                        }
                        commands.add(SwarmCommand.ReadPiece(content.groupId, content.root, p, e.peerId))
                    }
                    for (rej in eval.rejectedPieces) {
                        if (rej.reason == SwarmRejectReason.ELSEWHERE) {
                            for (p in rej.pieces) {
                                content.elsewhereRejections[e.peerId to p] = (content.elsewhereRejections[e.peerId to p] ?: 0) + 1
                            }
                        }
                        commands.add(
                            SwarmCommand.Send(
                                peerId = e.peerId,
                                frame = SwarmFrame.Reject(
                                    groupId = e.frame.groupId,
                                    root = e.frame.root,
                                    reason = rej.reason,
                                    retryAfterMs = rej.retryAfterMs,
                                    scopeAll = false,
                                    pieces = rej.pieces,
                                ),
                            )
                        )
                    }
                }
            }
        }
    }

    fun handlePieceRead(
        e: SwarmEvent.PieceRead,
        content: ContentState,
        isTombstoned: Boolean,
        isPeerAllowed: Boolean,
        commands: MutableList<SwarmCommand>,
        onSourceChanged: (ContentState) -> Unit,
    ) {
        val outstanding = content.outstandingServeBytes[e.peerId] ?: 0L
        val pieceLen = content.pieceLength(e.index).toLong()
        val remaining = maxOf(0L, outstanding - pieceLen)
        if (remaining == 0L) {
            content.outstandingServeBytes.remove(e.peerId)
            content.activeRequesters.remove(e.peerId)
        } else {
            content.outstandingServeBytes[e.peerId] = remaining
        }

        val pCount = (content.pendingServesByPiece[e.index] ?: 1) - 1
        if (pCount <= 0) {
            content.pendingServesByPiece.remove(e.index)
        } else {
            content.pendingServesByPiece[e.index] = pCount
        }

        when (e.status) {
            PieceReadStatus.OK -> {
                if (e.bytes != null &&
                    content.bitfield.get(e.index) &&
                    !isTombstoned &&
                    isPeerAllowed &&
                    content.state != SwarmLifecycleState.CANCELLED
                ) {
                    commands.add(
                        SwarmCommand.Send(
                            peerId = e.peerId,
                            frame = SwarmFrame.Piece(content.groupId, content.root, e.index, e.bytes),
                        )
                    )
                }
            }
            PieceReadStatus.CHANGED, PieceReadStatus.GONE -> {
                content.bitfield.set(e.index, false)
                content.persistedBits.set(e.index, false)
                content.bytesDone = maxOf(0L, content.bytesDone - pieceLen)
                commands.add(
                    SwarmCommand.SyncAndPersistBits(
                        groupId = content.groupId,
                        root = content.root,
                        bits = content.bitfield.toByteArray(),
                        bytesDone = content.bytesDone,
                    )
                )
                // ERROR-109: the requester asked for this piece and must hear that it will not come, or it waits on a
                // request nobody answers and asks again. A later request for another piece gets the same answer.
                if (isPeerAllowed) {
                    commands.add(
                        SwarmCommand.Send(
                            peerId = e.peerId,
                            frame = SwarmFrame.Reject(
                                groupId = content.groupId,
                                root = content.root,
                                reason = SwarmRejectReason.GONE,
                                retryAfterMs = 0L,
                                scopeAll = false,
                                pieces = listOf(e.index),
                            ),
                        )
                    )
                }
                if (content.role == SwarmRole.ORIGIN && !content.originSourceLost) {
                    content.originSourceLost = true
                    // The first failed read tells every member, signed, so they stop waiting on this origin (once, not per piece).
                    commands.add(
                        SwarmCommand.SignSourceStatus(
                            groupId = content.groupId,
                            root = content.root,
                            originId = content.originId,
                            messageId = content.messageId,
                            status = SourceState.LOST,
                            reason = if (e.status == PieceReadStatus.CHANGED) SourceReason.CHANGED else SourceReason.DELETED,
                            atMs = e.nowMs,
                        )
                    )
                }
                onSourceChanged(content)
            }
        }
    }

    fun handleUnrequestArrived(e: SwarmEvent.UnrequestArrived, content: ContentState) {
        for (p in e.frame.pieces) {
            val pLen = content.pieceLength(p).toLong()
            val curr = content.outstandingServeBytes[e.peerId] ?: 0L
            val rem = maxOf(0L, curr - pLen)
            if (rem == 0L) {
                content.outstandingServeBytes.remove(e.peerId)
                content.activeRequesters.remove(e.peerId)
            } else {
                content.outstandingServeBytes[e.peerId] = rem
            }
            val pCount = (content.pendingServesByPiece[p] ?: 1) - 1
            if (pCount <= 0) {
                content.pendingServesByPiece.remove(p)
            } else {
                content.pendingServesByPiece[p] = pCount
            }
        }
    }

    fun handleRejectArrived(
        e: SwarmEvent.RejectArrived,
        content: ContentState,
        peerState: PeerContentState?,
    ) {
        val piecesToClear = if (e.frame.scopeAll) {
            content.inFlightRequests.keys.filter { it.first == e.peerId }.map { it.second }
        } else {
            e.frame.pieces
        }

        for (p in piecesToClear) {
            content.inFlightRequests.remove(e.peerId to p)
            content.inFlightByPiece[p]?.remove(e.peerId)
            val pLen = content.pieceLength(p).toLong()
            requestWindow.removeInFlightBytes(pLen)
        }

        if (e.frame.reason != SwarmRejectReason.BUSY && e.frame.reason != SwarmRejectReason.ELSEWHERE) {
            // NOT_MEMBER, UNKNOWN, GONE, CANCELLED: asking again at once only repeats the answer every tick.
            if (peerState != null) {
                peerState.backoffUntilMs = maxOf(peerState.backoffUntilMs, e.nowMs + maxOf(5_000L, e.frame.retryAfterMs))
            }
        }
        if (e.frame.reason == SwarmRejectReason.BUSY || e.frame.reason == SwarmRejectReason.ELSEWHERE) {
            if (e.frame.reason == SwarmRejectReason.BUSY) {
                requestWindow.onCongestion(e.peerId, content.root)
            }
            if (peerState != null) {
                peerState.backoffUntilMs = maxOf(peerState.backoffUntilMs, e.nowMs + maxOf(1000L, e.frame.retryAfterMs))
            }
        }
    }
}
