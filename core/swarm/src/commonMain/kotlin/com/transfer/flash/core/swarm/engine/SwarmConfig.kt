package com.transfer.flash.core.swarm.engine

/**
 * Performance profile controlling memory budgets and concurrency (§4e, §4f, ripple 30).
 */
public enum class SwarmProfile(
    public val serveSlots: Int,
    public val inFlightByteBudget: Long,
) {
    /** 1 slot, 8 MiB in-flight budget. */
    LOW(serveSlots = 1, inFlightByteBudget = 8L * 1024 * 1024),

    /** 2 slots, 16 MiB in-flight budget. */
    MEDIUM(serveSlots = 2, inFlightByteBudget = 16L * 1024 * 1024),

    /** 4 slots, 32 MiB in-flight budget. */
    HIGH(serveSlots = 4, inFlightByteBudget = 32L * 1024 * 1024);
}

/**
 * Static configuration for the sans-IO swarm engine (§5.1, §5.2).
 */
public data class SwarmConfig(
    public val profile: SwarmProfile = SwarmProfile.MEDIUM,
    public val autoAcceptIncoming: Boolean = false,
    public val retentionMs: Long = 7L * 24 * 3600 * 1000,
    public val servingEnabled: Boolean = true,
)
