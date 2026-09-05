package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlashAttachmentLogicTest {

    @Test
    fun `attachment types define all 5 core Flash action categories`() {
        val types = FlashAttachmentType.values()
        assertEquals(5, types.size)

        val labels = types.map { it.label }
        assertTrue(labels.contains("Gallery"))
        assertTrue(labels.contains("Files"))
        assertTrue(labels.contains("Camera"))
        assertTrue(labels.contains("Audio"))
        assertTrue(labels.contains("Flash P2P"))
    }

    @Test
    fun `attachment types have valid non-empty labels and icons`() {
        for (type in FlashAttachmentType.values()) {
            assertTrue(type.label.isNotBlank(), "Label for $type must not be blank")
            assertNotNull(type.icon, "Icon for $type must not be null")
            assertNotNull(type.containerColor, "Container color for $type must not be null")
        }
    }
}
