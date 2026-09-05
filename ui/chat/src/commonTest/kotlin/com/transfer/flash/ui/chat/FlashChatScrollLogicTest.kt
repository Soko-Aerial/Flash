package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashChatScrollLogicTest {

    @Test
    fun `peer arrival while scrolled up increments unseen counter`() {
        assertEquals(
            1,
            FlashChatScrollMath.nextUnseenCount(
                current = 0,
                isNewTailMessage = true,
                wasAtBottom = false,
                isMine = false,
            ),
        )
        assertEquals(
            4,
            FlashChatScrollMath.nextUnseenCount(
                current = 3,
                isNewTailMessage = true,
                wasAtBottom = false,
                isMine = false,
            ),
        )
    }

    @Test
    fun `being at bottom resets the unseen counter`() {
        assertEquals(
            0,
            FlashChatScrollMath.nextUnseenCount(
                current = 5,
                isNewTailMessage = true,
                wasAtBottom = true,
                isMine = false,
            ),
        )
    }

    @Test
    fun `own messages reset the counter because they auto-scroll`() {
        assertEquals(
            0,
            FlashChatScrollMath.nextUnseenCount(
                current = 2,
                isNewTailMessage = true,
                wasAtBottom = false,
                isMine = true,
            ),
        )
    }

    @Test
    fun `non-arrival updates keep the counter unchanged`() {
        assertEquals(
            3,
            FlashChatScrollMath.nextUnseenCount(
                current = 3,
                isNewTailMessage = false,
                wasAtBottom = false,
                isMine = false,
            ),
        )
    }

    @Test
    fun `tail id change detects arrivals and ignores identical ids`() {
        assertTrue(FlashChatScrollMath.isNewTailMessage(previousTailId = "m1", currentTailId = "m2"))
        assertFalse(FlashChatScrollMath.isNewTailMessage(previousTailId = "m2", currentTailId = "m2"))
        // First load: no previous tail -> history, not an arrival
        assertFalse(FlashChatScrollMath.isNewTailMessage(previousTailId = null, currentTailId = null))
        assertTrue(FlashChatScrollMath.isNewTailMessage(previousTailId = null, currentTailId = "m1"))
    }

    @Test
    fun `pill visibility follows the counter`() {
        assertFalse(FlashChatScrollMath.shouldShowNewMessagesPill(0))
        assertTrue(FlashChatScrollMath.shouldShowNewMessagesPill(1))
        assertTrue(FlashChatScrollMath.shouldShowNewMessagesPill(12))
    }

    @Test
    fun `pill label pluralizes correctly`() {
        assertEquals("1 new message", FlashChatScrollMath.pillLabel(1))
        assertEquals("2 new messages", FlashChatScrollMath.pillLabel(2))
        assertEquals("12 new messages", FlashChatScrollMath.pillLabel(12))
    }
}
