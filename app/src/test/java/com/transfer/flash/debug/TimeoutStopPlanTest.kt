package com.transfer.flash.debug

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TimeoutStopPlan] (SW-2 Part C, audit B-02): a service timeout pauses
 * every moving transfer and cancels none.
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
    fun everyTransferringRowIsPausedNeverCancelled() {
        val transfers = listOf(
            transfer("t1", FlashTransferState.Transferring),
            transfer("t2", FlashTransferState.Paused),
            transfer("t3", FlashTransferState.Transferring),
            transfer("t4", FlashTransferState.Completed),
            transfer("t5", FlashTransferState.Offered),
            transfer("t6", FlashTransferState.Failed),
            transfer("t7", FlashTransferState.Cancelled),
        )

        val plan = TimeoutStopPlan.of(transfers)

        assertEquals(listOf("t1", "t3"), plan.toPause.map { it.id.value })
    }

    @Test
    fun emptyListProducesEmptyPlan() {
        assertTrue(TimeoutStopPlan.of(emptyList()).toPause.isEmpty())
    }
}
