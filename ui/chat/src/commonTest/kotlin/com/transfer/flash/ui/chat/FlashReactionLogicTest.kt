package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashReactionLogicTest {

    private val sampleMessage = FlashMessageUi(
        id = "msg-1",
        senderName = "Alex",
        senderInitials = "A",
        timeLabel = "10:00 AM",
        text = "Hello Flash!",
        isMine = false,
        reactions = emptyList(),
    )

    @Test
    fun `toggle reaction adds new reaction to message without reactions`() {
        val initial = listOf(sampleMessage)
        val updated = toggleMessageReaction(initial, "msg-1", "❤️")

        assertEquals(1, updated[0].reactions.size)
        val reaction = updated[0].reactions[0]
        assertEquals("❤️", reaction.emoji)
        assertEquals(1, reaction.count)
        assertTrue(reaction.isSelfReacted)
    }

    @Test
    fun `toggle reaction increments count and sets selfReacted when peer reacted first`() {
        val messageWithPeerReaction = sampleMessage.copy(
            reactions = listOf(
                FlashReaction(emoji = "👍", count = 2, isSelfReacted = false),
            ),
        )
        val initial = listOf(messageWithPeerReaction)
        val updated = toggleMessageReaction(initial, "msg-1", "👍")

        assertEquals(1, updated[0].reactions.size)
        val reaction = updated[0].reactions[0]
        assertEquals("👍", reaction.emoji)
        assertEquals(3, reaction.count)
        assertTrue(reaction.isSelfReacted)
    }

    @Test
    fun `toggle reaction decrements count and unsets selfReacted when count greater than 1`() {
        val messageWithSelfReaction = sampleMessage.copy(
            reactions = listOf(
                FlashReaction(emoji = "🔥", count = 3, isSelfReacted = true),
            ),
        )
        val initial = listOf(messageWithSelfReaction)
        val updated = toggleMessageReaction(initial, "msg-1", "🔥")

        assertEquals(1, updated[0].reactions.size)
        val reaction = updated[0].reactions[0]
        assertEquals("🔥", reaction.emoji)
        assertEquals(2, reaction.count)
        assertFalse(reaction.isSelfReacted)
    }

    @Test
    fun `toggle reaction removes reaction completely when only self reacted`() {
        val messageWithSoloReaction = sampleMessage.copy(
            reactions = listOf(
                FlashReaction(emoji = "🎉", count = 1, isSelfReacted = true),
            ),
        )
        val initial = listOf(messageWithSoloReaction)
        val updated = toggleMessageReaction(initial, "msg-1", "🎉")

        assertTrue(updated[0].reactions.isEmpty())
    }

    @Test
    fun `toggle reaction preserves other reactions on the message`() {
        val messageWithMultiple = sampleMessage.copy(
            reactions = listOf(
                FlashReaction(emoji = "❤️", count = 2, isSelfReacted = false),
                FlashReaction(emoji = "👍", count = 1, isSelfReacted = true),
            ),
        )
        val initial = listOf(messageWithMultiple)
        // Untoggle thumbs up
        val updated = toggleMessageReaction(initial, "msg-1", "👍")

        assertEquals(1, updated[0].reactions.size)
        assertEquals("❤️", updated[0].reactions[0].emoji)
        assertEquals(2, updated[0].reactions[0].count)
    }

    @Test
    fun `toggle reaction only modifies target message and leaves other messages intact`() {
        val msg1 = sampleMessage.copy(id = "msg-1")
        val msg2 = sampleMessage.copy(id = "msg-2", text = "Second message")
        val initial = listOf(msg1, msg2)

        val updated = toggleMessageReaction(initial, "msg-1", "🚀")

        assertEquals(1, updated[0].reactions.size)
        assertEquals("🚀", updated[0].reactions[0].emoji)
        assertTrue(updated[1].reactions.isEmpty())
    }
}
