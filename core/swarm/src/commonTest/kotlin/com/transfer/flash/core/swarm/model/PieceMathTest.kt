package com.transfer.flash.core.swarm.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PieceMathTest {

    @Test
    fun `edge cases for choosePieceSize`() {
        // Size 0 or negative: null
        assertNull(PieceMath.choosePieceSize(0))
        assertNull(PieceMath.choosePieceSize(-100))

        // Size 1: 64 KiB
        assertEquals(65536, PieceMath.choosePieceSize(1))

        // Exactly pieceSize (64 KiB): 64 KiB
        assertEquals(65536, PieceMath.choosePieceSize(65536))

        // pieceSize + 1 (65537): 64 KiB (2 pieces <= 16384)
        assertEquals(65536, PieceMath.choosePieceSize(65537))

        // Exactly 16,384 * 64 KiB (1 GiB): 64 KiB
        val oneGiB = 16384L * 65536
        assertEquals(65536, PieceMath.choosePieceSize(oneGiB))

        // 1 GiB + 1: steps up to 128 KiB
        assertEquals(131072, PieceMath.choosePieceSize(oneGiB + 1))

        // Exactly 16,384 * 1 MiB (16 GiB): 1 MiB
        val maxSwarmable = PieceMath.MAX_SWARMABLE_FILE_SIZE
        assertEquals(1048576, PieceMath.choosePieceSize(maxSwarmable))

        // 16 GiB + 1: not swarmable -> null
        assertNull(PieceMath.choosePieceSize(maxSwarmable + 1))
    }

    @Test
    fun `piece count calculation and edge cases`() {
        val pieceSize = 65536
        assertEquals(1, PieceMath.pieceCount(1, pieceSize))
        assertEquals(1, PieceMath.pieceCount(65536, pieceSize))
        assertEquals(2, PieceMath.pieceCount(65537, pieceSize))
        assertEquals(16384, PieceMath.pieceCount(16384L * pieceSize, pieceSize))
    }

    @Test
    fun `piece offset and piece length calculation`() {
        val totalSize = 100000L
        val pieceSize = 65536
        // pieceCount = 2: piece 0 (65536B), piece 1 (34464B)
        assertEquals(2, PieceMath.pieceCount(totalSize, pieceSize))

        assertEquals(0L, PieceMath.pieceOffset(0, pieceSize))
        assertEquals(65536L, PieceMath.pieceOffset(1, pieceSize))

        assertEquals(65536, PieceMath.pieceLength(0, totalSize, pieceSize))
        assertEquals(34464, PieceMath.pieceLength(1, totalSize, pieceSize))
    }

    @Test
    fun `piece count calculation with overflow totalSize throws IllegalArgumentException`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PieceMath.pieceCount(Long.MAX_VALUE, PieceMath.MIN_PIECE_SIZE)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PieceMath.pieceCount(PieceMath.MAX_SWARMABLE_FILE_SIZE + 1, PieceMath.MAX_PIECE_SIZE)
        }
    }
}
