package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level messaging repository contract for conversation management and message exchange.
 */
public interface FlashChatRepository {
    public val chatListState: StateFlow<FlashChatListUiState>
    public val conversationState: StateFlow<FlashConversationUiState>
    public fun openConversation(conversationId: String)
    public fun closeConversation()
    public fun sendText(text: String)

    /** Creates an ad-hoc trusted group. Phase 1 allows at most six members including this device. */
    public suspend fun createGroup(name: String, memberIds: Set<String>): FlashResult<String> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Adds trusted peers to an existing group. */
    public suspend fun addGroupMembers(groupId: String, memberIds: Set<String>): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Leaves a group and persists a tombstone so stale add frames cannot silently rejoin it. */
    public suspend fun leaveGroup(groupId: String): FlashResult<Unit> =
        FlashResult.Failure(com.transfer.flash.core.common.result.FlashError.Unknown("Groups unavailable"))

    /** Real roster for the currently requested group; lightweight implementations remain empty. */
    public suspend fun groupMembers(groupId: String): List<FlashGroupMemberUi> = emptyList()

    /**
     * F4: announce a group media attachment to one recipient (FLASH_GMEDIA intro) and return the
     * (messageId, wireFileId) the host must pass to its per-member sendFile. Default null keeps
     * lightweight implementations inert; the Room-backed repository overrides it.
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
     * Full-history global search (#12): conversation ids that have at least one non-tombstoned
     * message whose body contains [query] (case-insensitive). Empty for a blank query. Lets the
     * chat list surface a thread even when the match is buried deep in history — not just when it
     * appears in the title or the latest-message preview. Default returns nothing for
     * lightweight/sample implementations; the Room-backed repository overrides it.
     */
    public suspend fun searchMessageBodies(query: String): Set<String> = emptySet()

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
    public fun enterListSelectionMode(conversationId: String)
    public fun toggleListSelection(conversationId: String)
    public fun clearListSelection()
    public fun archiveConversation(conversationId: String)

    /** Tombstone a single message so it disappears from the conversation. Default no-op keeps
     *  lightweight/sample implementations compiling; the Room-backed repository overrides it. */
    public fun deleteMessage(localId: String) {}

    /** Tombstone several messages at once (conversation multi-select delete). */
    public fun deleteMessages(localIds: Set<String>) {
        localIds.forEach { deleteMessage(it) }
    }

    // Chat-list selection-mode bulk actions (UI-013). Default no-ops keep lightweight/sample
    // implementations compiling; the Room-backed repository overrides them.
    /** Hard-delete the selected conversations and their messages. */
    public fun deleteConversations(ids: Set<String>) {}

    /** Pin or unpin the selected conversations. */
    public fun setConversationsPinned(ids: Set<String>, pinned: Boolean) {}

    /** Mute or unmute the selected conversations. */
    public fun setConversationsMuted(ids: Set<String>, muted: Boolean) {}

    /** Mark the selected conversations read (clears their unread badge). */
    public fun markConversationsRead(ids: Set<String>) {}

    /** Archive the selected conversations. Defaults to archiving each individually. */
    public fun archiveConversations(ids: Set<String>) {
        ids.forEach { archiveConversation(it) }
    }
}
