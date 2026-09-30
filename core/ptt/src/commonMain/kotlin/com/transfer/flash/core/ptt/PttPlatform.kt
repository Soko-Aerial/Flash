package com.transfer.flash.core.ptt

/**
 * Platform seams of the PTT module. `actual`s: `PttPlatform.android.kt`, `PttPlatform.jvm.kt`.
 * Convention: CONVENTIONS.md R2/R5 — the JDK-bound bodies are duplicated per target, not shared
 * through an intermediate source set.
 */

/**
 * Monotonic milliseconds used for the floor machine's clocks and the wire timestamps.
 *
 * Android keeps `SystemClock.elapsedRealtime()` (it keeps counting in doze, which the burst cap and
 * the heartbeat timeout rely on); the JVM uses `System.nanoTime()`. The value is only ever compared
 * with another value from the same device, so the origin does not matter.
 *
 * Public because a session card shows "elapsed" as `now - PttFloorState.startedAtMs`, and both must be
 * read from this one clock (a wall clock or a different monotonic origin would show nonsense).
 */
public expect fun pttElapsedRealtimeMs(): Long

/** Monitor lock for the few synchronous gates (press / voice-note lease, heartbeat ledger). */
internal expect class PttLock() {
    fun <T> withLock(block: () -> T): T
}

/** The capture/playout implementation of this platform. */
public expect fun platformPttAudio(): PttAudioPlatform
