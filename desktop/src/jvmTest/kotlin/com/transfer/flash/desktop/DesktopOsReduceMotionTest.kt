package com.transfer.flash.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopOsReduceMotionTest {
    @Test
    fun `disabled animations mean reduce motion but query failure is unknown`() {
        assertEquals(true, clientAreaAnimationsToReduceMotion(true, 0))
        assertEquals(false, clientAreaAnimationsToReduceMotion(true, 1))
        assertNull(clientAreaAnimationsToReduceMotion(false, 0))
        assertNull(clientAreaAnimationsToReduceMotion(false, 1))
    }

    @Test
    fun `preference changes emit without another UI event and failed queries preserve state`() = runBlocking {
        val results = listOf<Boolean?>(false, false, true, null, null, false)
        var index = 0
        val collected = withTimeout(5_000L) {
            desktopReduceMotionPreferences({ results[(index++).coerceAtMost(results.lastIndex)] }, 1L)
                .take(3).toList()
        }
        assertEquals(listOf(false, true, false), collected)
    }

    @Test
    fun `unsupported initial query keeps app default and later success emits`() = runBlocking {
        var count = 0
        val collected = withTimeout(5_000L) {
            desktopReduceMotionPreferences({ if (count++ == 0) null else true }, 1L).take(2).toList()
        }
        assertEquals(listOf(false, true), collected)
    }

    @Test
    fun `documented Windows read succeeds on Windows and unsupported hosts return unknown`() {
        val result = DesktopOsReduceMotion.queryOsPreference()
        if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
            assertNotNull(result, "SPI_GETCLIENTAREAANIMATION read or its JNA binding failed")
        } else {
            assertNull(result)
        }
    }
}
