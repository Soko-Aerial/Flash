package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashStagingMathTest {

    @Test
    fun can_add_more_enforces_limit() {
        assertTrue(FlashStagingMath.canAddMore(0))
        assertTrue(FlashStagingMath.canAddMore(9))
        assertFalse(FlashStagingMath.canAddMore(10))
        assertFalse(FlashStagingMath.canAddMore(15))
    }

    @Test
    fun format_total_bytes_sums_attachment_sizes() {
        val items = listOf(
            FlashShareItemUi(uri = "u1", name = "a.png", sizeBytes = 1024L),
            FlashShareItemUi(uri = "u2", name = "b.png", sizeBytes = 2048L),
        )
        assertEquals("3.0 KB", FlashStagingMath.formatTotalBytes(items))
    }

    @Test
    fun format_staged_summary_singular_and_plural() {
        assertEquals("1 item (1.0 KB)", FlashStagingMath.formatStagedSummary(1, 1024L))
        assertEquals("3 items (4.0 MB)", FlashStagingMath.formatStagedSummary(3, 4 * 1024 * 1024L))
    }

    @Test
    fun `the composer hint says text follows the files while files are staged`() {
        assertEquals("Message...", FlashStagingMath.composerPlaceholder(0))
        assertEquals("Message (sent after the files)...", FlashStagingMath.composerPlaceholder(1))
        assertEquals("Message (sent after the files)...", FlashStagingMath.composerPlaceholder(4))
    }
}
