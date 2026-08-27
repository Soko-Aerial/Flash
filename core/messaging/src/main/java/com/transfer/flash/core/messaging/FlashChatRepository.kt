package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import com.transfer.flash.core.messaging.util.sampleDirectChatHeader
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.core.messaging.util.sampleFlashConversationState
import com.transfer.flash.core.messaging.util.sortedChatListItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

public class SampleFlashChatRepository(
    initialListState: FlashChatListUiState = sampleFlashChatListState(),
    conversations: Map<String, FlashConversationUiState> = defaultSampleConversations(),
) : FlashChatRepository {
    private val conversationMap = conversations.toMutableMap()
    private val _chatListState = MutableStateFlow(initialListState)
    override val chatListState: StateFlow<FlashChatListUiState> = _chatListState.asStateFlow()

    private val _conversationState = MutableStateFlow(conversationMap.getValue("conv-false-school"))
    override val conversationState: StateFlow<FlashConversationUiState> = _conversationState.asStateFlow()

    private var activeConversationId: String? = null

    override fun openConversation(conversationId: String) {
        conversationMap[conversationId]?.let { state ->
            activeConversationId = conversationId
            _conversationState.value = state
        }
    }

    override fun closeConversation() {
        activeConversationId = null
    }

    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val conversationId = activeConversationId ?: return
        val current = _conversationState.value
        val newMessage = FlashMessageUi(
            id = "local-${System.currentTimeMillis()}",
            senderName = "You",
            senderInitials = "YO",
            timeLabel = "Now",
            text = trimmed,
            isMine = true,
        )
        val updatedConversation = current.copy(
            messages = computeMessageGroupPositions(current.messages + newMessage),
        )
        _conversationState.value = updatedConversation
        conversationMap[conversationId] = updatedConversation
        updateListPreview(conversationId, previewText = trimmed, timestamp = "Now", unreadCount = 0)
    }

    override fun openAttachmentPicker() {
        // Flash Transfer owns attachment selection.
    }

    override fun enterListSelectionMode(conversationId: String) {
        _chatListState.update {
            it.copy(selectionMode = true, selectedIds = setOf(conversationId))
        }
    }

    override fun toggleListSelection(conversationId: String) {
        _chatListState.update { state ->
            val updated = state.selectedIds.toMutableSet()
            if (conversationId in updated) {
                updated.remove(conversationId)
            } else {
                updated.add(conversationId)
            }
            state.copy(
                selectedIds = updated,
                selectionMode = updated.isNotEmpty() || state.selectionMode,
            )
        }
    }

    override fun clearListSelection() {
        _chatListState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    override fun archiveConversation(conversationId: String) {
        _chatListState.update { state ->
            state.copy(items = sortedChatListItems(state.items.filter { it.id != conversationId }))
        }
        conversationMap.remove(conversationId)
        if (activeConversationId == conversationId) {
            activeConversationId = null
        }
    }

    private fun updateListPreview(
        conversationId: String,
        previewText: String,
        timestamp: String,
        unreadCount: Int,
    ) {
        _chatListState.update { state ->
            val updatedItems = state.items.map { item ->
                if (item.id == conversationId) {
                    item.copy(
                        previewText = previewText,
                        timestamp = timestamp,
                        unreadCount = unreadCount,
                        isTyping = false,
                        sortOrder = System.currentTimeMillis(),
                    )
                } else {
                    item
                }
            }
            state.copy(items = sortedChatListItems(updatedItems))
        }
    }
}

private fun defaultSampleConversations(): Map<String, FlashConversationUiState> {
    val groupConversation = sampleFlashConversationState()
    val directConversation = groupConversation.copy(
        header = sampleDirectChatHeader(),
        messages = computeMessageGroupPositions(
            listOf(
                FlashMessageUi(
                    id = "d1",
                    senderName = "Alex Chen",
                    senderInitials = "AC",
                    timeLabel = "11:48 PM",
                    text = "Can you send the build over Wi‑Fi Direct?",
                    isMine = false,
                ),
                FlashMessageUi(
                    id = "d2",
                    senderName = "You",
                    senderInitials = "YO",
                    timeLabel = "11:50 PM",
                    text = "Starting LAN discovery now.",
                    isMine = true,
                ),
            ),
        ),
    )
    return mapOf(
        "conv-false-school" to groupConversation,
        "conv-alex" to directConversation,
        "conv-design" to groupConversation.copy(
            header = groupConversation.header.copy(
                title = "Design Team",
                avatarInitials = "DT",
                memberSummary = "8 members, 3 online",
            ),
        ),
        "conv-transfer" to directConversation.copy(
            header = sampleDirectChatHeader().copy(
                title = "Flash Transfer",
                avatarInitials = "FT",
                showCallActions = false,
            ),
        ),
        "conv-offline" to directConversation.copy(
            header = sampleDirectChatHeader().copy(
                title = "Jordan Lee",
                avatarInitials = "JL",
                presence = FlashPeerPresence.Offline,
            ),
        ),
    )
}
