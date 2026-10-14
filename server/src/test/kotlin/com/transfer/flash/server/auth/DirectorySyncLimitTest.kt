package com.transfer.flash.server.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ORGT-U56: the default directory sync limit must hold one complete 20,000-member sync (40 pages of 500) from a cold start. */
class DirectorySyncLimitTest {
    @Test
    fun aFullTwentyThousandMemberSyncFitsTheBurstAndALoopIsStillStopped() {
        var now = 0L
        val limits = HubRateLimits(clock = { now })
        repeat(40) { assertTrue(limits.directorySync.tryAcquire("dev-1"), "page ${it + 1} of a full sync") }
        // the same device can keep going to the burst, then is refused until tokens refill
        repeat(20) { assertTrue(limits.directorySync.tryAcquire("dev-1")) }
        assertFalse(limits.directorySync.tryAcquire("dev-1"), "61st request inside the same second is refused")
        // another device has its own bucket
        assertTrue(limits.directorySync.tryAcquire("dev-2"))
        now += 2_000
        assertTrue(limits.directorySync.tryAcquire("dev-1"), "tokens refill at one per second")
    }
}
