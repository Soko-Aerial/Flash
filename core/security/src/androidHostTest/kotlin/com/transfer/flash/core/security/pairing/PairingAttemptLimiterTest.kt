package com.transfer.flash.core.security.pairing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAttemptLimiterTest {

    @Test
    fun fiveFailuresLockThePeerOutAndTheLockoutExpires() = runBlocking {
        PairingAttemptLimiter.clearForTest()
        val peer = "peer-limiter-lockout"
        val t0 = 1_000L

        repeat(4) { offset ->
            assertTrue(PairingAttemptLimiter.allow(peer, t0 + offset))
            PairingAttemptLimiter.recordFailure(peer, t0 + offset)
        }
        val fifth = t0 + 5
        assertTrue("the fifth attempt is allowed", PairingAttemptLimiter.allow(peer, fifth))
        PairingAttemptLimiter.recordFailure(peer, fifth)

        assertFalse(
            "five failures must lock the peer out",
            PairingAttemptLimiter.allow(peer, fifth + 1),
        )
        assertTrue(
            "the peer may retry after the lockout",
            PairingAttemptLimiter.allow(peer, fifth + PairingAttemptLimiter.LOCKOUT_MS + 1),
        )
    }

    @Test
    fun aSuccessfulPairingClearsTheRecord() = runBlocking {
        PairingAttemptLimiter.clearForTest()
        val peer = "peer-limiter-success"
        val t0 = 10_000L

        repeat(4) { offset ->
            PairingAttemptLimiter.recordFailure(peer, t0 + offset)
        }
        PairingAttemptLimiter.recordSuccess(peer)
        repeat(4) { offset ->
            assertTrue(PairingAttemptLimiter.allow(peer, t0 + 100 + offset))
            PairingAttemptLimiter.recordFailure(peer, t0 + 100 + offset)
        }
        assertTrue(
            "four failures after a reset must not lock the peer out",
            PairingAttemptLimiter.allow(peer, t0 + 200),
        )
    }

    @Test
    fun anotherPeerIsUnaffected() = runBlocking {
        PairingAttemptLimiter.clearForTest()
        val blocked = "peer-limiter-blocked"
        val other = "peer-limiter-other"
        val t0 = 20_000L

        repeat(PairingAttemptLimiter.MAX_FAILURES) { offset ->
            PairingAttemptLimiter.recordFailure(blocked, t0 + offset)
        }
        assertFalse(PairingAttemptLimiter.allow(blocked, t0 + 100))
        assertTrue(PairingAttemptLimiter.allow(other, t0 + 100))
    }
}
