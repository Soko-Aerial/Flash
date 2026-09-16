package com.transfer.flash.core.messaging.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun platformFormatTimeOfDay(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

internal actual fun platformFormatMonthDay(millis: Long): String =
    SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
