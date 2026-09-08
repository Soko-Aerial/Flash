package com.transfer.flash.ui.calling

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the call screen's `mm:ss` counter.
 *
 * The arithmetic used to live inline in `activeDuration`'s `LaunchedEffect`, where it could not be
 * called from a test at all; EXP-013 pulled it out as [formatCallDuration]. These are the first tests
 * in `:ui:callui` — the module had a `testImplementation(libs.junit)` dependency and no `src/test`.
 */
class FlashCallDurationTest {

    @Test
    fun `truncates instead of rounding, so the first second reads zero`() {
        assertEquals("00:00", formatCallDuration(0L))
        assertEquals("00:00", formatCallDuration(999L))
        assertEquals("00:01", formatCallDuration(1_000L))
        assertEquals("00:01", formatCallDuration(1_999L))
    }

    @Test
    fun `both fields are zero-padded to two digits`() {
        assertEquals("00:09", formatCallDuration(9_000L))
        assertEquals("01:00", formatCallDuration(60_000L))
        assertEquals("09:05", formatCallDuration(545_000L))
    }

    @Test
    fun `seconds roll into minutes at the boundary`() {
        assertEquals("00:59", formatCallDuration(59_000L))
        assertEquals("01:00", formatCallDuration(60_000L))
        assertEquals("01:01", formatCallDuration(61_000L))
    }

    /**
     * `connectedAt` is a `System.currentTimeMillis()` stamp, so an NTP correction or a manual clock
     * change mid-call can move "now" behind the start. A paused "00:00" is the honest reading;
     * "-1:-3" would be a rendering bug the user has to interpret.
     */
    @Test
    fun `a clock that moves backwards clamps to zero rather than going negative`() {
        assertEquals("00:00", formatCallDuration(-1L))
        assertEquals("00:00", formatCallDuration(-90_000L))
    }

    /**
     * `%02d` is a minimum width, not a truncation: a long call widens the minutes field instead of
     * silently wrapping back to 00. Pinned because an hours field was deliberately not added — a LAN
     * call has no billing meaning, and `100:00` is unambiguous.
     */
    @Test
    fun `a call past one hour widens the minutes field instead of wrapping`() {
        assertEquals("60:00", formatCallDuration(3_600_000L))
        assertEquals("100:00", formatCallDuration(6_000_000L))
        assertEquals("125:45", formatCallDuration(7_545_000L))
    }
}
