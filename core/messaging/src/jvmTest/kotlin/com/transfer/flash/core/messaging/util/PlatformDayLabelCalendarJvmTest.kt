package com.transfer.flash.core.messaging.util

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformDayLabelCalendarJvmTest {
    @Test
    fun midnight_crossing_uses_local_calendar_day() {
        val calendar = platformDayLabelCalendar("UTC")
        assertEquals(
            "Yesterday",
            dayLabelFor(
                sentAtMs = Instant.parse("2026-09-07T23:59:59Z").toEpochMilli(),
                nowMs = Instant.parse("2026-09-08T00:00:01Z").toEpochMilli(),
                calendar = calendar,
            ),
        )
    }

    @Test
    fun dst_gap_still_classifies_same_local_day() {
        val calendar = platformDayLabelCalendar("America/New_York")
        assertEquals(
            "Today",
            dayLabelFor(
                sentAtMs = Instant.parse("2026-03-08T06:59:00Z").toEpochMilli(),
                nowMs = Instant.parse("2026-03-08T07:01:00Z").toEpochMilli(),
                calendar = calendar,
            ),
        )
    }
}
