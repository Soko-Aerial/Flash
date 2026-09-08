package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [SampleFlashChatRepository], the one file this module's KMP conversion (Phase 11) changed:
 * its two JVM wall-clock calls became [SystemTimeSource].nowMs() so the file could live in
 * live in `commonMain` without violating CONVENTIONS.md R6.
 *
 * This suite lives in `commonTest` on purpose: it is the only thing in this module that executes
 * on BOTH the Android host-test JVM and the desktop `jvm()` target, which is what CONVENTIONS.md
 * R3.1 requires of a converted module — `jvmTest` running zero tests would mean the desktop target
 * is compiled but unproven. The two clock assertions below are the ones that matter: they prove
 * the replacement behaves like the call it replaced *on each platform's actual*, rather than only
 * on the one platform that used to run these tests.
 *
 * Items are always located by `id`, never by index: `sortedChatListItems` reorders the list on
 * every preview update, which is precisely what test 3 exercises.
 */
internal class SampleFlashChatRepositoryTest {

    private companion object {
        const val CONV = "conv-alex"
        const val UNKNOWN = "conv-does-not-exist"
    }

    private fun itemById(repo: SampleFlashChatRepository, id: String) =
        repo.chatListState.value.items.firstOrNull { it.id == id }

    @Test
    fun sendText_appends_a_mine_message_to_the_open_conversation() {
        val repo = SampleFlashChatRepository()
        repo.openConversation(CONV)
        val before = repo.conversationState.value.messages.size

        repo.sendText("  ship it  ")

        val messages = repo.conversationState.value.messages
        assertEquals(before + 1, messages.size)
        val last = messages.last()
        assertEquals("ship it", last.text, "sendText must trim before storing")
        assertTrue(last.isMine)
        assertEquals("Now", last.timeLabel)
    }

    @Test
    fun sendText_assigns_a_local_id_from_the_shared_clock() {
        val repo = SampleFlashChatRepository()
        repo.openConversation(CONV)

        val before = SystemTimeSource.nowMs()
        repo.sendText("hello")
        val after = SystemTimeSource.nowMs()

        val id = repo.conversationState.value.messages.last().id
        assertTrue(id.startsWith("local-"), "unexpected id shape: $id")
        val stamp = id.removePrefix("local-").toLongOrNull()
        assertNotNull(stamp, "id suffix must be an epoch-millis Long, was: $id")
        assertTrue(stamp in before..after, "stamp $stamp outside [$before, $after]")
    }

    @Test
    fun sendText_refreshes_the_list_preview_sortOrder_from_the_shared_clock() {
        val repo = SampleFlashChatRepository()
        repo.openConversation(CONV)
        // Sample items carry small hand-written ordinals (conv-alex is 4), not timestamps — so an
        // epoch-millis value after the send is unambiguous evidence the clock was read.
        assertEquals(4L, itemById(repo, CONV)?.sortOrder)

        val before = SystemTimeSource.nowMs()
        repo.sendText("ship it")
        val after = SystemTimeSource.nowMs()

        val item = assertNotNull(itemById(repo, CONV))
        assertTrue(item.sortOrder in before..after, "sortOrder ${item.sortOrder} outside window")
        assertEquals("ship it", item.previewText)
        assertEquals("Now", item.timestamp)
        assertEquals(0, item.unreadCount)
        assertFalse(item.isTyping, "conv-alex starts with isTyping = true; sending must clear it")
    }

    @Test
    fun sendText_ignores_blank_text() {
        val repo = SampleFlashChatRepository()
        repo.openConversation(CONV)
        val before = repo.conversationState.value

        repo.sendText("")
        repo.sendText("   \t ")

        assertSame(before, repo.conversationState.value)
        assertEquals(4L, itemById(repo, CONV)?.sortOrder, "a blank send must not touch the clock")
    }

    @Test
    fun sendText_without_an_open_conversation_is_a_no_op() {
        val repo = SampleFlashChatRepository()
        val before = repo.conversationState.value

        repo.sendText("nobody is listening")

        assertSame(before, repo.conversationState.value)
    }

    @Test
    fun openConversation_with_an_unknown_id_is_ignored() {
        val repo = SampleFlashChatRepository()
        val before = repo.conversationState.value

        repo.openConversation(UNKNOWN)

        assertSame(before, repo.conversationState.value)
        // No conversation was opened, so a send still goes nowhere.
        repo.sendText("hello")
        assertSame(before, repo.conversationState.value)
    }

    @Test
    fun archiveConversation_drops_the_item_and_clears_the_active_conversation() {
        val repo = SampleFlashChatRepository()
        repo.openConversation(CONV)
        assertNotNull(itemById(repo, CONV))

        repo.archiveConversation(CONV)

        assertNull(itemById(repo, CONV))
        val after = repo.conversationState.value
        repo.sendText("still here?")
        assertSame(after, repo.conversationState.value, "archiving the active conv clears it")
    }

    @Test
    fun toggleListSelection_adds_then_removes_and_clearListSelection_resets_the_mode() {
        val repo = SampleFlashChatRepository()

        repo.enterListSelectionMode(CONV)
        assertTrue(repo.chatListState.value.selectionMode)
        assertEquals(setOf(CONV), repo.chatListState.value.selectedIds)

        repo.toggleListSelection(CONV)
        assertEquals(emptySet(), repo.chatListState.value.selectedIds)
        assertTrue(repo.chatListState.value.selectionMode, "mode survives an empty selection")

        repo.toggleListSelection("conv-design")
        assertEquals(setOf("conv-design"), repo.chatListState.value.selectedIds)

        repo.clearListSelection()
        assertFalse(repo.chatListState.value.selectionMode)
        assertEquals(emptySet(), repo.chatListState.value.selectedIds)
    }
}
