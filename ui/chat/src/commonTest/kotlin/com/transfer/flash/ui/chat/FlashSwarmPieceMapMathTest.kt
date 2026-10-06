package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashSwarmPieceMapMathTest {

    @Test
    fun testCompleteProgressAllVerified() {
        val statuses = FlashSwarmPieceMapMath.computeBlockStatuses(
            totalBlocks = 32,
            progress = 1.0f,
        )
        assertEquals(32, statuses.size)
        assertTrue(statuses.all { it == FlashPieceStatus.VerifiedSaved })
        assertEquals("All 32 pieces verified & saved", FlashSwarmPieceMapMath.summaryText(statuses))
    }

    @Test
    fun testPartialProgressWithInFlight() {
        val statuses = FlashSwarmPieceMapMath.computeBlockStatuses(
            totalBlocks = 64,
            progress = 0.5f,
            holdersOnline = 2,
            isDownloading = true,
        )
        assertEquals(64, statuses.size)
        val verified = statuses.count { it == FlashPieceStatus.VerifiedSaved }
        val inFlight = statuses.count { it == FlashPieceStatus.InFlightDownloading }
        val onPeers = statuses.count { it == FlashPieceStatus.AvailableOnPeers }

        assertEquals(32, verified)
        assertEquals(4, inFlight)
        assertTrue(onPeers > 0)
        assertTrue(FlashSwarmPieceMapMath.summaryText(statuses).contains("32 of 64 pieces"))
    }

    @Test
    fun testZeroProgressIdle() {
        val statuses = FlashSwarmPieceMapMath.computeBlockStatuses(
            totalBlocks = 64,
            progress = 0.0f,
            holdersOnline = 0,
            isDownloading = false,
        )
        val verified = statuses.count { it == FlashPieceStatus.VerifiedSaved }
        val missing = statuses.count { it == FlashPieceStatus.Missing }
        assertEquals(0, verified)
        assertEquals(64, missing)
        assertEquals("0 of 64 pieces verified", FlashSwarmPieceMapMath.summaryText(statuses))
    }
}
