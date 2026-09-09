package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashChatHeaderUiState
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashConversationUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The honest stand-in for "no repository yet".
 *
 * A shell that has to render its chat tab before the transport stack has booted needs *a*
 * [FlashChatRepository] to bind to. Substituting `SampleFlashChatRepository` there — which is what
 * the app did until 2026-09-04 — makes the tab render fabricated conversations ("False School",
 * "Design Team", "Flash Transfer") that are then discarded the moment the real repository appears.
 * On a fast device the splash covers the whole window and nobody notices; on a slow one the splash's
 * 6-second ceiling expires first and the user watches invented threads appear and vanish.
 *
 * This implementation emits an empty list and an empty conversation and drops every command, so the
 * caller can report its *real* state (loading / empty / failed) through the screen's own
 * `isLoading` / `errorMessage` inputs instead of faking content. It is a stateless `object`: binding
 * to it costs one shared allocation for the process, not one per composition.
 *
 * Do not use it as a test double for behaviour — it asserts nothing. Use it only for the window
 * before a real repository exists.
 */
public object EmptyFlashChatRepository : FlashChatRepository {

    /**
     * A conversation with no title, no members and no messages. [FlashConversationUiState] is
     * non-nullable on the interface, so the pre-boot window needs a valid empty value rather than
     * `null`. Call actions are hidden — there is no peer to call.
     */
    public val emptyConversation: FlashConversationUiState = FlashConversationUiState(
        header = FlashChatHeaderUiState(
            title = "",
            avatarInitials = "",
            showCallActions = false,
        ),
        messages = emptyList(),
    )

    // hasLoaded stays false, which is the whole point: this repository has not loaded anything and
    // never will, so a screen bound to it renders "loading", not "you have no conversations".
    override val chatListState: StateFlow<FlashChatListUiState> =
        MutableStateFlow(FlashChatListUiState()).asStateFlow()

    override val conversationState: StateFlow<FlashConversationUiState> =
        MutableStateFlow(emptyConversation).asStateFlow()

    override fun openConversation(conversationId: String) {
        // No history to open. The shell gates navigation on the engine being ready.
    }

    override fun closeConversation() {
        // Nothing to close.
    }

    override fun sendText(text: String) {
        // Dropped deliberately: accepting a send here would lose the message silently once the real
        // repository takes over. The composer is not reachable before boot completes.
    }

    override fun openAttachmentPicker() {
        // Flash Transfer owns attachment selection, and it is not up yet.
    }

    override fun enterListSelectionMode(conversationId: String) {
        // There are no rows to select.
    }

    override fun toggleListSelection(conversationId: String) {
        // There are no rows to select.
    }

    override fun clearListSelection() {
        // Selection is always already clear.
    }

    override fun archiveConversation(conversationId: String) {
        // There is nothing to archive.
    }

    override fun unarchiveConversation(conversationId: String) {
        // There is nothing to unarchive.
    }
}
