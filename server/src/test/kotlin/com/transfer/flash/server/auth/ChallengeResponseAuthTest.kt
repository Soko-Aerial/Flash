package com.transfer.flash.server.auth

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
        val payload = (challenge.serverNonce + clientNonce + deviceId).encodeToByteArray()
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
            serial = 202L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        // Generate challenge 65 seconds in the past
        val challenge = auth.generateChallenge(now - 65_000)

        val clientNonce = "client-nonce-456"
        val payload = (challenge.serverNonce + clientNonce + deviceId).encodeToByteArray()
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
            serial = 203L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        val challenge = auth.generateChallenge(now)

        val clientNonce = "client-nonce-789"
        val payload = (challenge.serverNonce + clientNonce + deviceId).encodeToByteArray()
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
            serial = 204L,
        )

        val auth = ChallengeResponseAuth(rootKey.toBase64Spki())
        val challenge = auth.generateChallenge(now)
        val clientNonce = "client-nonce-replay"
        val payload = (challenge.serverNonce + clientNonce + deviceId).encodeToByteArray()
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
            friendlyName = "Scout 1",
            currentTimeMs = now,
        )
        assertTrue(enroll1.isSuccess, "First enrollment with valid token must succeed")
        val res1 = enroll1.getOrThrow()
        assertEquals("phone-scout-01", res1.deviceCert.deviceId)
        assertEquals("DEFENCE-INTELLIGENCE", res1.deviceCert.department)
        assertEquals(expectedRootFp, res1.rootFingerprint)
        assertTrue(res1.deviceCert.verifyChain(delegation, rootPublicKey, now))
        assertEquals(1, service.currentRemainingQuota, "Quota should be decremented to 1")

        // 3. Second enrollment with SAME token: must FAIL (single-use consumed)
        val enrollReuse = service.enrollDevice(
            token = token,
            deviceId = "phone-scout-imposter",
            subjectPublicKey = clientKey2.toBase64Spki(),
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
            currentTimeMs = now,
        )
        assertTrue(enroll3.isFailure, "Enrollment must fail when server device quota is exhausted")
        assertTrue(enroll3.exceptionOrNull()?.message?.contains("quota", ignoreCase = true) == true)
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

        routing {
            authRoutes(auth, enrollService)
        }

        // 1. GET /api/v1/auth/challenge -> 200 OK
        val challengeRes = client.get("/api/v1/auth/challenge")
        assertEquals(HttpStatusCode.OK, challengeRes.status)
        val challengeBody = challengeRes.bodyAsText()
        val serverNonce = extractJsonField(challengeBody, "serverNonce")
        assertNotNull(serverNonce)

        // 2. POST /api/v1/enroll with token
        val now = System.currentTimeMillis()
        val inviteLink = enrollService.generateInviteLink(
            serverUrl = "https://server.local",
            scope = InviteScope(department = "HQ", expiry = now + 60_000),
            currentTimeMs = now,
        )
        val token = inviteLink.substringAfter("token=").substringBefore("&")

        val enrollReq = """{"token":"$token","deviceId":"$deviceId","subjectPublicKey":"${clientKey.toBase64Spki()}","friendlyName":"HQ Commander Tablet"}"""
        val enrollRes = client.post("/api/v1/enroll") {
            contentType(ContentType.Application.Json)
            setBody(enrollReq)
        }
        assertEquals(HttpStatusCode.OK, enrollRes.status)
        val enrollBody = enrollRes.bodyAsText()
        assertTrue(enrollBody.contains(""""status":"ENROLLED""""))
        val deviceCert = parseDeviceCertFromJson(enrollBody)
        assertNotNull(deviceCert)
        assertEquals(deviceId, deviceCert.deviceId)

        // 3. POST /api/v1/auth/verify with client signature
        val clientNonce = "c-nonce-12345"
        val payload = (serverNonce + clientNonce + deviceId).encodeToByteArray()
        val signature = FlashCrypto.sign(clientKey.private, payload)

        val verifyReq = """{
            "serverNonce":"$serverNonce",
            "clientNonce":"$clientNonce",
            "deviceId":"$deviceId",
            "signature":"$signature",
            "deviceCert":{
                "deviceId":"${deviceCert.deviceId}",
                "subjectPublicKey":"${deviceCert.subjectPublicKey}",
                "role":"${deviceCert.role}",
                "department":"${deviceCert.department}",
                "validFrom":${deviceCert.validFrom},
                "validTo":${deviceCert.validTo},
                "serial":${deviceCert.serial},
                "issuerPublicKey":"${deviceCert.issuerPublicKey}",
                "issuerSignature":"${deviceCert.issuerSignature}"
            },
            "delegationCert":{
                "rootPublicKey":"${delegation.rootPublicKey}",
                "delegatePublicKey":"${delegation.delegatePublicKey}",
                "scopes":["enrol:member"],
                "validFrom":${delegation.validFrom},
                "validTo":${delegation.validTo},
                "maxDevices":${delegation.maxDevices},
                "serial":${delegation.serial},
                "signature":"${delegation.signature}"
            }
        }""".trimIndent()

        val verifyRes = client.post("/api/v1/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(verifyReq)
        }
        assertEquals(HttpStatusCode.OK, verifyRes.status)
        val verifyBody = verifyRes.bodyAsText()
        assertTrue(verifyBody.contains(""""status":"AUTHENTICATED""""))
        assertTrue(verifyBody.contains(""""deviceId":"$deviceId""""))
    }
}
