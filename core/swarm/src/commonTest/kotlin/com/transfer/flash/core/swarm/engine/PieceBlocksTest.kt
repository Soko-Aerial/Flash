package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PieceBlocksTest {
    private fun bits(size: Int, vararg set: Int) = Bitfield(size).also { b -> set.forEach { b.set(it) } }

    @Test
    fun `one block per piece when the file has few pieces`() {
        val blocks = PieceBlocks.compute(
            totalPieces = 4,
            local = bits(4, 0),
            inFlight = setOf(1),
            peerBitfields = listOf(bits(4, 2)),
        )
        assertEquals(
            listOf(PieceBlocks.VERIFIED, PieceBlocks.IN_FLIGHT, PieceBlocks.ON_PEERS, PieceBlocks.MISSING),
            blocks,
        )
    }

    @Test
    fun `a piece nobody holds is missing even when others are held`() {
        val blocks = PieceBlocks.compute(
            totalPieces = 3,
            local = bits(3),
            inFlight = emptySet(),
            peerBitfields = listOf(bits(3, 0), bits(3, 1)),
        )
        assertEquals(listOf(PieceBlocks.ON_PEERS, PieceBlocks.ON_PEERS, PieceBlocks.MISSING), blocks)
    }

    @Test
    fun `no peers means nothing is on peers and no invented availability`() {
        val blocks = PieceBlocks.compute(
            totalPieces = 10,
            local = bits(10, 0, 1, 2),
            inFlight = emptySet(),
            peerBitfields = emptyList(),
        )
        assertEquals(3, blocks.count { it == PieceBlocks.VERIFIED })
        assertEquals(7, blocks.count { it == PieceBlocks.MISSING })
        assertEquals(0, blocks.count { it == PieceBlocks.ON_PEERS })
    }

    @Test
    fun `large files are down-sampled to at most 64 blocks and a block is verified only when all its pieces are`() {
        val total = 1000
        val local = Bitfield(total)
        for (i in 0 until 500) local.set(i)
        val blocks = PieceBlocks.compute(total, local, emptySet(), emptyList())
        assertEquals(PieceBlocks.MAX_BLOCKS, blocks.size)
        // The boundary block straddles piece 500 (block 32 covers 500 until 515), so it is not verified.
        assertEquals(PieceBlocks.VERIFIED, blocks[31])
        assertEquals(PieceBlocks.MISSING, blocks[32])
        assertTrue(blocks.count { it == PieceBlocks.VERIFIED } == 32)
    }

    @Test
    fun `a complete file is all verified and an empty file has no blocks`() {
        val full = Bitfield(8).also { for (i in 0 until 8) it.set(i) }
        assertTrue(PieceBlocks.compute(8, full, emptySet(), emptyList()).all { it == PieceBlocks.VERIFIED })
        assertTrue(PieceBlocks.compute(0, Bitfield(0), emptySet(), emptyList()).isEmpty())
    }
}
