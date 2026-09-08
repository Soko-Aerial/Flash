package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashChatListSearchLogicTest {

    private fun chat(
        id: String,
        title: String,
        previewText: String,
    ) = FlashChatListItemUi(
        id = id,
        title = title,
        avatarInitials = "X",
        previewText = previewText,
        timestamp = "10:00 AM",
    )

    private val sampleChats = listOf(
        chat("c1", "False School", "Flash keeps this conversation local and private."),
        chat("c2", "Alex Chen", "Can you send the build over Wi-Fi Direct?"),
        chat("c3", "Design Team", "Belal: Updated the motion tokens doc"),
    )

    @Test
    fun `filter matches title case-insensitively`() {
        val result = FlashChatListSearchMath.filterChats(sampleChats, "ALEX")
        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun `filter matches preview text`() {
        val result = FlashChatListSearchMath.filterChats(sampleChats, "wi-fi direct")
        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun `filter matches title or preview and preserves original order`() {
        // "flash" appears in c1's title AND c1's preview; "doc" matches only c3's preview
        val result = FlashChatListSearchMath.filterChats(sampleChats, "doc")
        assertEquals(listOf("c3"), result.map { it.id })

        // Query matching several chats keeps list order (no relevance re-sorting)
        val multi = FlashChatListSearchMath.filterChats(sampleChats, "a")
        assertTrue(multi.size > 1)
        assertEquals(
            sampleChats.filter {
                it.title.contains("a", ignoreCase = true) || it.previewText.contains("a", ignoreCase = true)
            }.map { it.id },
            multi.map { it.id },
        )
    }

    @Test
    fun `blank query returns all items unfiltered`() {
        assertEquals(3, FlashChatListSearchMath.filterChats(sampleChats, "").size)
        assertEquals(3, FlashChatListSearchMath.filterChats(sampleChats, "   ").size)
        assertEquals(sampleChats.map { it.id }, FlashChatListSearchMath.filterChats(sampleChats, " ").map { it.id })
    }

    @Test
    fun `query with no matches yields empty list`() {
        assertTrue(FlashChatListSearchMath.filterChats(sampleChats, "zebra").isEmpty())
    }

    @Test
    fun `body match ids surface a chat whose title and preview do not match`() {
        // "zebra" matches no title/preview, but c3 has a deep-history body hit (#12).
        val result = FlashChatListSearchMath.filterChats(sampleChats, "zebra", bodyMatchIds = setOf("c3"))
        assertEquals(listOf("c3"), result.map { it.id })
    }

    @Test
    fun `body match is unioned with title-preview match without duplicating rows`() {
        // "a" already matches several rows by title/preview; adding c1 as a body hit must not
        // duplicate c1 and must preserve original list order.
        val textMatches = FlashChatListSearchMath.filterChats(sampleChats, "a").map { it.id }
        val unioned = FlashChatListSearchMath.filterChats(sampleChats, "a", bodyMatchIds = setOf("c1"))
        val expected = sampleChats.filter { it.id == "c1" || it.id in textMatches }.map { it.id }
        assertEquals(expected, unioned.map { it.id })
        assertEquals(unioned.map { it.id }.distinct(), unioned.map { it.id })
    }

    @Test
    fun `blank query ignores body match ids and returns all`() {
        assertEquals(3, FlashChatListSearchMath.filterChats(sampleChats, "", bodyMatchIds = setOf("c2")).size)
    }

    @Test
    fun `search active only for non-blank trimmed query`() {
        assertFalse(FlashChatListSearchMath.isSearchActive(""))
        assertFalse(FlashChatListSearchMath.isSearchActive("   "))
        assertTrue(FlashChatListSearchMath.isSearchActive("flash"))
        assertTrue(FlashChatListSearchMath.isSearchActive("  flash "))
    }

    @Test
    fun `result count label is singular and plural`() {
        assertEquals("0 chats", FlashChatListSearchMath.resultCountLabel(0))
        assertEquals("1 chat", FlashChatListSearchMath.resultCountLabel(1))
        assertEquals("12 chats", FlashChatListSearchMath.resultCountLabel(12))
    }

    @Test
    fun `recent searches dedupe case-insensitively keeping first spelling`() {
        val recents = FlashChatListSearchMath.recentSearches(
            listOf("Build", "build", "  BUILD ", "wi-fi"),
        )
        assertEquals(listOf("Build", "wi-fi"), recents)
    }

    @Test
    fun `recent searches trims entries and skips blanks`() {
        val recents = FlashChatListSearchMath.recentSearches(
            listOf("", "   ", " motion doc "),
        )
        assertEquals(listOf("motion doc"), recents)
    }

    @Test
    fun `recent searches capped at limit most recent`() {
        val recents = FlashChatListSearchMath.recentSearches(
            listOf("q1", "q2", "q3", "q4", "q5", "q6", "q7"),
            limit = 5,
        )
        assertEquals(listOf("q1", "q2", "q3", "q4", "q5"), recents)
    }
}
