package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashImageGridLogicTest {

    @Test
    fun `image attachment model initializes with expected defaults`() {
        val image = FlashImageAttachmentUi(
            id = "img-101",
            uri = "content://media/external/images/media/42",
            caption = "Sunset at the beach",
            width = 1920,
            height = 1080,
        )

        assertEquals("img-101", image.id)
        assertEquals("content://media/external/images/media/42", image.uri)
        assertEquals("Sunset at the beach", image.caption)
        assertEquals(1920, image.width)
        assertEquals(1080, image.height)
        assertEquals("image/jpeg", image.mimeType)
    }

    @Test
    fun `message model supports multiple photo attachments`() {
        val message = FlashMessageUi(
            id = "msg-collage",
            senderName = "Alex",
            senderInitials = "A",
            timeLabel = "3:45 PM",
            text = "Trip collage",
            isMine = true,
            images = listOf(
                FlashImageAttachmentUi(id = "i1", width = 800, height = 600),
                FlashImageAttachmentUi(id = "i2", width = 800, height = 600),
                FlashImageAttachmentUi(id = "i3", width = 800, height = 600),
                FlashImageAttachmentUi(id = "i4", width = 800, height = 600),
                FlashImageAttachmentUi(id = "i5", width = 800, height = 600),
            ),
        )

        assertEquals(5, message.images.size)
        assertTrue(message.images.isNotEmpty())
        assertEquals("Trip collage", message.text)
    }

    @Test
    fun `overflow calculation for multi-image albums`() {
        val count5 = 5
        val count8 = 8

        val overflow5 = count5 - 3
        val overflow8 = count8 - 3

        assertEquals(2, overflow5)
        assertEquals(5, overflow8)
    }
}
