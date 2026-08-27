package com.transfer.flash.core.messaging.model

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence

@JvmInline
public value class FlashMessageId(public val value: String)

@JvmInline
public value class FlashConversationId(public val value: String)

public enum class FlashMessageStatus {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed,
}

public enum class FlashMessageGroupPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM,
}

public enum class FlashListPreviewDelivery {
    Sending,
    Sent,
    Delivered,
    Read,
    Failed,
}

public enum class FlashNetworkTransport {
    Lan,
    WifiDirect,
    Relay,
    Unknown,
}

public data class FlashAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: String? = null,
)

public data class FlashMessage(
    val id: FlashMessageId,
    val conversationId: FlashConversationId,
    val senderId: FlashDeviceId,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: FlashMessageStatus = FlashMessageStatus.Sent,
    val attachments: List<FlashAttachment> = emptyList(),
)

/**
 * Aggregated reaction on a message (UI-009).
 *
 * One entry per unique emoji. [isSelfReacted] indicates whether the local
 * user contributed to this reaction.
 */
public data class FlashReaction(
    val emoji: String,
    val count: Int,
    val isSelfReacted: Boolean = false,
    val reactorIds: List<String> = emptyList(),
)

/**
 * In-bubble reference to a previous quoted message (UI-010).
 */
public data class FlashQuotedReplyUi(
    val messageId: String,
    val senderName: String,
    val textSnippet: String,
    val isMine: Boolean = false,
)

/**
 * Transfer state for file attachments (UI-016).
 */
public enum class FlashFileTransferStatus {
    NotDownloaded,
    Transferring,
    Downloaded,
    Failed,
}

/**
 * Live transfer progress for a chat attachment, keyed by transferId, injected from the transfer
 * layer so a chat bubble can render inline send/receive progress alongside the Transfers tab (B4).
 * Pure data (no Android types) → the join between chat rows and live transfers stays testable.
 */
public data class FlashAttachmentProgress(
    /** 0f..1f fraction of bytes moved. */
    val progress: Float,
    val status: FlashFileTransferStatus,
    /** Openable local file: source URI while sending, received path once inbound completes. */
    val localPath: String? = null,
    val speedMbps: Float = 0f,
    val etaSeconds: Int = 0,
)

/**
 * File attachment representation for conversation UI (UI-016).
 */
public data class FlashFileAttachmentUi(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String = "application/octet-stream",
    val transferStatus: FlashFileTransferStatus = FlashFileTransferStatus.Downloaded,
    val transferProgress: Float = 1.0f,
    val transferSpeedMbps: Float = 0f,
    val etaSeconds: Int = 0,
    val localUri: String? = null,
)

/**
 * Image attachment representation for conversation photo messages and grids (UI-017).
 */
public data class FlashImageAttachmentUi(
    val id: String,
    val uri: String? = null,
    val thumbUri: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "image/jpeg",
    val caption: String? = null,
    val seedColor: Long = 0xFF2A2D36,
    /** Video attachments reuse the image tile for a thumbnail but overlay a play button (B4). */
    val isVideo: Boolean = false,
)

/**
 * Voice message attachment representation for conversation UI (UI-019).
 *
 * [amplitudes] are pre-computed normalized loudness samples (0..100) captured at
 * record/send time; playback cards resample them into waveform bars.
 */
public data class FlashVoiceAttachmentUi(
    val id: String,
    val uri: String? = null,
    val durationMs: Long = 0L,
    val amplitudes: List<Int> = emptyList(),
    val mimeType: String = "audio/aac",
    val transferStatus: FlashFileTransferStatus = FlashFileTransferStatus.Downloaded,
)

public data class FlashMessageUi(
    val id: String,
    val senderName: String,
    val senderInitials: String,
    val timeLabel: String,
    val text: String,
    val isMine: Boolean,
    val images: List<FlashImageAttachmentUi> = emptyList(),
    val hasImageGrid: Boolean = false,
    val imageCountLabel: String? = null,
    val fileAttachments: List<FlashFileAttachmentUi> = emptyList(),
    val voiceAttachments: List<FlashVoiceAttachmentUi> = emptyList(),
    val reactions: List<FlashReaction> = emptyList(),
    val replyTo: FlashQuotedReplyUi? = null,
    val deliveryStatus: FlashMessageStatus? = null,
    val groupPosition: FlashMessageGroupPosition = FlashMessageGroupPosition.SINGLE,
    val showSenderHeader: Boolean = true,
)

public data class FlashChatListItemUi(
    val id: String,
    val title: String,
    val avatarInitials: String,
    val avatarSeed: String = title,
    val previewText: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isTyping: Boolean = false,
    val presence: FlashPeerPresence = FlashPeerPresence.Offline,
    val isGroup: Boolean = false,
    val previewIsMedia: Boolean = false,
    val previewDelivery: FlashListPreviewDelivery? = null,
    val sortOrder: Long = 0L,
)

public data class FlashChatListUiState(
    val items: List<FlashChatListItemUi> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

public data class FlashChatHeaderUiState(
    val title: String,
    val avatarInitials: String,
    val avatarSeed: String = title,
    val presence: FlashPeerPresence = FlashPeerPresence.Offline,
    val transport: FlashNetworkTransport = FlashNetworkTransport.Unknown,
    val isEncrypted: Boolean = false,
    val isGroup: Boolean = false,
    val memberSummary: String? = null,
    val showCallActions: Boolean = true,
    /** UI-028 group header: member initials for the collage avatar (up to 4 rendered). */
    val memberInitials: List<String> = emptyList(),
    /** UI-028 group header: total member count (subtitle computed when memberSummary is null). */
    val memberCount: Int = 0,
    /** UI-028 group header: currently reachable members on the local network. */
    val onlineCount: Int = 0,
    /** UI-028 group header: names of members currently typing (drives the named typing subtitle). */
    val typingMemberNames: List<String> = emptyList(),
)

/** Role of a member inside a group conversation (UI-029). */
public enum class FlashMemberRole { Owner, Admin, Member }

/** One member row of a group conversation (UI-029). */
public data class FlashGroupMemberUi(
    val id: String,
    val name: String,
    val initials: String,
    val isOnline: Boolean = false,
    val role: FlashMemberRole = FlashMemberRole.Member,
    val transport: FlashNetworkTransport = FlashNetworkTransport.Unknown,
)

public data class FlashConversationUiState(
    val header: FlashChatHeaderUiState,
    val messages: List<FlashMessageUi>,
    /** Persisted unsent composer text for this conversation (#9), restored when the screen opens. */
    val draftText: String = "",
)

public data class FlashConversation(
    val id: FlashConversationId,
    val title: String,
    val lastMessage: FlashMessage? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isGroup: Boolean = false,
    val presence: FlashPeerPresence = FlashPeerPresence.Offline,
)

public data class FlashConversationDetail(
    val conversation: FlashConversation,
    val messages: List<FlashMessage> = emptyList(),
)
