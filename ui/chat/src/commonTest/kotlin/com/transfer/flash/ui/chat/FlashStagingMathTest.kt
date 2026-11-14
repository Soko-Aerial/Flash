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

    @Test
    fun `two staged items with identical uri have distinct stable ids`() {
        val item1 = FlashShareItemUi(uri = "file:///img.png", name = "img.png", sizeBytes = 100L)
        val item2 = FlashShareItemUi(uri = "file:///img.png", name = "img.png", sizeBytes = 100L)
        assertEquals(item1.uri, item2.uri)
        kotlin.test.assertNotEquals(item1.id, item2.id)
    }

    @Test
    fun `removing staged item preserves stable ids of other items`() {
        val item1 = FlashShareItemUi(uri = "file:///img1.png", name = "img1.png", sizeBytes = 100L)
        val item2 = FlashShareItemUi(uri = "file:///img1.png", name = "img1.png", sizeBytes = 100L)
        val item3 = FlashShareItemUi(uri = "file:///img2.png", name = "img2.png", sizeBytes = 200L)

        val staged = listOf(item1, item2, item3)
        val remaining = staged.filter { it.id != item1.id }

        assertEquals(2, remaining.size)
        assertEquals(item2.id, remaining[0].id)
        assertEquals(item3.id, remaining[1].id)
    }

    @Test
    fun `appendUpToLimit enforces MAX_STAGED_ATTACHMENTS cap`() {
        var staged = emptyList<FlashShareItemUi>()
        repeat(FlashStagingMath.MAX_STAGED_ATTACHMENTS) { idx ->
            val (next, accepted) = FlashStagingMath.appendUpToLimit(
                staged,
                FlashShareItemUi(uri = "file:///$idx.png", name = "$idx.png", sizeBytes = 10L),
            )
            assertTrue(accepted)
            staged = next
        }
        assertEquals(FlashStagingMath.MAX_STAGED_ATTACHMENTS, staged.size)

        val (capped, acceptedOverflow) = FlashStagingMath.appendUpToLimit(
            staged,
            FlashShareItemUi(uri = "file:///extra.png", name = "extra.png", sizeBytes = 10L),
        )
        assertFalse(acceptedOverflow)
        assertEquals(FlashStagingMath.MAX_STAGED_ATTACHMENTS, capped.size)
    }

    @Test
    fun `picking the same photo again stages a single transfer`() {
        val photo = FlashShareItemUi(uri = "content://gallery/photo/42", name = "photo.jpg", sizeBytes = 1024L)
        val staged = listOf(photo)
        val duplicate = photo.copy(id = "another-picker-result")

        assertEquals(staged to true, FlashStagingMath.appendUpToLimit(staged, duplicate, maxLimit = 1))
        assertEquals(listOf(duplicate) to true, FlashStagingMath.appendUpToLimit(emptyList(), duplicate))

        val otherPhoto = photo.copy(uri = "content://gallery/photo/43", id = "other-photo")
        assertEquals(listOf(photo, otherPhoto) to true, FlashStagingMath.appendUpToLimit(staged, otherPhoto))
    }
}
