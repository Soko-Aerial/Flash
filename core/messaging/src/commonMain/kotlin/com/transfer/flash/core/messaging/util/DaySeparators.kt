package com.transfer.flash.core.messaging.util

/**
 * Local calendar fields used by date-separator logic without exposing JVM date APIs to commonMain.
 */
public data class FlashLocalDate(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int,
    val epochDay: Long,
)

/**
 * Injectable conversion/formatting seam for platform-local calendar rules and locale data.
 */
public interface FlashDayLabelCalendar {
    public fun localDate(epochMillis: Long): FlashLocalDate

    public fun formatDate(date: FlashLocalDate): String
}

/** Platform default calendar, time zone, and locale. */
public expect fun platformDayLabelCalendar(): FlashDayLabelCalendar

/** Platform calendar using an explicit IANA/Olson time-zone id; intended for deterministic tests. */
public expect fun platformDayLabelCalendar(timeZoneId: String): FlashDayLabelCalendar

/**
 * Labels [sentAtMs] relative to [nowMs] in [calendar]'s local time zone.
 *
 * Passing [calendar] makes midnight and daylight-saving behavior deterministic in common tests.
 */
public fun dayLabelFor(
    sentAtMs: Long,
    nowMs: Long,
    calendar: FlashDayLabelCalendar = platformDayLabelCalendar(),
): String {
    val sentDate = calendar.localDate(sentAtMs)
    val today = calendar.localDate(nowMs)
    return when (sentDate.epochDay) {
        today.epochDay -> "Today"
        today.epochDay - 1L -> "Yesterday"
        else -> calendar.formatDate(sentDate)
    }
}

/**
 * Assigns one separator to the first visible message of every local day in chronological order.
 */
public fun <T> assignDaySeparators(
    messages: List<Pair<T, com.transfer.flash.core.messaging.model.FlashMessageUi>>,
    nowMs: Long,
    calendar: FlashDayLabelCalendar = platformDayLabelCalendar(),
    sentAtMs: (T) -> Long,
): List<com.transfer.flash.core.messaging.model.FlashMessageUi> {
    var previousDay: Long? = null
    return messages.map { (source, message) ->
        val timestamp = sentAtMs(source)
        val day = calendar.localDate(timestamp).epochDay
        val separator = if (day != previousDay) {
            dayLabelFor(sentAtMs = timestamp, nowMs = nowMs, calendar = calendar)
        } else {
            null
        }
        previousDay = day
        message.copy(daySeparator = separator)
    }
}
