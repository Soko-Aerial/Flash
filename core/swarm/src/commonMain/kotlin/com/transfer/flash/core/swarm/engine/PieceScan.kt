package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield

/**
 * One available peer as the scan needs it: the pieces it holds, and how much room its request window has left.
 * [inFlightCount] is the number of pieces we already asked this peer for; it grows as the pass picks pieces.
 */
internal class ScanPeer(
    val peerId: String,
    val bitfield: Bitfield,
    var inFlightCount: Int,
    val window: Int,
) {
    /** Set once the peer's window is full: the reference picker drops such a peer from the holder counts entirely. */
    var exhausted: Boolean = false
}

/**
 * The rarity index behind [PiecePicker.beginScan] (OPT-O08-01).
 *
 * [PiecePicker.pickNextPiece] answers the same question by rescanning every missing piece against every peer, once
 * per requested piece. For a 4 GB file that is 4,096 pieces × 20 peers × up to 16 window slots per scheduling pass.
 * This class answers it from one word-wise pass and then keeps the answer up to date in O(1) per pick:
 *
 * - `buckets[k]` is a bitset of the missing, still eligible pieces that have exactly `k` eligible holders, so the
 *   rarest pieces are found by testing `buckets[1]`, `buckets[2]`, … — no per-piece peer loop.
 * - Holder counts are computed with a bit-sliced ripple-carry adder over 64-bit words (`planes`), which costs
 *   `peers × words × levels` word operations instead of `peers × pieces` bit tests.
 * - A pick changes exactly one piece's holder count and at most one peer's window, so the buckets are updated in
 *   place rather than rebuilt.
 *
 * The observable behaviour is the one the property test pins down: **given the same seed, this scan returns exactly
 * the sequence of pieces the reference scan returns**, including its randomness (one `nextInt` per pick over the
 * ascending candidate list). [holderChecks] and [wordOps] count the work so a benchmark can compare the two without
 * depending on wall-clock time (see `SwarmSchedulerScanBench`).
 *
 * Single-threaded: owned by [PiecePicker], which the scheduler only uses from the engine's actor.
 */
internal class PieceScan(private val random: SeededRandom) {

    private var totalPieces: Int = 0
    private var words: Int = 0
    private var lastWordMask: Long = -1L

    /** The local verified bitfield, one pass behind a copy of its words. */
    private var localWords: LongArray = LongArray(0)

    private val peers = ArrayList<ScanPeer>(0)

    /** `pieceBucket[p]` = the holder count bucket piece `p` is in, or 0 when it is not a candidate. */
    private var pieceBucket: IntArray = IntArray(0)

    /** Bucket `k` holds the pieces with exactly `k` eligible holders; index 0 is unused. */
    private var buckets: Array<LongArray> = emptyArray()
    private var bucketCounts: IntArray = IntArray(0)

    /** Union of every non-empty bucket: the candidate pool used while no piece is held yet (the reference's first pick). */
    private var hasHolder: LongArray = LongArray(0)
    private var hasHolderCount: Int = 0

    /** How many requests are in flight per piece *as this pass sees them*, including the ones it picked itself. */
    private var inFlightNow: IntArray = IntArray(0)

    private var levels: Int = 1
    private var planes: Array<LongArray> = emptyArray()

    private var maxInFlightPerPiece: Int = 1
    private var canDuplicate: ((Int) -> Boolean)? = null
    private var firstPieceMode: Boolean = false

    /** The live map passed to [begin]; only read, to know which peer is already asking for a piece. */
    private var lastInFlightByPiece: Map<Int, Set<String>>? = null

    /** Piece-peer holder tests avoided/paid by this scan, for measurement only. */
    var holderChecks: Long = 0L
        private set

    /** 64-bit word operations this scan performed, for measurement only. */
    var wordOps: Long = 0L
        private set

    /**
     * Rebuilds the index for one scheduling pass.
     *
     * @param availablePeers peers that are connected, allowed, un-banned, serving, not backed off and still have
     *   request-window room. [PiecePicker.pickNextPiece] receives exactly this list.
     * @param inFlightByPiece the live `piece -> peers already asked` map of the content.
     * @param canDuplicate the endgame gate; only consulted for a piece that already has a request in flight.
     */
    fun begin(
        localBitfield: Bitfield,
        availablePeers: List<PeerPieceAvailability>,
        inFlightByPiece: Map<Int, Set<String>>,
        canDuplicate: ((Int) -> Boolean)?,
        endgame: Boolean,
    ) {
        totalPieces = localBitfield.size
        words = (totalPieces + 63) ushr 6
        val tail = totalPieces and 63
        lastWordMask = if (tail == 0) -1L else (1L shl tail) - 1L
        maxInFlightPerPiece = if (endgame) 2 else 1
        this.canDuplicate = canDuplicate
        this.lastInFlightByPiece = inFlightByPiece
        firstPieceMode = localBitfield.isEmpty()
        holderChecks = 0L
        wordOps = 0L

        ensureCapacity(availablePeers.size)

        for (w in 0 until words) {
            localWords[w] = localBitfield.rawWord(w)
            hasHolder[w] = 0L
        }
        hasHolderCount = 0
        for (p in 0 until totalPieces) {
            pieceBucket[p] = 0
            inFlightNow[p] = 0
        }
        for (k in buckets.indices) {
            buckets[k].fill(0L)
            bucketCounts[k] = 0
        }
        peers.clear()

        // 1. Bit-sliced holder counts over the available peers: one ripple-carry add per peer word.
        for (l in 0 until levels) {
            val plane = planes[l]
            for (w in 0 until words) plane[w] = 0L
        }
        for (peer in availablePeers) {
            val sp = ScanPeer(peer.peerId, peer.bitfield, peer.inFlightCount, peer.window)
            peers.add(sp)
            for (w in 0 until words) {
                val mask = if (w == words - 1) lastWordMask else -1L
                var carry = sp.bitfield.rawWord(w) and localWords[w].inv() and mask
                wordOps++
                if (carry == 0L) continue
                for (l in 0 until levels) {
                    val plane = planes[l]
                    val next = plane[w] xor carry
                    carry = plane[w] and carry
                    plane[w] = next
                    wordOps++
                }
            }
        }

        // 2. Turn the planes into a bucket per missing piece.
        for (piece in 0 until totalPieces) {
            if (localWords[piece ushr 6] and (1L shl (piece and 63)) != 0L) continue
            val w = piece ushr 6
            val bit = piece and 63
            var holders = 0
            for (l in 0 until levels) {
                holders = holders or (((planes[l][w] ushr bit) and 1L).toInt() shl l)
            }
            holderChecks++
            val inFlight = inFlightByPiece[piece]
            val inFlightSize = inFlight?.size ?: 0
            inFlightNow[piece] = inFlightSize
            if (inFlightSize >= maxInFlightPerPiece) continue
            if (inFlightSize > 0 && canDuplicate != null && !canDuplicate(piece)) continue
            // A peer that is already asking for this piece cannot be asked again, so it is not a holder.
            if (inFlight != null) {
                for (peerId in inFlight) {
                    val sp = peers.firstOrNull { it.peerId == peerId } ?: continue
                    if (sp.bitfield.get(piece)) holders--
                }
            }
            if (holders > 0) putInBucket(piece, holders)
        }
    }

    /**
     * The next piece to request, or null when nothing eligible is left. The random draw happens exactly once per
     * non-null answer, over the candidates in ascending piece order — that is what keeps this scan's choices
     * identical to the reference scan's for the same seed.
     */
    fun pickNext(): Int? {
        val useFirstPiecePool = firstPieceMode
        val pool: LongArray
        var size: Int
        if (useFirstPiecePool) {
            size = hasHolderCount
            pool = hasHolder
        } else {
            var k = 1
            while (k <= peers.size && bucketCounts[k] == 0) k++
            if (k > peers.size) return null
            size = bucketCounts[k]
            pool = buckets[k]
        }
        if (size <= 0) return null
        val target = random.nextInt(size)
        return nthSetBit(pool, target)
    }

    /**
     * Records that [piece] was requested from [peerId]; the scheduler must call this exactly once per issued request,
     * after its source selection succeeded (a failed selection issues nothing).
     */
    fun onPeerRequested(peerId: String, piece: Int) {
        if (piece !in 0 until totalPieces) return
        inFlightNow[piece]++
        val holder = peers.firstOrNull { it.peerId == peerId }
        if (holder != null && !holder.exhausted && holder.bitfield.get(piece)) {
            decrementBucket(piece, 1)
        }
        val eligible = inFlightNow[piece] < maxInFlightPerPiece &&
            (inFlightNow[piece] == 0 || (canDuplicate?.invoke(piece) ?: true))
        if (!eligible) clearBucket(piece)
        if (holder != null && !holder.exhausted && ++holder.inFlightCount >= holder.window) {
            exhaustPeer(holder)
        }
    }

    /** The holder count the scan believes [piece] has; exposed for the equivalence property test. */
    fun bucketOf(piece: Int): Int = if (piece in 0 until totalPieces) pieceBucket[piece] else 0

    // --- buckets ------------------------------------------------------------------------------------------------

    private fun putInBucket(piece: Int, k: Int) {
        val capped = minOf(k, peers.size)
        pieceBucket[piece] = capped
        val w = piece ushr 6
        val bit = 1L shl (piece and 63)
        buckets[capped][w] = buckets[capped][w] or bit
        bucketCounts[capped]++
        if (hasHolder[w] and bit == 0L) {
            hasHolder[w] = hasHolder[w] or bit
            hasHolderCount++
        }
    }

    private fun movePiece(piece: Int, from: Int, to: Int) {
        val w = piece ushr 6
        val bit = 1L shl (piece and 63)
        if (from > 0) {
            buckets[from][w] = buckets[from][w] and bit.inv()
            bucketCounts[from]--
        }
        if (to > 0) {
            buckets[to][w] = buckets[to][w] or bit
            bucketCounts[to]++
            if (hasHolder[w] and bit == 0L) {
                hasHolder[w] = hasHolder[w] or bit
                hasHolderCount++
            }
        } else if (hasHolder[w] and bit != 0L) {
            hasHolder[w] = hasHolder[w] and bit.inv()
            hasHolderCount--
        }
        pieceBucket[piece] = to
    }

    private fun decrementBucket(piece: Int, by: Int) {
        val from = pieceBucket[piece]
        if (from == 0) return
        movePiece(piece, from, maxOf(0, from - by))
    }

    private fun clearBucket(piece: Int) {
        val from = pieceBucket[piece]
        if (from == 0) return
        movePiece(piece, from, 0)
    }

    /** A full request window removes the peer from the holder counts of everything it holds, as the reference does. */
    private fun exhaustPeer(peer: ScanPeer) {
        peer.exhausted = true
        for (w in 0 until words) {
            val mask = if (w == words - 1) lastWordMask else -1L
            var bits = peer.bitfield.rawWord(w) and localWords[w].inv() and mask
            wordOps++
            while (bits != 0L) {
                val bit = bits and (-bits)
                val piece = (w shl 6) + bit.countTrailingZeroBits()
                bits = bits xor bit
                // A piece this peer is already asking for was never counted for it, so it must not lose a holder here.
                val asking = lastInFlightByPiece?.get(piece)
                if (asking != null && peer.peerId in asking) continue
                decrementBucket(piece, 1)
            }
        }
    }

    private fun nthSetBit(pool: LongArray, target: Int): Int {
        var remaining = target
        for (w in 0 until words) {
            val count = pool[w].countOneBits()
            if (remaining < count) {
                var bits = pool[w]
                var seen = 0
                while (bits != 0L) {
                    val bit = bits and (-bits)
                    if (seen == remaining) return (w shl 6) + bit.countTrailingZeroBits()
                    seen++
                    bits = bits xor bit
                }
            }
            remaining -= count
        }
        return 0
    }

    private fun ensureCapacity(peerCount: Int) {
        if (localWords.size < words) localWords = LongArray(words)
        if (hasHolder.size < words) hasHolder = LongArray(words)
        if (pieceBucket.size < totalPieces) pieceBucket = IntArray(totalPieces)
        if (inFlightNow.size < totalPieces) inFlightNow = IntArray(totalPieces)

        val wantedLevels = if (peerCount <= 0) 1 else 32 - (peerCount + 1).countLeadingZeroBits()
        if (wantedLevels > levels || planes.isEmpty()) {
            // Grow only: an unused upper plane stays zero, so a pass with fewer peers reads the same counts.
            levels = maxOf(levels, wantedLevels)
            planes = Array(levels) { index -> planes.getOrNull(index)?.takeIf { it.size >= words } ?: LongArray(words) }
        }
        for (l in 0 until levels) {
            if (planes[l].size < words) planes[l] = LongArray(words)
        }

        val wantedBuckets = peerCount + 1
        if (buckets.size < wantedBuckets) {
            val grown = Array(wantedBuckets) { index ->
                buckets.getOrNull(index)?.takeIf { it.size >= words } ?: LongArray(words)
            }
            buckets = grown
            bucketCounts = IntArray(wantedBuckets) { bucketCounts.getOrElse(it) { 0 } }
        }
        for (k in buckets.indices) {
            if (buckets[k].size < words) buckets[k] = LongArray(words)
        }
    }
}
