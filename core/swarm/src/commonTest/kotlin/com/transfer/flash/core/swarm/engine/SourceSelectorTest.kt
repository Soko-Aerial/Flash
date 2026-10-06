package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceSelectorTest {

    private fun createCandidate(
        peerId: String,
        isOrigin: Boolean = false,
        heldPieces: List<Int> = listOf(0, 1, 2),
        isConnected: Boolean = true,
        isAllowed: Boolean = true,
        hasSw1: Boolean = true,
        servingEnabled: Boolean = true,
        strikes: Int = 0,
        backoffUntilMs: Long = 0L,
        inFlightCount: Int = 0,
        window: Int = 4,
        ewmaSpeedBps: Double = 1_000_000.0,
        inFlightPieces: Set<Int> = emptySet(),
    ): PeerSourceCandidate {
        val bf = Bitfield(10)
        heldPieces.forEach { bf.set(it, true) }
        return PeerSourceCandidate(
            peerId = peerId,
            isOrigin = isOrigin,
            bitfield = bf,
            isConnected = isConnected,
            isAllowed = isAllowed,
            hasSw1Feature = hasSw1,
            servingEnabled = servingEnabled,
            strikes = strikes,
            backoffUntilMs = backoffUntilMs,
            inFlightCount = inFlightCount,
            window = window,
            ewmaSpeedBps = ewmaSpeedBps,
            inFlightPieces = inFlightPieces,
        )
    }

    @Test
    fun `non-origin is selected before origin even if origin is faster`() {
        val selector = SourceSelector(SeededRandom(1L))
        val origin = createCandidate("origin", isOrigin = true, ewmaSpeedBps = 10_000_000.0)
        val peerA = createCandidate("peerA", isOrigin = false, ewmaSpeedBps = 500_000.0)

        val selected = selector.selectSource(pieceIndex = 0, candidates = listOf(origin, peerA), nowMs = 1000L)
        assertNotNull(selected)
        assertEquals("peerA", selected.peerId)
    }

    @Test
    fun `origin is selected only when no other holder holds the piece`() {
        val selector = SourceSelector(SeededRandom(1L))
        val origin = createCandidate("origin", isOrigin = true, heldPieces = listOf(0, 1))
        val peerA = createCandidate("peerA", isOrigin = false, heldPieces = listOf(0)) // doesn't have piece 1

        val selected = selector.selectSource(pieceIndex = 1, candidates = listOf(origin, peerA), nowMs = 1000L)
        assertNotNull(selected)
        assertEquals("origin", selected.peerId)
    }

    @Test
    fun `fastest EWMA speed is selected among non-origin holders`() {
        val selector = SourceSelector(SeededRandom(1L))
        val slow = createCandidate("slow", ewmaSpeedBps = 200_000.0)
        val fast = createCandidate("fast", ewmaSpeedBps = 2_000_000.0)

        val selected = selector.selectSource(pieceIndex = 0, candidates = listOf(slow, fast), nowMs = 1000L)
        assertNotNull(selected)
        assertEquals("fast", selected.peerId)
    }

    @Test
    fun `fewest in-flight is selected when EWMA speeds are similar`() {
        val selector = SourceSelector(SeededRandom(1L))
        val busyPeer = createCandidate("busy", ewmaSpeedBps = 1_000_000.0, inFlightCount = 3)
        val idlePeer = createCandidate("idle", ewmaSpeedBps = 1_000_000.0, inFlightCount = 0)

        val selected = selector.selectSource(pieceIndex = 0, candidates = listOf(busyPeer, idlePeer), nowMs = 1000L)
        assertNotNull(selected)
        assertEquals("idle", selected.peerId)
    }

    @Test
    fun `peers in backoff, banned, full window, or not allowed are excluded`() {
        val selector = SourceSelector(SeededRandom(1L))
        val inBackoff = createCandidate("backoff", backoffUntilMs = 2000L)
        val banned = createCandidate("banned", strikes = 3)
        val fullWindow = createCandidate("fullWin", inFlightCount = 4, window = 4)
        val notAllowed = createCandidate("notAllowed", isAllowed = false)
        val disconnected = createCandidate("disconnected", isConnected = false)
        val noSw1 = createCandidate("noSw1", hasSw1 = false)
        val servingDisabled = createCandidate("noServe", servingEnabled = false)
        val alreadyInFlight = createCandidate("alreadyReq", inFlightPieces = setOf(0))

        val allIneligible = listOf(
            inBackoff, banned, fullWindow, notAllowed, disconnected, noSw1, servingDisabled, alreadyInFlight
        )

        val selected = selector.selectSource(pieceIndex = 0, candidates = allIneligible, nowMs = 1000L)
        assertNull(selected)
    }
}
