package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi

public enum class FlashSharedContentTab(public val label: String) {
    Media("Media"),
    Files("Files"),
    Audio("Audio"),
    Links("Links"),
}

public data class FlashSharedMediaItem(
    val image: FlashImageAttachmentUi,
    val messageId: String,
    val senderName: String,
    val timeLabel: String,
)

public data class FlashSharedFileItem(
    val file: FlashFileAttachmentUi,
    val messageId: String,
    val senderName: String,
    val timeLabel: String,
)

public data class FlashSharedAudioItem(
    val voice: FlashVoiceAttachmentUi,
    val messageId: String,
    val senderName: String,
    val timeLabel: String,
)

public data class FlashSharedLinkItem(
    val url: String,
    val domain: String,
    val snippet: String,
    val messageId: String,
    val senderName: String,
    val timeLabel: String,
    val isInvite: Boolean,
)

public object FlashSharedContentMath {
    private val URL_REGEX = """(https?://[^\s]+|flash://[^\s]+)""".toRegex(RegexOption.IGNORE_CASE)

    /**
     * Extracts all photo and video attachments from the conversation messages in reverse chronological order.
     */
    public fun extractMedia(messages: List<FlashMessageUi>): List<FlashSharedMediaItem> {
        val results = mutableListOf<FlashSharedMediaItem>()
        for (message in messages.asReversed()) {
            for (image in message.images) {
                results.add(
                    FlashSharedMediaItem(
                        image = image,
                        messageId = message.id,
                        senderName = message.senderName,
                        timeLabel = message.timeLabel,
                    )
                )
            }
        }
        return results
    }

    /**
     * Extracts all file and document attachments from messages in reverse chronological order.
     */
    public fun extractFiles(messages: List<FlashMessageUi>): List<FlashSharedFileItem> {
        val results = mutableListOf<FlashSharedFileItem>()
        for (message in messages.asReversed()) {
            for (file in message.fileAttachments) {
                results.add(
                    FlashSharedFileItem(
                        file = file,
                        messageId = message.id,
                        senderName = message.senderName,
                        timeLabel = message.timeLabel,
                    )
                )
            }
        }
        return results
    }

    /**
     * Extracts all voice note attachments from messages in reverse chronological order.
     */
    public fun extractAudio(messages: List<FlashMessageUi>): List<FlashSharedAudioItem> {
        val results = mutableListOf<FlashSharedAudioItem>()
        for (message in messages.asReversed()) {
            for (voice in message.voiceAttachments) {
                results.add(
                    FlashSharedAudioItem(
                        voice = voice,
                        messageId = message.id,
                        senderName = message.senderName,
                        timeLabel = message.timeLabel,
                    )
                )
            }
        }
        return results
    }

    /**
     * Extracts HTTP/HTTPS web links and flash:// invite links from messages in reverse chronological order.
     */
    public fun extractLinks(messages: List<FlashMessageUi>): List<FlashSharedLinkItem> {
        val results = mutableListOf<FlashSharedLinkItem>()
        for (message in messages.asReversed()) {
            val matches = URL_REGEX.findAll(message.text)
            for (match in matches) {
                var rawUrl = match.value
                while (rawUrl.isNotEmpty() && rawUrl.last() in ".,;:!?)") {
                    rawUrl = rawUrl.dropLast(1)
                }
                if (rawUrl.isNotBlank()) {
                    val isInvite = rawUrl.startsWith("flash://", ignoreCase = true)
                    val domain = if (isInvite) "Flash Invite" else extractDomain(rawUrl)
                    results.add(
                        FlashSharedLinkItem(
                            url = rawUrl,
                            domain = domain,
                            snippet = message.text.take(120),
                            messageId = message.id,
                            senderName = message.senderName,
                            timeLabel = message.timeLabel,
                            isInvite = isInvite,
                        )
                    )
                }
            }
        }
        return results
    }

    /**
     * Parses host/domain from a URL string, returning "Link" if malformed.
     */
    public fun extractDomain(url: String): String {
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return "Link"
        val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore(':')
        return host.ifBlank { "Link" }
    }

    /**
     * File size formatter in human readable units (B, KB, MB, GB).
     */
    public fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "${(gb * 10).toLong() / 10.0} GB"
            mb >= 1.0 -> "${(mb * 10).toLong() / 10.0} MB"
            kb >= 1.0 -> "${(kb * 10).toLong() / 10.0} KB"
            else -> "$bytes B"
        }
    }

    /**
     * Extracts short 2-4 letter uppercase extension badge for a document (e.g., "PDF", "ZIP").
     */
    public fun badgeForExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").uppercase()
        return if (ext.length in 1..4) ext else "FILE"
    }

    /**
     * Formats tab label with count, e.g. "Media (12)" or "Media" if 0.
     */
    public fun tabLabelWithCount(tab: FlashSharedContentTab, count: Int): String {
        return if (count > 0) "${tab.label} ($count)" else tab.label
    }
}
