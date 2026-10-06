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
        if (!isExistingRequester && activeRequesters.size >= maxSlots) {
            return ServeEvaluation.Rejected(
                reason = SwarmRejectReason.BUSY,
                retryAfterMs = 1_000L,
                scopeAll = false,
                pieces = requestedPieces,
            )
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
            for (p in unseeded) {
                if (byteBudget >= pieceSize) {
                    toServe.add(p)
                    byteBudget -= pieceSize
                }
            }

            val rejectedParts = ArrayList<ServeEvaluation.Accepted.RejectedPart>()
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
            )
        }

        // 9. Non-origin serving: accept pieces within requester byte budget
        val toServe = ArrayList<Int>()
        for (p in heldPieces) {
            if (byteBudget >= pieceSize) {
                toServe.add(p)
                byteBudget -= pieceSize
            }
        }

        return ServeEvaluation.Accepted(
            piecesToServe = toServe,
        )
    }
}
