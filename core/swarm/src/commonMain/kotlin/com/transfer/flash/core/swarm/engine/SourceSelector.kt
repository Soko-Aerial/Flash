package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.Bitfield

/**
 * Candidate peer representation for source selection (§4d).
 */
public data class PeerSourceCandidate(
    public val peerId: String,
    public val isOrigin: Boolean,
    public val bitfield: Bitfield,
    public val isConnected: Boolean,
    public val isAllowed: Boolean,
    public val hasSw1Feature: Boolean,
    public val servingEnabled: Boolean,
    public val strikes: Int,
    public val backoffUntilMs: Long,
    public val inFlightCount: Int,
    public val window: Int,
    public val ewmaSpeedBps: Double,
    public val inFlightPieces: Set<Int>,
)

/**
 * Pure source selector implementing Rule A source prioritisation (§4d, §4.1).
 * Always prioritises non-origin holders first, then highest EWMA throughput, then fewest in-flight pieces.
 * Origin is only used when no other allowed connected holder has the piece.
 * Strictly avoids banned peers, backoff periods, and full request windows.
 */
public class SourceSelector(
    private val random: SeededRandom,
    private val maxStrikes: Int = 3,
) {
    /**
     * Chooses the best peer from [candidates] to request [pieceIndex] at time [nowMs].
     * Returns null if no eligible candidate exists.
     */
    public fun selectSource(
        pieceIndex: Int,
        candidates: List<PeerSourceCandidate>,
        nowMs: Long,
    ): PeerSourceCandidate? {
        // Filter eligible peers
        val eligible = candidates.filter { c ->
            c.isConnected &&
                c.isAllowed &&
                c.hasSw1Feature &&
                c.servingEnabled &&
                c.strikes < maxStrikes &&
                nowMs >= c.backoffUntilMs &&
                c.inFlightCount < c.window &&
                pieceIndex !in c.inFlightPieces &&
                c.bitfield.get(pieceIndex)
        }

        if (eligible.isEmpty()) return null

        // Partition into non-origin holders vs origin
        val nonOrigin = eligible.filter { !it.isOrigin }
        val pool = if (nonOrigin.isNotEmpty()) nonOrigin else eligible

        // Sort candidates: highest EWMA speed first, then fewest in-flight pieces
        var bestSpeed = -1.0
        var minInFlight = Int.MAX_VALUE
        val bestCandidates = ArrayList<PeerSourceCandidate>()

        for (c in pool) {
            val speed = c.ewmaSpeedBps
            val inFlight = c.inFlightCount

            if (bestCandidates.isEmpty()) {
                bestSpeed = speed
                minInFlight = inFlight
                bestCandidates.add(c)
                continue
            }

            // Compare speed (within 5% considered tie for load balancing)
            val speedDiff = speed - bestSpeed
            val significantlyFaster = speedDiff > maxOf(1.0, bestSpeed * 0.05)
            val significantlySlower = -speedDiff > maxOf(1.0, bestSpeed * 0.05)

            if (significantlyFaster) {
                bestSpeed = speed
                minInFlight = inFlight
                bestCandidates.clear()
                bestCandidates.add(c)
            } else if (!significantlySlower) {
                // Similar speed: compare in-flight
                if (inFlight < minInFlight) {
                    minInFlight = inFlight
                    bestCandidates.clear()
                    bestCandidates.add(c)
                } else if (inFlight == minInFlight) {
                    bestCandidates.add(c)
                }
            }
        }

        if (bestCandidates.isEmpty()) return null
        if (bestCandidates.size == 1) return bestCandidates[0]

        // Tie-break randomly from seed
        val pickedIdx = random.nextInt(bestCandidates.size)
        return bestCandidates[pickedIdx]
    }
}
