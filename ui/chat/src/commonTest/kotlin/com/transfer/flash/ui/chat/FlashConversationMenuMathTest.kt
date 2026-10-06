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

    @Test
    fun aDeviceThatIsOutOfTheGroupCanReadAndSearchButNotAddOrLeave() {
        val out = FlashConversationMenuMath.groupItems(canLeave = true, isMember = false)

        assertEquals(
            listOf(
                FlashConversationMenuItem.GROUP_INFO,
                FlashConversationMenuItem.SEARCH,
                FlashConversationMenuItem.MARK_UNREAD,
            ),
            out,
        )
        assertFalse(out.contains(FlashConversationMenuItem.ADD_MEMBERS))
        assertFalse(out.contains(FlashConversationMenuItem.LEAVE_GROUP))
    }

    @Test
    fun nonOwnerOfV2GroupCannotAddMembers() {
        val memberItems = FlashConversationMenuMath.groupItems(canLeave = true, isMember = true, canAddMembers = false)
        assertFalse(memberItems.contains(FlashConversationMenuItem.ADD_MEMBERS), "non-owner must not see Add members")
        assertTrue(memberItems.contains(FlashConversationMenuItem.GROUP_INFO))
        assertTrue(memberItems.contains(FlashConversationMenuItem.LEAVE_GROUP))
    }

    @Test
    fun continueInNewGroupOfferedWhenPermitted() {
        val continueItems = FlashConversationMenuMath.groupItems(
            canLeave = true,
            isMember = true,
            canAddMembers = false,
            canContinueInNewGroup = true,
        )
        assertTrue(continueItems.contains(FlashConversationMenuItem.CONTINUE_IN_NEW_GROUP))
        assertFalse(continueItems.contains(FlashConversationMenuItem.ADD_MEMBERS))
    }

    @Test
    fun groupSettingsAndInviteLinkOfferedWhenPermitted() {
        val v2Items = FlashConversationMenuMath.groupItems(
            canLeave = true,
            isMember = true,
            canShareInvite = true,
            isV2 = true,
        )
        assertTrue(v2Items.contains(FlashConversationMenuItem.GROUP_SETTINGS))
        assertTrue(v2Items.contains(FlashConversationMenuItem.INVITE_LINK))

        val legacyItems = FlashConversationMenuMath.groupItems(
            canLeave = true,
            isMember = true,
            canShareInvite = false,
            isV2 = false,
        )
        assertFalse(legacyItems.contains(FlashConversationMenuItem.GROUP_SETTINGS))
        assertFalse(legacyItems.contains(FlashConversationMenuItem.INVITE_LINK))
    }
}
