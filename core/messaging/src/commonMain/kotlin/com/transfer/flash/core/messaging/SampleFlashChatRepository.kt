package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.time.SystemTimeSource
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
 * In-memory [FlashChatRepository] backed by preview/sample data.
 *
 * This remains production-visible so Compose previews and external sample consumers can instantiate
 * it, but the production app boot path binds [EmptyFlashChatRepository] until the real repository is
 * ready. Do not use this repository as a runtime fallback in the app.
 */
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
            id = "local-${SystemTimeSource.nowMs()}",
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
                        sortOrder = SystemTimeSource.nowMs(),
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
