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

/** Real clock backed by [System.currentTimeMillis]. Production default. */
public object SystemTimeSource : FlashTimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}
