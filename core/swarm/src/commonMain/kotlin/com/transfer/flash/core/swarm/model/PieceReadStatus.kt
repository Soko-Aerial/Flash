package com.transfer.flash.core.swarm.model

/**
 * Status of a piece read from local storage / source (§5.2, E-18..E-20).
 */
public enum class PieceReadStatus {
    /** Read succeeded and piece hash matched manifest. */
    OK,

    /** File exists but piece hash failed re-verification (source changed or corrupt). */
    CHANGED,

    /** File/source is missing, deleted, or unreadable. */
    GONE,
}
