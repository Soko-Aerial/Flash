package com.transfer.flash.core.discovery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryRetryPolicyTest {

    @Test
    fun delays_doubleDeterministically_fromBaseUpToCap() {
        val policy = DiscoveryRetryPolicy(baseDelayMs = 1000, maxDelayMs = 30_000, maxAttempts = 5)
        assertEquals(1000L, policy.delayForAttempt(1))
        assertEquals(2000L, policy.delayForAttempt(2))
        assertEquals(4000L, policy.delayForAttempt(3))
        assertEquals(8000L, policy.delayForAttempt(4))
        assertEquals(16000L, policy.delayForAttempt(5))
    }

    @Test
    fun delays_capAtMaxDelay() {
        val policy = DiscoveryRetryPolicy(baseDelayMs = 3000, maxDelayMs = 5000, maxAttempts = 5)
        assertEquals(3000L, policy.delayForAttempt(1))
        assertEquals(5000L, policy.delayForAttempt(2))
        assertEquals(5000L, policy.delayForAttempt(3))
        assertEquals(5000L, policy.delayForAttempt(4))
        assertEquals(5000L, policy.delayForAttempt(5))
    }

    @Test
    fun giveUp_afterMaxAttempts_returnsNull() {
        val policy = DiscoveryRetryPolicy(maxAttempts = 5)
        assertNull(policy.delayForAttempt(6))
        assertNull(policy.delayForAttempt(100))
    }

    @Test
    fun invalidAttempts_returnNull() {
        val policy = DiscoveryRetryPolicy()
        assertNull(policy.delayForAttempt(0))
        assertNull(policy.delayForAttempt(-3))
    }

    @Test
    fun deterministic_repeatedCallsReturnIdenticalValues_noJitter() {
        val policy = DiscoveryRetryPolicy()
        val first = (1..5).map { policy.delayForAttempt(it) }
        val second = (1..5).map { policy.delayForAttempt(it) }
        assertEquals(first, second)
    }

    @Test
    fun reset_isCallableAndKeepsBehaviorIdentical() {
        val policy = DiscoveryRetryPolicy()
        val before = policy.delayForAttempt(3)
        policy.reset()
        assertEquals(before, policy.delayForAttempt(3))
        assertNull(policy.delayForAttempt(6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsNonPositiveBaseDelay() {
        DiscoveryRetryPolicy(baseDelayMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsMaxBelowBase() {
        DiscoveryRetryPolicy(baseDelayMs = 1000, maxDelayMs = 999)
    }
}
