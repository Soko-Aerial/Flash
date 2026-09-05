package com.transfer.flash.core.security.testutil

import com.transfer.flash.core.common.time.FlashTimeSource

/**
 * Deterministic clock for JVM unit tests. (:core:common's FakeTimeSource lives in
 * that module's test fixtures, which are not visible across modules, so :core:security
 * keeps this local copy — see C2.4/C2.6 test notes.)
 */
class FakeClock(startAtMs: Long = 0L) : FlashTimeSource {

    private var currentMs: Long = startAtMs

    override fun nowMs(): Long = currentMs

    fun advanceBy(deltaMs: Long) {
        currentMs += deltaMs
    }

    fun setTo(newNowMs: Long) {
        currentMs = newNowMs
    }
}
