@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** CLI-01 / CLI-09: bearer session, renewal, unenrol and revocation fetch against a scripted hub. */
class HubSessionTest {

    private val org = TestOrg()
    private val device = TestCrypto()
    private val deviceKey = Base64.encode(device.identityPublicKeyEncoded)
    private val hub = FakeHub()
    private var now = TestOrg.T0
    private val store = MemoryEnterpriseTrustStore()

    private fun session(allowCleartext: Boolean = false) =
        HubSession(hub.client(), { store.credentials.value }, device, store, clock = { now }, allowCleartext = { allowCleartext })

    private suspend fun enrolled(validTo: Long = TestOrg.T0 + 30 * TestOrg.DAY, hubUrl: String = "https://hub.example.test") {
        store.saveCredentials(org.credentialsFor("dev-1", device, hubUrl = hubUrl, validTo = validTo))
    }

    private var tokenCounter = 0

    private fun scriptAuth() {
        hub.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(org.fingerprint) }
        hub.on("POST", FakeHub.VERIFY) { HttpStatusCode.OK to FakeHub.verified("dev-1", "tok-${++tokenCounter}") }
    }

    @Test
    fun bearerIsCachedUntilTheTtlThenRefreshed() = runTest {
        enrolled()
        scriptAuth()
        val s = session()
        assertEquals("tok-1", s.bearer(false))
        assertEquals("tok-1", s.bearer(false))
        assertEquals(1, hub.count(FakeHub.VERIFY))
        now += 590_000L // 600 s TTL minus the 30 s margin has passed
        assertEquals("tok-2", s.bearer(false))
        assertEquals(2, hub.count(FakeHub.VERIFY))
    }

    @Test
    fun forceRefreshAsksTheHubAgain() = runTest {
        enrolled()
        scriptAuth()
        val s = session()
        s.bearer(false)
        assertEquals("tok-2", s.bearer(true))
    }

    @Test
    fun aHubThatIsNotThePinnedOrganisationGetsNothingSigned() = runTest {
        enrolled()
        hub.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(TestOrg().fingerprint) }
        hub.on("POST", FakeHub.VERIFY) { HttpStatusCode.OK to FakeHub.verified("dev-1", "tok") }
        val s = session()
        assertNull(s.bearer(false))
        assertEquals(HubAuthFailure.SERVER_MISMATCH, s.authFailure.value)
        assertEquals(0, hub.count(FakeHub.VERIFY))
    }

    @Test
    fun revokedAndExpiredAnswersAreDistinguished() = runTest {
        enrolled()
        hub.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(org.fingerprint) }
        hub.on("POST", FakeHub.VERIFY) { HttpStatusCode.Unauthorized to FakeHub.error(HubErrorCodes.ERR_REVOKED) }
        val s = session()
        assertNull(s.bearer(false))
        assertEquals(HubAuthFailure.REVOKED, s.authFailure.value)
        hub.on("POST", FakeHub.VERIFY) { HttpStatusCode.Unauthorized to FakeHub.error(HubErrorCodes.ERR_EXPIRED) }
        assertNull(s.bearer(true))
        assertEquals(HubAuthFailure.CERT_EXPIRED, s.authFailure.value)
        hub.online = false
        assertNull(s.bearer(true))
        assertEquals(HubAuthFailure.UNREACHABLE, s.authFailure.value)
    }

    @Test
    fun aStoredCleartextAddressIsNeverDialled() = runTest {
        enrolled(hubUrl = "http://hub.example.test")
        scriptAuth()
        val s = session()
        assertNull(s.bearer(false))
        assertEquals(HubAuthFailure.ADDRESS_REFUSED, s.authFailure.value)
        assertEquals(0, hub.requests.size)
    }

    // ---- renewal -----------------------------------------------------------------------------------------------

    private fun scriptRenew(serial: Long, validTo: Long, signerOrg: TestOrg = org, deviceId: String = "dev-1", key: String = deviceKey) {
        hub.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(org.fingerprint) }
        hub.on("POST", HubContract.PATH_ENROLL_RENEW) {
            val response = signerOrg.response(
                status = "RENEWED",
                cert = signerOrg.deviceCert(deviceId, key, serial = serial, validFrom = now - TestOrg.HOUR, validTo = validTo),
            )
            HttpStatusCode.OK to HubRestCodec.encode(response)
        }
    }

    @Test
    fun renewalInsideTheWindowReplacesTheCredentialsInOneSave() = runTest {
        enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        scriptRenew(serial = 11, validTo = TestOrg.T0 + 30 * TestOrg.DAY)
        val s = session()
        val outcome = s.renew()
        val fresh = assertIs<EnrollmentOutcome.Enrolled>(outcome).credentials
        assertEquals(11L, fresh.deviceCert.serial)
        assertEquals(fresh, store.credentials.value)
        assertEquals("Acme Hub", fresh.hubName)
        assertNull(s.renewalFailure.value)
    }

    @Test
    fun renewalBeforeTheWindowSendsNothing() = runTest {
        enrolled()
        scriptRenew(serial = 11, validTo = TestOrg.T0 + 60 * TestOrg.DAY)
        assertIs<EnrollmentOutcome.Failed>(session().renew())
        assertEquals(0, hub.requests.size)
    }

    @Test
    fun renewalThatKeepsTheSameSerialIsRejected() = runTest {
        enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        scriptRenew(serial = 10, validTo = TestOrg.T0 + 30 * TestOrg.DAY)
        val s = session()
        assertIs<EnrollmentOutcome.Failed>(s.renew())
        assertEquals(10L, store.credentials.value?.deviceCert?.serial)
        assertNotEquals(null, s.renewalFailure.value)
    }

    @Test
    fun renewalThatSwapsTheOrganisationRootIsRejected() = runTest {
        enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        val impostor = TestOrg()
        scriptRenew(serial = 11, validTo = TestOrg.T0 + 30 * TestOrg.DAY, signerOrg = impostor)
        val outcome = session().renew()
        assertEquals(EnrollFailureReason.WRONG_ORGANISATION, assertIs<EnrollmentOutcome.Failed>(outcome).reason)
        assertEquals(10L, store.credentials.value?.deviceCert?.serial)
    }

    @Test
    fun renewalThatBindsAnotherDeviceKeyIsRejected() = runTest {
        enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        scriptRenew(serial = 11, validTo = TestOrg.T0 + 30 * TestOrg.DAY, key = Base64.encode(TestCrypto().identityPublicKeyEncoded))
        assertEquals(EnrollFailureReason.WRONG_DEVICE, assertIs<EnrollmentOutcome.Failed>(session().renew()).reason)
    }

    @Test
    fun renewalWhileOfflineReportsUnreachableAndKeepsTheOldCertificate() = runTest {
        enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        scriptRenew(serial = 11, validTo = TestOrg.T0 + 30 * TestOrg.DAY)
        hub.online = false
        val s = session()
        assertEquals(EnrollFailureReason.UNREACHABLE, assertIs<EnrollmentOutcome.Failed>(s.renew()).reason)
        assertEquals(EnrollFailureReason.UNREACHABLE, s.renewalFailure.value)
        assertEquals(10L, store.credentials.value?.deviceCert?.serial)
    }

    // ---- unenrol and revocations ---------------------------------------------------------------------------------

    @Test
    fun unenrolSendsTheBearerAndIsConfirmed() = runTest {
        enrolled()
        scriptAuth()
        hub.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.OK to FakeHub.ok() }
        assertEquals(UnenrollOutcome.CONFIRMED, session().unenroll())
        assertEquals("Bearer tok-1", hub.requests.last { it.path == HubContract.PATH_DEVICE_UNENROLL }.authorization)
    }

    @Test
    fun unenrolRefreshesTheBearerOnceOn401() = runTest {
        enrolled()
        scriptAuth()
        var calls = 0
        hub.on("POST", HubContract.PATH_DEVICE_UNENROLL) {
            if (++calls == 1) HttpStatusCode.Unauthorized to FakeHub.error(HubErrorCodes.ERR_AUTH) else HttpStatusCode.OK to FakeHub.ok()
        }
        assertEquals(UnenrollOutcome.CONFIRMED, session().unenroll())
        assertEquals(2, calls)
        assertEquals(2, hub.count(FakeHub.VERIFY))
    }

    @Test
    fun unenrolOfAnAlreadyUnknownDeviceIsConfirmedAndOfflineIsReported() = runTest {
        enrolled()
        scriptAuth()
        hub.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.NotFound to FakeHub.error(HubErrorCodes.ERR_NOT_FOUND) }
        assertEquals(UnenrollOutcome.CONFIRMED, session().unenroll())
        hub.online = false
        assertEquals(UnenrollOutcome.UNREACHABLE, session().unenroll())
    }

    @Test
    fun unenrolRefusedByTheHubIsReportedAsRefused() = runTest {
        enrolled()
        scriptAuth()
        hub.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.Forbidden to FakeHub.error(HubErrorCodes.ERR_FORBIDDEN) }
        assertEquals(UnenrollOutcome.REFUSED, session().unenroll())
    }

    @Test
    fun revocationListIsReturnedOnlyWhenSignedByTheOrganisationRoot() = runTest {
        enrolled()
        hub.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(org.revocations(serials = setOf(10L))) }
        val trusted = session().fetchRevocations()
        assertEquals(true, trusted?.isRevoked(store.credentials.value!!.deviceCert))

        val forger = TestOrg()
        hub.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(forger.revocations(serials = setOf(10L))) }
        assertNull(session().fetchRevocations())
    }
}
