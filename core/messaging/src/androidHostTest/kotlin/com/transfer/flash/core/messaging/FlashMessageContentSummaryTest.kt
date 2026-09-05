package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.core.messaging.util.flashMessageContentSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class FlashMessageContentSummaryTest {

    private fun message(text: String = "", build: FlashMessageUi.() -> FlashMessageUi = { this }) =
        FlashMessageUi(
            id = "m1",
            senderName = "Alex",
            senderInitials = "A",
            timeLabel = "10:00 AM",
            text = text,
            isMine = false,
        ).build()

    @Test
    fun `text messages summarize to their own text`() {
        assertEquals("Hello there", flashMessageContentSummary(message(text = "Hello there")))
    }

    @Test
    fun `voice messages summarize with formatted duration`() {
        val summary = flashMessageContentSummary(
            message().copy(
                voiceAttachments = listOf(FlashVoiceAttachmentUi(id = "v1", durationMs = 23_400L)),
            ),
        )
        assertEquals("Voice message • 0:23", summary)
    }

    @Test
    fun `image messages summarize by count`() {
        val single = flashMessageContentSummary(
            message().copy(images = listOf(FlashImageAttachmentUi(id = "i1"))),
        )
        val album = flashMessageContentSummary(
            message().copy(
                images = listOf(
                    FlashImageAttachmentUi(id = "i1"),
                    FlashImageAttachmentUi(id = "i2"),
                    FlashImageAttachmentUi(id = "i3"),
                ),
            ),
        )

        assertEquals("Photo", single)
        assertEquals("3 photos", album)
    }

    @Test
    fun `file messages summarize to the file name`() {
        val summary = flashMessageContentSummary(
            message().copy(
                fileAttachments = listOf(FlashFileAttachmentUi(id = "f1", name = "report.pdf", sizeBytes = 10L)),
            ),
        )
        assertEquals("report.pdf", summary)
    }

    @Test
    fun `empty messages summarize to blank string`() {
        assertEquals("", flashMessageContentSummary(message()))
    }
}
