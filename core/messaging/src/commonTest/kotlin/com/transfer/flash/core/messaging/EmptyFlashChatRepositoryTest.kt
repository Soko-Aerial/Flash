package com.transfer.flash.core.messaging

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ERROR-034 regression guard. The shell binds its chat tab to [EmptyFlashChatRepository] for the
 * window before the transport stack has booted; if that repository ever starts carrying content the
 * "placeholder chats that show and then vanish" bug is back.
 */
class EmptyFlashChatRepositoryTest {

    @Test
    fun `chat list is empty and not in selection mode`() {
        val state = EmptyFlashChatRepository.chatListState.value
        assertTrue(state.items.isEmpty(), "pre-boot chat list must carry no rows")
        assertFalse(state.selectionMode)
        assertTrue(state.selectedIds.isEmpty())
        // The distinction that keeps the first-run panel honest: this repository has no rows AND
        // has not loaded, so a screen bound to it must render loading, not "no conversations yet".
        assertFalse(state.hasLoaded, "the pre-boot stand-in has loaded nothing")
    }

    @Test
    fun `conversation is empty and offers no call actions`() {
        val state = EmptyFlashChatRepository.conversationState.value
        assertTrue(state.messages.isEmpty())
        assertEquals("", state.header.title)
        assertEquals("", state.header.avatarInitials)
        assertEquals("", state.draftText)
        assertFalse(state.header.showCallActions, "there is no peer to call before the stack is up")
    }

    @Test
    fun `every command is a no-op so no fabricated state can appear`() {
        with(EmptyFlashChatRepository) {
            openConversation("conv-anything")
            sendText("hello")
            sendReply("hello", "m1", "quoted")
            saveDraft("draft")
            toggleReaction("m1", "👍")
            setTyping(true)
            openAttachmentPicker()
            sendAttachment(
                conversationId = "conv-anything",
                transferId = "t1",
                fileName = "a.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 1L,
                localPath = null,
            )
            enterListSelectionMode("conv-anything")
            toggleListSelection("conv-anything")
            deleteMessage("m1")
            deleteMessages(setOf("m1", "m2"))
            deleteConversations(setOf("conv-anything"))
            setConversationsPinned(setOf("conv-anything"), true)
            setConversationsMuted(setOf("conv-anything"), true)
            markConversationsRead(setOf("conv-anything"))
            archiveConversation("conv-anything")
            archiveConversations(setOf("conv-anything"))
            clearListSelection()
            closeConversation()
        }

        val list = EmptyFlashChatRepository.chatListState.value
        assertTrue(list.items.isEmpty(), "no command may materialise a row")
        assertFalse(list.selectionMode)
        assertTrue(list.selectedIds.isEmpty())
        assertTrue(
            EmptyFlashChatRepository.conversationState.value.messages.isEmpty(),
            "no command may materialise a message",
        )
    }

    @Test
    fun `searchMessageBodies finds nothing`() {
        val hits = runBlocking { EmptyFlashChatRepository.searchMessageBodies("anything") }
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `the empty repository is deliberately not the sample repository`() {
        // SampleFlashChatRepository remains production-visible for previews, but the app boot path
        // deliberately binds EmptyFlashChatRepository until the real repository is ready.
        assertTrue(SampleFlashChatRepository().chatListState.value.items.isNotEmpty())
        assertTrue(EmptyFlashChatRepository.chatListState.value.items.isEmpty())
    }
}
