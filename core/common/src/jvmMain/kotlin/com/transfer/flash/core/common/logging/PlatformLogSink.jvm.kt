@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Desktop/CI counterpart of the Android sink: writes to stderr.
 *
 * stderr rather than stdout so log noise never contaminates a program's real output —
 * this target is what `:sample` desktop runs and CI use. Same contract as every other
 * [FlashLogSink]: it MUST NOT throw, so the write is wrapped.
 */
private object JvmLogSink : FlashLogSink {
    override fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        try {
            System.err.println("${level.name.first()}/$tag: $message")
            throwable?.printStackTrace(System.err)
        } catch (_: Throwable) {
            // A logging failure must never propagate into a caller's code path.
        }
    }
}

internal actual fun platformLogSink(): FlashLogSink = JvmLogSink
