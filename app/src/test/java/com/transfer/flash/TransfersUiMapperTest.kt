package com.transfer.flash

import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.ui.transfers.FlashTransferDirection as UiDirection
import com.transfer.flash.ui.transfers.FlashTransferState as UiState
import com.transfer.flash.ui.transfers.TransfersUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the domain → UI transfer mapping seam ([toUiItem] / [fromDomain]).
 * Pure data mapping, so no Android runtime / Robolectric needed.
 */
class TransfersUiMapperTest {

    private fun domain(
        id: String = "t1",
        state: FlashTransferState,
        direction: FlashTransferDirection = FlashTransferDirection.Sending,
        etaSeconds: Long = 5L,
        errorMessage: String? = null,
    ) = FlashTransfer(
        id = FlashTransferId(id),
        peerName = "Pixel",
        fileName = "photo.jpg",
        direction = direction,
        bytesDone = 40L,
        bytesTotal = 100L,
        state = state,
        speedBytesPerSec = 1_000L,
        etaSeconds = etaSeconds,
        errorMessage = errorMessage,
    )

    @Test
    fun directionMapsBothWays() {
        assertEquals(
            UiDirection.Send,
            domain(state = FlashTransferState.Transferring, direction = FlashTransferDirection.Sending).toUiItem().direction,
        )
        assertEquals(
            UiDirection.Receive,
            domain(state = FlashTransferState.Transferring, direction = FlashTransferDirection.Receiving).toUiItem().direction,
        )
    }

    @Test
    fun stateBucketsCoverEveryDomainState() {
        assertEquals(UiState.Offered, domain(state = FlashTransferState.Offered).toUiItem().state)
        assertEquals(UiState.Queued, domain(state = FlashTransferState.Queued).toUiItem().state)
        assertEquals(UiState.Active, domain(state = FlashTransferState.Transferring).toUiItem().state)
        assertEquals(UiState.Active, domain(state = FlashTransferState.Verifying).toUiItem().state)
        assertEquals(UiState.Paused, domain(state = FlashTransferState.Paused).toUiItem().state)
        assertEquals(UiState.Completed, domain(state = FlashTransferState.Completed).toUiItem().state)
        assertEquals(UiState.Failed, domain(state = FlashTransferState.Failed).toUiItem().state)
        assertEquals(UiState.Failed, domain(state = FlashTransferState.Cancelled).toUiItem().state)
    }

    @Test
    fun cancelledSurfacesAsFailedWithLabel() {
        val item = domain(state = FlashTransferState.Cancelled).toUiItem()
        assertEquals(UiState.Failed, item.state)
        assertEquals("Cancelled", item.errorMessage)
    }

    @Test
    fun existingErrorMessageWinsOverCancelledLabel() {
        val item = domain(state = FlashTransferState.Cancelled, errorMessage = "peer left").toUiItem()
        assertEquals("peer left", item.errorMessage)
    }

    @Test
    fun nonPositiveEtaNormalisesToNull() {
        assertNull(domain(state = FlashTransferState.Transferring, etaSeconds = 0L).toUiItem().etaSeconds)
        assertNull(domain(state = FlashTransferState.Transferring, etaSeconds = -3L).toUiItem().etaSeconds)
        assertEquals(5L, domain(state = FlashTransferState.Transferring, etaSeconds = 5L).toUiItem().etaSeconds)
    }

    @Test
    fun verifiedOnlyForCompleted() {
        assertTrue(domain(state = FlashTransferState.Completed).toUiItem().verified)
        assertFalse(domain(state = FlashTransferState.Verifying).toUiItem().verified)
        assertFalse(domain(state = FlashTransferState.Transferring).toUiItem().verified)
    }

    @Test
    fun fromDomainBucketsIntoActiveFailedHistory() {
        val ui = TransfersUiState.fromDomain(
            transfers = listOf(
                domain(id = "a", state = FlashTransferState.Transferring),
                domain(id = "b", state = FlashTransferState.Queued),
                domain(id = "c", state = FlashTransferState.Paused),
                domain(id = "d", state = FlashTransferState.Failed),
                domain(id = "e", state = FlashTransferState.Cancelled),
                domain(id = "f", state = FlashTransferState.Completed),
            ),
            isLoading = false,
            isError = false,
        )
        assertEquals(3, ui.active.size) // Transferring + Queued + Paused
        assertEquals(2, ui.failed.size) // Failed + Cancelled
        assertEquals(1, ui.history.size) // Completed
    }

    @Test
    fun emptyDomainListYieldsEmptyState() {
        val ui = TransfersUiState.fromDomain(emptyList(), isLoading = false, isError = false)
        assertTrue(ui.active.isEmpty())
        assertTrue(ui.failed.isEmpty())
        assertTrue(ui.history.isEmpty())
        assertFalse("a booted repository with no transfers is empty, not loading", ui.isLoading)
        assertFalse(ui.isError)
    }

    /**
     * ERROR-034. The screen resolves Loading only when the state is *also* empty, so the flags have
     * to survive the bucketing rather than being dropped by it — an empty pre-boot state must read
     * as Loading, not as "No transfers yet".
     */
    @Test
    fun bootFlagsSurviveTheBucketing() {
        val loading = TransfersUiState.fromDomain(emptyList(), isLoading = true, isError = false)
        assertTrue(loading.isLoading)
        assertFalse(loading.isError)

        val failed = TransfersUiState.fromDomain(emptyList(), isLoading = false, isError = true)
        assertTrue(failed.isError)
        assertFalse(failed.isLoading)

        // Flags are orthogonal to content: a populated list still carries them through.
        val populated = TransfersUiState.fromDomain(
            transfers = listOf(domain(id = "a", state = FlashTransferState.Transferring)),
            isLoading = true,
            isError = false,
        )
        assertEquals(1, populated.active.size)
        assertTrue(populated.isLoading)
    }
}
