package com.transfer.flash.ui.transfers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM tests for UI-047 transfers-page pure helpers. */
class FlashTransfersLogicTest {

    private fun item(
        state: FlashTransferState,
        bytesDone: Long = 0,
        bytesTotal: Long = 1000,
        speed: Long = 0,
        eta: Long? = null,
        error: String? = null,
    ) = FlashTransferItemUi(
        id = "t",
        fileName = "a.pdf",
        direction = FlashTransferDirection.Send,
        peerName = "Ravi",
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        state = state,
        speedBytesPerSec = speed,
        etaSeconds = eta,
        errorMessage = error,
    )

    @Test
    fun `grouping routes rows into active failed history sections`() {
        val ui = TransfersUiState.fromItems(
            listOf(
                item(FlashTransferState.Active),
                item(FlashTransferState.Paused),
                item(FlashTransferState.Queued),
                item(FlashTransferState.Failed),
                item(FlashTransferState.Completed),
            ),
        )
        assertEquals(3, ui.active.size)
        assertEquals(1, ui.failed.size)
        assertEquals(1, ui.history.size)
    }

    @Test
    fun `progress fraction clamps and never divides by zero`() {
        assertEquals(0f, FlashTransfersMath.progressFraction(0, 100), 0.0001f)
        assertEquals(0.5f, FlashTransfersMath.progressFraction(50, 100), 0.0001f)
        assertEquals(1f, FlashTransfersMath.progressFraction(150, 100), 0.0001f)
        assertEquals(0f, FlashTransfersMath.progressFraction(10, 0), 0.0001f)
        assertEquals(0f, FlashTransfersMath.progressFraction(10, -5), 0.0001f)
    }

    @Test
    fun `speed formats human readable`() {
        assertEquals("", FlashTransfersMath.formatSpeed(0))
        assertEquals("512 KB/s", FlashTransfersMath.formatSpeed(512 * 1024))
        assertTrue(FlashTransfersMath.formatSpeed(3_500_000).startsWith("3."))
        assertTrue(FlashTransfersMath.formatSpeed(3_500_000).endsWith("MB/s"))
    }

    @Test
    fun `eta formats buckets`() {
        assertEquals("", FlashTransfersMath.formatEta(null))
        assertEquals("", FlashTransfersMath.formatEta(0))
        assertEquals("45 sec left", FlashTransfersMath.formatEta(45))
        assertEquals("2 min left", FlashTransfersMath.formatEta(120))
        assertEquals("1 h 5 min left", FlashTransfersMath.formatEta(3900))
    }

    @Test
    fun `status line prefers error message on failure`() {
        assertEquals(
            "Peer unreachable",
            FlashTransfersMath.statusLine(item(FlashTransferState.Failed, error = "Peer unreachable")),
        )
        assertEquals("Failed", FlashTransfersMath.statusLine(item(FlashTransferState.Failed)))
        assertEquals("Paused", FlashTransfersMath.statusLine(item(FlashTransferState.Paused)))
        assertEquals("Queued", FlashTransfersMath.statusLine(item(FlashTransferState.Queued)))
    }

    @Test
    fun `active status line joins speed and eta with dot separator`() {
        val line = FlashTransfersMath.statusLine(
            item(FlashTransferState.Active, speed = 1024 * 1024, eta = 90),
        )
        assertEquals("1.0 MB/s · 1 min left", line)
    }
}
