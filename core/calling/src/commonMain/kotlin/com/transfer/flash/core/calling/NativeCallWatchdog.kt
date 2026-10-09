@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.transfer.flash.core.common.logging.FlashLog
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Evidence for "a call leg sits on Connecting" (ERROR-128): every native WebRTC call that
 * runs on [callMediaDispatcher] is one thread, so one native call that blocks stalls signalling,
 * renders and every other leg behind it, and the log then shows nothing for the whole stall.
 * This wraps such a call: it logs a warning while the call is still running (the watchdog runs
 * on [Dispatchers.Default], NOT the blocked media thread) and the total time afterwards.
 * It changes no behaviour.
 */
internal suspend fun <T> timedNative(label: String, block: suspend CoroutineScope.() -> T): T {
    val started = TimeSource.Monotonic.markNow()
    return coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            var waited = 0L
            while (true) {
                delay(WATCHDOG_FIRST_MS.takeIf { waited == 0L } ?: WATCHDOG_REPEAT_MS)
                waited = started.elapsedNow().inWholeMilliseconds
                FlashLog.w("CALL_STALL", "native call still running: $label elapsedMs=$waited")
            }
        }
        try {
            block()
        } finally {
            watchdog.cancel()
            val took = started.elapsedNow().inWholeMilliseconds
            if (took >= SLOW_MS) FlashLog.w("CALL_STALL", "native call finished: $label tookMs=$took")
        }
    }
}

private const val WATCHDOG_FIRST_MS = 2_000L
private const val WATCHDOG_REPEAT_MS = 5_000L
private const val SLOW_MS = 1_000L
