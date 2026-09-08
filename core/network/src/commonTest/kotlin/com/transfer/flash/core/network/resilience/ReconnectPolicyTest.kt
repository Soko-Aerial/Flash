package com.transfer.flash.core.network.resilience

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Moved to `commonTest` in Phase 15-1. `kotlin.random.Random(seed)` is `commonMain` in the stdlib
 * and the policy takes its `random01` source as a parameter, so the seeded 2 000-sample sweep runs
 * identically on both targets. Beyond the JUnit 4 → `kotlin.test` import swap, the three
 * message-carrying `assertTrue` calls had their arguments reordered: `kotlin.test` puts the message
 * last, JUnit put it first, and a missed flip compiles silently into `assertTrue(nonEmptyString)`.
 */
class ReconnectPolicyTest {

    private fun seededPolicy(
        seed: Long = 7L,
        giveUpAfterMs: Long? = null,
        baseMs: Long = 1000,
        capMs: Long = 30000,
    ): ReconnectPolicy {
        val rng = Random(seed)
        return ReconnectPolicy(
            baseMs = baseMs,
            capMs = capMs,
            giveUpAfterMs = giveUpAfterMs,
            random01 = rng::nextDouble,
        )
    }

    @Test
    fun `attempt zero is exactly base regardless of random value`() {
        val policy = seededPolicy()
        assertEquals(1000L, policy.delayForAttempt(0, 0.0))
        assertEquals(1000L, policy.delayForAttempt(0, 0.999))
    }

    @Test
    fun `deterministic delay for fixed attempt and random value`() {
        val policy = seededPolicy()
        // bound(attempt=3) = min(30000, 1000*8) = 8000 → [1000, 8000]
        assertEquals(1000 + (7000.0 * 0.5).toLong(), policy.delayForAttempt(3, 0.5))
    }

    @Test
    fun `seeded distribution stays within full-jitter floor bounds`() {
        val policy = seededPolicy(seed = 1234)
        val samplesPerAttempt = 2_000
        for (attempt in 0..10) {
            val bound = policy.boundForAttempt(attempt)
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            repeat(samplesPerAttempt) {
                val d = policy.delayForAttempt(attempt)
                min = minOf(min, d)
                max = maxOf(max, d)
            }
            assertTrue(min >= 1000L, "attempt=$attempt min=$min < base")
            assertTrue(max <= bound, "attempt=$attempt max=$max > cap-bound $bound")
            // Distribution actually spans the jitter range (not degenerate).
            if (bound > 1000L) {
                assertTrue(max - min > (bound - 1000L) / 4, "attempt=$attempt range collapsed: [$min,$max]")
            }
        }
    }

    @Test
    fun `bounds double per attempt and respect the cap`() {
        val policy = seededPolicy()
        assertEquals(1000L, policy.boundForAttempt(0))
        assertEquals(2000L, policy.boundForAttempt(1))
        assertEquals(4000L, policy.boundForAttempt(2))
        assertEquals(8000L, policy.boundForAttempt(3))
        assertEquals(16000L, policy.boundForAttempt(4))
        assertEquals(30000L, policy.boundForAttempt(5)) // capped at 32k→30k
        assertEquals(30000L, policy.boundForAttempt(6))
        assertEquals(30000L, policy.boundForAttempt(50))
        assertEquals(30000L, policy.boundForAttempt(Int.MAX_VALUE))
    }

    @Test
    fun `negative attempt clamps to zero`() {
        val policy = seededPolicy()
        assertEquals(1000L, policy.boundForAttempt(-3))
    }

    @Test
    fun `no give-up budget means never giving up`() {
        val policy = seededPolicy(giveUpAfterMs = null)
        assertFalse(policy.shouldGiveUp(0))
        assertFalse(policy.shouldGiveUp(Long.MAX_VALUE / 2))
    }

    @Test
    fun `give-up boundary is inclusive`() {
        val policy = seededPolicy(giveUpAfterMs = 60_000)
        assertFalse(policy.shouldGiveUp(59_999))
        assertTrue(policy.shouldGiveUp(60_000))
        assertTrue(policy.shouldGiveUp(120_000))
    }

    @Test
    fun `stateful nextDelay advances and reset restarts from base`() {
        val policy = seededPolicy()
        val first = policy.nextDelay()
        assertEquals(0, policy.currentAttemptValue - 1)
        val second = policy.nextDelay()
        assertTrue(second >= first || second >= 1000) // both valid; counter advanced
        assertEquals(2, policy.currentAttemptValue)

        policy.reset()
        assertEquals(0, policy.currentAttemptValue)
        // After reset the sequence restarts deterministically with the same rng stream position.
        val afterResetFirst = policy.nextDelay()
        assertEquals(first, afterResetFirst) // same attempt index 0 → same draw
    }
}
