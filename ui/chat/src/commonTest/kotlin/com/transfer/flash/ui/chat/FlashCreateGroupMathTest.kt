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
    fun localDeviceCountsTowardTheSixDeviceCap() {
        // Five remote peers fill every slot; a sixth remote peer is not selectable.
        assertTrue(FlashCreateGroupMath.canCreate("Team", (1..5).map { "p$it" }.toSet()))
        assertFalse(FlashCreateGroupMath.canCreate("Team", (1..6).map { "p$it" }.toSet()))
        assertFalse(FlashCreateGroupMath.selectionAllowed(selectedCount = 5))
        assertTrue(FlashCreateGroupMath.selectionAllowed(selectedCount = 4))
    }

    @Test
    fun countLabelIncludesTheLocalDevice() {
        assertEquals("1 of 6 members chosen", FlashCreateGroupMath.countLabel(0))
        assertEquals("6 of 6 members chosen", FlashCreateGroupMath.countLabel(5))
    }
}
