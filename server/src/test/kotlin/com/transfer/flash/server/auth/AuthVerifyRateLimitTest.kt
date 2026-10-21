@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** SRVHUB-08 (AUTH-01, ADR-222 decision 4): `/auth/verify` is limited per device, so a hub restart behind one NAT is not a lockout. */
class AuthVerifyRateLimitTest {

    private val root = FlashCrypto.generateEcKeyPair()
    private val server = FlashCrypto.generateEcKeyPair()
    private val now = 1_800_000_000_000L
    private val delegation = DelegationCert.issue(
        rootPrivateKey = root.private,
        rootPublicKey = root.toBase64Spki(),
        delegatePublicKey = server.toBase64Spki(),
        scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
        validFrom = now - 60_000,
        validTo = now + 90L * 86_400_000L,
        maxDevices = 500,
        serial = 1L,
    )

    private fun ApplicationTestBuilder.mount(limits: HubRateLimits): ChallengeResponseAuth {
        val auth = ChallengeResponseAuth(root.toBase64Spki())
        val enrol = EnrollmentService(root.toBase64Spki(), server.private, delegation)
        routing { authRoutes(auth, enrol, limits = limits) }
        return auth
    }

    /** A verify with a wrong signature: it is refused with 401 after the limiters, which is all these tests need. */
    private suspend fun ApplicationTestBuilder.verify(auth: ChallengeResponseAuth, deviceId: String) =
        client.post("/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            val challenge = HubRestCodec.decode<AuthChallengeResponse>(client.get("/api/v1/auth/challenge").bodyAsText())
            val key = FlashCrypto.generateEcKeyPair()
            val cert = DeviceCert.issue(
                issuerPrivateKey = server.private,
                issuerPublicKey = server.toBase64Spki(),
                deviceId = deviceId,
                subjectPublicKey = key.toBase64Spki(),
                role = DeviceCert.ROLE_MEMBER,
                validFrom = now - 1_000,
                validTo = now + 86_400_000L,
                serial = 7L,
            )
            val signature = FlashCrypto.sign(FlashCrypto.generateEcKeyPair().private, HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, "cn", deviceId, 7L))
            setBody(HubRestCodec.encode(AuthVerifyRequest(challenge.serverNonce, "cn", deviceId, cert, listOf(delegation), signature)))
        }

    @Test
    fun twoHundredDevicesBehindOneAddressAreNotLimitedByEachOther() = testApplication {
        // Challenges stay per address (burst 30 here would block 200 devices), so give them room and test the verify path itself.
        val l = HubRateLimits(clock = { now }, auth = TokenBucketLimiter(100_000, 1000.0, clock = { now }))
        val auth = mount(l)
        val statuses = (1..200).map { verify(auth, "dev-$it").status }
        assertEquals(setOf(HttpStatusCode.Unauthorized), statuses.toSet(), "every device meets the hub's answer, none a 429")
    }

    @Test
    fun oneDeviceIsStillLimitedToItsOwnBurst() = testApplication {
        val l = HubRateLimits(clock = { now }, auth = TokenBucketLimiter(100_000, 1000.0, clock = { now }))
        val auth = mount(l)
        val statuses = (1..8).map { verify(auth, "dev-loud").status }
        assertEquals(List(5) { HttpStatusCode.Unauthorized }, statuses.take(5))
        assertEquals(List(3) { HttpStatusCode.TooManyRequests }, statuses.drop(5), "burst 5, then 429")
        // The neighbour is untouched.
        assertNotEquals(HttpStatusCode.TooManyRequests, verify(auth, "dev-quiet").status)
    }

    @Test
    fun theDeviceBucketRefillsOneTokenEveryFiveSeconds() = testApplication {
        var t = now
        val l = HubRateLimits(clock = { t }, auth = TokenBucketLimiter(100_000, 1000.0, clock = { t }))
        val auth = mount(l)
        repeat(5) { verify(auth, "dev-a") }
        assertEquals(HttpStatusCode.TooManyRequests, verify(auth, "dev-a").status)
        t += 5_000
        assertEquals(HttpStatusCode.Unauthorized, verify(auth, "dev-a").status)
        assertEquals(HttpStatusCode.TooManyRequests, verify(auth, "dev-a").status)
    }

    @Test
    fun garbageWithoutADeviceIdMeetsTheStrictPerAddressLimit() = testApplication {
        mount(HubRateLimits(clock = { now }, auth = TokenBucketLimiter(3, 0.001, clock = { now })))
        val bodies = (1..5).map {
            client.post("/api/v1/auth/verify") {
                contentType(ContentType.Application.Json)
                setBody("not json")
            }
        }
        assertEquals(List(3) { HttpStatusCode.BadRequest }, bodies.take(3).map { it.status })
        assertEquals(List(2) { HttpStatusCode.TooManyRequests }, bodies.drop(3).map { it.status })
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, HubRestCodec.decode<ErrorBody>(bodies.last().bodyAsText()).code)
    }

    @Test
    fun theChallengeStaysPerAddress() = testApplication {
        mount(HubRateLimits(clock = { now }, auth = TokenBucketLimiter(2, 0.001, clock = { now })))
        val statuses = (1..4).map { client.get("/api/v1/auth/challenge").status }
        assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.TooManyRequests, HttpStatusCode.TooManyRequests), statuses)
    }

    @Test
    fun madeUpDeviceIdsCannotBeUsedToBypassTheAddressCeiling() = testApplication {
        val auth = mount(
            HubRateLimits(
                clock = { now },
                auth = TokenBucketLimiter(100_000, 1000.0, clock = { now }),
                verifyIp = TokenBucketLimiter(10, 0.001, clock = { now }),
            ),
        )
        val statuses = (1..14).map { verify(auth, "fake-$it").status }
        assertEquals(10, statuses.count { it == HttpStatusCode.Unauthorized })
        assertEquals(4, statuses.count { it == HttpStatusCode.TooManyRequests })
    }
}
