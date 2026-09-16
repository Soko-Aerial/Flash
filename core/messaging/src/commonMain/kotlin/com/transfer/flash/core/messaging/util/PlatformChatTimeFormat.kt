package com.transfer.flash.core.messaging.util

/**
 * Platform time-of-day labels for chat bubbles (Phase 2, slice 4).
 *
 * `java.text.SimpleDateFormat` exists on Android and the desktop JVM but not in
 * `commonMain`, and locale-aware formatting is inherently platform data — same reason
 * `platformDayLabelCalendar` is a seam. The relative-time thresholds ("Now", "5m", "3h")
 * stay common; only the locale-formatted tails live behind these functions.
 *
 * `internal`: only `RealFlashChatRepository` calls them.
 */

/** `"h:mm a"` in the platform default locale — the bubble time label. */
internal expect fun platformFormatTimeOfDay(millis: Long): String

/** `"MMM d"` in the platform default locale — the old-message relative-time fallback. */
internal expect fun platformFormatMonthDay(millis: Long): String
