package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashCreateGroupMathTest {
    @Test
    fun titleIsTrimmedAndBounded() {
        assertEquals("Team", FlashCreateGroupMath.normalizedTitle("  Team "))
        assertNull(FlashCreateGroupMath.normalizedTitle("   "))
        assertNull(FlashCreateGroupMath.normalizedTitle("x".repeat(81)))
    }

    @Test
    fun creationNeedsTitleAndAtLeastOnePeer() {
        assertTrue(FlashCreateGroupMath.canCreate("Team", setOf("a")))
        assertFalse(FlashCreateGroupMath.canCreate("   ", setOf("a")))
        assertFalse(FlashCreateGroupMath.canCreate("Team", emptySet()))
    }

    @Test
    fun localDeviceCountsTowardTheSignedGroupCap() {
        // Nineteen remote peers fill every slot of a 20-device signed group; a twentieth is not selectable.
        assertEquals(20, FlashCreateGroupMath.MAX_MEMBERS)
        assertTrue(FlashCreateGroupMath.canCreate("Team", (1..19).map { "p$it" }.toSet()))
        assertFalse(FlashCreateGroupMath.canCreate("Team", (1..20).map { "p$it" }.toSet()))
        assertFalse(FlashCreateGroupMath.selectionAllowed(selectedCount = 19))
        assertTrue(FlashCreateGroupMath.selectionAllowed(selectedCount = 18))
    }

    @Test
    fun countLabelIncludesTheLocalDevice() {
        assertEquals("1 of 20 members chosen", FlashCreateGroupMath.countLabel(0))
        assertEquals("20 of 20 members chosen", FlashCreateGroupMath.countLabel(19))
    }
}
