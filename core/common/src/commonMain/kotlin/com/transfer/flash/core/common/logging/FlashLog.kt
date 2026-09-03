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
    private var sink: FlashLogSink = platformLogSink()

    /** Replaces the active sink. Used by platform bootstrap and by tests. */
    public fun installSink(newSink: FlashLogSink) {
        sink = newSink
    }

    public fun i(tag: String, message: String, throwable: Throwable? = null) {
        sink.write(FlashLogLevel.INFO, tag, message, throwable)
    }

    public fun w(tag: String, message: String, throwable: Throwable? = null) {
        sink.write(FlashLogLevel.WARN, tag, message, throwable)
    }

    public fun e(tag: String, message: String, throwable: Throwable? = null) {
        sink.write(FlashLogLevel.ERROR, tag, message, throwable)
    }
}