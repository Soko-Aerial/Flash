package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashSharedContentMathTest {

    private fun sampleMessage(
        id: String,
        text: String = "",
        images: List<FlashImageAttachmentUi> = emptyList(),
        files: List<FlashFileAttachmentUi> = emptyList(),
        voice: List<FlashVoiceAttachmentUi> = emptyList(),
    ) = FlashMessageUi(
        id = id,
        senderName = "Alice",
        senderInitials = "A",
        timeLabel = "10:30 AM",
        text = text,
        isMine = false,
        images = images,
        fileAttachments = files,
        voiceAttachments = voice,
    )

    @Test
    fun testExtractMediaReverseChronological() {
        val img1 = FlashImageAttachmentUi(id = "img1", uri = "file:///img1.jpg")
        val img2 = FlashImageAttachmentUi(id = "img2", uri = "file:///img2.jpg")
        val messages = listOf(
            sampleMessage("m1", images = listOf(img1)),
            sampleMessage("m2", images = listOf(img2)),
        )

        val media = FlashSharedContentMath.extractMedia(messages)
        assertEquals(2, media.size)
        assertEquals("img2", media[0].image.id)
        assertEquals("m2", media[0].messageId)
        assertEquals("img1", media[1].image.id)
    }

    @Test
    fun testExtractFilesReverseChronological() {
        val f1 = FlashFileAttachmentUi(id = "f1", name = "spec.pdf", sizeBytes = 1024L)
        val f2 = FlashFileAttachmentUi(id = "f2", name = "data.zip", sizeBytes = 2048L)
        val messages = listOf(
            sampleMessage("m1", files = listOf(f1)),
            sampleMessage("m2", files = listOf(f2)),
        )

        val files = FlashSharedContentMath.extractFiles(messages)
        assertEquals(2, files.size)
        assertEquals("data.zip", files[0].file.name)
        assertEquals("m2", files[0].messageId)
        assertEquals("spec.pdf", files[1].file.name)
    }

    @Test
    fun testExtractAudioReverseChronological() {
        val v1 = FlashVoiceAttachmentUi(id = "v1", durationMs = 3000L)
        val v2 = FlashVoiceAttachmentUi(id = "v2", durationMs = 8000L)
        val messages = listOf(
            sampleMessage("m1", voice = listOf(v1)),
            sampleMessage("m2", voice = listOf(v2)),
        )

        val audio = FlashSharedContentMath.extractAudio(messages)
        assertEquals(2, audio.size)
        assertEquals("v2", audio[0].voice.id)
        assertEquals("m2", audio[0].messageId)
        assertEquals("v1", audio[1].voice.id)
    }

    @Test
    fun testExtractLinksWithPunctuationTrimming() {
        val messages = listOf(
            sampleMessage("m1", text = "Check out https://github.com/project and http://example.com/test."),
            sampleMessage("m2", text = "Join the group: flash://invite/abc123xyz! Cool right?"),
        )

        val links = FlashSharedContentMath.extractLinks(messages)
        assertEquals(3, links.size)

        // Newest message first
        assertEquals("flash://invite/abc123xyz", links[0].url)
        assertTrue(links[0].isInvite)
        assertEquals("Flash Invite", links[0].domain)

        assertEquals("https://github.com/project", links[1].url)
        assertFalse(links[1].isInvite)
        assertEquals("github.com", links[1].domain)

        assertEquals("http://example.com/test", links[2].url)
        assertEquals("example.com", links[2].domain)
    }

    @Test
    fun testExtractDomain() {
        assertEquals("github.com", FlashSharedContentMath.extractDomain("https://github.com/foo/bar?q=1"))
        assertEquals("google.com", FlashSharedContentMath.extractDomain("http://google.com:8080/path"))
        assertEquals("Link", FlashSharedContentMath.extractDomain("invalid-url"))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", FlashSharedContentMath.formatFileSize(0L))
        assertEquals("500 B", FlashSharedContentMath.formatFileSize(500L))
        assertEquals("1.5 KB", FlashSharedContentMath.formatFileSize(1536L))
        assertEquals("4.0 MB", FlashSharedContentMath.formatFileSize(4 * 1024 * 1024L))
        assertEquals("2.5 GB", FlashSharedContentMath.formatFileSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testBadgeForExtension() {
        assertEquals("PDF", FlashSharedContentMath.badgeForExtension("report.pdf"))
        assertEquals("ZIP", FlashSharedContentMath.badgeForExtension("archive.ZIP"))
        assertEquals("GZ", FlashSharedContentMath.badgeForExtension("backup.tar.gz"))
        assertEquals("FILE", FlashSharedContentMath.badgeForExtension("noextension"))
        assertEquals("FILE", FlashSharedContentMath.badgeForExtension("too_long_extension.longext"))
    }

    @Test
    fun testTabLabelWithCount() {
        assertEquals("Media", FlashSharedContentMath.tabLabelWithCount(FlashSharedContentTab.Media, 0))
        assertEquals("Media (5)", FlashSharedContentMath.tabLabelWithCount(FlashSharedContentTab.Media, 5))
    }
}
