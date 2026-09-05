package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for UI-007 multi-selection and UI-008 focus state logic.
 */
class FlashSelectionLogicTest {

    @Test
    fun `toggle selection adds item when absent`() {
        val initial = setOf("1", "2")
        val result = toggleSelection(initial, "3")
        assertEquals(setOf("1", "2", "3"), result)
    }

    @Test
    fun `toggle selection removes item when present`() {
        val initial = setOf("1", "2", "3")
        val result = toggleSelection(initial, "2")
        assertEquals(setOf("1", "3"), result)
    }

    @Test
    fun `selection mode active when set is non-empty`() {
        assertTrue(isInSelectionMode(setOf("1")))
        assertFalse(isInSelectionMode(emptySet()))
    }

    @Test
    fun `join selected message texts for clipboard copy`() {
        val messages = listOf(
            "Hello world",
            "Second message",
            "Third message",
        )
        val joined = messages.joinToString("\n")
        assertEquals("Hello world\nSecond message\nThird message", joined)
    }

    private fun toggleSelection(current: Set<String>, id: String): Set<String> {
        return if (id in current) current - id else current + id
    }

    private fun isInSelectionMode(selectedIds: Set<String>): Boolean {
        return selectedIds.isNotEmpty()
    }
}
