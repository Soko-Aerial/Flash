package com.transfer.flash.ui.transfers

import com.transfer.flash.ui.theme.FlashMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM tests for UI-047 transfers-page pure helpers. */
class FlashTransfersLogicTest {

    /**
     * The pacing window guards a HIGH-tier animation, so it is an invariant rather than a taste
     * setting: both the per-row progress fill and the header throughput roll animate for
     * [FlashMotion.NormalMillis], and a window longer than that would let each animation finish and
     * then sit still until the next value arrived — a periodic dead stop on the one tier that is not
     * allowed to give anything up. Strictly below the duration means every new target lands mid-flight.
     */
    @Test
    fun `the progress pacing window stays inside the animation it feeds`() {
        assertTrue(
            FlashTransfersMath.PROGRESS_THROTTLE_MS < FlashMotion.NormalMillis.toLong(),
            "a ${FlashTransfersMath.PROGRESS_THROTTLE_MS}ms window would dead-stop a " +
                "${FlashMotion.NormalMillis}ms animation",
        )
        // And it has to actually throttle: a non-positive window is the operator's disable switch.
        assertTrue(FlashTransfersMath.PROGRESS_THROTTLE_MS > 0L)
    }

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

    // ---------------------------------------------------------------------------
    // progressBarWidthPx — the arithmetic lifted out of Modifier.fillMaxWidth(fraction)
    // when the progress fill moved to a layout-phase read (EXP-013). These assertions are
    // what pins it to Compose's own FillNode formula.
    // ---------------------------------------------------------------------------

    @Test
    fun `progress bar width spans nothing at zero and the whole track at one`() {
        assertEquals(0, FlashTransfersMath.progressBarWidthPx(0, 400, 0f))
        assertEquals(400, FlashTransfersMath.progressBarWidthPx(0, 400, 1f))
    }

    @Test
    fun `progress bar width rounds to the nearest pixel rather than truncating`() {
        // 401 * 0.5 = 200.5 -> 201, the same half-up rounding FillNode's roundToInt does.
        assertEquals(201, FlashTransfersMath.progressBarWidthPx(0, 401, 0.5f))
        assertEquals(200, FlashTransfersMath.progressBarWidthPx(0, 400, 0.4999f))
    }

    @Test
    fun `progress bar width never escapes the incoming constraints`() {
        // An animation can overshoot its target; the fill must not measure wider than the track.
        assertEquals(400, FlashTransfersMath.progressBarWidthPx(0, 400, 1.4f))
        assertEquals(0, FlashTransfersMath.progressBarWidthPx(0, 400, -0.2f))
        // A fixed-width parent pins both bounds, so the fraction cannot shrink the fill below it.
        assertEquals(400, FlashTransfersMath.progressBarWidthPx(400, 400, 0.1f))
    }

    @Test
    fun `a zero width track produces a zero width fill instead of dividing`() {
        assertEquals(0, FlashTransfersMath.progressBarWidthPx(0, 0, 0.6f))
    }
}
