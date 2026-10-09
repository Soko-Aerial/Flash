package com.transfer.flash.core.engine.swarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R-10 (sweep 2026-10-09): a sender-controlled `sentAt` must not make a swarm announcement immortal or overflow its expiry. */
class SwarmExpiryTest {

    private val now = 1_800_000_000_000L

    @Test
    fun anHonestAnnouncementExpiresSevenDaysAfterItWasSent() {
        val sentAt = now - 60_000L
        assertEquals(sentAt + SWARM_ANNOUNCEMENT_TTL_MS, swarmExpiryFor(sentAt, now))
    }

    @Test
    fun aFarFutureSentAtIsCappedToNowPlusTheSkewAllowance() {
        val expiry = swarmExpiryFor(now + 365L * 24 * 3600 * 1000L, now)
        assertEquals(now + SWARM_SENT_AT_SKEW_MS + SWARM_ANNOUNCEMENT_TTL_MS, expiry)
    }

    @Test
    fun longMaxValueDoesNotOverflowToANegativeExpiry() {
        val expiry = swarmExpiryFor(Long.MAX_VALUE, now)
        assertTrue(expiry > now, "expiry $expiry must be in the future")
        assertEquals(now + SWARM_SENT_AT_SKEW_MS + SWARM_ANNOUNCEMENT_TTL_MS, expiry)
    }

    @Test
    fun theSumSaturatesEvenWhenNowItselfIsHuge() {
        assertEquals(Long.MAX_VALUE, swarmExpiryFor(Long.MAX_VALUE, Long.MAX_VALUE - 1_000L))
    }

    @Test
    fun anAncientSentAtSimplyExpiresAlready() {
        assertTrue(swarmExpiryFor(0L, now) < now)
    }
}
