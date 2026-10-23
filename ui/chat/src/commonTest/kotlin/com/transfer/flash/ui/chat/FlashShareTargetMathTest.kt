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
    fun filter_recipients_deduplicates_by_id() {
        val r1 = FlashShareRecipientUi("1", "Alice", "AL")
        val r1Dup = FlashShareRecipientUi("1", "Alice Duplicate", "AL")
        val r2 = FlashShareRecipientUi("2", "Bob", "BO")
        val list = listOf(r1, r1Dup, r2)

        val filteredEmptyQuery = FlashShareTargetMath.filterRecipients(list, "")
        assertEquals(2, filteredEmptyQuery.size)
        assertEquals(listOf("1", "2"), filteredEmptyQuery.map { it.id })

        val filteredWithQuery = FlashShareTargetMath.filterRecipients(list, "ali")
        assertEquals(1, filteredWithQuery.size)
        assertEquals("1", filteredWithQuery.first().id)
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

    // ---- F49: Multi-select forward payload builder

    private fun testMsg(
        id: String,
        text: String = "",
        images: List<com.transfer.flash.core.messaging.model.FlashImageAttachmentUi> = emptyList(),
        files: List<com.transfer.flash.core.messaging.model.FlashFileAttachmentUi> = emptyList(),
        voice: List<com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi> = emptyList(),
    ) = com.transfer.flash.core.messaging.model.FlashMessageUi(
        id = id,
        senderName = "User",
        senderInitials = "U",
        timeLabel = "12:00",
        text = text,
        isMine = true,
        images = images,
        fileAttachments = files,
        voiceAttachments = voice,
    )

    @Test
    fun `buildMultiForwardPayload includes text and attachment items from multiple messages`() {
        val msg1 = testMsg(id = "1", text = "Hello world")
        val msg2 = testMsg(
            id = "2",
            images = listOf(com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(id = "img1", uri = "file:///img.jpg", mimeType = "image/jpeg")),
        )
        val msg3 = testMsg(
            id = "3",
            files = listOf(com.transfer.flash.core.messaging.model.FlashFileAttachmentUi(id = "f1", name = "doc.pdf", sizeBytes = 4096L, mimeType = "application/pdf", localUri = "file:///doc.pdf")),
            voice = listOf(com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi(id = "v1", uri = "file:///voice.aac", mimeType = "audio/aac")),
        )

        val payload = FlashShareTargetMath.buildMultiForwardPayload(listOf(msg1, msg2, msg3))
        kotlin.test.assertNotNull(payload)
        assertEquals("Hello world", payload.text)
        assertEquals(3, payload.items.size)
        assertEquals("file:///img.jpg", payload.items[0].uri)
        assertEquals("file:///doc.pdf", payload.items[1].uri)
        assertEquals("file:///voice.aac", payload.items[2].uri)
    }

    @Test
    fun `buildMultiForwardPayload returns null for empty or blank messages`() {
        val emptyMsg = testMsg(id = "1", text = "   ")
        kotlin.test.assertNull(FlashShareTargetMath.buildMultiForwardPayload(listOf(emptyMsg)))
        kotlin.test.assertNull(FlashShareTargetMath.buildMultiForwardPayload(emptyList()))
    }

    @Test
    fun `hasUnforwardableAttachments flags undownloaded attachments`() {
        val ready = testMsg(
            id = "1",
            files = listOf(com.transfer.flash.core.messaging.model.FlashFileAttachmentUi(id = "f1", name = "doc.pdf", sizeBytes = 4096L, localUri = "file:///doc.pdf")),
        )
        val notReady = testMsg(
            id = "2",
            files = listOf(com.transfer.flash.core.messaging.model.FlashFileAttachmentUi(id = "f2", name = "inbound.pdf", sizeBytes = 4096L, localUri = null)),
        )

        kotlin.test.assertFalse(FlashShareTargetMath.hasUnforwardableAttachments(listOf(ready)))
        assertTrue(FlashShareTargetMath.hasUnforwardableAttachments(listOf(ready, notReady)))
    }
}
