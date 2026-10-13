package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.EnrollRequest
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
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
import io.ktor.server.testing.testApplication
import java.security.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChallengeResponseAuthTest {

    private fun setupTestKeys(): Triple<KeyPair, KeyPair, DelegationCert> {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val serverKey = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()

        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = serverKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 5_000,
            validTo = now + 600_000,
            maxDevices = 10,
            serial = 1L,
        )
        return Triple(rootKey, serverKey, delegation)
    }

    private fun enrollProof(service: EnrollmentService, key: KeyPair, token: String, deviceId: String): String =
        FlashCrypto.sign(
            key.private,
            HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), service.serverId),
        )

    @Test
    fun testChallengeGeneration() {
        val (rootKey, _, _) = setupTestKeys()
        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())

        val challenge = auth.generateChallenge()
        assertNotNull(challenge.serverNonce)
        assertTrue(challenge.serverNonce.isNotBlank())
        assertEquals(60L, challenge.ttlSeconds)
        assertTrue(challenge.timestamp > 0)
    }

    @Test
    fun testValidSignatureAcceptance() {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val clientKey = FlashCrypto.generateEcKeyPair()
        val deviceId = "gh-radio-unit-42"
        val now = System.currentTimeMillis()

        val deviceCert = DeviceCert.issue(
            issuerPrivateKey = serverKey.private,
            issuerPublicKey = serverKey.toBase64Spki(),
            deviceId = deviceId,
            subjectPublicKey = clientKey.toBase64Spki(),
            role = DeviceCert.ROLE_MEMBER,
            department = "GH-SIG-CORPS",
            validFrom = now - 1_000,
            validTo = now + 60_000,
            serial = 201L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        val challenge = auth.generateChallenge(now)

        val clientNonce = "client-nonce-xyz-123"
        val payload = HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, clientNonce, deviceId, deviceCert.serial)
        val signature = FlashCrypto.sign(clientKey.private, payload)

        val result = auth.verifyChallenge(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = deviceId,
            signature = signature,
            deviceCert = deviceCert,
            delegationCert = delegation,
            currentTimeMs = now + 2_000,
        )

        assertTrue(result.isSuccess, "Valid challenge response must authenticate successfully")
        val principal = result.getOrThrow()
        assertEquals(deviceId, principal.deviceId)
        assertEquals(DeviceCert.ROLE_MEMBER, principal.role)
        assertEquals("GH-SIG-CORPS", principal.department)
        assertEquals(201L, principal.certSerial)
    }

    @Test
    fun testExpiredNonceRejection() {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val clientKey = FlashCrypto.generateEcKeyPair()
        val deviceId = "gh-radio-unit-43"
        val now = System.currentTimeMillis()

        val deviceCert = DeviceCert.issue(
            issuerPrivateKey = serverKey.private,
            issuerPublicKey = serverKey.toBase64Spki(),
            deviceId = deviceId,
            subjectPublicKey = clientKey.toBase64Spki(),
            validFrom = now - 1_000,
            validTo = now + 60_000,
            serial = 202L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        // Generate challenge 65 seconds in the past
        val challenge = auth.generateChallenge(now - 65_000)

        val clientNonce = "client-nonce-456"
        val payload = HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, clientNonce, deviceId, deviceCert.serial)
        val signature = FlashCrypto.sign(clientKey.private, payload)

        val result = auth.verifyChallenge(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = deviceId,
            signature = signature,
            deviceCert = deviceCert,
            delegationCert = delegation,
            currentTimeMs = now,
        )

        assertTrue(result.isFailure, "Expired server nonce must be rejected")
        assertTrue(result.exceptionOrNull()?.message?.contains("expired", ignoreCase = true) == true)
    }

    @Test
    fun testBadSignatureRejection() {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val clientKey = FlashCrypto.generateEcKeyPair()
        val wrongKey = FlashCrypto.generateEcKeyPair()
        val deviceId = "gh-radio-unit-44"
        val now = System.currentTimeMillis()

        val deviceCert = DeviceCert.issue(
            issuerPrivateKey = serverKey.private,
            issuerPublicKey = serverKey.toBase64Spki(),
            deviceId = deviceId,
            subjectPublicKey = clientKey.toBase64Spki(),
            validFrom = now - 1_000,
            validTo = now + 60_000,
            serial = 203L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        val challenge = auth.generateChallenge(now)

        val clientNonce = "client-nonce-789"
        val payload = HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, clientNonce, deviceId, deviceCert.serial)
        // Sign with WRONG private key
        val badSignature = FlashCrypto.sign(wrongKey.private, payload)

        val result = auth.verifyChallenge(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = deviceId,
            signature = badSignature,
            deviceCert = deviceCert,
            delegationCert = delegation,
            currentTimeMs = now,
        )

        assertTrue(result.isFailure, "Bad signature must be rejected")
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testSingleUseNonceReplayRejection() {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val clientKey = FlashCrypto.generateEcKeyPair()
        val deviceId = "gh-radio-unit-45"
        val now = System.currentTimeMillis()

        val deviceCert = DeviceCert.issue(
            issuerPrivateKey = serverKey.private,
            issuerPublicKey = serverKey.toBase64Spki(),
            deviceId = deviceId,
            subjectPublicKey = clientKey.toBase64Spki(),
            validFrom = now - 1_000,
            validTo = now + 60_000,
            serial = 204L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        val challenge = auth.generateChallenge(now)
        val clientNonce = "client-nonce-replay"
        val payload = HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, clientNonce, deviceId, deviceCert.serial)
        val signature = FlashCrypto.sign(clientKey.private, payload)

        // First verification: success
        val firstResult = auth.verifyChallenge(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = deviceId,
            signature = signature,
            deviceCert = deviceCert,
            delegationCert = delegation,
            currentTimeMs = now,
        )
        assertTrue(firstResult.isSuccess, "First verification attempt must succeed")

        // Second verification attempt with same serverNonce: must FAIL
        val secondResult = auth.verifyChallenge(
            serverNonce = challenge.serverNonce,
            clientNonce = clientNonce,
            deviceId = deviceId,
            signature = signature,
            deviceCert = deviceCert,
            delegationCert = delegation,
            currentTimeMs = now + 1_000,
        )
        assertTrue(secondResult.isFailure, "Reused server nonce must be rejected (replay attack prevention)")
    }

    @Test
    fun testSingleUseEnrollmentTokenWorkflowAndQuota() {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val rootPublicKey = rootKey.toBase64Spki()
        val clientKey1 = FlashCrypto.generateEcKeyPair()
        val clientKey2 = FlashCrypto.generateEcKeyPair()
        val clientKey3 = FlashCrypto.generateEcKeyPair()

        val now = System.currentTimeMillis()
        val service = EnrollmentService(
            rootPublicKey = rootPublicKey,
            serverPrivateKey = serverKey.private,
            delegationCert = delegation,
            initialQuota = 2, // Quota allows exactly 2 devices
        )

        assertEquals(2, service.currentRemainingQuota)

        // 1. Generate invite link
        val scope = InviteScope(
            department = "DEFENCE-INTELLIGENCE",
            role = DeviceCert.ROLE_MEMBER,
            expiry = now + 300_000,
            maxUses = 1,
        )
        val link = service.generateInviteLink("https://flash.mod.gov.gh", scope, now)
        assertTrue(link.startsWith("flash://enroll?server="), "Link must use flash://enroll scheme")
        assertTrue(link.contains("token="), "Link must contain token")
        val expectedRootFp = FlashCrypto.sha256FingerprintHex(rootPublicKey)
        assertTrue(link.contains("root=$expectedRootFp"), "Link must contain root fingerprint")

        val token = link.substringAfter("token=").substringBefore("&")

        // 2. First enrollment with token: must succeed
        val enroll1 = service.enrollDevice(
            token = token,
            deviceId = "phone-scout-01",
            subjectPublicKey = clientKey1.toBase64Spki(),
            proof = enrollProof(service, clientKey1, token, "phone-scout-01"),
            friendlyName = "Scout 1",
            currentTimeMs = now,
        )
        assertTrue(enroll1.isSuccess, "First enrollment with valid token must succeed")
        val res1 = enroll1.getOrThrow()
        assertEquals("phone-scout-01", res1.deviceCert.deviceId)
        assertEquals("DEFENCE-INTELLIGENCE", res1.deviceCert.department)
        assertEquals(expectedRootFp, res1.rootFingerprint)
        assertTrue(res1.deviceCert.verifyChain(delegation, rootPublicKey, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L))
        assertTrue(res1.deviceCert.validTo <= delegation.validTo, "SEC-18: the leaf never outlives its delegation")
        assertEquals(rootPublicKey, res1.rootPublicKey)
        assertEquals(1, service.currentRemainingQuota, "Quota should be decremented to 1")

        // 3. Second enrollment with SAME token: must FAIL (single-use consumed)
        val enrollReuse = service.enrollDevice(
            token = token,
            deviceId = "phone-scout-imposter",
            subjectPublicKey = clientKey2.toBase64Spki(),
            proof = enrollProof(service, clientKey2, token, "phone-scout-imposter"),
            currentTimeMs = now,
        )
        assertTrue(enrollReuse.isFailure, "Reusing single-use token must fail")

        // 4. Enroll second device with fresh token: must succeed
        val link2 = service.generateInviteLink("https://flash.mod.gov.gh", scope, now)
        val token2 = link2.substringAfter("token=").substringBefore("&")
        val enroll2 = service.enrollDevice(
            token = token2,
            deviceId = "phone-scout-02",
            subjectPublicKey = clientKey2.toBase64Spki(),
            proof = enrollProof(service, clientKey2, token2, "phone-scout-02"),
            currentTimeMs = now,
        )
        assertTrue(enroll2.isSuccess, "Second enrollment with fresh token must succeed")
        assertEquals(0, service.currentRemainingQuota, "Quota should be decremented to 0")

        // 5. Enroll third device when quota is exhausted: must FAIL
        val link3 = service.generateInviteLink("https://flash.mod.gov.gh", scope, now)
        val token3 = link3.substringAfter("token=").substringBefore("&")
        val enroll3 = service.enrollDevice(
            token = token3,
            deviceId = "phone-scout-03",
            subjectPublicKey = clientKey3.toBase64Spki(),
            proof = enrollProof(service, clientKey3, token3, "phone-scout-03"),
            currentTimeMs = now,
        )
        assertTrue(enroll3.isFailure, "Enrollment must fail when server device quota is exhausted")
        assertTrue(enroll3.exceptionOrNull()?.message?.contains("quota", ignoreCase = true) == true)
    }

    @Test
    fun enrolmentRequiresValidProofOfPossessionAndDoesNotBurnTheToken() {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val rootPublicKey = rootKey.toBase64Spki()
        val owner = FlashCrypto.generateEcKeyPair()
        val thief = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()
        val service = EnrollmentService(rootPublicKey, serverKey.private, delegation, initialQuota = 5)
        val link = service.generateInviteLink("https://hub.example", InviteScope(expiry = now + 60_000), now)
        val token = link.substringAfter("token=").substringBefore("&")

        // A proof made by a different key than the one being enrolled must be refused (SEC-14 / CLI-10).
        val forged = service.enrollDevice(
            token = token,
            deviceId = "dev-pop",
            subjectPublicKey = owner.toBase64Spki(),
            proof = enrollProof(service, thief, token, "dev-pop"),
            currentTimeMs = now,
        )
        assertTrue(forged.isFailure, "A proof by another key must fail")
        assertTrue(forged.exceptionOrNull() is SecurityException)
        assertEquals(5, service.currentRemainingQuota, "A failed proof must not consume quota")

        // The token is still usable by the real key holder.
        val ok = service.enrollDevice(
            token = token,
            deviceId = "dev-pop",
            subjectPublicKey = owner.toBase64Spki(),
            proof = enrollProof(service, owner, token, "dev-pop"),
            currentTimeMs = now,
        )
        assertTrue(ok.isSuccess, "The real key holder can still use the token")

        // A proof for another deviceId does not transfer.
        val link2 = service.generateInviteLink("https://hub.example", InviteScope(expiry = now + 60_000), now)
        val token2 = link2.substringAfter("token=").substringBefore("&")
        val wrongDevice = service.enrollDevice(
            token = token2,
            deviceId = "dev-other",
            subjectPublicKey = owner.toBase64Spki(),
            proof = enrollProof(service, owner, token2, "dev-pop"),
            currentTimeMs = now,
        )
        assertTrue(wrongDevice.isFailure, "A proof is bound to the deviceId")
    }

    @Test
    fun enrolledCertificateIsClampedToTheDelegationWindow() {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val serverKey = FlashCrypto.generateEcKeyPair()
        val device = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()
        // A delegation that ends in 2 hours: the default 7 day leaf must be cut to that.
        val shortDelegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = serverKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 5_000,
            validTo = now + 2 * 3600 * 1000L,
            maxDevices = 10,
            serial = 7L,
        )
        val service = EnrollmentService(rootKey.toBase64Spki(), serverKey.private, shortDelegation)
        val token = service.generateInviteLink("https://hub.example", InviteScope(expiry = now + 60_000), now)
            .substringAfter("token=").substringBefore("&")
        val result = service.enrollDevice(
            token = token,
            deviceId = "dev-clamp",
            subjectPublicKey = device.toBase64Spki(),
            proof = enrollProof(service, device, token, "dev-clamp"),
            currentTimeMs = now,
        ).getOrThrow()
        assertEquals(shortDelegation.validTo, result.deviceCert.validTo, "SEC-18: leaf validTo clamps to the delegation")
    }

    @Test
    fun testAuthAndEnrollRoutes() = testApplication {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val rootPublicKey = rootKey.toBase64Spki()
        val clientKey = FlashCrypto.generateEcKeyPair()
        val deviceId = "tablet-command-01"

        val auth = ChallengeResponseAuth(rootPublicKey)
        val enrollService = EnrollmentService(
            rootPublicKey = rootPublicKey,
            serverPrivateKey = serverKey.private,
            delegationCert = delegation,
            initialQuota = 10,
        )
        val bearerTokens = BearerTokenStore()

        routing {
            authRoutes(auth, enrollService, bearerTokens = bearerTokens)
        }

        // 1. GET /api/v1/auth/challenge -> 200 OK, DTO body
        val challengeRes = client.get("/api/v1/auth/challenge")
        assertEquals(HttpStatusCode.OK, challengeRes.status)
        val challenge = HubRestCodec.decode<AuthChallengeResponse>(challengeRes.bodyAsText())
        assertTrue(challenge.serverNonce.isNotBlank())
        assertEquals(auth.serverId, challenge.serverId)

        // 2. POST /api/v1/enroll with token + proof of possession
        val now = System.currentTimeMillis()
        val inviteLink = enrollService.generateInviteLink(
            serverUrl = "https://server.local",
            scope = InviteScope(department = "HQ", expiry = now + 60_000),
            currentTimeMs = now,
        )
        val token = inviteLink.substringAfter("token=").substringBefore("&")

        // 2a. No proof: refused with the contract error body, token untouched.
        val noProof = client.post("/api/v1/enroll") {
            contentType(ContentType.Application.Json)
            setBody(
                HubRestCodec.encode(
                    EnrollRequest(token = token, deviceId = deviceId, subjectPublicKey = clientKey.toBase64Spki()),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, noProof.status)
        val noProofBody = HubRestCodec.decode<ErrorBody>(noProof.bodyAsText())
        assertEquals(HubErrorCodes.ERR_BAD_REQUEST, noProofBody.code)
        assertTrue(noProofBody.correlationId.isNotBlank())

        // 2b. With proof.
        val enrollRes = client.post("/api/v1/enroll") {
            contentType(ContentType.Application.Json)
            setBody(
                HubRestCodec.encode(
                    EnrollRequest(
                        token = token,
                        deviceId = deviceId,
                        subjectPublicKey = clientKey.toBase64Spki(),
                        friendlyName = "HQ Commander Tablet",
                        proof = enrollProof(enrollService, clientKey, token, deviceId),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, enrollRes.status)
        val enrolled = HubRestCodec.decode<EnrollResponse>(enrollRes.bodyAsText())
        assertEquals(EnrollResponse.STATUS_ENROLLED, enrolled.status)
        assertEquals(deviceId, enrolled.deviceCert.deviceId)
        assertEquals(rootPublicKey, enrolled.rootPublicKey)
        assertEquals(auth.serverId, enrolled.rootFingerprint)
        assertEquals(1, enrolled.delegationChain.size)

        // 3. POST /api/v1/auth/verify with client signature
        val clientNonce = "c-nonce-12345"
        val signature = FlashCrypto.sign(
            clientKey.private,
            HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, clientNonce, deviceId, enrolled.deviceCert.serial),
        )
        val verifyRes = client.post("/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                HubRestCodec.encode(
                    AuthVerifyRequest(
                        serverNonce = challenge.serverNonce,
                        clientNonce = clientNonce,
                        deviceId = deviceId,
                        deviceCert = enrolled.deviceCert,
                        delegationChain = enrolled.delegationChain,
                        signature = signature,
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, verifyRes.status)
        val verified = HubRestCodec.decode<AuthVerifyResponse>(verifyRes.bodyAsText())
        assertEquals(AuthVerifyResponse.STATUS_AUTHENTICATED, verified.status)
        assertEquals(deviceId, verified.principal.deviceId)
        assertEquals(deviceId, bearerTokens.resolve(verified.token)?.deviceId, "the issued bearer resolves to the principal")

        // 4. The same nonce cannot be replayed.
        val replay = client.post("/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                HubRestCodec.encode(
                    AuthVerifyRequest(challenge.serverNonce, clientNonce, deviceId, enrolled.deviceCert, enrolled.delegationChain, signature),
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals(HubErrorCodes.ERR_AUTH, HubRestCodec.decode<ErrorBody>(replay.bodyAsText()).code)
    }

    @Test
    fun verifyRouteRejectsABadSignatureWithAFixedSentence() = testApplication {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val rootPublicKey = rootKey.toBase64Spki()
        val clientKey = FlashCrypto.generateEcKeyPair()
        val other = FlashCrypto.generateEcKeyPair()
        val auth = ChallengeResponseAuth(rootPublicKey)
        val enrollService = EnrollmentService(rootPublicKey, serverKey.private, delegation)
        routing { authRoutes(auth, enrollService) }

        val now = System.currentTimeMillis()
        val cert = DeviceCert.issue(
            issuerPrivateKey = serverKey.private,
            issuerPublicKey = serverKey.toBase64Spki(),
            deviceId = "dev-bad-sig",
            subjectPublicKey = clientKey.toBase64Spki(),
            role = DeviceCert.ROLE_MEMBER,
            validFrom = now - 1_000,
            validTo = now + 60_000,
            serial = 9L,
        )
        val challenge = HubRestCodec.decode<AuthChallengeResponse>(client.get("/api/v1/auth/challenge").bodyAsText())
        val badSig = FlashCrypto.sign(
            other.private,
            HubAuth.authSigningBytes(auth.serverId, challenge.serverNonce, "cn", "dev-bad-sig", 9L),
        )
        val res = client.post("/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(AuthVerifyRequest(challenge.serverNonce, "cn", "dev-bad-sig", cert, listOf(delegation), badSig)))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        val body = HubRestCodec.decode<ErrorBody>(res.bodyAsText())
        assertEquals(HubErrorCodes.ERR_AUTH, body.code)
        assertFalse(body.message.contains("signature", ignoreCase = true), "SEC-16: the reason is not echoed to the caller")
    }

    @Test
    fun thePublicRevocationListIsRateLimitedPerClientAddress() = testApplication {
        val (rootKey, serverKey, delegation) = setupTestKeys()
        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        val enrollService = EnrollmentService(rootKey.toBase64Spki(), serverKey.private, delegation)
        // REV-12 / SEC-09: the list is public so a revoked device can confirm its own revocation, so it must not be a free scraper endpoint.
        routing { authRoutes(auth, enrollService, limits = HubRateLimits(unauthenticated = TokenBucketLimiter(3, 0.001, clock = { 0L }))) }
        repeat(3) { assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/revocations").status, "no list published yet: 404, but counted") }
        val limited = client.get("/api/v1/revocations")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, HubRestCodec.decode<ErrorBody>(limited.bodyAsText()).code)
    }
}
