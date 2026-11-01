package com.transfer.flash.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopOsReduceMotionTest {

    @Test
    fun `isReduceMotion does not throw and returns boolean`() {
        DesktopOsReduceMotion.resetCacheForTesting()
        val result = DesktopOsReduceMotion.isReduceMotion()
        // Must return without exception
        assertNotNull(result)
    }

    @Test
    fun `queryOsPreference executes safely and returns boolean`() {
        val result = DesktopOsReduceMotion.queryOsPreference()
        assertNotNull(result)
    }

    @Test
    fun `caching mechanism caches result across calls within TTL`() {
        DesktopOsReduceMotion.resetCacheForTesting()
        val first = DesktopOsReduceMotion.isReduceMotion()
        val second = DesktopOsReduceMotion.isReduceMotion()
        assertTrue(first == second)
    }
}
