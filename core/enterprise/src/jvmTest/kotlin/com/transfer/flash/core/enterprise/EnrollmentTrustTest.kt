@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-01: nothing is trusted or stored unless the pinned root, the chain and the device binding all check out. */
class EnrollmentTrustTest {

    private val org = TestOrg()
    private val device = TestCrypto()
    private val deviceKey = Base64.encode(device.identityPublicKeyEncoded)
    private val hub = FakeHub()
    private val store = MemoryEnterpriseTrustStore()

    private fun invite(
        url: String = "https://hub.example.test",
        pin: String = org.fingerprint,
    ) = EnrollmentInvite(serverUrl = url, token = "tok-1", rootFingerprint = pin)

    private fun client(
        trust: EnterpriseTrustStore? = store,
        allowCleartext: Boolean = false,
    ) = EnterpriseEnrollmentClient(hub.client(), trust, allowCleartext = { allowCleartext }, clock = { TestOrg.T0 })

    private fun script(response: com.transfer.flash.core.hubprotocol.rest.EnrollResponse) =
        hub.respond("POST", HubContract.PATH_ENROLL, HttpStatusCode.OK, HubRestCodec.encode(response))

    private fun goodResponse() = org.response(cert = org.deviceCert("dev-1", deviceKey))

    private fun failed(outcome: EnrollmentOutcome): EnrollFailureReason = assertIs<EnrollmentOutcome.Failed>(outcome).reason

    @Test
    fun enrolmentWithPinnedRootStoresVerifiedCredentialsInOneSave() = runTest {
        script(goodResponse())
        val outcome = client().enroll(invite(), "dev-1", "Phone", device)
        val creds = assertIs<EnrollmentOutcome.Enrolled>(outcome).credentials
        assertEquals(creds, store.credentials.value)
        assertEquals(org.fingerprint, creds.rootFingerprint)
        assertEquals("Acme Hub", creds.hubName)
        assertEquals(1, creds.delegationChain.size)
        assertEquals("https://hub.example.test", creds.hubUrl)
    }

    @Test
    fun blankPinIsRefusedBeforeAnyRequestIsSent() = runTest {
        script(goodResponse())
        assertEquals(EnrollFailureReason.INVITE_NOT_PINNED, failed(client().enroll(invite(pin = ""), "dev-1", "Phone", device)))
        assertEquals(0, hub.requests.size)
        assertNull(store.credentials.value)
    }

    @Test
    fun cleartextHubIsRefusedWithoutSendingTheInviteToken() = runTest {
        script(goodResponse())
        assertEquals(EnrollFailureReason.CLEARTEXT_REFUSED, failed(client().enroll(invite(url = "http://hub.example.test"), "dev-1", "Phone", device)))
        assertEquals(0, hub.requests.size)
    }

    @Test
    fun cleartextLoopbackAndTheDevelopmentOverrideAreAllowed() = runTest {
        script(goodResponse())
        assertIs<EnrollmentOutcome.Enrolled>(client().enroll(invite(url = "http://127.0.0.1:8443"), "dev-1", "Phone", device))
        store.clearCredentials()
        assertIs<EnrollmentOutcome.Enrolled>(client(allowCleartext = true).enroll(invite(url = "http://hub.lan:8443"), "dev-1", "Phone", device))
    }

    @Test
    fun rootThatDoesNotMatchThePinIsRefusedAndNothingIsStored() = runTest {
        val other = TestOrg()
        script(other.response(cert = other.deviceCert("dev-1", deviceKey)))
        assertEquals(EnrollFailureReason.WRONG_ORGANISATION, failed(client().enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun aHubThatLiesAboutItsServerIdButPresentsAnotherRootIsStillRefused() = runTest {
        // The attack the pin exists for: a hostile hub issues a perfectly valid chain under ITS OWN root and names the
        // organisation's fingerprint as its server id. Only the comparison of the presented root with the pin catches it.
        val other = TestOrg()
        script(other.response(cert = other.deviceCert("dev-1", deviceKey), serverId = org.fingerprint))
        assertEquals(EnrollFailureReason.WRONG_ORGANISATION, failed(client().enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun aHubThatNamesNoServerIdAndPresentsAnotherRootIsRefused() = runTest {
        val other = TestOrg()
        script(other.response(cert = other.deviceCert("dev-1", deviceKey), serverId = ""))
        assertEquals(EnrollFailureReason.WRONG_ORGANISATION, failed(client().enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun certificateForAnotherDeviceIdIsRefused() = runTest {
        script(org.response(cert = org.deviceCert("someone-else", deviceKey)))
        assertEquals(EnrollFailureReason.WRONG_DEVICE, failed(client().enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun certificateBoundToAnotherKeyIsRefused() = runTest {
        script(org.response(cert = org.deviceCert("dev-1", Base64.encode(TestCrypto().identityPublicKeyEncoded))))
        assertEquals(EnrollFailureReason.WRONG_DEVICE, failed(client().enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun forgedIssuerSignatureIsRefused() = runTest {
        // Signed by a key that is not the delegate named as the issuer.
        script(org.response(cert = org.deviceCert("dev-1", deviceKey, signer = TestCrypto())))
        assertEquals(EnrollFailureReason.CERTIFICATE_INVALID, failed(client().enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun expiredCertificateIsRefused() = runTest {
        script(org.response(cert = org.deviceCert("dev-1", deviceKey, validFrom = TestOrg.T0 - 10 * TestOrg.DAY, validTo = TestOrg.T0 - 2 * TestOrg.DAY)))
        assertEquals(EnrollFailureReason.CERTIFICATE_INVALID, failed(client().enroll(invite(), "dev-1", "Phone", device)))
    }

    @Test
    fun aStorageFailureReportsFailureAndLeavesNothingBehind() = runTest {
        val failing = object : EnterpriseTrustStore by store {
            override suspend fun saveCredentials(credentials: EnrollmentCredentials) = throw IOException("disk full")
        }
        script(goodResponse())
        assertEquals(EnrollFailureReason.STORAGE_FAILED, failed(client(trust = failing).enroll(invite(), "dev-1", "Phone", device)))
        assertNull(store.credentials.value)
    }

    @Test
    fun hubAnswersAreMappedToPlainReasons() = runTest {
        suspend fun reasonFor(status: HttpStatusCode, code: String): EnrollFailureReason {
            hub.respond("POST", HubContract.PATH_ENROLL, status, FakeHub.error(code))
            return failed(client().enroll(invite(), "dev-1", "Phone", device))
        }
        assertEquals(EnrollFailureReason.INVITE_EXPIRED, reasonFor(HttpStatusCode.Gone, HubErrorCodes.ERR_EXPIRED))
        assertEquals(EnrollFailureReason.INVITE_USED, reasonFor(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT))
        assertEquals(EnrollFailureReason.QUOTA_FULL, reasonFor(HttpStatusCode.Forbidden, HubErrorCodes.ERR_QUOTA_EXCEEDED))
        assertEquals(EnrollFailureReason.RATE_LIMITED, reasonFor(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT))
        assertEquals(EnrollFailureReason.INVITE_REJECTED, reasonFor(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH))
        assertEquals(EnrollFailureReason.HUB_ERROR, reasonFor(HttpStatusCode.InternalServerError, HubErrorCodes.ERR_INTERNAL))
    }

    @Test
    fun anUnreachableHubIsReportedAsUnreachable() = runTest {
        hub.online = false
        assertEquals(EnrollFailureReason.UNREACHABLE, failed(client().enroll(invite(), "dev-1", "Phone", device)))
    }

    @Test
    fun nonEmptyDelegationChainSurvivesTheCodecAndBothStores() = runTest {
        val creds = org.credentialsFor("dev-1", device)
        assertTrue(creds.delegationChain.isNotEmpty())

        val decoded = assertNotNull(EnterpriseCredentialCodec.parse(EnterpriseCredentialCodec.serialize(creds)))
        assertEquals(creds, decoded)
        assertEquals("Acme Hub", decoded.hubName)

        val dir = Files.createTempDirectory("hubc-chain").toFile()
        try {
            val file = JvmFileEnterpriseTrustStore(dir)
            file.saveCredentials(creds)
            val reloaded = assertNotNull(JvmFileEnterpriseTrustStore(dir).credentials.value)
            assertEquals(creds.delegationChain, reloaded.delegationChain)
            // The reloaded chain still verifies against the pinned root.
            val verifier = com.transfer.flash.core.security.enterprise.FlashCryptoEcdsaVerifier(device)
            assertEquals(
                com.transfer.flash.core.hubprotocol.certs.ChainStatus.OK,
                reloaded.deviceCert.checkChain(reloaded.delegationChain.first(), reloaded.rootPublicKey, verifier, TestOrg.T0),
            )
        } finally {
            dir.deleteRecursively()
        }
        val memory = MemoryEnterpriseTrustStore()
        memory.saveCredentials(creds)
        assertEquals(creds.delegationChain, memory.credentials.value?.delegationChain)
    }
}
