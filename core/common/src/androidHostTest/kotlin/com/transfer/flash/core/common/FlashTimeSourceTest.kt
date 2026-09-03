package com.transfer.flash.core.common

import com.transfer.flash.core.common.time.FakeTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashTimeSourceTest {

    @Test
    fun fake_time_source_starts_at_zero_by_default() {
        assertEquals(0L, FakeTimeSource().nowMs())
    }

    @Test
    fun fake_time_source_starts_at_configured_value() {
        assertEquals(1_000L, FakeTimeSource(currentMs = 1_000L).nowMs())
    }

    @Test
    fun fake_time_source_advance_moves_clock_forward() {
        val time = FakeTimeSource(currentMs = 100L)

        time.advance(50)
        assertEquals(150L, time.nowMs())

        time.advance(0)
        assertEquals(150L, time.nowMs())
    }

    @Test
    fun fake_time_source_is_stable_without_advance() {
        val time = FakeTimeSource()
        val first = time.nowMs()

        assertEquals(first, time.nowMs())
        assertEquals(first, time.nowMs())
    }

    @Test(expected = IllegalArgumentException::class)
    fun fake_time_source_rejects_negative_advance() {
        FakeTimeSource().advance(-1)
    }

    @Test
    fun system_time_source_returns_current_wall_clock() {
        val before = System.currentTimeMillis()
        val now = SystemTimeSource.nowMs()
        val after = System.currentTimeMillis()

        assertTrue(now in before..after)
    }
}
