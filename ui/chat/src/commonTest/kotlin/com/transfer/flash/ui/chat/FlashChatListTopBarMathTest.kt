package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UI-058 H5 (HUBT-U*): text rules of the Transfers action in the chat-list top bar. */
class FlashChatListTopBarMathTest {

    @Test
    fun `no active transfers shows no badge and the plain label`() {
        assertNull(FlashChatListTopBarMath.badgeText(0))
        assertEquals("Transfers", FlashChatListTopBarMath.contentDescription(0))
    }

    @Test
    fun `negative counts behave like zero`() {
        assertNull(FlashChatListTopBarMath.badgeText(-5))
        assertEquals("Transfers", FlashChatListTopBarMath.contentDescription(-5))
        assertEquals(0, FlashChatListTopBarMath.normalisedCount(Int.MIN_VALUE))
    }

    @Test
    fun `small counts show exactly`() {
        assertEquals("1", FlashChatListTopBarMath.badgeText(1))
        assertEquals("99", FlashChatListTopBarMath.badgeText(99))
        assertEquals("Transfers, 1 active", FlashChatListTopBarMath.contentDescription(1))
    }

    @Test
    fun `badge caps at 99 plus but the spoken label keeps the real count`() {
        assertEquals("99+", FlashChatListTopBarMath.badgeText(100))
        assertEquals("99+", FlashChatListTopBarMath.badgeText(Int.MAX_VALUE))
        assertEquals("Transfers, 250 active", FlashChatListTopBarMath.contentDescription(250))
    }
}
