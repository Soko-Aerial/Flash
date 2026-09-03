package com.transfer.flash.core.common.time

/**
 * Deterministic fake clock for unit tests. Lives in the test source set so it is never
 * published to library consumers (testFixtures-style, per C0.4 spec).
 *
 * Starts at a fixed [currentMs] (default 0) and only moves when [advance] is called,
 * giving tests full control over elapsed-time behavior.
 */
class FakeTimeSource(var currentMs: Long = 0L) : FlashTimeSource {

    override fun nowMs(): Long = currentMs

    /** Moves the clock forward by [ms]; must be non-negative (time does not go backward). */
    fun advance(ms: Long) {
        require(ms >= 0) { "advance must be non-negative, was $ms" }
        currentMs += ms
    }
}
