package com.transfer.flash.core.messaging.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupMembershipStatusTextTest {

    @Test
    fun testTable83Sentences() {
        assertEquals("This invite is not valid. Ask for a new one.", GroupMembershipStatusText.INVALID_INVITE)
        assertEquals("You are already in Ada's Group.", GroupMembershipStatusText.alreadyMember("Ada's Group"))
        assertEquals("Waiting for a member of Ada's Group to be nearby.", GroupMembershipStatusText.waitingForMember("Ada's Group"))
        assertEquals("The device that answered is not the one that shared this invite.", GroupMembershipStatusText.IDENTITY_MISMATCH)
        assertEquals("This invite is no longer valid.", GroupMembershipStatusText.INVITE_NO_LONGER_VALID)
        assertEquals("This invite was replaced. Ask for a new one.", GroupMembershipStatusText.INVITE_REPLACED)
        assertEquals("Waiting for an admin of Ada's Group to approve.", GroupMembershipStatusText.waitingForAdmin("Ada's Group"))
        assertEquals("Your request to join Ada's Group was declined.", GroupMembershipStatusText.requestDeclined("Ada's Group"))
        assertEquals("Ada's Group is full.", GroupMembershipStatusText.groupFull("Ada's Group"))
        assertEquals("Update Flash on Pixel 7 to use invites.", GroupMembershipStatusText.updateRequired("Pixel 7"))
        assertEquals("Pixel 7 is known to this device under a different key.", GroupMembershipStatusText.pinConflict("Pixel 7"))
        assertEquals("Getting the new group code…", GroupMembershipStatusText.GETTING_NEW_GROUP_CODE)
        assertEquals("Getting the group code…", GroupMembershipStatusText.GETTING_GROUP_CODE)
        assertEquals("No admin of Ada's Group is available, so nobody can approve new members.", GroupMembershipStatusText.noAdminAvailable("Ada's Group"))
    }
}
