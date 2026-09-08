package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlashGroupHeaderLogicTest {

    // --- Collage layout selection ---

    @Test
    fun `collage layout follows member count`() {
        assertEquals(FlashGroupHeaderMath.CollageLayout.Single, FlashGroupHeaderMath.collageLayoutFor(1))
        assertEquals(FlashGroupHeaderMath.CollageLayout.TwoVertical, FlashGroupHeaderMath.collageLayoutFor(2))
        assertEquals(FlashGroupHeaderMath.CollageLayout.OneLargeTwoSmall, FlashGroupHeaderMath.collageLayoutFor(3))
        assertEquals(FlashGroupHeaderMath.CollageLayout.Quad, FlashGroupHeaderMath.collageLayoutFor(4))
        assertEquals(FlashGroupHeaderMath.CollageLayout.Quad, FlashGroupHeaderMath.collageLayoutFor(50))
        // Degenerate inputs fall back to single tile
        assertEquals(FlashGroupHeaderMath.CollageLayout.Single, FlashGroupHeaderMath.collageLayoutFor(0))
        assertEquals(FlashGroupHeaderMath.CollageLayout.Single, FlashGroupHeaderMath.collageLayoutFor(-3))
    }

    @Test
    fun `visible initials cap at four and drop blanks`() {
        assertEquals(listOf("AR"), FlashGroupHeaderMath.visibleInitials(listOf("AR")))
        assertEquals(
            listOf("AR", "BK", "LF", "SR"),
            FlashGroupHeaderMath.visibleInitials(listOf("AR", "BK", "LF", "SR", "JD", "TW")),
        )
        assertEquals(listOf("AR", "BK"), FlashGroupHeaderMath.visibleInitials(listOf("", "AR", "", "BK")))
        assertEquals(emptyList<String>(), FlashGroupHeaderMath.visibleInitials(emptyList()))
    }

    // --- Subtitle labels ---

    @Test
    fun `member status label handles singular plural and online counts`() {
        assertNull(FlashGroupHeaderMath.memberStatusLabel(memberCount = 0, onlineCount = 0))
        assertEquals("1 member", FlashGroupHeaderMath.memberStatusLabel(memberCount = 1, onlineCount = 0))
        assertEquals("5 members · 2 online", FlashGroupHeaderMath.memberStatusLabel(memberCount = 5, onlineCount = 2))
        assertEquals("15 members", FlashGroupHeaderMath.memberStatusLabel(memberCount = 15, onlineCount = 0))
    }

    @Test
    fun `explicit member summary takes precedence over computed counts`() {
        assertEquals(
            "custom summary",
            FlashGroupHeaderMath.groupSubtitle(memberSummary = "custom summary", memberCount = 5, onlineCount = 2),
        )
        assertEquals(
            "5 members · 2 online",
            FlashGroupHeaderMath.groupSubtitle(memberSummary = null, memberCount = 5, onlineCount = 2),
        )
        assertNull(FlashGroupHeaderMath.groupSubtitle(memberSummary = null, memberCount = 0, onlineCount = 0))
    }

    // --- Named typing labels (capped at two names per design) ---

    @Test
    fun `typing labels cover one two and many typists`() {
        assertNull(FlashGroupHeaderMath.typingStatusLabel(emptyList()))
        assertNull(FlashGroupHeaderMath.typingStatusLabel(listOf("")))
        assertEquals("Alex is typing…", FlashGroupHeaderMath.typingStatusLabel(listOf("Alex")))
        assertEquals(
            "Alex and Sam are typing…",
            FlashGroupHeaderMath.typingStatusLabel(listOf("Alex", "Sam")),
        )
        assertEquals(
            "Alex, Sam +2 more are typing…",
            FlashGroupHeaderMath.typingStatusLabel(listOf("Alex", "Sam", "Jo", "Rae")),
        )
    }
}
