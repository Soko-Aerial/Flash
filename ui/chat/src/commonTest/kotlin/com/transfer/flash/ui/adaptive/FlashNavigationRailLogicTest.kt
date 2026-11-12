package com.transfer.flash.ui.adaptive

import com.transfer.flash.ui.theme.FlashDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashNavigationRailLogicTest {

    @Test
    fun `rail geometry keeps 68dp width and accessible 48dp profile target`() {
        assertEquals(68f, FlashNavigationRailMath.RAIL_WIDTH_DP)
        assertTrue(FlashNavigationRailMath.PROFILE_HIT_TARGET_DP >= FlashDimensions.minTouchTarget.value)
        assertEquals("Settings / Device profile", FlashNavigationRailMath.PROFILE_CONTENT_DESCRIPTION)
    }

    @Test
    fun `tab content descriptions include unread counts without duplicating icon text`() {
        assertEquals("Chats tab", FlashNavigationRailMath.tabContentDescription("Chats", 0))
        assertEquals("Chats tab, 5 unread", FlashNavigationRailMath.tabContentDescription("Chats", 5))
        assertEquals("99+", FlashNavigationRailMath.badgeText(120))
        assertEquals("7", FlashNavigationRailMath.badgeText(7))
    }

    @Test
    fun `profile tooltip includes display name when set and falls back cleanly`() {
        assertEquals(
            "Jordan · Settings / Device profile",
            FlashNavigationRailMath.profileTooltipText("  Jordan  "),
        )
        assertEquals(
            "Settings / Device profile",
            FlashNavigationRailMath.profileTooltipText("   "),
        )
        assertEquals(
            "Settings / Device profile",
            FlashNavigationRailMath.profileTooltipText(null),
        )
    }
}
