package com.transfer.flash.core.network.tls

import com.transfer.flash.core.security.trust.TrustChangeHub
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A resumed TLS 1.3 session does not re-run the trust manager, so a cached SSLContext must not
 * survive a trust change. These tests pin the invalidation contract at the cache boundary; the
 * store-side publish is covered by `FlashTrustStoreTest`.
 */
class FlashTlsContextFactoryTrustChangeTest {

    private val verifier = FlashPinVerifier { _, _ -> true }

    @Test
    fun clientContextIsEvictedWhenTrustChanges() {
        val deviceId = "peer-tls-eviction-client"
        val first = FlashTlsContextFactory.clientContext(verifier, deviceId)
        val second = FlashTlsContextFactory.clientContext(verifier, deviceId)
        assertSame("the per-peer context must be reused while trust is unchanged", first, second)

        TrustChangeHub.publish(deviceId)

        val third = FlashTlsContextFactory.clientContext(verifier, deviceId)
        assertNotSame("a trust change must drop the cached client context", second, third)
    }

    @Test
    fun serverContextIsEvictedWhenTrustChanges() {
        val deviceId = "peer-tls-eviction-server"
        val first = FlashTlsContextFactory.serverContext(verifier, expectedDeviceId = deviceId)
        val second = FlashTlsContextFactory.serverContext(verifier, expectedDeviceId = deviceId)
        assertSame("the per-peer context must be reused while trust is unchanged", first, second)

        TrustChangeHub.publish(deviceId)

        val third = FlashTlsContextFactory.serverContext(verifier, expectedDeviceId = deviceId)
        assertNotSame("a trust change must drop the cached server context", second, third)
    }
}
