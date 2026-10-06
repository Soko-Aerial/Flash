package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield

/**
 * Down-samples the real per-piece state of one content item into at most [MAX_BLOCKS] display blocks
 * for the piece map (UI-055 / Task 3.6). Nothing here is estimated: every block is derived from this
 * device's own bitfield, the pieces currently requested, and the bitfields connected peers announced.
 *
 * Block codes (also documented on `FlashTransfer.pieceBlocks`):
 * - [VERIFIED]: every piece in the block is verified and stored here.
 * - [IN_FLIGHT]: not all verified, and at least one piece is being requested right now.
 * - [ON_PEERS]: not all verified, nothing in flight, and a connected member holds a piece we lack.
 * - [MISSING]: a piece we lack that no connected member is known to hold.
 */
internal object PieceBlocks {
    const val MISSING: Int = 0
    const val ON_PEERS: Int = 1
    const val IN_FLIGHT: Int = 2
    const val VERIFIED: Int = 3

    const val MAX_BLOCKS: Int = 64

    /**
     * @param local this device's verified pieces.
     * @param inFlight piece indexes with an outstanding request.
     * @param peerBitfields bitfields of connected, allowed peers for this content.
     */
    fun compute(
        totalPieces: Int,
        local: Bitfield,
        inFlight: Set<Int>,
        peerBitfields: List<Bitfield>,
    ): List<Int> {
        if (totalPieces <= 0) return emptyList()
        val blocks = minOf(MAX_BLOCKS, totalPieces)
        return List(blocks) { b ->
            // Even split: block b covers [start, end) and every piece belongs to exactly one block.
            val start = (b.toLong() * totalPieces / blocks).toInt()
            val end = ((b + 1).toLong() * totalPieces / blocks).toInt()
            var allDone = true
            var anyInFlight = false
            var anyOnPeers = false
            for (i in start until end) {
                if (local.get(i)) continue
                allDone = false
                if (i in inFlight) anyInFlight = true
                if (!anyOnPeers && peerBitfields.any { it.get(i) }) anyOnPeers = true
            }
            when {
                allDone -> VERIFIED
                anyInFlight -> IN_FLIGHT
                anyOnPeers -> ON_PEERS
                else -> MISSING
            }
        }
    }
}
