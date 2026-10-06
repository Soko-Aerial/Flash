package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.group.GroupLocalPreferences
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashGroupJoinRequestUi
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashMessageInfoUi
import com.transfer.flash.core.messaging.protocol.GroupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * High-level messaging repository contract for conversation management and message exchange.
 */
public interface FlashChatRepository {
    public val chatListState: StateFlow<FlashChatListUiState>
    public val conversationState: StateFlow<FlashConversationUiState>
    public fun openConversation(conversationId: String)
    public fun closeConversation()
    public fun sendText(text: String)

    /**
     * Sends [text] into [conversationId] without opening it, so a forward to several chats reaches
     * each one (the open conversation is not touched). Default no-op for lightweight implementations.
     */
    public fun sendTextTo(conversationId: String, text: String) {}

    /**
     * Retry sending a previously failed message (#21, ERROR-089).
     * Re-inserts the message into the outbox with attempts reset and now as createdAt.
     */
    public fun retryMessage(localId: String) {}

    /** Creates an ad-hoc trusted group. Phase 1 allows at most six members including this device. */
    public suspend fun createGroup(name: String, memberIds: Set<String>): FlashResult<String> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Creates a v2 group with only the creator to be shared via invite link (GM-4). */
    public suspend fun createGroupForInvite(name: String): FlashResult<String> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Generates an invite link for [groupId] using the current secret and address hints (GM-4). */
    public suspend fun inviteFor(groupId: String): FlashResult<String> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Accepts an invite link, stores the secret, vouches the inviter, and initiates join (GM-4). */
    public suspend fun acceptInvite(inviteUri: String): FlashResult<String> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Approves a pending join request for [groupId] and issues a certificate (GM-4). Admin-only. */
    public suspend fun approveJoinRequest(groupId: String, subjectId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Refuses a pending join request for [groupId] (GM-4). Admin-only. */
    public suspend fun refuseJoinRequest(
        groupId: String,
        subjectId: String,
        reason: String = "declined",
    ): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * Returns the user-facing status sentence for an accepted invite to [groupId] (Table 8.3, M-03, M-07, M-08).
     * Returns null if no invite exists for [groupId].
     */
    public suspend fun inviteStatusSentence(groupId: String): String? = null

    /** The name this device knows [deviceId] by (paired or discovered), or null: the invite dialog's "Invited by". */
    public fun inviterDisplayName(deviceId: String): String? = null

    /**
     * Retries dialing address hints for all pending invites (e.g. after a network change).
     */
    public suspend fun retryPendingInviteHints(): Unit = Unit

    /**
     * Returns pending join requests for [groupId] awaiting admin decision (GM-10).
     */
    public suspend fun getPendingJoinRequests(groupId: String): List<FlashGroupJoinRequestUi> = emptyList()

    /**
     * Cancels / abandons a pending invite for [groupId] (GM-10).
     */
    public suspend fun cancelPendingInvite(groupId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Adds trusted peers to an existing group. */
    public suspend fun addGroupMembers(groupId: String, memberIds: Set<String>): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Leaves a group and persists a tombstone so stale add frames cannot silently rejoin it. */
    public suspend fun leaveGroup(groupId: String): FlashResult<Unit> = leaveGroup(groupId, null)

    /**
     * ADR-063: Leaves a group, optionally promoting [successorId] to admin before leaving if the owner.
     */
    public suspend fun leaveGroup(groupId: String, successorId: String? = null): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * ADR-063: Promotes an active member to admin in a v2 group. Owner-only.
     */
    public suspend fun promoteAdmin(groupId: String, deviceId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * ADR-063: Demotes an active admin to regular member in a v2 group. Owner-only.
     */
    public suspend fun demoteAdmin(groupId: String, deviceId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * ADR-044 V2 / ADR-063: the owner or admin of a v2 group removes [deviceId]. Fails for unauthorized callers;
     * lightweight implementations decline.
     */
    public suspend fun removeGroupMember(groupId: String, deviceId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * GM-6: Rotates the group secret ("Change group code"), invalidating prior invites while keeping
     * existing members unaffected. Owner or admin action.
     */
    public suspend fun changeGroupCode(groupId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * GM-9: Gets the current signed group settings for [groupId].
     */
    public suspend fun getGroupSettings(groupId: String): GroupSettings =
        GroupSettings.defaults(groupId)

    /**
     * GM-9: Updates group settings for [groupId] and distributes the updated bundle to active members.
     * Owner or admin action.
     */
    public suspend fun updateGroupSettings(
        groupId: String,
        joinPolicy: String? = null,
        inviteSharers: String? = null,
        maxMembers: Int? = null,
        swarmServing: Boolean? = null,
        membersMayAdd: Boolean? = null,
    ): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * GM-9: Gets device-local preferences for [groupId].
     */
    public suspend fun getGroupLocalPreferences(groupId: String): GroupLocalPreferences =
        GroupLocalPreferences.defaults(groupId)

    /**
     * GM-9: Updates device-local preferences for [groupId].
     */
    public suspend fun updateGroupLocalPreferences(
        groupId: String,
        serveToGroup: Boolean? = null,
        serveWifiOnly: Boolean? = null,
        batteryThresholdPercent: Int? = null,
        keepAvailableDays: Int? = null,
        autoAcceptSizeBytes: Long? = null,
    ): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Real roster for the currently requested group; lightweight implementations remain empty. */
    public suspend fun groupMembers(groupId: String): List<FlashGroupMemberUi> = emptyList()

    /**
     * Option D (GO-23): creates a new group owned by this device containing all active, paired members
     * of [groupId] (excluding the previous owner or departed members). The old group remains intact as
     * historical record. Returns the new group ID on success.
     */
    public suspend fun continueInNewGroup(groupId: String): FlashResult<String> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /**
     * Message Info (UI-051): who has read, received or not yet received one group message this device sent, observed
     * live while the sheet is open. Null when the message is not one of this device's own group messages (a direct
     * message's info is built by the screen from its bubble status) or does not exist. The default declines.
     */
    public fun observeMessageInfo(messageId: String): Flow<FlashMessageInfoUi?> = flowOf(null)

    /**
     * Legacy group-media hook retained for source compatibility. It cannot provide the complete
     * identity tuple, so the safe default declines the announcement.
     */
    public suspend fun beginGroupAttachment(
        groupId: String,
        recipientDeviceId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        wireFileId: String,
    ): Pair<String, String>? = null

    /**
     * Announces one recipient-specific group media transfer. The host owns every identity so the
     * intro and transfer FILE_START cannot diverge. Returns false when the recipient is ineligible
     * or the intro could not be sent. The default keeps lightweight implementations inert.
     */
    public suspend fun beginGroupAttachment(
        groupId: String,
        recipientDeviceId: String,
        messageId: String,
        transferId: String,
        wireFileId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): Boolean = false

    /**
     * Announces one recipient-specific group media transfer with optional swarm fields (SW-8).
     */
    public suspend fun beginGroupAttachment(
        groupId: String,
        recipientDeviceId: String,
        messageId: String,
        transferId: String,
        wireFileId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        root: String?,
        pieceSize: Int?,
        swarm: Int?,
        rootSig: String?,
    ): Boolean = beginGroupAttachment(
        groupId = groupId,
        recipientDeviceId = recipientDeviceId,
        messageId = messageId,
        transferId = transferId,
        wireFileId = wireFileId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
    )

    /** Optional listener invoked when a verified group media swarm announcement arrives (SW-8). */
    public var swarmAnnouncementListener: GroupSwarmAnnouncementListener?
        get() = null
        set(_) {}

    /** Optional hook invoked when a group message is deleted for everyone by origin (SW-9). */
    public var onGroupMessageDeletedForEveryone: ((groupId: String, messageId: String) -> Unit)?
        get() = null
        set(_) {}

    /** Returns true if [groupId] is a v2 signed group. */
    public fun isV2Group(groupId: String): Boolean =
        com.transfer.flash.core.messaging.protocol.GroupPolicy.isV2GroupId(groupId)

    /**
     * Checks if [peerId] has proved knowledge of the group secret in this live session (GM-3).
     * Used only for join requests and roster preview (GINV-2). Grants NO chat, call, or file traffic.
     */
    public fun hasProvedGroup(peerId: String, groupId: String): Boolean = false

    /**
     * Initiates mutual proof of group secret knowledge with [peerId] for [groupId] at [epoch] (GM-3).
     */
    public suspend fun initiateGroupProof(
        peerId: String,
        groupId: String,
        epoch: Long,
    ): com.transfer.flash.core.messaging.group.GroupProofResult =
        com.transfer.flash.core.messaging.group.GroupProofResult.UNSUPPORTED

    /**
     * Full-history global search (#12): conversation ids that have at least one non-tombstoned
     * message whose body contains [query] (case-insensitive). Empty for a blank query. Lets the
     * chat list surface a thread even when the match is buried deep in history — not just when it
     * appears in the title or the latest-message preview. Default returns nothing for
     * lightweight/sample implementations; the Room-backed repository overrides it.
     */
    public suspend fun searchMessageBodies(query: String): Set<String> = emptySet()

    /**
     * In-conversation content search: case-insensitive substring match over message
     * bodies within [conversationId]. Returns matching message IDs.
     */
    public suspend fun searchConversationMessages(
        conversationId: String,
        query: String,
        limit: Int = 100,
    ): List<String> = emptyList()

    /**
     * Send a reply/quote (#8). [replyToId] is the quoted message's local id and [replyToPreview] a
     * short snapshot of its text, both carried on the wire so the peer renders the quote. Default
     * no-op keeps lightweight/sample implementations compiling; the Room-backed repo overrides it.
     */
    public fun sendReply(text: String, replyToId: String, replyToPreview: String) {}

    /**
     * Persist the current unsent composer text for the active conversation (#9), so it survives
     * navigation and process death and is restored via [FlashConversationUiState.draftText]. A blank
     * text clears the draft. Default no-op for lightweight/sample implementations.
     */
    public fun saveDraft(text: String) {}

    /**
     * Pin or unpin a message of the active conversation, on this device only: nothing is sent to the peer or the
     * group. Pins survive leaving the chat and a restart and are exposed through
     * [FlashConversationUiState.pinnedMessageIds]. Default no-op for lightweight/sample implementations.
     */
    public fun setMessagePinned(messageId: String, pinned: Boolean) {}

    /**
     * Toggle the local user's [emoji] reaction on a message (#7). Persists the aggregated reaction
     * and broadcasts the delta to the peer. Default no-op for lightweight/sample implementations.
     */
    public fun toggleReaction(messageId: String, emoji: String) {}

    /**
     * Broadcast the local user's typing state for the active conversation (#11). Ephemeral — never
     * persisted. Default no-op for lightweight/sample implementations.
     */
    public fun setTyping(isTyping: Boolean) {}

    public fun openAttachmentPicker()

    /**
     * Write a local chat message row for an outbound attachment so it appears inline in the
     * conversation (image thumbnail / video play button / file card) with live progress joined
     * from the transfer layer via [transferId] (B4). Default no-op keeps lightweight/sample
     * implementations compiling; the Room-backed repository overrides it.
     */
    public fun sendAttachment(
        conversationId: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        localPath: String?,
        /** B9: voice-note duration; 0 for non-audio attachments. */
        voiceDurationMs: Long = 0L,
        /** B9: captured waveform (0..100 samples) rendered by the playback card. */
        voiceAmplitudes: List<Int> = emptyList(),
    ) {}
    /**
     * Writes the sender's single group attachment row under the shared [messageId]. The transfer
     * identity remains separate because each recipient receives its own binary session.
     */
    public fun sendGroupAttachment(
        conversationId: String,
        messageId: String,
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        localPath: String?,
        voiceDurationMs: Long = 0L,
        voiceAmplitudes: List<Int> = emptyList(),
    ) {
        sendAttachment(
            conversationId, transferId, fileName, mimeType, sizeBytes, localPath,
            voiceDurationMs, voiceAmplitudes,
        )
    }

    /**
     * The swarm offer of a group file this device is the origin of ([root], [pieceSize], the origin's signature
     * [rootSig]), recorded on the sender's row before [sendGroupAttachment] even when no member was offered the file
     * as a swarm (none connected yet, or none advertises "sw1"). A member that connects later is then given the offer
     * by catch-up and can pull the file from the swarm (ERROR-117). Default no-op for lightweight/sample implementations.
     */
    public fun recordSwarmOffer(messageId: String, root: String, pieceSize: Int, rootSig: String) {}

    /** Returns active recipient transfer IDs associated with an outbound group message id. */
    public fun getRecipientTransferIds(messageId: String): Set<String> = emptySet()

    public fun enterListSelectionMode(conversationId: String)
    public fun toggleListSelection(conversationId: String)
    public fun clearListSelection()
    public fun archiveConversation(conversationId: String)

    /** Tombstone a single message so it disappears from the conversation. Default no-op keeps
     *  lightweight/sample implementations compiling; the Room-backed repository overrides it. */
    public fun deleteMessage(localId: String) {}

    /** Tombstone several messages locally (conversation multi-select delete). */
    public fun deleteMessages(localIds: Set<String>) {
        localIds.forEach { deleteMessage(it) }
    }

    /**
     * Tombstone one locally authored message and ask every eligible recipient to tombstone it too.
     * Kept separate from [deleteMessage] so UI can explicitly choose local versus shared deletion.
     */
    public fun deleteMessageForEveryone(localId: String) {}

    // Chat-list selection-mode bulk actions (UI-013). Default no-ops keep lightweight/sample
    // implementations compiling; the Room-backed repository overrides them.
    /** Hard-delete the selected conversations and their messages. */
    public fun deleteConversations(ids: Set<String>) {}

    /** Pin or unpin the selected conversations. */
    public fun setConversationsPinned(ids: Set<String>, pinned: Boolean) {}

    /** Mute or unmute the selected conversations. */
    public fun setConversationsMuted(ids: Set<String>, muted: Boolean) {}

    /** Mark one conversation unread by clearing its read cursor. */
    public fun markConversationUnread(conversationId: String) {}

    /** Mark the selected conversations read (clears their unread badge). */
    public fun markConversationsRead(ids: Set<String>) {}

    /** Archive the selected conversations. Defaults to archiving each individually. */
    public fun archiveConversations(ids: Set<String>) {
        ids.forEach { archiveConversation(it) }
    }

    /** Unarchive a single conversation. */
    public fun unarchiveConversation(conversationId: String) {}

    /** Unarchive the selected conversations. Defaults to unarchiving each individually. */
    public fun unarchiveConversations(ids: Set<String>) {
        ids.forEach { unarchiveConversation(it) }
    }

    /** Updates the local display name used for outbound chat messages and signed groups. */
    public suspend fun updateLocalDisplayName(newName: String) {}
}
