@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.tls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransportSecurityTest {

    @Test
    fun returnsTheOptionsWhenTheFirstAttemptSucceeds() {
        var calls = 0
        val options = requireTransportSecurity { calls++; "tls" }
        assertEquals("tls", options)
        assertEquals(1, calls)
    }

    @Test
    fun retriesOnceThenSucceeds() {
        var calls = 0
        val failures = mutableListOf<Int>()
        val options = requireTransportSecurity(onAttemptFailed = { attempt, _ -> failures += attempt }) {
            calls++
            if (calls == 1) error("keystore hiccup") else "tls"
        }
        assertEquals("tls", options)
        assertEquals(listOf(1), failures)
    }

    @Test
    fun failsClosedInsteadOfReturningAPlaintextFallback() {
        // Audit S3: there must be no path that yields "no TLS" — only options or an exception.
        val cause = IllegalStateException("no keystore")
        var calls = 0
        val thrown = assertFailsWith<TransportSecurityUnavailableException> {
            requireTransportSecurity<String> { calls++; throw cause }
        }
        assertEquals(2, calls, "default budget is one retry")
        assertSame(cause, thrown.cause)
        assertTrue(thrown.message!!.contains("will not start without TLS"))
    }
}
