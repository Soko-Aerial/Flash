package com.transfer.flash.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTaskbarBadgeManagerTest {

    @Test
    fun baseIconsGeneratedForAllTargetResolutions() {
        val baseIcons = DesktopTaskbarBadgeManager.getBaseIcons()
        assertEquals(5, baseIcons.size)

        val expectedSizes = listOf(16, 24, 32, 48, 64)
        baseIcons.forEachIndexed { index, image ->
            val expected = expectedSizes[index]
            assertEquals(expected, image.width)
            assertEquals(expected, image.height)
            // Verify image has non-zero pixels (ARGB)
            val centerRgb = image.getRGB(expected / 2, expected / 2)
            assertTrue("Center pixel should not be completely transparent", (centerRgb ushr 24) != 0)
        }
    }

    @Test
    fun badgedIconsGeneratedWithNotificationCounters() {
        val singleBadged = DesktopTaskbarBadgeManager.getBadgedIcons(1)
        assertEquals(5, singleBadged.size)

        val multipleBadged = DesktopTaskbarBadgeManager.getBadgedIcons(15)
        assertEquals(5, multipleBadged.size)

        // Count <= 0 returns base icons
        val zeroBadged = DesktopTaskbarBadgeManager.getBadgedIcons(0)
        assertEquals(DesktopTaskbarBadgeManager.getBaseIcons(), zeroBadged)
    }

    @Test
    fun safeFallbackWhenWindowIsNull() {
        // Must not throw NPE or any exception
        DesktopTaskbarBadgeManager.updateBadge(null, 5)
        DesktopTaskbarBadgeManager.clearBadge(null)
        DesktopTaskbarBadgeManager.requestAttention(null)
    }
}
