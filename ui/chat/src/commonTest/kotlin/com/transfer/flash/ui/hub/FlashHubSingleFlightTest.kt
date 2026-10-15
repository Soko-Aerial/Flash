package com.transfer.flash.ui.hub

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HUBT-U80..: "Message colleague" is double-tap safe: one request in flight, released on every exit. */
class FlashHubSingleFlightTest {

    @Test fun theFirstTapStartsAndASecondWhileBusyIsRefused() {
        val f = FlashHubSingleFlight()
        assertTrue(f.tryStart())
        assertTrue(f.busy)
        assertFalse(f.tryStart())
    }

    @Test fun finishingReleasesSoALaterTapWorks() {
        val f = FlashHubSingleFlight()
        f.tryStart()
        f.finish()
        assertFalse(f.busy)
        assertTrue(f.tryStart())
    }

    @Test fun finishingTwiceIsHarmless() {
        val f = FlashHubSingleFlight()
        f.finish()
        f.finish()
        assertTrue(f.tryStart())
    }

    @Test fun aThrowingRequestStillReleasesThroughTryFinally() {
        val f = FlashHubSingleFlight()
        runCatching {
            check(f.tryStart())
            try { error("boom") } finally { f.finish() }
        }
        assertFalse(f.busy)
    }
}
