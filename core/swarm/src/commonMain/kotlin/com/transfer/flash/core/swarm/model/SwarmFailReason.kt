package com.transfer.flash.core.swarm.model

/**
 * Standard reasons why a swarm transfer failed permanently (§5.4, §8.2).
 */
public enum class SwarmFailReason(public val reasonCode: String) {
    /** Whole-file hash mismatch after re-verification and re-fetch (E-14). */
    DAMAGED("DAMAGED"),

    /** Origin file deleted, changed, or unreadable and missing pieces exist nowhere (E-19, E-20). */
    SOURCE_LOST("SOURCE_LOST"),

    /** Local device is no longer an active member of the group (E-34). */
    NOT_MEMBER("NOT_MEMBER"),

    /** Retention period elapsed without completing missing pieces (E-47). */
    EXPIRED("EXPIRED"),

    /** Local storage unrecoverable error (E-27, E-28). */
    STORAGE_FAILED("STORAGE_FAILED");

    public companion object {
        public fun fromCode(code: String?): SwarmFailReason? = when (code) {
            "DAMAGED" -> DAMAGED
            "SOURCE_LOST" -> SOURCE_LOST
            "NOT_MEMBER" -> NOT_MEMBER
            "EXPIRED" -> EXPIRED
            "STORAGE_FAILED" -> STORAGE_FAILED
            else -> null
        }
    }
}
