package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashChatSearchLogicTest {

    private fun message(id: String, text: String) = FlashMessageUi(
        id = id,
        senderName = "Alex",
        senderInitials = "A",
        timeLabel = "10:00 AM",
        text = text,
        isMine = false,
    )

    @Test
    fun `matching is case-insensitive and oldest-first`() {
        val messages = listOf(
            message("m1", "Meeting at NOON today"),
            message("m2", "Lunch plans?"),
            message("m3", "noon works for me"),
        )

        assertEquals(listOf("m1", "m3"), FlashChatSearchMath.findMatches(messages, "noon"))
    }

    @Test
    fun `blank query yields no matches`() {
        val messages = listOf(message("m1", "hello"), message("m2", "world"))

        assertTrue(FlashChatSearchMath.findMatches(messages, "").isEmpty())
        assertTrue(FlashChatSearchMath.findMatches(messages, "   ").isEmpty())
    }

    @Test
    fun `match range finds first case-insensitive occurrence`() {
        assertEquals(0..4, FlashChatSearchMath.matchRange("Hello World", "hello"))
        assertEquals(6..10, FlashChatSearchMath.matchRange("Hello World", "WORLD"))
        assertNull(FlashChatSearchMath.matchRange("Hello", "xyz"))
        assertNull(FlashChatSearchMath.matchRange("", "a"))
        assertNull(FlashChatSearchMath.matchRange("Hello", ""))
    }

    @Test
    fun `match ranges are non-overlapping left to right`() {
        // "abab" with query "aba": first match consumes 0..2, scan resumes at 3 -> no second match
        assertEquals(listOf(0..2), FlashChatSearchMath.matchRanges("abab", "aba"))
        // Two disjoint matches
        assertEquals(listOf(0..1, 3..4), FlashChatSearchMath.matchRanges("abXab", "ab"))
    }

    @Test
    fun `active result starts at newest match`() {
        assertEquals(-1, FlashChatSearchMath.initialResultIndex(0))
        assertEquals(0, FlashChatSearchMath.initialResultIndex(1))
        assertEquals(4, FlashChatSearchMath.initialResultIndex(5))
    }

    @Test
    fun `stepping wraps around in both directions`() {
        // Forward from last wraps to first
        assertEquals(0, FlashChatSearchMath.stepIndex(current = 2, count = 3, forward = true))
        // Backward from first wraps to last
        assertEquals(2, FlashChatSearchMath.stepIndex(current = 0, count = 3, forward = false))
        // Plain stepping
        assertEquals(1, FlashChatSearchMath.stepIndex(current = 0, count = 3, forward = true))
        assertEquals(1, FlashChatSearchMath.stepIndex(current = 2, count = 3, forward = false))
        // Empty results stay invalid
        assertEquals(-1, FlashChatSearchMath.stepIndex(current = -1, count = 0, forward = true))
        // First activation lands on newest
        assertEquals(2, FlashChatSearchMath.stepIndex(current = -1, count = 3, forward = true))
    }

    @Test
    fun `counter label is one-based`() {
        assertEquals("0 / 0", FlashChatSearchMath.counterLabel(activeIndex = -1, total = 0))
        assertEquals("1 / 7", FlashChatSearchMath.counterLabel(activeIndex = 0, total = 7))
        assertEquals("7 / 7", FlashChatSearchMath.counterLabel(activeIndex = 6, total = 7))
    }

    @Test
    fun `has results gates stepper visibility`() {
        assertTrue(FlashChatSearchMath.hasResults(activeIndex = 0, total = 3))
        assertTrue(!FlashChatSearchMath.hasResults(activeIndex = -1, total = 0))
        assertTrue(!FlashChatSearchMath.hasResults(activeIndex = 5, total = 3))
    }
}
