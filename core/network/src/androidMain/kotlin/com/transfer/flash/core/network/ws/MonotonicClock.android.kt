package com.transfer.flash.core.network.ws

import android.os.SystemClock

/**
 * [monotonicNowMs] on Android: `elapsedRealtime()`, which keeps counting through deep sleep — the
 * keepalive must not read a suspended radio as a fresh one (SEC-D26-01).
 *
 * JVM-hosted Android unit tests run against a stub `android.jar` whose methods throw ("not mocked"),
 * so the source is resolved once and cached: `SystemClock` on a device, `System.nanoTime` under the
 * stub. Both are monotonic millisecond readings, which is all any caller needs.
 */
internal actual fun monotonicNowMs(): Long = clock()

private val clock: () -> Long by lazy {
    if (runCatching { SystemClock.elapsedRealtime() }.isSuccess) {
        { SystemClock.elapsedRealtime() }
    } else {
        { System.nanoTime() / 1_000_000L }
    }
}
