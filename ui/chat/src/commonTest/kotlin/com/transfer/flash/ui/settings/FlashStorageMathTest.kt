package com.transfer.flash.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashStorageMathTest {

    @Test
    fun `byte formatting uses binary units and safe non-negative values`() {
        assertEquals("0 B", FlashStorageMath.formatBytes(-1L))
        assertEquals("0 B", FlashStorageMath.formatBytes(0L))
        assertEquals("1023 B", FlashStorageMath.formatBytes(1023L))
        assertEquals("1 KB", FlashStorageMath.formatBytes(1024L))
        assertEquals("1.5 KB", FlashStorageMath.formatBytes(1536L))
        assertEquals("1 MB", FlashStorageMath.formatBytes(1024L * 1024L))
        assertEquals("2.5 GB", FlashStorageMath.formatBytes(5L * 1024L * 1024L * 1024L / 2L))
        assertEquals("1 TB", FlashStorageMath.formatBytes(1024L * 1024L * 1024L * 1024L))
    }

    @Test
    fun `safe summary distinguishes unknown loading error empty and cached refresh`() {
        assertEquals("Calculating storage usage", FlashStorageMath.usageSummary(null, isLoading = true, hasError = false))
        assertEquals("Storage usage unavailable", FlashStorageMath.usageSummary(null, isLoading = false, hasError = true))
        assertEquals("Storage usage unavailable", FlashStorageMath.usageSummary(null, isLoading = false, hasError = false))
        assertEquals("No received files", FlashStorageMath.usageSummary(0L, isLoading = false, hasError = false))
        assertEquals("1.5 KB used", FlashStorageMath.usageSummary(1536L, isLoading = false, hasError = false))
        assertEquals(
            "Refresh failed · 1.5 KB shown from last scan",
            FlashStorageMath.usageSummary(1536L, isLoading = false, hasError = true),
        )
        assertEquals("Refreshing · 1.5 KB used", FlashStorageMath.usageSummary(1536L, isLoading = true, hasError = false))
    }

    @Test
    fun `clear policy requires a successful non-empty idle scan`() {
        assertFalse(FlashStorageMath.canClearReceivedFiles(null, isLoading = false, hasError = false, isClearing = false))
        assertFalse(FlashStorageMath.canClearReceivedFiles(0L, isLoading = false, hasError = false, isClearing = false))
        assertFalse(FlashStorageMath.canClearReceivedFiles(1L, isLoading = true, hasError = false, isClearing = false))
        assertFalse(FlashStorageMath.canClearReceivedFiles(1L, isLoading = false, hasError = true, isClearing = false))
        assertFalse(FlashStorageMath.canClearReceivedFiles(1L, isLoading = false, hasError = false, isClearing = true))
        assertTrue(FlashStorageMath.canClearReceivedFiles(1L, isLoading = false, hasError = false, isClearing = false))
    }
}
