package com.transfer.flash.core.messaging.util

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashListPreviewDelivery
import com.transfer.flash.core.messaging.model.FlashMessageGroupPosition
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashNetworkTransport

public fun computeMessageGroupPositions(messages: List<FlashMessageUi>): List<FlashMessageUi> {
    if (messages.isEmpty()) return messages

    return messages.mapIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val sameSenderAsPrevious = previous?.senderName == message.senderName && previous.isMine == message.isMine
        val sameSenderAsNext = next?.senderName == message.senderName && next.isMine == message.isMine

        val position = when {
            !sameSenderAsPrevious && !sameSenderAsNext -> FlashMessageGroupPosition.SINGLE
            !sameSenderAsPrevious && sameSenderAsNext -> FlashMessageGroupPosition.TOP
            sameSenderAsPrevious && sameSenderAsNext -> FlashMessageGroupPosition.MIDDLE
            else -> FlashMessageGroupPosition.BOTTOM
        }

        message.copy(
            groupPosition = position,
            showSenderHeader = !message.isMine && !sameSenderAsPrevious,
        )
    }
}

public fun sortedChatListItems(items: List<FlashChatListItemUi>): List<FlashChatListItemUi> {
    return items.sortedWith(
        compareByDescending<FlashChatListItemUi> { it.isPinned }
            .thenByDescending { it.sortOrder },
    )
}

public fun sampleFlashChatListState(): FlashChatListUiState {
    val items = listOf(
        FlashChatListItemUi(
            id = "conv-false-school",
            title = "False School",
            avatarInitials = "FS",
            previewText = "Flash keeps this conversation local and private.",
            timestamp = "Now",
            unreadCount = 3,
            isPinned = true,
            isGroup = true,
            presence = FlashPeerPresence.Online,
            sortOrder = 5,
        ),
        FlashChatListItemUi(
            id = "conv-alex",
            title = "Alex Chen",
            avatarInitials = "AC",
            previewText = "Can you send the build over Wi‑Fi Direct?",
            timestamp = "11:48 PM",
            isTyping = true,
            presence = FlashPeerPresence.Online,
            sortOrder = 4,
        ),
        FlashChatListItemUi(
            id = "conv-design",
            title = "Design Team",
            avatarInitials = "DT",
            previewText = "Belal: Updated the motion tokens doc",
            timestamp = "9:12 PM",
            unreadCount = 12,
            isGroup = true,
            sortOrder = 3,
        ),
        FlashChatListItemUi(
            id = "conv-transfer",
            title = "Flash Transfer",
            avatarInitials = "FT",
            previewText = "photo.jpg · 24 MB received",
            timestamp = "Yesterday",
            previewIsMedia = true,
            previewDelivery = FlashListPreviewDelivery.Read,
            isMuted = true,
            sortOrder = 2,
        ),
        FlashChatListItemUi(
            id = "conv-offline",
            title = "Jordan Lee",
            avatarInitials = "JL",
            previewText = "Message failed to send",
            timestamp = "Mon",
            previewDelivery = FlashListPreviewDelivery.Failed,
            presence = FlashPeerPresence.Offline,
            sortOrder = 1,
        ),
    )
    // hasLoaded: a sample dataset is complete the moment it is constructed — there is no query
    // behind it to wait for. Leaving it false would make every preview and test that binds this
    // state render the loading skeleton instead of the rows it was built to show (ERROR-034).
    return FlashChatListUiState(items = sortedChatListItems(items), hasLoaded = true)
}

public fun sampleFlashConversationState(): FlashConversationUiState {
    val rawMessages = listOf(
        FlashMessageUi(
            id = "1",
            senderName = "Sebastian",
            senderInitials = "S",
            timeLabel = "1:20 PM",
            text = "Here is a short video I put together for the event. I hope you enjoy it and please feel free to share.",
            isMine = false,
            reactions = listOf(
                com.transfer.flash.core.messaging.model.FlashReaction(emoji = "👍", count = 2, isSelfReacted = true),
                com.transfer.flash.core.messaging.model.FlashReaction(emoji = "🔥", count = 1, isSelfReacted = false),
            ),
        ),
        FlashMessageUi(
            id = "2",
            senderName = "Belal Khan",
            senderInitials = "BK",
            timeLabel = "1:23 PM",
            text = "Esteem my advice it an excuse enable",
            isMine = false,
        ),
        FlashMessageUi(
            id = "3",
            senderName = "Leandro Borges Ferreira",
            senderInitials = "LF",
            timeLabel = "11:42 AM",
            text = "Check out the photos from the outdoor meetup!",
            isMine = false,
            images = listOf(
                com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(
                    id = "img-1",
                    caption = "Mountain view",
                    width = 1200,
                    height = 900,
                    seedColor = 0xFF4579A8,
                ),
                com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(
                    id = "img-2",
                    caption = "Forest trail",
                    width = 1200,
                    height = 800,
                    seedColor = 0xFF3E6B5C,
                ),
                com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(
                    id = "img-3",
                    caption = "Lake reflection",
                    width = 800,
                    height = 1200,
                    seedColor = 0xFFAD7450,
                ),
                com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(
                    id = "img-4",
                    caption = "Sunset camp",
                    width = 1000,
                    height = 700,
                    seedColor = 0xFF8A5A44,
                ),
                com.transfer.flash.core.messaging.model.FlashImageAttachmentUi(
                    id = "img-5",
                    caption = "Starry night",
                    width = 1200,
                    height = 800,
                    seedColor = 0xFF4D5055,
                ),
            ),
            reactions = listOf(
                com.transfer.flash.core.messaging.model.FlashReaction(emoji = "😮", count = 1, isSelfReacted = false),
            ),
        ),
        FlashMessageUi(
            id = "4",
            senderName = "You",
            senderInitials = "YO",
            timeLabel = "Now",
            text = "Flash keeps this conversation local and private.",
            isMine = true,
            replyTo = com.transfer.flash.core.messaging.model.FlashQuotedReplyUi(
                messageId = "1",
                senderName = "Sebastian",
                textSnippet = "Here is a short video I put together for the event.",
            ),
            reactions = listOf(
                com.transfer.flash.core.messaging.model.FlashReaction(emoji = "❤️", count = 3, isSelfReacted = true),
            ),
        ),
        FlashMessageUi(
            id = "5",
            senderName = "Belal Khan",
            senderInitials = "BK",
            timeLabel = "12:05 PM",
            text = "",
            isMine = false,
            voiceAttachments = listOf(
                com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi(
                    id = "voice-1",
                    durationMs = 23_400L,
                    amplitudes = listOf(
                        22, 41, 63, 38, 74, 91, 55, 30, 48, 82, 67, 44, 29, 58, 93, 71,
                        40, 26, 52, 78, 88, 61, 35, 47, 70, 95, 59, 33, 24, 56, 80, 66,
                        43, 31, 49, 72, 85, 54, 37, 28,
                    ),
                ),
            ),
        ),
    )

    return FlashConversationUiState(
        header = FlashChatHeaderUiState(
            title = "False School",
            avatarInitials = "FS",
            presence = FlashPeerPresence.Online,
            transport = FlashNetworkTransport.Lan,
            isEncrypted = true,
            isGroup = true,
            memberSummary = null,
            memberInitials = listOf("AR", "BK", "LF", "SR"),
            memberCount = 15,
            onlineCount = 4,
            typingMemberNames = emptyList(),
            showCallActions = false,
        ),
        messages = computeMessageGroupPositions(rawMessages),
    )
}

public fun sampleDirectChatHeader(): FlashChatHeaderUiState = FlashChatHeaderUiState(
    title = "Alex Chen",
    avatarInitials = "AC",
    presence = FlashPeerPresence.Online,
    transport = FlashNetworkTransport.WifiDirect,
    isEncrypted = true,
    showCallActions = true,
)

public fun chatListRowContentDescription(item: FlashChatListItemUi): String {
    val preview = when {
        item.isTyping -> "typing"
        else -> item.previewText
    }
    val unreadPart = if (item.unreadCount > 0) {
        ", ${item.unreadCount} unread"
    } else {
        ""
    }
    return "${item.title}, $preview, ${item.timestamp}$unreadPart"
}

/**
 * Human-readable content summary for a message whose text is blank (UI-008 focus
 * preview, chat-list previews). Voice → `Voice message • m:ss`, images → count,
 * files → file name. Returns "" when there is nothing to summarize.
 */
public fun flashMessageContentSummary(message: FlashMessageUi): String {
    if (message.text.isNotBlank()) return message.text

    message.voiceAttachments.firstOrNull()?.let { voice ->
        val totalSeconds = voice.durationMs.coerceAtLeast(0L) / 1000L
        return "Voice message • ${totalSeconds / 60L}:${(totalSeconds % 60L).toString().padStart(2, '0')}"
    }
    if (message.images.isNotEmpty()) {
        return if (message.images.size == 1) "Photo" else "${message.images.size} photos"
    }
    message.fileAttachments.firstOrNull()?.let { file ->
        return file.name
    }
    return ""
}
