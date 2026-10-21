package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashChatSearchResultsUi
import com.transfer.flash.core.messaging.model.FlashFileSearchHitUi
import com.transfer.flash.core.messaging.model.FlashMessageSearchHitUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UI-024 upgrade: the structured global result list is arranged by pure logic so section order,
 * the "See all" cutoff and scope filtering are testable without Compose.
 */
class FlashSearchResultMathTest {

    private fun chat(id: String, title: String) = FlashChatListItemUi(
        id = id,
        title = title,
        avatarInitials = title.take(2).uppercase(),
        previewText = "latest message in $title",
        timestamp = "Now",
    )

    private fun messageHit(
        messageId: String,
        conversationId: String = "conv-$messageId",
        snippet: String = "snippet $messageId",
    ) = FlashMessageSearchHitUi(
        messageId = messageId,
        conversationId = conversationId,
        conversationTitle = "Chat $conversationId",
        senderName = "Alex",
        snippet = snippet,
        timestampMs = 1_000L,
        timeLabel = "Now",
    )

    private fun fileHit(
        messageId: String,
        fileName: String,
        mimeType: String = "application/pdf",
        conversationId: String = "conv-$messageId",
    ) = FlashFileSearchHitUi(
        messageId = messageId,
        conversationId = conversationId,
        conversationTitle = "Chat $conversationId",
        senderName = "Alex",
        fileName = fileName,
        fileSizeBytes = 2_048L,
        mimeType = mimeType,
        timestampMs = 1_000L,
        timeLabel = "Now",
    )

    @Test
    fun `All orders sections Chats Messages Files Media Links and caps each section`() {
        val results = FlashChatSearchResultsUi(
            messageHits = (1..4).map { messageHit("m$it") },
            fileHits = listOf(
                fileHit("f1", "report.pdf"),
                fileHit("f2", "photo.jpg", "image/jpeg"),
                fileHit("f3", "clip.mp4", "video/mp4"),
            ),
        )
        val entries = FlashSearchResultMath.entries(
            scope = FlashSearchScope.All,
            chats = listOf(chat("c1", "Design Team")),
            results = results,
        )

        val headers = entries.filterIsInstance<FlashSearchListEntry.SectionHeader>()
        assertEquals(listOf("Chats", "Messages", "Files", "Media"), headers.map { it.title })
        // Messages has 4 hits, so it shows a "See all"; Files/Media have one each and do not.
        assertFalse(headers.first { it.title == "Chats" }.showSeeAll)
        assertTrue(headers.first { it.title == "Messages" }.showSeeAll)
        assertFalse(headers.first { it.title == "Files" }.showSeeAll)

        val messages = entries.filterIsInstance<FlashSearchListEntry.Message>()
        assertEquals(FlashSearchResultMath.SECTION_PREVIEW_LIMIT, messages.size)
        assertEquals(1, entries.filterIsInstance<FlashSearchListEntry.File>().count { !it.hit.isMedia })
        assertEquals(2, entries.filterIsInstance<FlashSearchListEntry.File>().count { it.hit.isMedia })
    }

    @Test
    fun `a dedicated scope lists everything in it without a header`() {
        val results = FlashChatSearchResultsUi(
            messageHits = (1..5).map { messageHit("m$it") },
            fileHits = listOf(fileHit("f1", "report.pdf"), fileHit("f2", "photo.jpg", "image/jpeg")),
        )

        val messages = FlashSearchResultMath.entries(FlashSearchScope.Messages, emptyList(), results)
        assertEquals(5, messages.size)
        assertTrue(messages.all { it is FlashSearchListEntry.Message })

        val files = FlashSearchResultMath.entries(FlashSearchScope.Files, emptyList(), results)
        assertEquals(listOf("f1"), files.map { (it as FlashSearchListEntry.File).hit.messageId })

        val media = FlashSearchResultMath.entries(FlashSearchScope.Media, emptyList(), results)
        assertEquals(listOf("f2"), media.map { (it as FlashSearchListEntry.File).hit.messageId })
    }

    @Test
    fun `Links lists only message hits whose text carries a link`() {
        val results = FlashChatSearchResultsUi(
            messageHits = listOf(
                messageHit("m1", snippet = "join at https://meet.example.com/abc123"),
                messageHit("m2", snippet = "meet me at noon"),
            ),
        )

        val links = FlashSearchResultMath.entries(FlashSearchScope.Links, emptyList(), results)

        assertEquals(1, links.size)
        assertEquals("m1", assertIs<FlashSearchListEntry.Message>(links.single()).hit.messageId)
    }

    @Test
    fun `scope counts follow the same partitioning as the lists`() {
        val results = FlashChatSearchResultsUi(
            messageHits = listOf(
                messageHit("m1", snippet = "https://example.com/x"),
                messageHit("m2"),
            ),
            fileHits = listOf(fileHit("f1", "report.pdf"), fileHit("f2", "photo.jpg", "image/jpeg")),
        )
        val chats = listOf(chat("c1", "Design"), chat("c2", "Ops"))

        assertEquals(6, FlashSearchResultMath.scopeCount(FlashSearchScope.All, chats, results))
        assertEquals(2, FlashSearchResultMath.scopeCount(FlashSearchScope.Chats, chats, results))
        assertEquals(2, FlashSearchResultMath.scopeCount(FlashSearchScope.Messages, chats, results))
        assertEquals(1, FlashSearchResultMath.scopeCount(FlashSearchScope.Files, chats, results))
        assertEquals(1, FlashSearchResultMath.scopeCount(FlashSearchScope.Media, chats, results))
        assertEquals(1, FlashSearchResultMath.scopeCount(FlashSearchScope.Links, chats, results))
    }

    @Test
    fun `nothing found yields one NoResults entry naming the scope`() {
        val all = FlashSearchResultMath.entries(FlashSearchScope.All, emptyList(), FlashChatSearchResultsUi.Empty)
        assertEquals(1, all.size)
        assertEquals("All", assertIs<FlashSearchListEntry.NoResults>(all.single()).scopeLabel)

        val files = FlashSearchResultMath.entries(FlashSearchScope.Files, emptyList(), FlashChatSearchResultsUi.Empty)
        assertEquals("Files", assertIs<FlashSearchListEntry.NoResults>(files.single()).scopeLabel)
    }

    @Test
    fun `entry keys are stable and unique`() {
        val results = FlashChatSearchResultsUi(
            messageHits = listOf(messageHit("m1", conversationId = "c1")),
            fileHits = listOf(fileHit("f1", "report.pdf", conversationId = "c1")),
        )
        val entries = FlashSearchResultMath.entries(
            scope = FlashSearchScope.All,
            chats = listOf(chat("c1", "Design")),
            results = results,
        )

        val keys = entries.map { FlashSearchResultMath.key(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys, entries.map { FlashSearchResultMath.key(it) })
    }

    @Test
    fun `a link message is listed once in All and keeps a unique key`() {
        val results = FlashChatSearchResultsUi(
            messageHits = listOf(messageHit("m1", snippet = "see https://example.com/x")),
        )

        val entries = FlashSearchResultMath.entries(FlashSearchScope.All, emptyList(), results)

        // Header + one row, under Links — not twice under Messages and Links.
        assertEquals(2, entries.size)
        assertEquals("Links", assertIs<FlashSearchListEntry.SectionHeader>(entries.first()).title)
        val keys = entries.map { FlashSearchResultMath.key(it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `count label is singular for one result`() {
        assertEquals("1 result", FlashSearchResultMath.countLabel(1))
        assertEquals("0 results", FlashSearchResultMath.countLabel(0))
        assertEquals("12 results", FlashSearchResultMath.countLabel(12))
    }

    @Test
    fun `two same-named attachments of one message cannot produce a duplicate list key`() {
        fun file() = FlashFileSearchHitUi(
            messageId = "m1",
            conversationId = "c1",
            conversationTitle = "Chat",
            senderName = "Alex",
            fileName = "scan.pdf",
            fileSizeBytes = 10,
            mimeType = "application/pdf",
            timestampMs = 1L,
            timeLabel = "Now",
        )
        val entries = FlashSearchResultMath.entries(
            FlashSearchScope.All,
            emptyList(),
            FlashChatSearchResultsUi(fileHits = listOf(file(), file())),
        )
        val keys = entries.map { FlashSearchResultMath.key(it) }
        assertEquals(keys.size, keys.toSet().size)
    }
}
