@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource

/**
 * Bounded, thread-safe in-memory debug logger (C0.3, AGENTS.md §24).
 *
 * Every entry is:
 * 1. Appended to a bounded ring buffer (default [DEFAULT_CAPACITY] entries; oldest evicted
 *    first) for later display in the debug sheet via [recent].
 * 2. Forwarded to `android.util.Log` when running on Android.
 *
 * ## Design notes
 *
 * - **Thread safety:** all buffer access is guarded by a single `synchronized` monitor over an
 *   [ArrayDeque]. For a low-frequency debug log (~512-entry bound) this is the right trade-off:
 *   `ConcurrentLinkedQueue` is lock-free but unbounded — it cannot enforce the eviction bound
 *   atomically without extra synchronization anyway — and lock-free ring buffers only pay off
 *   at ultra-low-latency rates that a debug logger never sees. Kotlin's own concurrency
 *   guidance recommends synchronized/thread-safe structures for simple shared state:
 *   https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html
 *   Background: https://www.baeldung.com/java-ring-buffer and
 *   https://mortoray.com/wait-free-queueing-and-ultra-low-latency-logging/ (wait-free rings are
 *   explicitly recommended against unless nanosecond latency matters).
 * - **Android forwarding:** `:core:common` is an Android library module (android.jar on the
 *   compile classpath), so `Log.println` compiles directly in [FlashPlatformLogSink]. On the
 *   JVM unit-test tier `android.util.Log` methods throw "not mocked"; the sink therefore
 *   wraps the forward call so it can never break callers there — the in-memory buffer still
 *   records every entry.
 * - **Structured tags:** callers pass one of the §24 tag constants (DISCOVERY, LAN,
 *   WIFI_DIRECT, CONNECTION, PAIRING, TLS, TRANSFER, CHUNK, STORAGE, DATABASE, SERVICE,
 *   PERFORMANCE). Never log secrets or sensitive user data (§24).
 */
internal class FlashLogger(
    private val tag: String,
    capacity: Int = DEFAULT_CAPACITY,
    private val timeSource: FlashTimeSource = SystemTimeSource,
) {

    init {
        require(capacity > 0) { "FlashLogger capacity must be positive, was $capacity" }
        require(tag.isNotBlank()) { "FlashLogger tag must not be blank" }
    }

    private val lock = Any()
    private val maxCapacity: Int = capacity
    private val buffer = ArrayDeque<FlashLogEntry>(capacity)

    /** Logs at [FlashLogLevel.INFO]. */
    fun i(message: String) = log(FlashLogLevel.INFO, message, null)

    /** Logs at [FlashLogLevel.INFO] with an optional throwable for stack-trace capture. */
    fun i(message: String, throwable: Throwable?) = log(FlashLogLevel.INFO, message, throwable)

    /** Logs at [FlashLogLevel.WARN]. */
    fun w(message: String) = log(FlashLogLevel.WARN, message, null)

    /** Logs at [FlashLogLevel.WARN] with an optional throwable for stack-trace capture. */
    fun w(message: String, throwable: Throwable?) = log(FlashLogLevel.WARN, message, throwable)

    /** Logs at [FlashLogLevel.ERROR]. */
    fun e(message: String) = log(FlashLogLevel.ERROR, message, null)

    /** Logs at [FlashLogLevel.ERROR] with an optional throwable for stack-trace capture. */
    fun e(message: String, throwable: Throwable?) = log(FlashLogLevel.ERROR, message, throwable)

    /**
     * Returns up to [limit] most recent entries in chronological order (oldest first).
     * Returns fewer when the buffer holds less than [limit]; returns an empty list if empty.
     */
    fun recent(limit: Int = DEFAULT_CAPACITY): List<FlashLogEntry> {
        require(limit >= 0) { "limit must be non-negative, was $limit" }
        synchronized(lock) {
            if (buffer.isEmpty() || limit == 0) return emptyList()
            return buffer.takeLast(limit)
        }
    }

    /** Drops all buffered entries. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }

    private fun log(level: FlashLogLevel, message: String, throwable: Throwable?) {
        val entry = FlashLogEntry(level, tag, message, timeSource.nowMs())
        synchronized(lock) {
            if (buffer.size >= maxCapacity) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
        forwardToSink(level, entry.message, throwable)
    }

    private fun forwardToSink(
        level: FlashLogLevel,
        message: String,
        throwable: Throwable?,
    ) {
        when (level) {
            FlashLogLevel.INFO -> FlashLog.i(tag, message, throwable)
            FlashLogLevel.WARN -> FlashLog.w(tag, message, throwable)
            FlashLogLevel.ERROR -> FlashLog.e(tag, message, throwable)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 512
    }
}
