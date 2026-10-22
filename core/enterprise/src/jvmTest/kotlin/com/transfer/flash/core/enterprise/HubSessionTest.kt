@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private var jitterMs = 0L

    private fun session(allowCleartext: Boolean = false) =
        HubSession(hub.client(), { store.credentials.value }, device, store, clock = { now }, allowCleartext = { allowCleartext }, reauthJitterMs = { jitterMs })

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
    fun callersRefusedWithTheSameTokenSignInOnce() = runTest {
        enrolled()
        scriptAuth()
        jitterMs = 150L // every caller records which sign-in it was refused on before any of them re-authenticates
        val s = session()
        assertEquals("tok-1", s.bearer(false))
        val tokens = (1..5).map { async(Dispatchers.Default) { s.bearer(true) } }.awaitAll()
        assertEquals(List(5) { "tok-2" }, tokens, "one sign-in served all five refused callers")
        assertEquals(2, hub.count(FakeHub.VERIFY), "the first sign-in plus ONE refresh, not five")
        assertEquals("tok-3", s.bearer(true), "a later refusal of the new token signs in again")
    }

    @Test
    fun theFirstSignInWaitsForNoJitterButARefusedLiveTokenDoes() = runTest {
        enrolled()
        scriptAuth()
        val waits = mutableListOf<Long>()
        val s = HubSession(
            hub.client(), { store.credentials.value }, device, store, clock = { now },
            reauthJitterMs = { 7L.also { waits += it } },
        )
        s.bearer(true) // nothing cached: nobody refused anything, no reason to wait
        assertEquals(emptyList(), waits)
        s.bearer(false)
        assertEquals(emptyList(), waits, "a cache hit never waits")
        s.bearer(true) // the hub refused a token this device still thought valid: the hub-restart signature
        assertEquals(listOf(7L), waits)
        now += 600_000L // the token expired locally: an ordinary refresh, not a restart
        s.bearer(true)
        assertEquals(listOf(7L), waits)
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

    // ---- CLI-02: a silent hub cannot hold the session -----------------------------------------------------------

    @Test
    fun aHubThatNeverAnswersTheSignInIsUnreachableAfterTheBudgetNotForEver() = runTest {
        enrolled()
        scriptAuth()
        hub.hangingPaths += FakeHub.VERIFY
        val s = HubSession(
            hub.client(), { store.credentials.value }, device, store, StandardTestDispatcher(testScheduler),
            clock = { now }, reauthJitterMs = { 0L },
        )
        val before = testScheduler.currentTime
        assertNull(s.bearer(false), "no token from a hub that never answers")
        assertEquals(HubAuthFailure.UNREACHABLE, s.authFailure.value)
        assertEquals(HubSession.NETWORK_BUDGET_MS, testScheduler.currentTime - before, "gave up at the budget")

        // The lock was released: once the hub answers, the next call signs in normally.
        hub.hangingPaths.clear()
        assertEquals("tok-1", s.bearer(false))
        assertNull(s.authFailure.value)
    }

    @Test
    fun aRenewalThatNeverGetsAnAnswerIsUnreachableAndReleasesTheLock() = runTest {
        enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        scriptRenew(serial = 11, validTo = TestOrg.T0 + 30 * TestOrg.DAY)
        hub.hangingPaths += HubContract.PATH_ENROLL_RENEW
        val s = HubSession(
            hub.client(), { store.credentials.value }, device, store, StandardTestDispatcher(testScheduler),
            clock = { now }, reauthJitterMs = { 0L },
        )
        assertEquals(EnrollFailureReason.UNREACHABLE, assertIs<EnrollmentOutcome.Failed>(s.renew()).reason)
        assertEquals(10L, store.credentials.value?.deviceCert?.serial)
    }

    // ---- CLI-03: a response is never read past its limit --------------------------------------------------------

    @Test
    fun aBodyOverTheLimitIsCutOneByteAfterItAndASmallOneIsReadWhole() = runTest {
        hub.respond("GET", "/big", HttpStatusCode.OK, "x".repeat(200_000))
        hub.respond("GET", "/small", HttpStatusCode.OK, "{\"a\":1}")
        val client = hub.client()
        val big = client.get("https://hub.example.test/big").boundedText()
        assertEquals(HubContract.MAX_REST_BODY_BYTES + 1, big.length, "stopped one byte past the 64 KiB limit")
        assertEquals("{\"a\":1}", client.get("https://hub.example.test/small").boundedText())
        val sync = client.get("https://hub.example.test/big").boundedText(HubContract.MAX_DIRECTORY_SYNC_BODY_BYTES)
        assertEquals(200_000, sync.length, "a directory page may be larger, up to its own limit")
    }

    @Test
    fun anOversizedChallengeIsRefusedAsRejectedNotAllocated() = runTest {
        enrolled()
        hub.respond("GET", FakeHub.CHALLENGE, HttpStatusCode.OK, "x".repeat(500_000))
        val s = session()
        assertNull(s.bearer(false))
        assertEquals(HubAuthFailure.REJECTED, s.authFailure.value)
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
