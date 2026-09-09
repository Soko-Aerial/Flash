package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashConversationMenuMathTest {
    @Test
    fun directMenuShowsProfileSearchAndClearTrustOnlyWhenPaired() {
        val trusted = FlashConversationMenuMath.directItems(canRevokeTrust = true)
        assertEquals(
            listOf(
                FlashConversationMenuItem.VIEW_PROFILE,
                FlashConversationMenuItem.SEARCH,
                FlashConversationMenuItem.REVOKE_TRUST,
                FlashConversationMenuItem.MARK_UNREAD,
                FlashConversationMenuItem.CLEAR_CONVERSATION,
            ),
            trusted,
        )

        val unpaired = FlashConversationMenuMath.directItems(canRevokeTrust = false)
        assertFalse(unpaired.contains(FlashConversationMenuItem.REVOKE_TRUST))
        assertTrue(unpaired.contains(FlashConversationMenuItem.MARK_UNREAD))
    }

    @Test
    fun groupMenuShowsInfoAddSearchAndLeaveOnlyWhenOthersRemain() {
        val group = FlashConversationMenuMath.groupItems(canLeave = true)
        assertEquals(
            listOf(
                FlashConversationMenuItem.GROUP_INFO,
                FlashConversationMenuItem.ADD_MEMBERS,
                FlashConversationMenuItem.SEARCH,
                FlashConversationMenuItem.MARK_UNREAD,
                FlashConversationMenuItem.LEAVE_GROUP,
            ),
            group,
        )

        // The last remaining device cannot leave the group pointless-less: hide Leave.
        val lastMember = FlashConversationMenuMath.groupItems(canLeave = false)
        assertFalse(lastMember.contains(FlashConversationMenuItem.LEAVE_GROUP))
        assertTrue(lastMember.contains(FlashConversationMenuItem.GROUP_INFO))
        assertTrue(lastMember.contains(FlashConversationMenuItem.MARK_UNREAD))
    }
}
