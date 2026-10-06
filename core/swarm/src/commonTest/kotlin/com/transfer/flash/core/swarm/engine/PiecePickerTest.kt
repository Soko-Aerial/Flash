package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiecePickerTest {

    @Test
    fun `first piece is picked randomly from available pieces`() {
        val picker = PiecePicker(SeededRandom(12345L))
        val local = Bitfield(10) // all missing
        val peerA = Bitfield(10)
        peerA.set(2, true)
        peerA.set(5, true)
        peerA.set(8, true)

        val peers = listOf(PeerPieceAvailability("peerA", peerA))
        val picked = picker.pickNextPiece(local, peers, emptyMap())

        assertNotNull(picked)
        assertTrue(picked in listOf(2, 5, 8))
    }

    @Test
    fun `rarest first picks piece held by fewest peers`() {
        val picker = PiecePicker(SeededRandom(42L))
        val local = Bitfield(10)
        local.set(0, true) // not empty anymore

        // Piece 1 is held by peerA only (rarity = 1)
        // Piece 2 is held by peerA, peerB, peerC (rarity = 3)
        // Piece 3 is held by peerB, peerC (rarity = 2)
        val peerA = Bitfield(10).apply { set(1, true); set(2, true) }
        val peerB = Bitfield(10).apply { set(2, true); set(3, true) }
        val peerC = Bitfield(10).apply { set(2, true); set(3, true) }

        val peers = listOf(
            PeerPieceAvailability("peerA", peerA),
            PeerPieceAvailability("peerB", peerB),
            PeerPieceAvailability("peerC", peerC),
        )

        val picked = picker.pickNextPiece(local, peers, emptyMap())
        assertEquals(1, picked, "Should pick rarest piece 1 with rarity 1")
    }

    @Test
    fun `already held pieces and in-flight pieces are skipped in normal mode`() {
        val picker = PiecePicker(SeededRandom(999L))
        val local = Bitfield(10)
        local.set(0, true)
        local.set(1, true) // held

        val peerA = Bitfield(10).apply {
            set(1, true) // already held
            set(2, true) // in flight
            set(3, true) // available!
        }
        val peers = listOf(PeerPieceAvailability("peerA", peerA))
        val inFlight = mapOf(2 to setOf("peerA"))

        val picked = picker.pickNextPiece(local, peers, inFlight)
        assertEquals(3, picked)
    }

    @Test
    fun `endgame mode detects threshold and allows up to 2 sources per piece`() {
        val picker = PiecePicker(SeededRandom(1L))
        assertTrue(picker.isEndgame(missingCount = 2, totalPieces = 100))
        assertTrue(picker.isEndgame(missingCount = 32, totalPieces = 2000))
        assertFalse(picker.isEndgame(missingCount = 33, totalPieces = 2000))

        val local = Bitfield(100)
        // Mark 99 pieces as held -> only 1 missing (endgame!)
        for (i in 0 until 99) local.set(i, true)
        val missingPiece = 99

        val peerA = Bitfield(100).apply { set(missingPiece, true) }
        val peerB = Bitfield(100).apply { set(missingPiece, true) }
        val peers = listOf(
            PeerPieceAvailability("peerA", peerA),
            PeerPieceAvailability("peerB", peerB),
        )

        // 1 source in-flight: in endgame, can still pick for 2nd source
        val inFlight1 = mapOf(missingPiece to setOf("peerA"))
        val picked1 = picker.pickNextPiece(local, peers, inFlight1)
        assertEquals(missingPiece, picked1)

        // 2 sources in-flight: max reached in endgame, cannot pick anymore
        val inFlight2 = mapOf(missingPiece to setOf("peerA", "peerB"))
        val picked2 = picker.pickNextPiece(local, peers, inFlight2)
        assertNull(picked2)
    }

    @Test
    fun `returns null when no piece is available`() {
        val picker = PiecePicker(SeededRandom(1L))
        val local = Bitfield(5)
        for (i in 0 until 5) local.set(i, true) // all complete
        val peers = listOf(PeerPieceAvailability("peerA", Bitfield(5)))

        assertNull(picker.pickNextPiece(local, peers, emptyMap()))
    }
}
