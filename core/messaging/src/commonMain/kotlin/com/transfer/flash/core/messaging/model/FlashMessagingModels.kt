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
    AwaitingAcceptance,
    Transferring,
    Paused,
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

/**
 * What a finished call looks like in a chat thread (UI-050).
 *
 * The four cases are the ones worth distinguishing visually; the wire protocol has no call-log
 * frame, so each device derives its own row from state it already held when the call ended.
 */
public enum class FlashCallEventKind {
    /** This device placed the call and media flowed. */
    Outgoing,

    /** The peer called and media flowed. */
    Incoming,

    /** The peer called and media never flowed — declined here, or the caller gave up. */
    Missed,

    /** This device called and the peer never answered or declined. */
    Unanswered,
}

/**
 * A call row in a conversation (UI-050) — the chat-side record of a voice or video call.
 *
 * Attached to a [FlashMessageUi] rather than being a message type of its own so a call row
 * inherits bubble selection, long-press and timestamps for free.
 */
public data class FlashCallEventUi(
    val kind: FlashCallEventKind,
    val video: Boolean,
    /** How long media actually flowed, ms. Zero when the call never connected. */
    val durationMs: Long = 0L,
) {
    /** True for the one case that deserves a different colour: an incoming call with no media. */
    public val missed: Boolean
        get() = kind == FlashCallEventKind.Missed

    /** `"7:04"` / `"1:02:11"`, or null when the call never connected. */
    public val durationLabel: String?
        get() {
            if (durationMs <= 0L) return null
            val totalSeconds = durationMs / 1000L
            val seconds = totalSeconds % 60L
            val minutes = (totalSeconds / 60L) % 60L
            val hours = totalSeconds / 3600L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
}

public data class FlashMessageUi(
    val id: String,
    val senderName: String,
    val senderInitials: String,
    val timeLabel: String,
    val text: String,
    val isMine: Boolean,
    val images: List<FlashImageAttachmentUi> = emptyList(),
    val fileAttachments: List<FlashFileAttachmentUi> = emptyList(),
    val voiceAttachments: List<FlashVoiceAttachmentUi> = emptyList(),
    val reactions: List<FlashReaction> = emptyList(),
    val replyTo: FlashQuotedReplyUi? = null,
    val deliveryStatus: FlashMessageStatus? = null,
    /** Delivered recipients for an outbound group message; null when no group-delivery rows exist. */
    val deliveredTo: Int? = null,
    /** Total recipient rows for an outbound group message; null outside that aggregate. */
    val deliveredTotal: Int? = null,
    val groupPosition: FlashMessageGroupPosition = FlashMessageGroupPosition.SINGLE,
    val showSenderHeader: Boolean = true,
    /** Label rendered before this message when it starts a new local calendar day. */
    val daySeparator: String? = null,
    /** Non-null when this row is a call log entry instead of a text/attachment message. */
    val callEvent: FlashCallEventUi? = null,
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
    /** Group Phase C: how many members are online right now (0 for direct chats). */
    val groupOnlineCount: Int = 0,
    val previewIsMedia: Boolean = false,
    val previewDelivery: FlashListPreviewDelivery? = null,
    val sortOrder: Long = 0L,
    val isArchived: Boolean = false,
)

public data class FlashChatListUiState(
    val items: List<FlashChatListItemUi> = emptyList(),
    val archivedItems: List<FlashChatListItemUi> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /**
     * True once the backing store has produced its first list — even if that list is empty
     * (ERROR-034).
     *
     * Without this an empty [items] is ambiguous: it means both "this device has no conversations"
     * and "the query has not answered yet". The shell resolved the ambiguity with the engine's
     * `ready` flag, but `ready` flips when the transport stack finishes booting, which is strictly
     * earlier than the first Room emission — so a device with conversations rendered the
     * first-run "No conversations yet" panel and then crossfaded to real rows. Screens must treat
     * `!hasLoaded` as loading, not as empty.
     */
    val hasLoaded: Boolean = false,
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
    /**
     * ADR-044 V2: the display name of the group owner who introduced this member when this device never paired with
     * them (their key is trusted in this group on the owner's word, until the user verifies them). Null for yourself,
     * for the owner, and for every paired member; legacy groups never set it.
     */
    val introducedBy: String? = null,
)

/** Where one recipient stands for a message the user sent (Message Info, UI-051). */
public enum class FlashRecipientState { Read, Delivered, Waiting }

/** Why a [FlashRecipientState.Waiting] recipient has not got the message yet. */
public enum class FlashWaitingReason {
    /** Still a member; the device has not been reachable since the message was sent. */
    DeviceNotReached,

    /** No longer in the group (left or removed) without having received the message. */
    LeftGroup,
}

/** One recipient of a message in the Message Info sheet. */
public data class FlashMessageRecipientUi(
    val id: String,
    val name: String,
    val initials: String,
    val state: FlashRecipientState,
    /**
     * Local time the delivery was confirmed, for a [FlashRecipientState.Delivered] recipient. Null for everyone else:
     * a read recipient carries no time because Flash stores a read cursor, not when it moved.
     */
    val deliveredAtLabel: String? = null,
    val waitingReason: FlashWaitingReason? = null,
)

/**
 * The Message Info sheet's content (UI-051): one message the user sent and who has read it, received it, or not yet.
 * [recipients] is already ordered: read, then delivered (earliest first), then waiting.
 */
public data class FlashMessageInfoUi(
    val messageId: String,
    val preview: String,
    val sentLabel: String,
    val recipients: List<FlashMessageRecipientUi>,
) {
    val readBy: List<FlashMessageRecipientUi> get() = recipients.filter { it.state == FlashRecipientState.Read }
    val deliveredTo: List<FlashMessageRecipientUi> get() = recipients.filter { it.state == FlashRecipientState.Delivered }
    val waiting: List<FlashMessageRecipientUi> get() = recipients.filter { it.state == FlashRecipientState.Waiting }
}

public data class FlashActiveGroupCallBarUi(
    val callId: String,
    val callerName: String,
    val video: Boolean,
    val participantCount: Int = 1,
)

/**
 * Whether this device is still a member of the group it is looking at (ADR-044 V2, removal ripple). A device that left,
 * or that the owner removed, keeps the conversation and everything it already received, but can neither send nor place
 * calls: [Left] and [Removed] tell the screen which sentence to show. Direct chats are always [Active].
 */
public enum class FlashSelfMembership { Active, Left, Removed }

public data class FlashConversationUiState(
    val header: FlashChatHeaderUiState,
    val messages: List<FlashMessageUi>,
    /** Persisted unsent composer text for this conversation (#9), restored when the screen opens. */
    val draftText: String = "",
    /**
     * Group Phase B: the real member roster for a group conversation (names, per-member online
     * flags, roles). Empty for direct chats and for group states produced before Phase B's
     * repository wiring — consumers fall back to header-derived rows when empty.
     */
    val members: List<FlashGroupMemberUi> = emptyList(),
    /** Active ongoing group call announced by peers, if any. */
    val ongoingCall: FlashActiveGroupCallBarUi? = null,
    /**
     * ADR-044 V2 (E5): true only when this device owns this v2 group, i.e. it may remove members. False for direct
     * chats, legacy groups (no signed roster to remove from) and groups someone else owns.
     */
    val canRemoveMembers: Boolean = false,
    /** Whether this device can add members to the group (owner in v2 groups, active member in legacy groups). */
    val canAddMembers: Boolean = false,
    /** True if this device is the owner of the group. */
    val isGroupOwner: Boolean = false,
    /** ADR-063: true if this device is an active admin of the group. */
    val isGroupAdmin: Boolean = false,
    /** ADR-063: true if this device can promote/demote admins in this group (owner only). */
    val canPromoteAdmin: Boolean = false,
    /** Option D: whether active members can continue/fork this group into a new group (e.g. if the creator left or is gone). */
    val canContinueInNewGroup: Boolean = false,
    /** Removal ripple: false once this device left the group or the owner removed it; the composer gives way to a notice. */
    val selfMembership: FlashSelfMembership = FlashSelfMembership.Active,
    /** UI-052: non-null while earlier group messages are arriving through catch-up; drives the "catching up" banner. */
    val groupSync: FlashGroupSyncUi? = null,
)

/**
 * Earlier messages of this group are arriving (catch-up, UI-052). [receivedCount] is how many new ones have landed since
 * the banner appeared. There is deliberately no total: the requester cannot know how many holders will push.
 */
public data class FlashGroupSyncUi(val receivedCount: Int)

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
