package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlin.concurrent.Volatile

/**
 * Process-wide log entry point for Flash internals.
 *
 * Replaces direct `android.util.Log` calls so that non-Android targets can install a
 * different sink. Tag values are the structured tags from AGENTS.md §24
 * (DISCOVERY, LAN, CONNECTION, PAIRING, TLS, TRANSFER, CHUNK, STORAGE, ...).
 *
 * The default sink comes from `platformLogSink()` — the Phase 06 `expect`/`actual` seam that
 * picks `android.util.Log` on Android and stderr on the JVM. [installSink] still overrides it.
 *
 * Never log secrets, keys, fingerprints, or user message content.
 */
@FlashInternalApi
public object FlashLog {

    @Volatile
    @PublishedApi
    internal var sink: FlashLogSink = platformLogSink()

    @Volatile
    @PublishedApi
    internal var currentMinLevel: FlashLogLevel = FlashLogLevel.INFO

    /** Replaces the active sink. Used by platform bootstrap and by tests. */
    public fun installSink(newSink: FlashLogSink) {
        sink = newSink
    }

    /** Sets the minimum log level. Messages below this level are discarded before string evaluation. */
    public fun setMinLevel(level: FlashLogLevel) {
        currentMinLevel = level
    }

    /** Returns the currently configured minimum log level. */
    public fun minLevel(): FlashLogLevel = currentMinLevel

    /** Checks whether a message at [level] (and optional [tag]) is loggable under current filtering. */
    @PublishedApi
    internal fun isLoggable(level: FlashLogLevel): Boolean = level.ordinal >= currentMinLevel.ordinal

    public fun isLoggable(level: FlashLogLevel, tag: String? = null): Boolean = isLoggable(level)

    @PublishedApi
    internal fun log(level: FlashLogLevel, tag: String, message: String, throwable: Throwable?) {
        sink.write(level, tag, message, throwable)
    }

    public fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (isLoggable(FlashLogLevel.DEBUG)) {
            log(FlashLogLevel.DEBUG, tag, message, throwable)
        }
    }

    public inline fun d(tag: String, message: () -> String) {
        if (isLoggable(FlashLogLevel.DEBUG)) {
            log(FlashLogLevel.DEBUG, tag, message(), null)
        }
    }

    public inline fun d(tag: String, throwable: Throwable?, message: () -> String) {
        if (isLoggable(FlashLogLevel.DEBUG)) {
            log(FlashLogLevel.DEBUG, tag, message(), throwable)
        }
    }

    public fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (isLoggable(FlashLogLevel.INFO)) {
            log(FlashLogLevel.INFO, tag, message, throwable)
        }
    }

    public inline fun i(tag: String, message: () -> String) {
        if (isLoggable(FlashLogLevel.INFO)) {
            log(FlashLogLevel.INFO, tag, message(), null)
        }
    }

    public inline fun i(tag: String, throwable: Throwable?, message: () -> String) {
        if (isLoggable(FlashLogLevel.INFO)) {
            log(FlashLogLevel.INFO, tag, message(), throwable)
        }
    }

    public fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (isLoggable(FlashLogLevel.WARN)) {
            log(FlashLogLevel.WARN, tag, message, throwable)
        }
    }

    public inline fun w(tag: String, message: () -> String) {
        if (isLoggable(FlashLogLevel.WARN)) {
            log(FlashLogLevel.WARN, tag, message(), null)
        }
    }

    public inline fun w(tag: String, throwable: Throwable?, message: () -> String) {
        if (isLoggable(FlashLogLevel.WARN)) {
            log(FlashLogLevel.WARN, tag, message(), throwable)
        }
    }

    public fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (isLoggable(FlashLogLevel.ERROR)) {
            log(FlashLogLevel.ERROR, tag, message, throwable)
        }
    }

    public inline fun e(tag: String, message: () -> String) {
        if (isLoggable(FlashLogLevel.ERROR)) {
            log(FlashLogLevel.ERROR, tag, message(), null)
        }
    }

    public inline fun e(tag: String, throwable: Throwable?, message: () -> String) {
        if (isLoggable(FlashLogLevel.ERROR)) {
            log(FlashLogLevel.ERROR, tag, message(), throwable)
        }
    }
}
