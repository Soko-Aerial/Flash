package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashAttachmentSheetVisibilityTest {

    @Test
    fun `nothing hidden shows the whole palette in order`() {
        assertEquals(FlashAttachmentType.values().toList(), visibleAttachmentActions(emptySet()))
    }

    @Test
    fun `a platform without camera capture does not offer Camera but keeps everything else`() {
        val shown = visibleAttachmentActions(setOf(FlashAttachmentType.Camera))
        assertFalse(FlashAttachmentType.Camera in shown)
        assertTrue(FlashAttachmentType.Gallery in shown && FlashAttachmentType.Files in shown)
        assertEquals(FlashAttachmentType.values().size - 1, shown.size)
    }
}
