package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.SwarmWaitReason

/**
 * Environmental context evaluated by [WaitClassifier] (§4i, §8.1).
 */
public data class WaitClassificationContext(
    public val systemSuspended: Boolean,
    public val networkUp: Boolean,
    public val freeBytes: Long,
    public val remainingBytes: Long,
    public val storageUnavailable: Boolean,
    public val isOrigin: Boolean,
    public val isOriginConnected: Boolean,
    public val isOriginSourceLost: Boolean,
    public val hasConnectedHoldersForMissing: Boolean,
    public val hasDisconnectedHoldersForMissing: Boolean,
)

/**
 * Pure function classifying whether content is currently waiting and why (§4i, §8.1, INV-9).
 * Recomputed after every event that touches that content.
 */
public object WaitClassifier {
    /**
     * Determines the active [SwarmWaitReason] or null if the content is actively transferring.
     */
    public fun classify(context: WaitClassificationContext): SwarmWaitReason? {
        // 1. System level suspend (service limit, battery saver)
        if (context.systemSuspended) {
            return SwarmWaitReason.WAITING_FOR_SYSTEM
        }

        // 2. Network connectivity
        if (!context.networkUp) {
            return SwarmWaitReason.WAITING_FOR_NETWORK
        }

        // 3. Storage space
        if (context.remainingBytes > 0 && context.freeBytes < context.remainingBytes) {
            return SwarmWaitReason.WAITING_FOR_SPACE
        }

        // 4. Storage availability / permission
        if (context.storageUnavailable) {
            return SwarmWaitReason.WAITING_FOR_STORAGE
        }

        // Origins don't wait for peers to download
        if (context.isOrigin) {
            return null
        }

        // If we have connected holders that have our missing pieces, we can transfer right now
        if (context.hasConnectedHoldersForMissing) {
            return null
        }

        // If origin is connected and source is intact, origin can serve missing pieces
        if (context.isOriginConnected && !context.isOriginSourceLost) {
            return null
        }

        // If known holders exist but aren't connected yet (session ceiling / dial budget)
        if (context.hasDisconnectedHoldersForMissing) {
            return SwarmWaitReason.WAITING_FOR_SESSION
        }

        // Missing pieces cannot be fetched from any currently connected peer
        // Check if origin is offline but holds the pieces
        if (!context.isOriginConnected && !context.isOriginSourceLost) {
            return SwarmWaitReason.WAITING_FOR_SENDER
        }

        // Otherwise no reachable holder has the missing pieces
        return SwarmWaitReason.WAITING_FOR_HOLDERS
    }
}
