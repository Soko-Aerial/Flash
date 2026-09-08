package com.transfer.flash.core.transfer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlashTransferModelTest {

    @Test
    fun flashTransfer_defaultsAndAccessors() {
        val transfer = FlashTransfer(
            id = FlashTransferId("transfer-1"),
            peerName = "Alice",
            fileName = "photo.jpg",
            direction = FlashTransferDirection.Sending,
            bytesDone = 500L,
            bytesTotal = 1000L,
            state = FlashTransferState.Transferring,
        )

        assertEquals("transfer-1", transfer.id.value)
        assertEquals("Alice", transfer.peerName)
        assertEquals("photo.jpg", transfer.fileName)
        assertEquals(FlashTransferDirection.Sending, transfer.direction)
        assertEquals(500L, transfer.bytesDone)
        assertEquals(1000L, transfer.bytesTotal)
        assertEquals(FlashTransferState.Transferring, transfer.state)
        assertEquals(0L, transfer.speedBytesPerSec)
        assertEquals(0L, transfer.etaSeconds)
        assertNull(transfer.errorMessage)
    }

    @Test
    fun flashTransferState_allStatesExist() {
        val states = FlashTransferState.values()
        assertEquals(8, states.size)
        assertEquals(FlashTransferState.Offered, states[0])
        assertEquals(FlashTransferState.Queued, states[1])
        assertEquals(FlashTransferState.Transferring, states[2])
        assertEquals(FlashTransferState.Paused, states[3])
        assertEquals(FlashTransferState.Verifying, states[4])
        assertEquals(FlashTransferState.Completed, states[5])
        assertEquals(FlashTransferState.Failed, states[6])
        assertEquals(FlashTransferState.Cancelled, states[7])
    }
}
