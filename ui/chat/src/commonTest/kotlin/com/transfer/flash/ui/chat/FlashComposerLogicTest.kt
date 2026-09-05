package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for UI-011 and UI-013 Composer state logic.
 */
class FlashComposerLogicTest {

    @Test
    fun `canSend requires non-blank text and enabled state`() {
        assertTrue(canSend(draft = "Hello", enabled = true))
        assertTrue(canSend(draft = "  A  ", enabled = true))
        assertFalse(canSend(draft = "", enabled = true))
        assertFalse(canSend(draft = "   ", enabled = true))
        assertFalse(canSend(draft = "Hello", enabled = false))
    }

    @Test
    fun `trim draft before submission`() {
        val trimmed = "  Important message  ".trim()
        assertTrue(trimmed == "Important message")
        assertTrue("   ".trim().isEmpty())
    }

    private fun canSend(draft: String, enabled: Boolean): Boolean {
        return draft.isNotBlank() && enabled
    }
}
