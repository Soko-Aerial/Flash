package com.transfer.flash.core.network.ws

/**
 * Milliseconds off a clock that only moves forward while the device is awake, for every keepalive
 * judgement (SEC-D26-01).
 *
 * Timeouts and deadlines used to read `System.currentTimeMillis()`, which NTP, a manual change or a
 * network clock sync can move in either direction. A jump forward reaped healthy sessions; a jump
 * back left a dead one looking fresh for as long as the jump. Values here are only ever differenced
 * against each other — never stored, never compared with a wall-clock reading — so a monotonic source
 * is the correct one.
 *
 * Android counts from boot including deep sleep (`SystemClock.elapsedRealtime()`), the JVM uses
 * `System.nanoTime()`. The two are not comparable across devices, which is fine: nothing here crosses
 * the wire.
 *
 * A plain `expect fun` rather than an `expect object`, so this module still needs no
 * `-Xexpect-actual-classes` flag (see `build.gradle.kts`).
 */
internal expect fun monotonicNowMs(): Long
