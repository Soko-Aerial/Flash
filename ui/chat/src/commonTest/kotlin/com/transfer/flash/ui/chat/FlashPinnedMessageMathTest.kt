package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashPinnedMessageMathTest {

    @Test
    fun testSenderHeader() {
        assertEquals("Pinned message • You", FlashPinnedMessageMath.senderHeader("Alice", isMine = true))
        assertEquals("Pinned message • Alice", FlashPinnedMessageMath.senderHeader("Alice", isMine = false))
        assertEquals("Pinned message • Peer", FlashPinnedMessageMath.senderHeader("", isMine = false))
    }

    @Test
    fun testPreviewSnippet() {
        assertEquals("Hello world", FlashPinnedMessageMath.previewSnippet("Hello\nworld"))
        assertEquals("Attachment", FlashPinnedMessageMath.previewSnippet("   ", fallback = "Attachment"))
        val longText = "a".repeat(100)
        val preview = FlashPinnedMessageMath.previewSnippet(longText, maxChars = 80)
        assertEquals(81, preview.length) // 80 chars + '…'
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun testIsPinned() {
        assertTrue(FlashPinnedMessageMath.isPinned("msg1", "msg1"))
        assertFalse(FlashPinnedMessageMath.isPinned("msg1", "msg2"))
        assertFalse(FlashPinnedMessageMath.isPinned("msg1", null))
    }
}
