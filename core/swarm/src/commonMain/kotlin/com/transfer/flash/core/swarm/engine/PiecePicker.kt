package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield

/**
 * Peer view passed to [PiecePicker] to determine available pieces and rarity.
 */
public data class PeerPieceAvailability(
    public val peerId: String,
    public val bitfield: Bitfield,
)

/**
 * Pure rarest-first piece picker implementing Rule A (§4c, §4.1).
 * First piece is chosen randomly; subsequent pieces rarest-first with random tie-breaks.
 * Switches to endgame mode when remaining missing pieces <= min(32, 2% of total).
 */
public class PiecePicker(
    private val random: SeededRandom,
) {
    /**
     * Calculates the endgame threshold for [totalPieces].
     * Endgame starts when missing <= min(32, 2% of pieces).
     */
    public fun isEndgame(missingCount: Int, totalPieces: Int): Boolean {
        if (totalPieces <= 0) return false
        val twoPercent = maxOf(1, (totalPieces * 2 + 99) / 100)
        val threshold = minOf(32, twoPercent)
        return missingCount in 1..threshold
    }

    /**
     * Selects the next piece index to request, or null if no eligible piece is available.
     *
     * @param localBitfield Local verified piece bitfield.
     * @param availablePeers List of peers that are connected, allowed, non-banned, and have capacity.
     * @param inFlightByPiece Mapping from piece index to the set of peer IDs currently requesting it.
     */
    public fun pickNextPiece(
        localBitfield: Bitfield,
        availablePeers: List<PeerPieceAvailability>,
        inFlightByPiece: Map<Int, Set<String>>,
    ): Int? {
        val totalPieces = localBitfield.size
        if (totalPieces == 0 || localBitfield.isComplete() || availablePeers.isEmpty()) {
            return null
        }

        val missingCount = totalPieces - localBitfield.count()
        val inEndgame = isEndgame(missingCount, totalPieces)
        val maxInFlightPerPiece = if (inEndgame) 2 else 1

        val isFirstPiece = localBitfield.isEmpty()

        // Scan missing pieces deterministically (0 until totalPieces)
        val candidatePieces = ArrayList<Int>()
        var minRarity = Int.MAX_VALUE

        for (p in 0 until totalPieces) {
            if (localBitfield.get(p)) continue // Already have it

            val inFlight = inFlightByPiece[p] ?: emptySet()
            if (inFlight.size >= maxInFlightPerPiece) continue // Exceeded in-flight limit

            // Count available holders who don't already have this piece in flight
            var holders = 0
            for (peer in availablePeers) {
                if (peer.peerId !in inFlight && peer.bitfield.get(p)) {
                    holders++
                }
            }

            if (holders == 0) continue // No eligible peer has this piece

            if (isFirstPiece) {
                candidatePieces.add(p)
            } else {
                if (holders < minRarity) {
                    minRarity = holders
                    candidatePieces.clear()
                    candidatePieces.add(p)
                } else if (holders == minRarity) {
                    candidatePieces.add(p)
                }
            }
        }

        if (candidatePieces.isEmpty()) {
            return null
        }

        val pickedIdx = random.nextInt(candidatePieces.size)
        return candidatePieces[pickedIdx]
    }
}
