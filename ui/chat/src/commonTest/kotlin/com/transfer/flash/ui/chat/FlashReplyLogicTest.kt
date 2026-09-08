package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlashReplyLogicTest {

    private val messages = listOf(
        FlashMessageUi(
            id = "msg-1",
            senderName = "Alex",
            senderInitials = "A",
            timeLabel = "10:00 AM",
            text = "Are you sending the files?",
            isMine = false,
        ),
        FlashMessageUi(
            id = "msg-2",
            senderName = "You",
            senderInitials = "Y",
            timeLabel = "10:01 AM",
            text = "Yes, transferring now!",
            isMine = true,
            replyTo = FlashQuotedReplyUi(
                messageId = "msg-1",
                senderName = "Alex",
                textSnippet = "Are you sending the files?",
            ),
        ),
    )

    @Test
    fun `message with replyTo correctly stores quoted metadata`() {
        val replyMessage = messages[1]
        assertNotNull(replyMessage.replyTo)
        assertEquals("msg-1", replyMessage.replyTo?.messageId)
        assertEquals("Alex", replyMessage.replyTo?.senderName)
        assertEquals("Are you sending the files?", replyMessage.replyTo?.textSnippet)
    }

    @Test
    fun `jump target index in reversed list resolves correctly`() {
        val reversedList = messages.asReversed() // [msg-2, msg-1]

        val targetIndex1 = reversedList.indexOfFirst { it.id == "msg-1" }
        assertEquals(1, targetIndex1)

        val targetIndex2 = reversedList.indexOfFirst { it.id == "msg-2" }
        assertEquals(0, targetIndex2)

        val nonExistentIndex = reversedList.indexOfFirst { it.id == "msg-unknown" }
        assertEquals(-1, nonExistentIndex)
    }

    @Test
    fun `quoted reply snippet creation from message`() {
        val original = messages[0]
        val quote = FlashQuotedReplyUi(
            messageId = original.id,
            senderName = original.senderName,
            textSnippet = original.text,
            isMine = original.isMine,
        )

        assertEquals("msg-1", quote.messageId)
        assertEquals("Alex", quote.senderName)
        assertEquals("Are you sending the files?", quote.textSnippet)
        assertEquals(false, quote.isMine)
    }
}
