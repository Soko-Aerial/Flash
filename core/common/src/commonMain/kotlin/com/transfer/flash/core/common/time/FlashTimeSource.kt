package com.transfer.flash.core.common.time

/**
 * Injectable wall-clock abstraction (C0.4) so time-dependent logic (reconnect backoff,
 * heartbeat deadlines, transfer ETA, log timestamps) can be unit-tested deterministically.
 * Consumers depend on this interface; concrete wiring happens in `:core:engine` / `:app`.
 */
public interface FlashTimeSource {
    /** Current time in epoch milliseconds. */
    public fun nowMs(): Long
}

/**
 * Real wall clock. Production default.
 *
 * The object stays in `commonMain`; only the clock read is a seam
 * ([currentTimeMillisPlatform]), so the published FQN and shape are unchanged from the
 * pre-KMP version and cross-module callers needed no edit.
 */
public object SystemTimeSource : FlashTimeSource {
    override fun nowMs(): Long = currentTimeMillisPlatform()
}
