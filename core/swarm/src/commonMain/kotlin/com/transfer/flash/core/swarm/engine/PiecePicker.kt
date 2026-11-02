package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield

/**
 * Peer view passed to [PiecePicker] to determine available pieces and rarity.
 *
 * [inFlightCount] and [window] are what the scheduler already checked when it built the list; [PieceScan] needs them
 * because a scheduling pass can fill a peer's window part way through and the reference picker then drops that peer
 * from the holder counts.
 */
public data class PeerPieceAvailability(
    public val peerId: String,
    public val bitfield: Bitfield,
    public val inFlightCount: Int = 0,
    public val window: Int = Int.MAX_VALUE,
)

/**
 * Pure rarest-first piece picker implementing Rule A (§4c, §4.1).
 * First piece is chosen randomly; subsequent pieces rarest-first with random tie-breaks.
 * Switches to endgame mode when remaining missing pieces <= min(32, 2% of total).
 */
public class PiecePicker(
    private val random: SeededRandom,
) {
    /** The rarity index a scheduling pass reuses (OPT-O08-01); one pass at a time, on the engine's actor. */
    private val scan = PieceScan(random)

    /**
     * Piece-peer holder tests the reference [pickNextPiece] performed in its last call, and the equivalent count for
     * [beginScan]. Measurement only: the benchmark compares work, not wall-clock time.
     */
    internal var lastHolderChecks: Long = 0L
        private set

    /**
     * Starts one scheduling pass: builds the rarity index [PieceScan] answers [PieceScan.pickNext] from, which is the
     * fast equivalent of calling [pickNextPiece] once per window slot. The caller must drive [PieceScan.onPeerRequested]
     * for every request it issues and rebuild the scan each pass.
     */
    internal fun beginScan(
        localBitfield: Bitfield,
        availablePeers: List<PeerPieceAvailability>,
        inFlightByPiece: Map<Int, Set<String>>,
        canDuplicate: ((Int) -> Boolean)? = null,
    ): PieceScan {
        val missingCount = localBitfield.size - localBitfield.count()
        scan.begin(
            localBitfield = localBitfield,
            availablePeers = availablePeers,
            inFlightByPiece = inFlightByPiece,
            canDuplicate = canDuplicate,
            endgame = isEndgame(missingCount, localBitfield.size),
        )
        lastHolderChecks = scan.holderChecks
        return scan
    }

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
        canDuplicate: ((Int) -> Boolean)? = null,
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
        lastHolderChecks = 0L

        for (p in 0 until totalPieces) {
            if (localBitfield.get(p)) continue // Already have it

            val inFlight = inFlightByPiece[p] ?: emptySet()
            if (inFlight.size >= maxInFlightPerPiece) continue // Exceeded in-flight limit
            if (inFlight.isNotEmpty() && canDuplicate != null && !canDuplicate(p)) continue

            // Count available holders who don't already have this piece in flight
            var holders = 0
            for (peer in availablePeers) {
                if (peer.peerId !in inFlight && peer.bitfield.get(p)) {
                    holders++
                }
            }
            lastHolderChecks += availablePeers.size

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
