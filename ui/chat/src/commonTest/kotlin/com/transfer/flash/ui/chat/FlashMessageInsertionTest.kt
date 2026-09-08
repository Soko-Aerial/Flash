package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-006 message insertion choreography: entrance gating, at-bottom detection, scroll policy.
 */
class FlashMessageInsertionTest {

    private val initialIds = setOf("1", "2", "3")

    @Test
    fun `new tail message plays entrance`() {
        assertTrue(
            shouldAnimateMessageEnter(messageId = "4", layoutIndex = 0, initialMessageIds = initialIds),
        )
    }

    @Test
    fun `historical tail message does not play entrance`() {
        assertFalse(
            shouldAnimateMessageEnter(messageId = "3", layoutIndex = 0, initialMessageIds = initialIds),
        )
    }

    @Test
    fun `new non-tail message does not play entrance`() {
        // Future history pagination inserts away from the tail (layout index 0).
        assertFalse(
            shouldAnimateMessageEnter(messageId = "0", layoutIndex = 5, initialMessageIds = initialIds),
        )
    }

    @Test
    fun `own send always auto scrolls`() {
        assertTrue(shouldAutoScrollToNewMessage(isMine = true, atBottom = false))
        assertTrue(shouldAutoScrollToNewMessage(isMine = true, atBottom = true))
    }

    @Test
    fun `incoming message auto scrolls only at bottom`() {
        assertTrue(shouldAutoScrollToNewMessage(isMine = false, atBottom = true))
        assertFalse(shouldAutoScrollToNewMessage(isMine = false, atBottom = false))
    }

    @Test
    fun `at bottom requires newest item within offset threshold`() {
        assertTrue(isAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0, thresholdPx = 96f))
        assertTrue(isAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 96, thresholdPx = 96f))
        assertFalse(isAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 200, thresholdPx = 96f))
        assertFalse(isAtBottom(firstVisibleItemIndex = 2, firstVisibleItemScrollOffset = 0, thresholdPx = 96f))
    }
}
