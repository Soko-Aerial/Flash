package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashStatesLogicTest {

    // --- UI-026 delay guard ---

    @Test
    fun `loading indicator is suppressed for fast loads`() {
        assertFalse(FlashStateMath.shouldShowLoadingIndicator(elapsedMs = 0L))
        assertFalse(FlashStateMath.shouldShowLoadingIndicator(elapsedMs = 299L))
        assertTrue(FlashStateMath.shouldShowLoadingIndicator(elapsedMs = 300L))
        assertTrue(FlashStateMath.shouldShowLoadingIndicator(elapsedMs = 5_000L))
    }

    @Test
    fun `skeleton row count is capped and never zero`() {
        assertEquals(1, FlashStateMath.skeletonRowCount(0))
        assertEquals(8, FlashStateMath.skeletonRowCount(8))
        assertEquals(FlashStateMath.MAX_SKELETON_ROWS, FlashStateMath.skeletonRowCount(50))
    }

    // --- UI-025 copy specificity (research: generic copy is an anti-pattern) ---

    @Test
    fun `empty state copy is specific per screen and non-blank`() {
        val kinds = FlashStateCopy.EmptyKind.entries
        kinds.forEach { kind ->
            val copy = FlashStateCopy.emptyStateCopy(kind)
            assertTrue(copy.headline.isNotBlank())
            assertTrue(copy.body.isNotBlank())
            // Generic-copy anti-pattern guard: no bare "Nothing here yet" placeholders.
            assertFalse(copy.headline.equals("Nothing here yet", ignoreCase = true))
        }
    }

    @Test
    fun `chat list first run names the P2P action`() {
        val copy = FlashStateCopy.emptyStateCopy(FlashStateCopy.EmptyKind.ChatListFirstRun)
        assertEquals("No conversations yet", copy.headline)
        assertTrue(copy.body.contains("nearby device"))
        assertEquals("Find devices", copy.actionLabel)
    }

    @Test
    fun `conversation empty state has no CTA`() {
        val copy = FlashStateCopy.emptyStateCopy(FlashStateCopy.EmptyKind.ConversationEmpty)
        assertEquals(null, copy.actionLabel)
        assertTrue(copy.body.contains("your network"))
    }
}
