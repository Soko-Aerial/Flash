@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.enterprise.relay.ConnectScript
import com.transfer.flash.core.enterprise.relay.FlashRelayClient
import com.transfer.flash.core.enterprise.relay.RelayFailure
import com.transfer.flash.core.enterprise.relay.RelayFailureKind
import com.transfer.flash.core.enterprise.relay.ScriptedRelayTransport
import com.transfer.flash.core.security.enterprise.EnterpriseTrustStore
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The organisation façade: unenrol, pending unenrol, revocation confirmation, renewal, relink and live status. */
class EnterpriseHubTest {

    private class Rig(testScope: TestScope, val store: MemoryEnterpriseTrustStore = MemoryEnterpriseTrustStore(), trust: EnterpriseTrustStore = store) {
        val org = TestOrg()
        val device = TestCrypto()
        val fake = FakeHub()
        var now = TestOrg.T0
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val transport = ScriptedRelayTransport { now }
        val scope = CoroutineScope(dispatcher)
        var token = 0

        val hub = EnterpriseHub(
            deviceId = "dev-1",
            friendlyName = "Phone",
            crypto = device,
            trustStore = trust,
            scope = scope,
            httpClient = fake.client(),
            clock = { now },
            ioDispatcher = dispatcher,
            relayFactory = { idp ->
                FlashRelayClient(
                    deviceId = "dev-1", friendlyName = "Phone", hubUrl = "", identityProvider = idp, transport = transport,
                    timeSource = object : FlashTimeSource { override fun nowMs(): Long = now },
                    ioDispatcher = dispatcher, scope = CoroutineScope(dispatcher), jitter = { it },
                )
            },
            tickMs = 60_000L,
            maintenanceEveryMs = 1_000L,
        )

        suspend fun enrolled(validTo: Long = TestOrg.T0 + 30 * TestOrg.DAY, hubUrl: String = "https://hub.example.test") {
            store.saveCredentials(org.credentialsFor("dev-1", device, hubUrl = hubUrl, validTo = validTo))
        }

        fun scriptAuth() {
            fake.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(org.fingerprint) }
            fake.on("POST", FakeHub.VERIFY) { HttpStatusCode.OK to FakeHub.verified("dev-1", "tok-${++token}") }
        }
    }

    private val rigs = mutableListOf<Rig>()

    private fun newRig(
        scope: TestScope,
        store: MemoryEnterpriseTrustStore = MemoryEnterpriseTrustStore(),
        trust: EnterpriseTrustStore = store,
    ): Rig = Rig(scope, store, trust).also { rigs += it }

    /** runTest that always stops every hub it started: their tick loops would otherwise never let virtual time finish. */
    private fun hubTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            rigs.forEach { it.hub.shutdown(); it.scope.cancel() }
        }
    }

    private fun failingClear(store: MemoryEnterpriseTrustStore): EnterpriseTrustStore = object : EnterpriseTrustStore by store {
        override suspend fun clearCredentials(): Unit = throw IOException("disk")
    }

    @Test
    fun unenrolTellsTheHubThenClearsAndRemembersTheLinkedGroups() = hubTest {
        val r = newRig(this)
        r.enrolled(); r.scriptAuth()
        r.fake.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.OK to FakeHub.ok() }
        r.store.linkGroupToHub("g-1"); r.store.linkGroupToHub("g-2")

        assertEquals(UnenrolResult.DONE, r.hub.unenrol())

        assertNull(r.store.credentials.value)
        assertNull(r.store.pendingUnenrol.value)
        assertEquals(setOf("g-1", "g-2"), r.store.previousHubLinks.value?.groupIds)
        assertEquals(r.org.fingerprint, r.store.previousHubLinks.value?.rootFingerprint)
        assertTrue(r.fake.requests.any { it.path == HubContract.PATH_DEVICE_UNENROLL && it.authorization == "Bearer tok-1" })
    }

    @Test
    fun leavingTheOrganisationAlsoForgetsGroupJoinRequestsThatWereWaitingForApproval() = hubTest {
        val r = newRig(this)
        r.enrolled(); r.scriptAuth()
        r.fake.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.OK to FakeHub.ok() }
        r.store.setPendingJoin("g-wait", "Night shift")
        assertEquals(mapOf("g-wait" to "Night shift"), r.store.pendingJoins.value)

        assertEquals(UnenrolResult.DONE, r.hub.unenrol())

        assertTrue(r.store.pendingJoins.value.isEmpty(), "a request to the old organisation must not outlive the enrolment")
    }

    @Test
    fun unenrolWhileOfflineClearsLocallyAndRetriesUntilTheHubIsTold() = hubTest {
        val r = newRig(this)
        r.enrolled(); r.scriptAuth()
        r.fake.on("POST", HubContract.PATH_DEVICE_UNENROLL) { HttpStatusCode.OK to FakeHub.ok() }
        r.fake.online = false

        assertEquals(UnenrolResult.DONE_HUB_NOT_TOLD, r.hub.unenrol())
        assertNull(r.store.credentials.value)
        assertNotNull(r.store.pendingUnenrol.value, "the intention is kept")

        r.hub.maintenanceOnce(force = true)
        assertNotNull(r.store.pendingUnenrol.value, "still offline: kept")

        r.fake.online = true
        r.hub.maintenanceOnce(force = true)
        assertNull(r.store.pendingUnenrol.value, "told at last: forgotten")
        assertEquals(1, r.fake.count(HubContract.PATH_DEVICE_UNENROLL))
    }

    @Test
    fun unenrolThatCannotClearTheStoreReportsFailureAndKeepsTheDeviceEnrolled() = hubTest {
        val store = MemoryEnterpriseTrustStore()
        val r = newRig(this, store, failingClear(store))
        r.enrolled(); r.scriptAuth()
        r.fake.online = false

        assertEquals(UnenrolResult.STORAGE_FAILED, r.hub.unenrol())
        assertNotNull(store.credentials.value)
        assertNull(store.pendingUnenrol.value)
    }

    @Test
    fun aSignedRevocationListNamingThisCertificateWipesTheCredentialsAndKeepsTheNotice() = hubTest {
        val r = newRig(this)
        r.enrolled()
        r.store.linkGroupToHub("g-1")
        r.fake.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(r.org.revocations(serials = setOf(10L))) }

        assertTrue(r.hub.confirmRevocation())

        assertNull(r.store.credentials.value)
        r.hub.start()
        r.runUntilIdle(this)
        assertEquals(HubLinkState.REVOKED, r.hub.status.value.state)
        r.hub.shutdown()
        assertEquals(setOf("g-1"), r.store.previousHubLinks.value?.groupIds)
    }

    @Test
    fun aRevokedFrameWithoutAMatchingSignedListWipesNothing() = hubTest {
        val r = newRig(this)
        r.enrolled()
        // The list is root-signed but does not name this device.
        r.fake.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(r.org.revocations(serials = setOf(99L))) }
        assertFalse(r.hub.confirmRevocation())
        assertNotNull(r.store.credentials.value)

        // A forger lists it, but is not the organisation root.
        val forger = TestOrg()
        r.fake.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(forger.revocations(serials = setOf(10L))) }
        assertFalse(r.hub.confirmRevocation())
        assertNotNull(r.store.credentials.value)

        // The hub cannot be reached for the list.
        r.fake.online = false
        assertFalse(r.hub.confirmRevocation())
        assertNotNull(r.store.credentials.value)
    }

    @Test
    fun theRelayRevokedFrameLeadsToAConfirmedWipeAndAStickyRevokedStatus() = hubTest {
        val r = newRig(this)
        r.enrolled()
        r.fake.on("GET", HubContract.PATH_REVOCATIONS) { HttpStatusCode.OK to HubRestCodec.encode(r.org.revocations(ids = setOf("dev-1"))) }
        r.transport.script = { ConnectScript.Fail(RelayFailure(RelayFailureKind.REVOKED, "ERR_REVOKED", r.now)) }

        r.hub.start()
        r.runUntilIdle(this)

        assertNull(r.store.credentials.value)
        assertEquals(HubLinkState.REVOKED, r.hub.status.value.state)
        r.hub.dismissRevokedNotice()
        r.runUntilIdle(this)
        assertEquals(HubLinkState.NOT_ENROLLED, r.hub.status.value.state)
        r.hub.shutdown()
    }

    @Test
    fun renewalRunsOnlyInsideTheWindow() = hubTest {
        val r = newRig(this)
        r.enrolled()
        assertNull(r.hub.renewIfDue(), "nothing is due 30 days before the end")

        r.store.saveCredentials(r.org.credentialsFor("dev-1", r.device, serial = 10L, validTo = TestOrg.T0 + 12 * TestOrg.HOUR))
        r.fake.on("GET", FakeHub.CHALLENGE) { HttpStatusCode.OK to FakeHub.challenge(r.org.fingerprint) }
        r.fake.on("POST", HubContract.PATH_ENROLL_RENEW) {
            val cert = r.org.deviceCert("dev-1", Base64.encode(r.device.identityPublicKeyEncoded), serial = 11L, validFrom = r.now - TestOrg.HOUR, validTo = r.now + 30 * TestOrg.DAY)
            HttpStatusCode.OK to HubRestCodec.encode(r.org.response(status = "RENEWED", cert = cert))
        }
        assertIs<EnrollmentOutcome.Enrolled>(r.hub.renewIfDue())
        assertEquals(11L, r.store.credentials.value?.deviceCert?.serial)
    }

    @Test
    fun aFailingRenewalShowsUpInTheStatusAsExpiringWhileConnected() = hubTest {
        val r = newRig(this)
        r.enrolled(validTo = TestOrg.T0 + 12 * TestOrg.HOUR)
        r.scriptAuth()
        r.fake.on("POST", HubContract.PATH_ENROLL_RENEW) { HttpStatusCode.InternalServerError to FakeHub.error("ERR_INTERNAL") }
        r.hub.start()
        r.runUntilIdle(this)
        assertEquals(HubLinkState.CERT_EXPIRING, r.hub.status.value.state)
        assertTrue(r.hub.status.value.renewalFailed)
        r.hub.shutdown()
    }

    @Test
    fun theRememberedGroupsAreOfferedOnlyToTheSameOrganisation() = hubTest {
        val r = newRig(this)
        r.enrolled()
        r.store.linkGroupToHub("g-1")
        r.store.clearCredentials()

        // Same organisation again.
        r.enrolled()
        assertEquals(setOf("g-1"), r.hub.relinkableGroups())
        assertEquals(1, r.hub.relinkPreviousGroups())
        assertTrue(r.store.isGroupLinkedToHub("g-1"))
        assertNull(r.store.previousHubLinks.value)

        // A different organisation never inherits them.
        r.store.clearCredentials()
        val other = TestOrg()
        r.store.saveCredentials(other.credentialsFor("dev-1", r.device))
        assertTrue(r.hub.relinkableGroups().isEmpty())
    }

    @Test
    fun enrollingThroughTheFacadeStartsTheRelayAndReportsOnline() = hubTest {
        val r = newRig(this)
        val deviceKey = Base64.encode(r.device.identityPublicKeyEncoded)
        r.fake.respond(
            "POST", HubContract.PATH_ENROLL, HttpStatusCode.OK,
            HubRestCodec.encode(r.org.response(cert = r.org.deviceCert("dev-1", deviceKey, validFrom = r.now - TestOrg.HOUR))),
        )
        r.hub.start()
        r.runUntilIdle(this)
        assertEquals(HubLinkState.NOT_ENROLLED, r.hub.status.value.state)

        val invite = EnrollmentInvite("https://hub.example.test", "tok", r.org.fingerprint)
        assertIs<EnrollmentOutcome.Enrolled>(r.hub.enroll(invite))
        r.runUntilIdle(this)

        val status = r.hub.status.value
        assertEquals(HubLinkState.ONLINE, status.state)
        assertEquals("Acme Hub", status.hubName)
        assertEquals("hub.example.test", status.hubHost)
        assertEquals(30, status.certDaysLeft)
        assertEquals(1, r.transport.connectTimes.size)
        r.hub.shutdown()
    }

    @Test
    fun aStoredCleartextAddressIsNeverDialledAndIsShownAsRefused() = hubTest {
        val r = newRig(this)
        r.enrolled(hubUrl = "http://hub.example.test")
        r.hub.start()
        r.runUntilIdle(this)
        assertTrue(r.transport.connectTimes.isEmpty())
        assertEquals(HubLinkState.OFFLINE, r.hub.status.value.state)
        assertEquals(HubOfflineReason.REFUSED, r.hub.status.value.offlineReason)
        r.hub.shutdown()
    }

    private fun Rig.runUntilIdle(scope: TestScope) {
        scope.runCurrent()
        scope.advanceTimeBy(100L)
        scope.runCurrent()
    }
}
