package com.transfer.flash.core.swarm.api

import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.transfer.model.FlashTransfer
import kotlinx.coroutines.flow.StateFlow

/**
 * Public facade for swarm operations and status observation (§5.2, §7A SW-8 Task 1).
 */
public interface FlashSwarm {
    /**
     * Active transfer rows managed by the swarm.
     */
    public val rows: StateFlow<List<FlashTransfer>>

    /**
     * Detailed status and mesh health for a specific transfer row (keyed by transferId).
     */
    public fun status(transferId: String): StateFlow<FlashSwarmStatus?>

    /**
     * Receiver accepts an offered swarm transfer.
     */
    public suspend fun accept(transferId: String)

    /**
     * Receiver declines an offered swarm transfer (local cancel).
     */
    public suspend fun decline(transferId: String)

    /**
     * User pauses an active transfer.
     */
    public suspend fun pause(transferId: String)

    /**
     * User resumes a paused transfer.
     */
    public suspend fun resume(transferId: String)

    /**
     * Receiver cancels locally (deletes partial, leaves others unaffected).
     */
    public suspend fun cancelLocal(transferId: String)

    /**
     * Origin cancels transfer for everyone (signs and broadcasts tombstone).
     */
    public suspend fun cancelAsOrigin(
        transferId: String,
        reason: SwarmTombstoneReason = SwarmTombstoneReason.USER,
    )

    /**
     * System pauses the transfer (e.g. background service timeout; WAITING_FOR_SYSTEM).
     */
    public suspend fun pauseForSystem(transferId: String, reason: String)

    /**
     * Re-evaluates wait reasons across all transfers (invoked on network change, wake hook).
     */
    public fun reevaluate()

    /**
     * Purges expired records and tombstones from database, and cleans up orphaned partials (SW-9).
     */
    public suspend fun runRetentionCleanup()
}
