package com.transfer.flash.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.transfer.flash.ui.theme.FlashColors
import kotlin.math.max
import kotlin.math.min
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

    @Test
    fun `archived chats empty state has Back to chats CTA`() {
        val copy = FlashStateCopy.emptyStateCopy(FlashStateCopy.EmptyKind.ArchivedChatsEmpty)
        assertEquals("No archived chats", copy.headline)
        assertEquals("Back to chats", copy.actionLabel)
    }

    @Test
    fun `state view CTA buttons meet WCAG AA contrast across light, dark, and dynamic accents`() {
        val themes = listOf(
            FlashColors.light(),
            FlashColors.dark(),
            FlashColors.light().withAccent(Color(0xFF6750A4)),
            FlashColors.dark().withAccent(Color(0xFFD0BCFF)),
        )
        themes.forEach { colors ->
            val (bg, fg) = FlashStateMath.ctaColors(colors)
            assertTrue(
                contrastRatio(fg, bg) >= 4.5f,
                "State CTA contrast below AA (bg=$bg, fg=$fg, ratio=${contrastRatio(fg, bg)})",
            )
        }
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }
}
