package com.transfer.flash.debug

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferState

/**
 * Result of [TimeoutStopPlan.of]: the transfers to pause because the foreground service is about to stop.
 */
public data class TimeoutPlan(
    val toPause: List<FlashTransfer>,
)

/**
 * Pure function extracted from [FlashBackgroundService.onTimeout] for testability (SW-2 Part C).
 *
 * Every transfer that is moving bytes is **paused**, 1:1 as well as swarm and hub (audit 2026-10-20 B-02). The service
 * timeout is a platform event, not a decision by the user or the peer: cancelling a 1:1 send destroyed its resume state
 * (`forgetChunks`) and told the peer it was cancelled, and cancelling a group file would have sent a tombstone to the
 * whole group. A paused transfer keeps its done-set and can be resumed.
 */
public object TimeoutStopPlan {
    public fun of(transfers: Collection<FlashTransfer>): TimeoutPlan =
        TimeoutPlan(toPause = transfers.filter { it.state == FlashTransferState.Transferring })
}
