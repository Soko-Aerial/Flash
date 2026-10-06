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

    @Test
    fun `several pins number the banner and a single pin does not`() {
        assertEquals("Pinned message 2 of 3 • Alice", FlashPinnedMessageMath.senderHeader("Alice", isMine = false, position = 1, total = 3))
        assertEquals("Pinned message 1 of 2 • You", FlashPinnedMessageMath.senderHeader("Alice", isMine = true, position = 0, total = 2))
        assertEquals("Pinned message • Alice", FlashPinnedMessageMath.senderHeader("Alice", isMine = false, position = 0, total = 1))
    }

    @Test
    fun `tapping the banner walks every pin and wraps`() {
        assertEquals(1, FlashPinnedMessageMath.nextIndex(0, 3))
        assertEquals(2, FlashPinnedMessageMath.nextIndex(1, 3))
        assertEquals(0, FlashPinnedMessageMath.nextIndex(2, 3))
        assertEquals(0, FlashPinnedMessageMath.nextIndex(0, 1))
        assertEquals(0, FlashPinnedMessageMath.nextIndex(0, 0))
    }

    @Test
    fun `the shown pin index stays in range when pins disappear`() {
        assertEquals(1, FlashPinnedMessageMath.clampIndex(5, 2))
        assertEquals(0, FlashPinnedMessageMath.clampIndex(-1, 2))
        assertEquals(0, FlashPinnedMessageMath.clampIndex(3, 0))
        assertEquals(1, FlashPinnedMessageMath.clampIndex(1, 3))
    }

    @Test
    fun `a pin toast says the pin is local and never claims the peer sees it`() {
        assertEquals("Pinned on this device", FlashPinnedMessageMath.toastText(true))
        assertEquals("Unpinned", FlashPinnedMessageMath.toastText(false))
    }

    @Test
    fun `isPinnedIn checks the whole list`() {
        assertTrue(FlashPinnedMessageMath.isPinnedIn("b", listOf("a", "b")))
        assertFalse(FlashPinnedMessageMath.isPinnedIn("c", listOf("a", "b")))
        assertFalse(FlashPinnedMessageMath.isPinnedIn("a", emptyList()))
    }
}
