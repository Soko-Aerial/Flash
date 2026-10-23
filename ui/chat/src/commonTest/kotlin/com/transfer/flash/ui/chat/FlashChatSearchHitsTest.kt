package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SEARCH-01 / UI-023 upgrade: in-chat search must see attachments and links, not only the message
 * body — a file is usually the thing a person remembers.
 */
class FlashChatSearchHitsTest {

    private fun message(
        id: String,
        text: String,
        files: List<String> = emptyList(),
        captions: List<String> = emptyList(),
        sender: String = "Alex",
    ) = FlashMessageUi(
        id = id,
        senderName = sender,
        senderInitials = sender.take(1),
        timeLabel = "10:00",
        text = text,
        isMine = false,
        fileAttachments = files.mapIndexed { index, name ->
            FlashFileAttachmentUi(id = "$id-file-$index", name = name, sizeBytes = 1024)
        },
        images = captions.mapIndexed { index, caption ->
            FlashImageAttachmentUi(id = "$id-image-$index", caption = caption)
        },
    )

    @Test
    fun `the Messages scope is text only and differs from All`() {
        val messages = listOf(
            message("m1", "budget review", files = listOf("notes.pdf")),
            message("m2", "see you", files = listOf("Budget_Q4.pdf")),
        )
        val all = FlashChatSearchHits.hits(messages, "budget", FlashSearchScope.All).map { it.messageId }
        val onlyMessages = FlashChatSearchHits.hits(messages, "budget", FlashSearchScope.Messages)
        assertEquals(listOf("m1", "m2"), all)
        assertEquals(listOf("m1"), onlyMessages.map { it.messageId })
        assertEquals(FlashChatSearchKind.Message, onlyMessages.single().kind)
    }

    @Test
    fun `findMatches includes attachment file names`() {
        val messages = listOf(
            message("m1", "here it is", files = listOf("Budget_Q4.pdf")),
            message("m2", "nothing relevant"),
        )

        assertEquals(listOf("m1"), FlashChatSearchMath.findMatches(messages, "budget"))
    }

    @Test
    fun `buildHighlightedMessageText creates annotated string with highlighted background spans`() {
        val text = "Hello world, hello friends"
        val highlighted = buildHighlightedMessageText(text, "hello", androidx.compose.ui.graphics.Color.Yellow)
        assertEquals(text, highlighted.text)
        assertEquals(2, highlighted.spanStyles.size)
        assertEquals(0, highlighted.spanStyles[0].start)
        assertEquals(5, highlighted.spanStyles[0].end)
        assertEquals(13, highlighted.spanStyles[1].start)
        assertEquals(18, highlighted.spanStyles[1].end)
    }

    @Test
    fun `a file-only match is reported as a File hit named after the file`() {
        val messages = listOf(message("m1", "here it is", files = listOf("Budget_Q4.pdf")))

        val hits = FlashChatSearchHits.hits(messages, "budget")

        assertEquals(1, hits.size)
        assertEquals(FlashChatSearchKind.File, hits.single().kind)
        assertEquals("Budget_Q4.pdf", hits.single().snippet)
        assertEquals("m1", hits.single().messageId)
    }

    @Test
    fun `a text match on the same message keeps the sentence as the snippet`() {
        val messages = listOf(message("m1", "the budget is approved", files = listOf("Budget_Q4.pdf")))

        val hits = FlashChatSearchHits.hits(messages, "budget")

        assertEquals(1, hits.size)
        assertEquals(FlashChatSearchKind.File, hits.single().kind)
        assertEquals("the budget is approved", hits.single().snippet)
    }

    @Test
    fun `image captions are searchable`() {
        val messages = listOf(message("m1", "", captions = listOf("Sunset over the bay")))

        val hits = FlashChatSearchHits.hits(messages, "sunset")

        assertEquals(1, hits.size)
        assertEquals(FlashChatSearchKind.File, hits.single().kind)
        assertEquals("Sunset over the bay", hits.single().snippet)
    }

    @Test
    fun `the Files scope excludes pure text matches`() {
        val messages = listOf(
            message("m1", "the budget is approved"),
            message("m2", "here it is", files = listOf("budget.xlsx")),
        )

        val all = FlashChatSearchHits.hits(messages, "budget")
        val files = FlashChatSearchHits.hits(messages, "budget", FlashSearchScope.Files)

        assertEquals(listOf("m1", "m2"), all.map { it.messageId })
        assertEquals(listOf("m2"), files.map { it.messageId })
    }

    @Test
    fun `the Links scope only matches the link itself`() {
        val messages = listOf(
            message("m1", "join the call at https://meet.example.com/abc123"),
            message("m2", "meet me at noon"),
        )

        val all = FlashChatSearchHits.hits(messages, "meet")
        val links = FlashChatSearchHits.hits(messages, "meet", FlashSearchScope.Links)

        // Both messages contain "meet", but only the first has it inside a URL.
        assertEquals(listOf("m1", "m2"), all.map { it.messageId })
        assertEquals(listOf("m1"), links.map { it.messageId })
        assertEquals(FlashChatSearchKind.Link, links.single().kind)
    }

    @Test
    fun `the Links scope finds a message by its second link too`() {
        // F54 of the 2026-10-22 review: both links in one message are link matches, not only the first.
        val messages = listOf(
            message("m1", "read https://first.example/a and https://second.example/b"),
            message("m2", "no links in this one"),
        )

        assertEquals(
            listOf("m1"),
            FlashChatSearchHits.hits(messages, "second.example", FlashSearchScope.Links).map { it.messageId },
        )
        assertEquals(
            listOf("m1"),
            FlashChatSearchHits.hits(messages, "first.example", FlashSearchScope.Links).map { it.messageId },
        )
    }

    @Test
    fun `a link-scoped search with a non-URL query returns nothing`() {
        val messages = listOf(message("m1", "join at https://meet.example.com/abc123"))

        assertTrue(FlashChatSearchHits.hits(messages, "join", FlashSearchScope.Links).isEmpty())
    }

    @Test
    fun `a dedicated scope describes what that scope matched`() {
        // Both a file name and a link contain "meet"; the row's kind must follow the active scope.
        val messages = listOf(
            message(
                "m1",
                "the meeting link: https://meet.example.com/abc123",
                files = listOf("meeting.pdf"),
            ),
        )

        assertEquals(FlashChatSearchKind.File, FlashChatSearchHits.hits(messages, "meet", FlashSearchScope.Files).single().kind)
        val linkHit = FlashChatSearchHits.hits(messages, "meet", FlashSearchScope.Links).single()
        assertEquals(FlashChatSearchKind.Link, linkHit.kind)
        assertTrue("meet.example.com" in linkHit.snippet)
    }

    @Test
    fun `blank queries produce no hits in any scope`() {
        val messages = listOf(message("m1", "hello", files = listOf("hello.txt")))

        FlashSearchScope.entries.forEach { scope ->
            assertTrue(
                FlashChatSearchHits.hits(messages, "   ", scope).isEmpty(),
                "scope ${scope.name} should be empty",
            )
        }
    }
}
