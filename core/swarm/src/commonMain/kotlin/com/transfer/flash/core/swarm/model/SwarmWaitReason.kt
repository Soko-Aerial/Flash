package com.transfer.flash.core.swarm.model

/**
 * Reason why a swarm transfer is currently queued/waiting (table 8.1, INV-9).
 * Every wait reason has a deterministic recovery event.
 */
public enum class SwarmWaitReason {
    /** Missing pieces exist only at origin, and origin is offline. */
    WAITING_FOR_SENDER,

    /** Origin cannot serve, and no online peer has missing pieces. */
    WAITING_FOR_HOLDERS,

    /** Device has no usable network connection. */
    WAITING_FOR_NETWORK,

    /** Disk space is insufficient to receive remaining pieces. */
    WAITING_FOR_SPACE,

    /** Partial or target destination storage is unavailable or inaccessible. */
    WAITING_FOR_STORAGE,

    /** OS background/power limits stopped transfer execution (e.g. FGS timeout). */
    WAITING_FOR_SYSTEM,

    /** Holders are known but session ceiling / dial budget prevents connecting. */
    WAITING_FOR_SESSION,
}
