package com.transfer.flash.debug

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation and unit tests for [TimeoutStopPlan] (SW-2 Part C).
 * Ensures that service timeout preserves existing 1:1 cancel behavior while
 * routing group swarm rows to pause instead of cancel.
 */
class TimeoutStopPlanTest {

    private fun transfer(
        id: String,
        state: FlashTransferState,
    ) = FlashTransfer(
        id = FlashTransferId(id),
        peerName = "Peer-$id",
        fileName = "file-$id.dat",
        direction = FlashTransferDirection.Sending,
        bytesDone = 50L,
        bytesTotal = 100L,
        state = state,
        speedBytesPerSec = 1000L,
        etaSeconds = 10L,
    )

    @Test
    fun defaultBehaviorPinsTodaysCancelAllTransferring() {
        val transfers = listOf(
            transfer("t1", FlashTransferState.Transferring),
            transfer("t2", FlashTransferState.Paused),
            transfer("t3", FlashTransferState.Transferring),
            transfer("t4", FlashTransferState.Completed),
            transfer("t5", FlashTransferState.Offered),
            transfer("t6", FlashTransferState.Failed),
        )

        val plan = TimeoutStopPlan.of(transfers)

        assertEquals(listOf("t1", "t3"), plan.toCancel.map { it.id.value })
        assertTrue("toPause must be empty when isSwarmRow is default false", plan.toPause.isEmpty())
    }

    @Test
    fun swarmTransfersArePausedInsteadOfCancelled() {
        val transfers = listOf(
            transfer("t1-1to1", FlashTransferState.Transferring),
            transfer("t2-swarm", FlashTransferState.Transferring),
            transfer("t3-swarm", FlashTransferState.Paused),
            transfer("t4-1to1", FlashTransferState.Transferring),
        )

        val plan = TimeoutStopPlan.of(transfers, isSwarmRow = { it.value.contains("swarm") })

        assertEquals(listOf("t1-1to1", "t4-1to1"), plan.toCancel.map { it.id.value })
        assertEquals(listOf("t2-swarm"), plan.toPause.map { it.id.value })
    }

    @Test
    fun emptyListProducesEmptyPlan() {
        val plan = TimeoutStopPlan.of(emptyList())
        assertTrue(plan.toCancel.isEmpty())
        assertTrue(plan.toPause.isEmpty())
    }
}
