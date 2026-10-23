package com.transfer.flash.core.messaging.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SEARCH-01: pure helpers behind structured search. Kept free of Room/Compose so both the repository
 * (building hits from rows) and the chat UI (in-chat search, shared content) can share them.
 */
class FlashSearchTextTest {

    @Test
    fun `contains is case-insensitive, trims the query and ignores null text`() {
        assertTrue(FlashSearchText.contains("Budget_Q4.pdf", "budget"))
        assertTrue(FlashSearchText.contains("Budget_Q4.pdf", "  Q4 "))
        assertFalse(FlashSearchText.contains("Budget_Q4.pdf", "invoice"))
        assertFalse(FlashSearchText.contains(null, "budget"))
        assertFalse(FlashSearchText.contains("anything", "   "))
        assertFalse(FlashSearchText.contains("", "a"))
    }

    @Test
    fun `short text is returned whitespace-collapsed without ellipses`() {
        assertEquals("ship the build", FlashSearchText.snippet("ship  the   build", "build"))
        assertEquals("", FlashSearchText.snippet("   ", "build"))
    }

    @Test
    fun `long text is cut around the match with ellipses on trimmed edges`() {
        val text = "a".repeat(200) + " budget " + "b".repeat(200)
        val snippet = FlashSearchText.snippet(text, "budget", maxChars = 60)
        assertTrue("budget" in snippet)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length <= 62, "snippet should respect the budget: ${snippet.length}")
    }

    @Test
    fun `a query that is absent still yields a leading extract`() {
        val text = "x".repeat(100)
        val snippet = FlashSearchText.snippet(text, "missing", maxChars = 20)
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length <= 20)
    }

    @Test
    fun `firstUrl finds http https and flash links and trims punctuation`() {
        assertEquals("https://example.com/a", FlashSearchText.firstUrl("see https://example.com/a."))
        assertEquals("http://example.com", FlashSearchText.firstUrl("http://example.com, then more"))
        assertEquals("flash://g/1/abc", FlashSearchText.firstUrl("join flash://g/1/abc)"))
        assertNull(FlashSearchText.firstUrl("no links here"))
        assertNull(FlashSearchText.firstUrl(null))
    }

    @Test
    fun `matchesLink only matches text inside the link`() {
        val text = "join here flash://g/1/abcdef"
        assertTrue(FlashSearchText.matchesLink(text, "abcdef"))
        assertFalse(FlashSearchText.matchesLink(text, "join"))
        assertFalse(FlashSearchText.matchesLink("plain text about abcdef", "abcdef"))
        assertFalse(FlashSearchText.matchesLink(text, "  "))
    }

    @Test
    fun `matchesLink examines every link, not only the first`() {
        // F54 of the 2026-10-22 review: the Links scope dropped a message whose SECOND link matched.
        val text = "read https://first.example/a and https://second.example/b."
        assertTrue(FlashSearchText.matchesLink(text, "second.example"), "the second link is a link too")
        assertTrue(FlashSearchText.matchesLink(text, "FIRST.example"), "and so is the first")
        assertTrue(FlashSearchText.matchesLink(text, "second.example/b"), "punctuation is trimmed from the tail only")
        assertFalse(FlashSearchText.matchesLink(text, "read"), "prose between links is still not a link match")
        assertFalse(FlashSearchText.matchesLink(text, "third.example"))
    }

    @Test
    fun `firstUrl still returns the first link when there are several`() {
        assertEquals(
            "https://first.example/a",
            FlashSearchText.firstUrl("read https://first.example/a and https://second.example/b."),
        )
    }

    @Test
    fun `media mime classification covers images and videos only`() {
        assertTrue(FlashSearchText.isMediaMime("image/jpeg"))
        assertTrue(FlashSearchText.isMediaMime("video/mp4"))
        assertFalse(FlashSearchText.isMediaMime("application/pdf"))
        assertFalse(FlashSearchText.isMediaMime(null))
    }
}
