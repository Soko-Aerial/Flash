package com.transfer.flash.core.swarm.model

/**
 * Local lifecycle state of a swarm content item (§5.4, §5.5).
 */
public enum class SwarmLifecycleState {
    /** Announced, not yet accepted by user or policy. */
    OFFERED,

    /** Active download or serving in progress. */
    ACTIVE,

    /** Paused explicitly by user. */
    PAUSED_BY_USER,

    /** All pieces received; whole-file SHA-256 integrity verification in progress. */
    VERIFYING,

    /** Transfer completed and verified. */
    COMPLETE,

    /** Transfer was cancelled by origin or local user. */
    CANCELLED,

    /** Transfer failed unrecoverably (e.g. damaged, source lost, expired). */
    FAILED,
}
