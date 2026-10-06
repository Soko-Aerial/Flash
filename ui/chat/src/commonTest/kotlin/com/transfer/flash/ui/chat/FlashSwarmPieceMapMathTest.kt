package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashSwarmPieceMapMathTest {

    @Test
    fun `engine block codes map to the matching statuses`() {
        assertEquals(
            listOf(
                FlashPieceStatus.Missing,
                FlashPieceStatus.AvailableOnPeers,
                FlashPieceStatus.InFlightDownloading,
                FlashPieceStatus.VerifiedSaved,
            ),
            FlashSwarmPieceMapMath.statusesFromBlocks(listOf(0, 1, 2, 3)),
        )
    }

    @Test
    fun `an unknown code reads as missing, never as progress`() {
        assertEquals(listOf(FlashPieceStatus.Missing), FlashSwarmPieceMapMath.statusesFromBlocks(listOf(9)))
    }

    @Test
    fun `no blocks means no statuses so nothing is invented`() {
        assertTrue(FlashSwarmPieceMapMath.statusesFromBlocks(emptyList()).isEmpty())
    }

    @Test
    fun `summary counts come from the real blocks`() {
        val statuses = FlashSwarmPieceMapMath.statusesFromBlocks(List(32) { 3 } + List(4) { 2 } + List(28) { 0 })
        assertEquals("32 of 64 pieces • 4 in-flight", FlashSwarmPieceMapMath.summaryText(statuses))
        assertEquals(
            "All 8 pieces verified & saved",
            FlashSwarmPieceMapMath.summaryText(FlashSwarmPieceMapMath.statusesFromBlocks(List(8) { 3 })),
        )
    }
}
