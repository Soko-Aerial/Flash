package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashShareTargetMathTest {

    @Test
    fun single_file_summary_formats_filename() {
        val payload = FlashSharePayloadUi(
            items = listOf(
                FlashShareItemUi(
                    uri = "content://media/external/images/media/123",
                    name = "vacation_photo.jpg",
                    sizeBytes = 2_500_000L,
                    mimeType = "image/jpeg",
                ),
            ),
        )
        assertEquals("vacation_photo.jpg", FlashShareTargetMath.formatItemSummary(payload))
        assertEquals("2.3 MB", FlashShareTargetMath.formatItemSubtitle(payload))
    }

    @Test
    fun multiple_files_summary_formats_count_and_total_size() {
        val payload = FlashSharePayloadUi(
            items = listOf(
                FlashShareItemUi("uri1", "doc1.pdf", 1_048_576L, "application/pdf"),
                FlashShareItemUi("uri2", "doc2.pdf", 2_097_152L, "application/pdf"),
                FlashShareItemUi("uri3", "doc3.pdf", 1_048_576L, "application/pdf"),
            ),
        )
        assertEquals("3 files (4.0 MB)", FlashShareTargetMath.formatItemSummary(payload))
        assertEquals("doc1.pdf, doc2.pdf, doc3.pdf", FlashShareTargetMath.formatItemSubtitle(payload))
    }

    @Test
    fun multiple_files_more_than_three_appends_plus_more() {
        val payload = FlashSharePayloadUi(
            items = listOf(
                FlashShareItemUi("uri1", "1.png", 100L),
                FlashShareItemUi("uri2", "2.png", 100L),
                FlashShareItemUi("uri3", "3.png", 100L),
                FlashShareItemUi("uri4", "4.png", 100L),
                FlashShareItemUi("uri5", "5.png", 100L),
            ),
        )
        assertEquals("5 files (500 B)", FlashShareTargetMath.formatItemSummary(payload))
        assertEquals("1.png, 2.png, 3.png +2 more", FlashShareTargetMath.formatItemSubtitle(payload))
    }

    @Test
    fun text_only_share_formats_text_summary_and_snippet() {
        val longText = "a".repeat(100)
        val payload = FlashSharePayloadUi(text = longText)
        assertEquals("Shared Text", FlashShareTargetMath.formatItemSummary(payload))
        assertEquals("a".repeat(57) + "…", FlashShareTargetMath.formatItemSubtitle(payload))

        val shortPayload = FlashSharePayloadUi(text = "Hello world")
        assertEquals("Hello world", FlashShareTargetMath.formatItemSubtitle(shortPayload))
    }

    @Test
    fun files_and_text_combined_formats_with_message_suffix() {
        val payload = FlashSharePayloadUi(
            items = listOf(FlashShareItemUi("uri1", "notes.txt", 500L)),
            text = "Here are the meeting notes",
        )
        assertEquals("notes.txt + message", FlashShareTargetMath.formatItemSummary(payload))
    }

    @Test
    fun format_bytes_boundaries() {
        assertEquals("0 B", FlashShareTargetMath.formatBytes(0L))
        assertEquals("512 B", FlashShareTargetMath.formatBytes(512L))
        assertEquals("1.0 KB", FlashShareTargetMath.formatBytes(1024L))
        assertEquals("1.5 MB", FlashShareTargetMath.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", FlashShareTargetMath.formatBytes((2.0 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun initials_generation() {
        assertEquals("KP", FlashShareTargetMath.initialsFor("Kali PC"))
        assertEquals("FL", FlashShareTargetMath.initialsFor("Flash"))
        assertEquals("GT", FlashShareTargetMath.initialsFor("Galaxy Tab S8"))
        assertEquals("?", FlashShareTargetMath.initialsFor(""))
    }

    @Test
    fun filter_recipients_matches_name_and_subtitle() {
        val r1 = FlashShareRecipientUi("1", "Alice", "AL", subtitle = "Office Team")
        val r2 = FlashShareRecipientUi("2", "Bob", "BO", subtitle = "Mobile")
        val r3 = FlashShareRecipientUi("3", "Charlie", "CH", subtitle = "Alice friend")
        val list = listOf(r1, r2, r3)

        assertEquals(list, FlashShareTargetMath.filterRecipients(list, ""))
        assertEquals(list, FlashShareTargetMath.filterRecipients(list, "   "))

        val filteredName = FlashShareTargetMath.filterRecipients(list, "bob")
        assertEquals(1, filteredName.size)
        assertEquals("2", filteredName.first().id)

        val filteredSub = FlashShareTargetMath.filterRecipients(list, "alice")
        assertEquals(2, filteredSub.size)
        assertEquals(setOf("1", "3"), filteredSub.map { it.id }.toSet())
    }

    @Test
    fun toggle_selection_enforces_max_limit() {
        var selected = emptySet<String>()

        selected = FlashShareTargetMath.toggleSelection(selected, "1", maxLimit = 3)
        assertEquals(setOf("1"), selected)

        selected = FlashShareTargetMath.toggleSelection(selected, "2", maxLimit = 3)
        assertEquals(setOf("1", "2"), selected)

        selected = FlashShareTargetMath.toggleSelection(selected, "3", maxLimit = 3)
        assertEquals(setOf("1", "2", "3"), selected)

        // Attempting to add 4th should reject (remain unchanged)
        selected = FlashShareTargetMath.toggleSelection(selected, "4", maxLimit = 3)
        assertEquals(setOf("1", "2", "3"), selected)

        // Deselecting existing item works
        selected = FlashShareTargetMath.toggleSelection(selected, "2", maxLimit = 3)
        assertEquals(setOf("1", "3"), selected)
    }
}
