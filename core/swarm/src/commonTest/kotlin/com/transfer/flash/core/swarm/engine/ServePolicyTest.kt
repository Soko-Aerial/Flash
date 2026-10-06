package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.SwarmRejectReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServePolicyTest {

    private val policy = ServePolicy(maxOutstandingBytesPerRequester = 1024 * 1024)

    @Test
    fun `slot calculation enforces profile limits and origin floor`() {
        assertEquals(1, policy.calculateMaxSlots(SwarmProfile.LOW, isOrigin = false, isCallActive = false))
        assertEquals(2, policy.calculateMaxSlots(SwarmProfile.LOW, isOrigin = true, isCallActive = false), "Origin floor >= 2")
        assertEquals(2, policy.calculateMaxSlots(SwarmProfile.MEDIUM, isOrigin = false, isCallActive = false))
        assertEquals(4, policy.calculateMaxSlots(SwarmProfile.HIGH, isOrigin = false, isCallActive = false))

        // Call active drops slots to 1 regardless of profile or role
        assertEquals(1, policy.calculateMaxSlots(SwarmProfile.HIGH, isOrigin = true, isCallActive = true))
    }

    @Test
    fun `guard - allowed false rejects with NOT_MEMBER`() {
        val eval = policy.evaluateRequest(
            peerId = "peerA",
            requestedPieces = listOf(0),
            pieceSize = 65536,
            isAllowed = false,
            contentKnown = true,
            isTombstoned = false,
            servingEnabled = true,
            isOrigin = false,
            isCallActive = false,
            profile = SwarmProfile.MEDIUM,
            activeRequesters = emptySet(),
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { 0 },
            pendingCount = { 0 },
        )
        val rej = assertIs<ServeEvaluation.Rejected>(eval)
        assertEquals(SwarmRejectReason.NOT_MEMBER, rej.reason)
        assertTrue(rej.scopeAll)
    }

    @Test
    fun `guard - unknown content rejects with UNKNOWN`() {
        val eval = policy.evaluateRequest(
            peerId = "peerA",
            requestedPieces = listOf(0),
            pieceSize = 65536,
            isAllowed = true,
            contentKnown = false,
            isTombstoned = false,
            servingEnabled = true,
            isOrigin = false,
            isCallActive = false,
            profile = SwarmProfile.MEDIUM,
            activeRequesters = emptySet(),
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { 0 },
            pendingCount = { 0 },
        )
        val rej = assertIs<ServeEvaluation.Rejected>(eval)
        assertEquals(SwarmRejectReason.UNKNOWN, rej.reason)
    }

    @Test
    fun `guard - tombstoned content rejects with CANCELLED`() {
        val eval = policy.evaluateRequest(
            peerId = "peerA",
            requestedPieces = listOf(0),
            pieceSize = 65536,
            isAllowed = true,
            contentKnown = true,
            isTombstoned = true,
            servingEnabled = true,
            isOrigin = false,
            isCallActive = false,
            profile = SwarmProfile.MEDIUM,
            activeRequesters = emptySet(),
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { 0 },
            pendingCount = { 0 },
        )
        val rej = assertIs<ServeEvaluation.Rejected>(eval)
        assertEquals(SwarmRejectReason.CANCELLED, rej.reason)
    }

    @Test
    fun `guard - serving disabled rejects with BUSY for 60s`() {
        val eval = policy.evaluateRequest(
            peerId = "peerA",
            requestedPieces = listOf(0),
            pieceSize = 65536,
            isAllowed = true,
            contentKnown = true,
            isTombstoned = false,
            servingEnabled = false,
            isOrigin = false,
            isCallActive = false,
            profile = SwarmProfile.MEDIUM,
            activeRequesters = emptySet(),
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { 0 },
            pendingCount = { 0 },
        )
        val rej = assertIs<ServeEvaluation.Rejected>(eval)
        assertEquals(SwarmRejectReason.BUSY, rej.reason)
        assertEquals(60_000L, rej.retryAfterMs)
    }

    @Test
    fun `origin offer policy serves unseeded pieces and rejects duplicated with ELSEWHERE`() {
        // Piece 0: unseeded (copies=0, pending=0)
        // Piece 1: already seeded (copies=1, pending=0)
        // Piece 2: currently pending for another member (copies=0, pending=1)
        val eval = policy.evaluateRequest(
            peerId = "peerA",
            requestedPieces = listOf(0, 1, 2),
            pieceSize = 65536,
            isAllowed = true,
            contentKnown = true,
            isTombstoned = false,
            servingEnabled = true,
            isOrigin = true,
            isCallActive = false,
            profile = SwarmProfile.MEDIUM,
            activeRequesters = emptySet(),
            currentOutstandingBytes = 0L,
            localHoldsPiece = { true },
            copiesCount = { p -> if (p == 1) 1 else 0 },
            pendingCount = { p -> if (p == 2) 1 else 0 },
        )

        val acc = assertIs<ServeEvaluation.Accepted>(eval)
        assertEquals(listOf(0), acc.piecesToServe, "Only unseeded piece 0 should be served")
        assertEquals(1, acc.rejectedPieces.size)
        val rejPart = acc.rejectedPieces[0]
        assertEquals(SwarmRejectReason.ELSEWHERE, rejPart.reason)
        assertEquals(listOf(1, 2), rejPart.pieces, "Duplicated and currently-pending pieces rejected ELSEWHERE")
    }
}
