package com.transfer.flash.core.swarm.engine

/**
 * Deterministic 64-bit XorShift pseudo-random number generator (§5.2, INV-11).
 * Never relies on platform PRNG or system clock.
 */
public class SeededRandom(seed: Long) {
    private var state: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    /**
     * Generates next pseudo-random 64-bit Long.
     */
    public fun nextLong(): Long {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return x
    }

    /**
     * Generates a non-negative pseudo-random integer strictly less than [bound].
     */
    public fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be > 0, got $bound" }
        val nonNegative = nextLong() and 0x7FFFFFFFFFFFFFFFL
        return (nonNegative % bound).toInt()
    }
}
