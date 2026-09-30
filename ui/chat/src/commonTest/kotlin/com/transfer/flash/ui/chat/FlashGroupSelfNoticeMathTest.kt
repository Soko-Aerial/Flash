package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashSelfMembership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FlashGroupSelfNoticeMathTest {
    @Test
    fun anActiveMemberGetsNoNotice() {
        assertNull(FlashGroupSelfNoticeMath.title(FlashSelfMembership.Active), "an active member keeps the composer")
        assertNull(FlashGroupSelfNoticeMath.detail(FlashSelfMembership.Active))
    }

    @Test
    fun aRemovedMemberIsToldItWasRemoved() {
        assertEquals("You were removed from this group", FlashGroupSelfNoticeMath.title(FlashSelfMembership.Removed))
        assertEquals("You can still read what you already received.", FlashGroupSelfNoticeMath.detail(FlashSelfMembership.Removed))
    }

    @Test
    fun aMemberWhoLeftIsToldItLeftAndTheTwoStatesReadDifferently() {
        assertEquals("You left this group", FlashGroupSelfNoticeMath.title(FlashSelfMembership.Left))
        assertNotEquals(
            FlashGroupSelfNoticeMath.title(FlashSelfMembership.Left),
            FlashGroupSelfNoticeMath.title(FlashSelfMembership.Removed),
            "leaving is the member's own choice, a removal is not: the words must not blur them",
        )
    }
}
