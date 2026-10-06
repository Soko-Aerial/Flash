package com.transfer.flash.core.swarm.model

/**
 * Rejection reason in a REJECT frame (ADR-071, protocol §5.3).
 */
public enum class SwarmRejectReason(public val wireValue: Int) {
    /** Slots or budget full; ask again after retryAfterMs. */
    BUSY(1),

    /** Origin only: another member holds the piece (origin offer policy, INV-7). */
    ELSEWHERE(2),

    /** This device holds no announcement of this root in this group (INV-8). */
    UNKNOWN(3),

    /** This device had the file and can no longer read it. */
    GONE(4),

    /** The requester fails the group gate for this group (ADR-075). */
    NOT_MEMBER(5),

    /** Every announcement of this content in this group is tombstoned. */
    CANCELLED(6),

    /** The envelope version is unknown. */
    UNSUPPORTED(7);

    public companion object {
        /**
         * Resolves reason from wire byte. Unknown values default to [BUSY] per protocol spec.
         */
        public fun fromWire(value: Int): SwarmRejectReason = when (value) {
            1 -> BUSY
            2 -> ELSEWHERE
            3 -> UNKNOWN
            4 -> GONE
            5 -> NOT_MEMBER
            6 -> CANCELLED
            7 -> UNSUPPORTED
            else -> BUSY
        }
    }
}
