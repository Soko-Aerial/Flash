package com.transfer.flash.core.messaging.util

import com.transfer.flash.core.messaging.model.FlashMessageGroupPosition
import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DaySeparatorsTest {
    private val calendar = object : FlashDayLabelCalendar {
        override fun localDate(epochMillis: Long): FlashLocalDate = when (epochMillis) {
            10L, 11L, 12L -> FlashLocalDate(2026, 9, 8, 100L)
            9L -> FlashLocalDate(2026, 9, 7, 99L)
            else -> FlashLocalDate(2026, 9, 6, 98L)
        }

        override fun formatDate(date: FlashLocalDate): String =
            "${date.dayOfMonth} M${date.month} ${date.year}"
    }

    @Test
    fun same_day_is_today() {
        assertEquals("Today", dayLabelFor(sentAtMs = 10L, nowMs = 12L, calendar = calendar))
    }

    @Test
    fun previous_local_day_is_yesterday() {
        assertEquals("Yesterday", dayLabelFor(sentAtMs = 9L, nowMs = 12L, calendar = calendar))
    }

    @Test
    fun older_day_uses_injected_formatter() {
        assertEquals("6 M9 2026", dayLabelFor(sentAtMs = 8L, nowMs = 12L, calendar = calendar))
    }

    @Test
    fun same_day_streak_emits_only_one_separator() {
        val result = assignDaySeparators(
            messages = listOf(10L to message("a"), 11L to message("b"), 9L to message("c")),
            nowMs = 12L,
            calendar = calendar,
            sentAtMs = { it },
        )

        assertEquals("Today", result[0].daySeparator)
        assertNull(result[1].daySeparator)
        assertEquals("Yesterday", result[2].daySeparator)
    }

    @Test
    fun separator_breaks_a_same_sender_bubble_group() {
        val grouped = computeMessageGroupPositions(
            listOf(message("a"), message("b").copy(daySeparator = "Today")),
        )

        assertEquals(FlashMessageGroupPosition.SINGLE, grouped[0].groupPosition)
        assertEquals(FlashMessageGroupPosition.SINGLE, grouped[1].groupPosition)
    }

    @Test
    fun filtered_rows_cannot_shift_visible_day_boundaries() {
        val visible = listOf(10L to message("new"), 9L to message("old"))
        val result = assignDaySeparators(
            visible,
            nowMs = 12L,
            calendar = calendar,
            sentAtMs = { it },
        )

        assertEquals(listOf("Today", "Yesterday"), result.map { it.daySeparator })
    }

    private fun message(id: String) = FlashMessageUi(
        id = id,
        senderName = "Peer",
        senderInitials = "P",
        timeLabel = "12:00 PM",
        text = id,
        isMine = false,
    )
}
