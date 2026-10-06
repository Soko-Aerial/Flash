package com.transfer.flash.debug

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState

/**
 * Result of [TimeoutStopPlan.of], separating transfers that must be cancelled
 * (1:1 transfers on foreground service timeout) from transfers that must be paused
 * (swarm rows, where cancelling would send a destructive tombstone to the entire group).
 */
public data class TimeoutPlan(
    val toCancel: List<FlashTransfer>,
    val toPause: List<FlashTransfer>,
)

/**
 * Pure function extracted from [FlashBackgroundService.onTimeout] for testability (SW-2 Part C).
 * Ensures that service timeout does not destroy group swarm transfers while preserving
 * existing 1:1 cancel behavior.
 */
public object TimeoutStopPlan {
    public fun of(
        transfers: Collection<FlashTransfer>,
        isSwarmRow: (FlashTransferId) -> Boolean = { false },
    ): TimeoutPlan {
        val toCancel = mutableListOf<FlashTransfer>()
        val toPause = mutableListOf<FlashTransfer>()
        for (transfer in transfers) {
            if (transfer.state == FlashTransferState.Transferring) {
                if (isSwarmRow(transfer.id)) {
                    toPause.add(transfer)
                } else {
                    toCancel.add(transfer)
                }
            }
        }
        return TimeoutPlan(toCancel = toCancel, toPause = toPause)
    }
}
