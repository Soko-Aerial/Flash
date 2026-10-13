package com.transfer.flash.server.auth

import com.transfer.flash.server.admin.AdminListenerPolicy
import com.transfer.flash.server.admin.CsrfGuard
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.config.TlsSupport
import io.ktor.http.HttpMethod
import java.nio.file.Files
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SEC-09 / SEC-13 / SEC-06 building blocks, tested on their own (no HTTP): limiter, proxy trust, CSRF, TLS. */
class AbuseControlsTest {

    // ------------------------------------------------------------------------------------------ rate limiter

    @Test
    fun aBucketAllowsItsBurstThenRefillsWithTime() {
        var t = 0L
        val limiter = TokenBucketLimiter(capacity = 3, refillPerSecond = 1.0, clock = { t })
        repeat(3) { assertTrue(limiter.tryAcquire("ip")) }
        assertFalse(limiter.tryAcquire("ip"), "burst exhausted")
        assertTrue(limiter.retryAfterSeconds("ip") >= 1)
        t += 1_000
        assertTrue(limiter.tryAcquire("ip"), "one token came back")
        assertFalse(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("other"), "keys are independent")
    }

    @Test
    fun theKeyMapIsBoundedSoRotatingKeysCannotGrowMemory() {
        var t = 0L
        val limiter = TokenBucketLimiter(capacity = 1, refillPerSecond = 0.001, maxKeys = 50, clock = { t })
        repeat(1_000) { limiter.tryAcquire("attacker-$it") }
        assertTrue(limiter.size <= 51, "size ${limiter.size} stays near the cap")
        assertFalse(limiter.tryAcquire("attacker-new"), "once full, new keys share one throttled bucket")
        t += 11 * 60 * 1000
        limiter.sweep()
        assertEquals(0, limiter.size, "idle buckets expire")
    }

    @Test
    fun aKnownKeyKeepsItsOwnBucketWhileAFloodOfNewKeysSharesTheOverflowBucket() {
        var t = 0L
        val limiter = TokenBucketLimiter(capacity = 5, refillPerSecond = 0.001, maxKeys = 10, clock = { t })
        assertTrue(limiter.tryAcquire("office"), "an established client")
        repeat(100) { limiter.tryAcquire("flood-$it", cost = 5) }
        // Overflow is defined behaviour: strangers share ONE bucket (throttled together), clients already known are not affected.
        assertTrue(limiter.tryAcquire("office"), "the known key still has its own tokens")
        assertFalse(limiter.tryAcquire("stranger-1", cost = 5), "a new key during the flood is throttled with the flood")
        t += 11 * 60 * 1000
        assertTrue(limiter.tryAcquire("stranger-2"), "after the flood the idle buckets expire and new keys get their own again")
    }

    // ------------------------------------------------------------------------------------------ trusted proxies

    @Test
    fun forwardedHeadersAreIgnoredFromAnUntrustedPeer() {
        val proxies = TrustedProxies(listOf("10.0.0.0/8"))
        assertEquals("203.0.113.9", proxies.clientAddress("203.0.113.9", "1.2.3.4"), "a direct client can not choose its address")
        assertEquals("1.2.3.4", proxies.clientAddress("10.1.1.1", "1.2.3.4"))
    }

    @Test
    fun theRightmostUntrustedEntryWinsSoPrependedEntriesDoNotForge() {
        val proxies = TrustedProxies(listOf("10.0.0.0/8"))
        // The client sent "6.6.6.6"; the proxy appended the address it really saw (203.0.113.9), then a second proxy.
        assertEquals("203.0.113.9", proxies.clientAddress("10.0.0.2", "6.6.6.6, 203.0.113.9, 10.0.0.1"))
        assertEquals("10.0.0.2", proxies.clientAddress("10.0.0.2", "not-an-ip"), "garbage is not guessed at")
    }

    @Test
    fun withNoTrustedProxyNothingIsBelievedAndMalformedEntriesFailFast() {
        val none = TrustedProxies()
        assertTrue(none.isEmpty)
        assertEquals("10.0.0.2", none.clientAddress("10.0.0.2", "1.2.3.4"))
        assertFalse(none.forwardedAsHttps("10.0.0.2", "https"))
        assertFailsWith<IllegalArgumentException> { TrustedProxies(listOf("10.0.0.0/33")) }
        assertFailsWith<IllegalArgumentException> { TrustedProxies(listOf("proxy.example.org")) }
        assertTrue(TrustedProxies(listOf("::1")).isTrusted("::1"))
    }

    @Test
    fun httpsIsBelievedOnlyFromATrustedProxy() {
        val proxies = TrustedProxies(listOf("127.0.0.1"))
        assertTrue(proxies.forwardedAsHttps("127.0.0.1", "https"))
        assertFalse(proxies.forwardedAsHttps("8.8.8.8", "https"))
        assertFalse(proxies.forwardedAsHttps("127.0.0.1", "http"))
    }

    // ------------------------------------------------------------------------------------------ CSRF

    private fun csrf(
        method: HttpMethod = HttpMethod.Post,
        origin: String? = null,
        fetchSite: String? = null,
        host: String? = "hub.example.org",
        token: String? = "t",
        session: String? = "t",
        requireToken: Boolean = true,
    ) = CsrfGuard.check(method, origin, fetchSite, host, "https://hub.example.org", token, session, requireToken)

    @Test
    fun safeMethodsPassAndStateChangesNeedAMatchingToken() {
        assertEquals(CsrfGuard.Verdict.OK, csrf(method = HttpMethod.Get, token = null))
        assertEquals(CsrfGuard.Verdict.OK, csrf())
        assertEquals(CsrfGuard.Verdict.BAD_TOKEN, csrf(token = null))
        assertEquals(CsrfGuard.Verdict.BAD_TOKEN, csrf(token = "x"))
        assertEquals(CsrfGuard.Verdict.BAD_TOKEN, csrf(session = null))
        assertEquals(CsrfGuard.Verdict.OK, csrf(token = null, requireToken = false), "HTML forms rely on origin + SameSite")
    }

    @Test
    fun aForeignOriginOrCrossSiteFetchIsRefusedBeforeTheTokenIsLookedAt() {
        assertEquals(CsrfGuard.Verdict.BAD_ORIGIN, csrf(origin = "https://evil.example"))
        assertEquals(CsrfGuard.Verdict.BAD_ORIGIN, csrf(origin = "null"))
        assertEquals(CsrfGuard.Verdict.BAD_ORIGIN, csrf(fetchSite = "cross-site"))
        assertEquals(CsrfGuard.Verdict.BAD_ORIGIN, csrf(fetchSite = "same-site"))
        assertEquals(CsrfGuard.Verdict.OK, csrf(origin = "https://hub.example.org", fetchSite = "same-origin"))
        assertEquals(CsrfGuard.Verdict.OK, csrf(origin = "https://hub.example.org", host = "internal:8443"), "the configured external URL is accepted too")
    }

    @Test
    fun theConsoleIsReachableOnlyOnTheManagementPortUnlessOpened() {
        val policy = AdminListenerPolicy(publicPort = 8443, adminPort = 8444, adminOnPublicPort = false)
        assertTrue(policy.allows(8444))
        assertFalse(policy.allows(8443), "the public listener never serves /admin")
        assertTrue(AdminListenerPolicy(8443, 8444, adminOnPublicPort = true).allows(8443))
        assertTrue(AdminListenerPolicy(9000, 9000, adminOnPublicPort = false).allows(9000), "a single-port hub has only one listener")
    }

    // ------------------------------------------------------------------------------------------ TLS

    @Test
    fun selfModeGeneratesOneStableOwnerOnlyCertificateAndReusesIt() {
        val dir = Files.createTempDirectory("flash-tls-test").toFile()
        try {
            val config = ServerConfig(dataDir = dir.path, tlsMode = "self", externalUrl = "https://hub.example.org:8443")
            val first = assertNotNull(TlsSupport.loadOrCreate(config))
            assertTrue(first.generated)
            val cert = first.keyStore.getCertificate(first.alias) as X509Certificate
            assertEquals(first.fingerprint, TlsSupport.fingerprintOf(cert))
            assertTrue(cert.subjectAlternativeNames.orEmpty().any { it[1] == "hub.example.org" }, "the external host is a SAN")
            assertTrue(SecureFiles.isOwnerOnly(dir.resolve("tls/hub-tls.p12").toPath()))
            assertTrue(SecureFiles.isOwnerOnly(dir.resolve("tls/hub-tls.pass").toPath()))

            val second = assertNotNull(TlsSupport.loadOrCreate(config))
            assertFalse(second.generated, "a restart keeps the certificate (clients pin its fingerprint)")
            assertEquals(first.fingerprint, second.fingerprint)

            val renewed = assertNotNull(TlsSupport.loadOrCreate(config, nowMs = System.currentTimeMillis() + 340L * 24 * 3600 * 1000))
            assertTrue(renewed.generated, "within 30 days of expiry a new certificate is made")
            assertNotEquals(first.fingerprint, renewed.fingerprint)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun proxyAndNoneModesTerminateNoTlsAndASuppliedKeystoreMustExist() {
        assertNull(TlsSupport.loadOrCreate(ServerConfig(tlsMode = "proxy")))
        assertNull(TlsSupport.loadOrCreate(ServerConfig(tlsMode = "none", allowInsecure = true, externalUrl = "http://localhost:8443")))
        val missing = ServerConfig(tlsMode = "self", tlsKeystoreFile = "/no/such/keystore.p12", tlsKeystorePassword = "x")
        assertFailsWith<IllegalArgumentException> { TlsSupport.loadOrCreate(missing) }
    }

    @Test
    fun theConnectorsFollowThePortsAndTheAdminListenerIsLoopbackByDefault() {
        val config = ServerConfig(port = 9100, adminPort = 9101)
        val connectors = TlsSupport.connectors(config, null)
        assertEquals(listOf(9100, 9101), connectors.map { it.port })
        assertEquals("127.0.0.1", connectors[1].host)
        val single = TlsSupport.connectors(ServerConfig(port = 9100, adminPort = 9100, adminOnPublicPort = true), null)
        assertEquals(1, single.size)
    }
}
