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
 * In-memory [FlashChatRepository] over a fabricated roster (False School, Alex Chen, Design Team,
 * Jordan Lee …). Test-only, and deliberately so.
 *
 * ERROR-034: this class used to live in `src/main`, which is how it ended up bound as the shell's
 * pre-boot chat repository — "so the shell is never empty". It made the chat tab render invented
 * conversations that were discarded the moment the real repository appeared, which on a slow device
 * is visible as threads that show and then vanish. It has no production caller now, and living in
 * the test source set is what keeps it that way: a future accidental binding will not compile, and
 * none of it ships in the release APK. The pre-boot stand-in is [EmptyFlashChatRepository].
 *
 * The pure sample *data* (`sampleFlashChatListState`, `sampleFlashConversationState`, …) stays in
 * main because `@Preview` functions in `:ui:chat`'s main source set need it in both variants.
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
