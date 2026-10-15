package com.transfer.flash.core.security.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ERROR-402: the identity certificate must end before 2050 or UTCTime wraps it to 1951. */
class IdentityCertValidityTest {
    private val jan2026 = 1_767_225_600_000L

    @Test
    fun `a key generated in 2026 ends on the 2049 cap, not 2051`() {
        assertEquals(CERT_LATEST_NOT_AFTER_MS, identityCertNotAfterMs(jan2026))
    }

    @Test
    fun `a clock far in the past keeps the full 25 years`() {
        val t = 1_000_000_000_000L
        assertEquals(t + 25L * 365 * 24 * 60 * 60 * 1000, identityCertNotAfterMs(t))
    }

    @Test
    fun `the result is always before 2050`() {
        for (now in longArrayOf(0L, jan2026, 2_000_000_000_000L, 4_000_000_000_000L)) {
            assertTrue(identityCertNotAfterMs(now) <= CERT_LATEST_NOT_AFTER_MS)
        }
    }
}
