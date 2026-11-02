package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.SwarmRejectReason

/**
 * Result of evaluating an inbound piece request (§4f).
 */
public sealed interface ServeEvaluation {
    /**
     * Request accepted to serve [piecesToServe]. Any [rejectedPieces] are rejected with specific reason.
     */
    public data class Accepted(
        public val piecesToServe: List<Int>,
        public val rejectedPieces: List<RejectedPart> = emptyList(),
        /**
         * An active requester that must give up its serve slot to let [peerId] in (OPT-O08-03). Its in-flight reads are
         * not cancelled — only its admission to *new* work.
         */
        public val yieldPeerId: String? = null,
    ) : ServeEvaluation {
        public data class RejectedPart(
            public val reason: SwarmRejectReason,
            public val retryAfterMs: Long,
            public val pieces: List<Int>,
        )
    }

    /**
     * Whole request rejected with [reason].
     */
    public data class Rejected(
        public val reason: SwarmRejectReason,
        public val retryAfterMs: Long,
        public val scopeAll: Boolean,
        public val pieces: List<Int> = emptyList(),
    ) : ServeEvaluation
}

/**
 * Pure serve policy implementing slot allocations and origin offer policy (§4f, INV-7, Rule B).
 */
public class ServePolicy(
    public val maxOutstandingBytesPerRequester: Long = 2L * 1024 * 1024,
) {
    /**
     * Calculates the maximum active serve slots for the given profile and role.
     */
    public fun calculateMaxSlots(
        profile: SwarmProfile,
        isOrigin: Boolean,
        isCallActive: Boolean,
    ): Int {
        if (isCallActive) return 1
        val base = profile.serveSlots
        return if (isOrigin) maxOf(2, base) else base
    }

    /**
     * Evaluates an inbound REQUEST frame against all guard checks and origin offer policy (§4f).
     */
    public fun evaluateRequest(
        peerId: String,
        requestedPieces: List<Int>,
        pieceSize: Int,
        isAllowed: Boolean,
        contentKnown: Boolean,
        isTombstoned: Boolean,
        servingEnabled: Boolean,
        isOrigin: Boolean,
        isCallActive: Boolean,
        profile: SwarmProfile,
        activeRequesters: Set<String>,
        currentOutstandingBytes: Long,
        localHoldsPiece: (Int) -> Boolean,
        copiesCount: (Int) -> Int,
        pendingCount: (Int) -> Int,
        hasBackedOffElsewhere: (Int) -> Boolean = { false },
        servedInTenure: Map<String, Long> = emptyMap(),
        refusedSinceMs: Map<String, Long> = emptyMap(),
        nowMs: Long = 0L,
    ): ServeEvaluation {
        // 1. Membership gate check (ADR-075)
        if (!isAllowed) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.NOT_MEMBER,
                retryAfterMs = 0L,
                scopeAll = true,
                pieces = requestedPieces,
            )
        }

        // 2. Unknown root check (INV-8)
        if (!contentKnown) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.UNKNOWN,
                retryAfterMs = 0L,
                scopeAll = true,
                pieces = requestedPieces,
            )
        }

        // 3. Tombstoned check
        if (isTombstoned) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.CANCELLED,
                retryAfterMs = 0L,
                scopeAll = true,
                pieces = requestedPieces,
            )
        }

        // 4. Serving enabled check (ECO / battery saver / user toggle)
        if (!servingEnabled) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.BUSY,
                retryAfterMs = 60_000L,
                scopeAll = true,
                pieces = requestedPieces,
            )
        }

        // 5. Slot capacity check
        val maxSlots = calculateMaxSlots(profile, isOrigin, isCallActive)
        val isExistingRequester = peerId in activeRequesters
        var yieldPeerId: String? = null
        if (!isExistingRequester && activeRequesters.size >= maxSlots) {
            // OPT-O08-03: a fast member that keeps its outstanding bytes above zero keeps its slot for ever (its next
            // request arrives before the queue drains to zero), so with two slots the other members of a 20-node group
            // sat in "Waiting for peers" for the whole transfer. Deficit round robin: a requester that has had more than
            // its share of a tenure yields, and only to a peer that has already been waiting (age weighting), so nobody
            // is preempted on their first attempt.
            val waitedSince = refusedSinceMs[peerId]
            val waitedMs = if (waitedSince == null) -1L else nowMs - waitedSince
            val victim = if (waitedMs >= ARRIVAL_MIN_WAIT_MS) {
                yieldCandidate(peerId, activeRequesters, servedInTenure, pieceSize)
            } else {
                null
            }
            if (victim == null) {
                return ServeEvaluation.Rejected(
                    reason = SwarmRejectReason.BUSY,
                    retryAfterMs = 1_000L,
                    scopeAll = false,
                    pieces = requestedPieces,
                )
            }
            yieldPeerId = victim
        }

        // 6. Filter pieces held locally
        val heldPieces = requestedPieces.filter { localHoldsPiece(it) }
        if (heldPieces.isEmpty()) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.ELSEWHERE,
                retryAfterMs = 5_000L,
                scopeAll = false,
                pieces = requestedPieces,
            )
        }

        // 7. Requester bandwidth budget cap
        var byteBudget = maxOutstandingBytesPerRequester - currentOutstandingBytes
        if (byteBudget <= 0) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.BUSY,
                retryAfterMs = 1_000L,
                scopeAll = false,
                pieces = requestedPieces,
            )
        }

        // 8. Origin offer policy (INV-7, 4.1 Rule B)
        if (isOrigin) {
            val unseeded = ArrayList<Int>()
            val elsewhere = ArrayList<Int>()

            for (p in heldPieces) {
                val copies = copiesCount(p)
                val pending = pendingCount(p)
                if (copies + pending == 0) {
                    unseeded.add(p)
                } else {
                    elsewhere.add(p)
                }
            }

            // Unseeded pieces take priority; reject duplicated pieces with ELSEWHERE
            val toServe = ArrayList<Int>()
            val overBudget = ArrayList<Int>()
            for (p in unseeded) {
                if (byteBudget >= pieceSize) {
                    toServe.add(p)
                    byteBudget -= pieceSize
                } else {
                    overBudget.add(p)
                }
            }

            val rejectedParts = ArrayList<ServeEvaluation.Accepted.RejectedPart>()
            // RVW22 F5: a piece we could serve but have no budget for is answered BUSY; staying silent left it charged to the requester's window.
            if (overBudget.isNotEmpty()) {
                rejectedParts.add(
                    ServeEvaluation.Accepted.RejectedPart(
                        reason = SwarmRejectReason.BUSY,
                        retryAfterMs = 1_000L,
                        pieces = overBudget,
                    )
                )
            }
            if (unseeded.isEmpty()) {
                val stillElsewhere = ArrayList<Int>()
                for (p in elsewhere) {
                    if (hasBackedOffElsewhere(p) && pendingCount(p) == 0 && byteBudget >= pieceSize) {
                        toServe.add(p)
                        byteBudget -= pieceSize
                    } else {
                        stillElsewhere.add(p)
                    }
                }
                if (stillElsewhere.isNotEmpty()) {
                    rejectedParts.add(
                        ServeEvaluation.Accepted.RejectedPart(
                            reason = SwarmRejectReason.ELSEWHERE,
                            retryAfterMs = 5_000L,
                            pieces = stillElsewhere,
                        )
                    )
                }
            } else if (elsewhere.isNotEmpty()) {
                rejectedParts.add(
                    ServeEvaluation.Accepted.RejectedPart(
                        reason = SwarmRejectReason.ELSEWHERE,
                        retryAfterMs = 5_000L,
                        pieces = elsewhere,
                    )
                )
            }

            return ServeEvaluation.Accepted(
                piecesToServe = toServe,
                rejectedPieces = rejectedParts,
                yieldPeerId = yieldPeerId,
            )
        }

        // 9. Non-origin serving: accept pieces within requester byte budget
        val toServe = ArrayList<Int>()
        val overBudget = ArrayList<Int>()
        for (p in heldPieces) {
            if (byteBudget >= pieceSize) {
                toServe.add(p)
                byteBudget -= pieceSize
            } else {
                overBudget.add(p)
            }
        }

        // RVW22 F5: every held piece is either served or answered BUSY, never dropped without a word.
        return ServeEvaluation.Accepted(
            piecesToServe = toServe,
            rejectedPieces = if (overBudget.isEmpty()) {
                emptyList()
            } else {
                listOf(ServeEvaluation.Accepted.RejectedPart(reason = SwarmRejectReason.BUSY, retryAfterMs = 1_000L, pieces = overBudget))
            },
            yieldPeerId = yieldPeerId,
        )
    }

    /**
     * Deficit round robin over the serve slots (OPT-O08-03): returns the active requester that must give its slot to
     * [arrivingPeerId], or null when nobody has had more than a fair share of its tenure.
     *
     * A peer that has served at least [YIELD_AFTER_PIECES] pieces since it was admitted has had its turn; the one with
     * the most served bytes goes first, ties broken by the larger id so the choice is deterministic. Only one slot is
     * yielded per admission, so the slot count never grows.
     */
    public fun yieldCandidate(
        arrivingPeerId: String,
        activeRequesters: Set<String>,
        servedInTenure: Map<String, Long>,
        pieceSize: Int,
    ): String? {
        if (activeRequesters.isEmpty() || pieceSize <= 0) return null
        val floor = pieceSize.toLong() * YIELD_AFTER_PIECES
        var victim: String? = null
        var victimServed = floor
        for (peer in activeRequesters) {
            if (peer == arrivingPeerId) continue
            val served = servedInTenure[peer] ?: 0L
            if (served < floor) continue
            if (victim == null || served > victimServed || (served == victimServed && peer > victim)) {
                victim = peer
                victimServed = served
            }
        }
        return victim
    }

    public companion object {
        /** Pieces a peer may be served in one tenure before another waiting member may take its slot. */
        public const val YIELD_AFTER_PIECES: Int = 2

        /** How long a peer must have been refused before it may take a slot from somebody else. */
        public const val ARRIVAL_MIN_WAIT_MS: Long = 1_000L
    }
}
