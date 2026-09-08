package com.transfer.flash.core.messaging.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

public actual fun platformDayLabelCalendar(): FlashDayLabelCalendar = JvmDayLabelCalendar()

public actual fun platformDayLabelCalendar(timeZoneId: String): FlashDayLabelCalendar =
    JvmDayLabelCalendar(timeZone = TimeZone.getTimeZone(timeZoneId))

private class JvmDayLabelCalendar(
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val locale: Locale = Locale.getDefault(),
) : FlashDayLabelCalendar {
    override fun localDate(epochMillis: Long): FlashLocalDate {
        val calendar = Calendar.getInstance(timeZone, locale).apply { timeInMillis = epochMillis }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return FlashLocalDate(year, month, day, civilEpochDay(year, month, day))
    }

    override fun formatDate(date: FlashLocalDate): String =
        SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = this@JvmDayLabelCalendar.timeZone }
            .format(Date(localMiddayMillis(date)))

    private fun localMiddayMillis(date: FlashLocalDate): Long =
        Calendar.getInstance(timeZone, locale).apply {
            clear()
            set(date.year, date.month - 1, date.dayOfMonth, 12, 0, 0)
        }.timeInMillis
}

private fun civilEpochDay(year: Int, month: Int, day: Int): Long {
    var adjustedYear = year.toLong()
    adjustedYear -= if (month <= 2) 1L else 0L
    val era = floorDiv(adjustedYear, 400L)
    val yearOfEra = adjustedYear - era * 400L
    val shiftedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153L * shiftedMonth + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146097L + dayOfEra - 719468L
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1L else quotient
}
