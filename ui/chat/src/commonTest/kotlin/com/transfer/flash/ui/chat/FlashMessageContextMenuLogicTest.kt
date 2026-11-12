package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashGroupMemberUi
import com.transfer.flash.core.messaging.model.FlashReaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashMessageContextMenuLogicTest {
    @Test
    fun deleteForEveryoneIsVisibleOnlyForOwnMessages() {
        assertTrue("Delete for everyone" in messageActionLabels(isMine = true))
        assertFalse("Delete for everyone" in messageActionLabels(isMine = false))
        assertTrue("Delete" in messageActionLabels(isMine = true))
        assertTrue("Delete" in messageActionLabels(isMine = false))
    }

    @Test
    fun stopSendingIsOfferedOnlyWhenTheCallerHasAPendingRadioRetry() {
        assertFalse("Stop sending" in messageActionLabels(isMine = true))
        assertTrue("Stop sending" in messageActionLabels(isMine = true, canStopRadioRetry = true))
    }

    @Test
    fun copyTextIsHiddenWhenMessageTextIsBlank() {
        assertTrue("Copy Text" in messageActionLabels(isMine = true, hasText = true))
        assertFalse("Copy Text" in messageActionLabels(isMine = true, hasText = false))
    }

    @Test
    fun extendedReactionPaletteIsDistinctAndNonEmpty() {
        val emojis = FlashReactionPickerMath.EXTENDED_REACTIONS
        assertTrue(emojis.size >= 24)
        assertEquals(emojis.distinct().size, emojis.size)
    }

    @Test
    fun reactionDetailRowsResolveSelfAndMembersAndFilterByEmoji() {
        val reactions = listOf(
            FlashReaction(emoji = "❤️", count = 2, isSelfReacted = true, reactorIds = listOf("m1")),
            FlashReaction(emoji = "🔥", count = 1, isSelfReacted = false, reactorIds = listOf("m2")),
        )
        val members = listOf(
            FlashGroupMemberUi(id = "m1", name = "Alice", initials = "AL", isOnline = true),
            FlashGroupMemberUi(id = "m2", name = "Bob", initials = "BO", isOnline = false),
        )

        val allRows = FlashReactionPickerMath.buildReactorRows(
            reactions = reactions,
            selectedEmoji = null,
            members = members,
            peerTitle = "Group",
            isGroup = true,
        )
        assertEquals(3, allRows.size)
        assertEquals("You", allRows[0].displayName)
        assertTrue(allRows[0].isSelf)
        assertEquals("Alice", allRows[1].displayName)
        assertEquals("Bob", allRows[2].displayName)

        val fireRows = FlashReactionPickerMath.buildReactorRows(
            reactions = reactions,
            selectedEmoji = "🔥",
            members = members,
            peerTitle = "Group",
            isGroup = true,
        )
        assertEquals(1, fireRows.size)
        assertEquals("Bob", fireRows[0].displayName)
    }

    @Test
    fun selfReactedDoesNotDuplicateAsPeerInOneToOneOrGroupChats() {
        // RealFlashChatRepository stores localDeviceId inside reactorIds alongside isSelfReacted = true.
        val selfOnlyOneToOne = listOf(
            FlashReaction(emoji = "❤️", count = 1, isSelfReacted = true, reactorIds = listOf("self-dev")),
        )
        val rowsWithSelfId = FlashReactionPickerMath.buildReactorRows(
            reactions = selfOnlyOneToOne,
            selectedEmoji = null,
            members = emptyList(),
            peerTitle = "Alex Rivera",
            isGroup = false,
            selfDeviceId = "self-dev",
        )
        assertEquals(1, rowsWithSelfId.size)
        assertEquals("You", rowsWithSelfId[0].displayName)
        assertTrue(rowsWithSelfId[0].isSelf)

        // Even when selfDeviceId is omitted, 1:1 count=1 must not emit a duplicate peerTitle row.
        val rowsWithoutSelfId = FlashReactionPickerMath.buildReactorRows(
            reactions = selfOnlyOneToOne,
            selectedEmoji = null,
            members = emptyList(),
            peerTitle = "Alex Rivera",
            isGroup = false,
        )
        assertEquals(1, rowsWithoutSelfId.size)
        assertEquals("You", rowsWithoutSelfId[0].displayName)

        // Both self and peer reacted in 1:1, and self + member in a group with selfDeviceId.
        val bothOneToOne = listOf(
            FlashReaction(emoji = "👍", count = 2, isSelfReacted = true, reactorIds = listOf("self-dev", "peer-dev")),
        )
        val bothOneToOneRows = FlashReactionPickerMath.buildReactorRows(
            reactions = bothOneToOne,
            selectedEmoji = null,
            members = emptyList(),
            peerTitle = "Alex Rivera",
            isGroup = false,
            selfDeviceId = "self-dev",
        )
        assertEquals(listOf("You", "Alex Rivera"), bothOneToOneRows.map { it.displayName })

        val groupReactions = listOf(
            FlashReaction(emoji = "🔥", count = 2, isSelfReacted = true, reactorIds = listOf("m1", "self-dev")),
        )
        val groupMembers = listOf(
            FlashGroupMemberUi(id = "self-dev", name = "Me", initials = "ME", isOnline = true),
            FlashGroupMemberUi(id = "m1", name = "Alice", initials = "AL", isOnline = true),
        )
        val groupRows = FlashReactionPickerMath.buildReactorRows(
            reactions = groupReactions,
            selectedEmoji = null,
            members = groupMembers,
            peerTitle = "Team",
            isGroup = true,
            selfDeviceId = "self-dev",
        )
        assertEquals(listOf("You", "Alice"), groupRows.map { it.displayName })
    }
}
