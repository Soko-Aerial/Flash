package com.transfer.flash.core.messaging.util

import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for FlashMessagingUtils, focusing on accessibility TalkBack descriptions (Plan B Phase B3).
 */
class FlashMessagingUtilsTest {

    private fun sampleItem(
        title: String = "Alice",
        previewText: String = "Hello world",
        timestamp: String = "10:30 AM",
        unreadCount: Int = 0,
        isTyping: Boolean = false,
        isColleague: Boolean = false,
        orgName: String? = null,
    ) = FlashChatListItemUi(
        id = "conv-1",
        title = title,
        avatarInitials = "A",
        previewText = previewText,
        timestamp = timestamp,
        unreadCount = unreadCount,
        isTyping = isTyping,
        isColleague = isColleague,
        orgName = orgName,
    )

    @Test
    fun `ORGT_U36_chatListRowContentDescription_when_isColleague_is_false`() {
        val nonColleague = sampleItem(isColleague = false, unreadCount = 0)
        val description = chatListRowContentDescription(nonColleague)

        assertEquals("Alice, Hello world, 10:30 AM", description)
        assertFalse(description.contains("Colleague verified"), "Non-colleague must not announce colleague verification")
    }

    @Test
    fun `ORGT_U36_chatListRowContentDescription_when_isColleague_is_true`() {
        val colleague = sampleItem(isColleague = true, unreadCount = 0)
        val description = chatListRowContentDescription(colleague)

        assertEquals("Alice, Colleague verified by the company server, Hello world, 10:30 AM", description)
        assertTrue(description.contains(", Colleague verified by the company server"))
    }

    @Test
    fun `ORGT_U36_chatListRowContentDescription_with_unread_and_typing_states`() {
        // Colleague with unread count
        val unreadColleague = sampleItem(isColleague = true, unreadCount = 5)
        assertEquals(
            "Alice, Colleague verified by the company server, Hello world, 10:30 AM, 5 unread",
            chatListRowContentDescription(unreadColleague),
        )

        // Colleague currently typing
        val typingColleague = sampleItem(isColleague = true, isTyping = true)
        assertEquals(
            "Alice, Colleague verified by the company server, typing, 10:30 AM",
            chatListRowContentDescription(typingColleague),
        )

        // Non-colleague with unread count
        val unreadNonColleague = sampleItem(isColleague = false, unreadCount = 2)
        assertEquals(
            "Alice, Hello world, 10:30 AM, 2 unread",
            chatListRowContentDescription(unreadNonColleague),
        )
    }
}
