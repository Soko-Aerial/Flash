@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.common.logging

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Forwards records to `android.util.Log`.
 *
 * On the JVM unit-test tier `android.util.Log` methods throw "not mocked", so every
 * call is wrapped — forwarding failures are swallowed by design. Do not add non-logging
 * logic here.
 */
private object AndroidLogSink : FlashLogSink {
    override fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val priority = when (level) {
            FlashLogLevel.INFO -> Log.INFO
            FlashLogLevel.WARN -> Log.WARN
            FlashLogLevel.ERROR -> Log.ERROR
        }
        try {
            val full = if (throwable != null) {
                "$message\n${Log.getStackTraceString(throwable)}"
            } else {
                message
            }
            Log.println(priority, tag, full)
        } catch (_: Throwable) {
            // Unit-test tier: android.util.Log is not mocked. Intentionally ignored.
        }
    }
}

internal actual fun platformLogSink(): FlashLogSink = AndroidLogSink
