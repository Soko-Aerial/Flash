@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubUrlPolicyTest {

    private fun check(url: String, allowCleartext: Boolean = false) = HubUrlPolicy.check(url, allowCleartext)

    @Test
    fun secureSchemesAreAccepted() {
        assertEquals(HubUrlPolicy.Verdict.OK, check("https://hub.example.test"))
        assertEquals(HubUrlPolicy.Verdict.OK, check("wss://hub.example.test:8443/flash-relay"))
    }

    @Test
    fun cleartextIsRefusedExceptLoopbackOrTheDevelopmentOptIn() {
        assertEquals(HubUrlPolicy.Verdict.CLEARTEXT_REFUSED, check("http://hub.example.test"))
        assertEquals(HubUrlPolicy.Verdict.CLEARTEXT_REFUSED, check("ws://192.168.1.50:8443"))
        assertEquals(HubUrlPolicy.Verdict.OK, check("http://127.0.0.1:8443"))
        assertEquals(HubUrlPolicy.Verdict.OK, check("ws://localhost:8443/flash-relay"))
        assertEquals(HubUrlPolicy.Verdict.OK, check("http://192.168.1.50:8443", allowCleartext = true))
    }

    @Test
    fun garbageAndCredentialsInTheAddressAreInvalid() {
        assertEquals(HubUrlPolicy.Verdict.INVALID, check(""))
        assertEquals(HubUrlPolicy.Verdict.INVALID, check("ftp://hub.example.test"))
        assertEquals(HubUrlPolicy.Verdict.INVALID, check("https://user:pw@hub.example.test"))
        assertEquals(HubUrlPolicy.Verdict.INVALID, check("https://"))
    }

    @Test
    fun baseUrlStripsTheRelayPathAndMapsWebSocketSchemes() {
        assertEquals("https://hub.example.test:8443", HubUrlPolicy.baseUrl("wss://hub.example.test:8443/flash-relay"))
        assertEquals("http://127.0.0.1:8443", HubUrlPolicy.baseUrl("ws://127.0.0.1:8443"))
    }

    @Test
    fun sameHubComparesHostAndEffectivePortOnly() {
        assertTrue(HubUrlPolicy.sameHub("https://hub.example.test", "wss://hub.example.test:443/flash-relay"))
        assertFalse(HubUrlPolicy.sameHub("https://hub.example.test", "https://evil.example.test"))
        assertFalse(HubUrlPolicy.sameHub("https://hub.example.test:8443", "https://hub.example.test"))
    }
}
