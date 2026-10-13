@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.network.enterprise.EnrollmentOutcome
import com.transfer.flash.core.network.enterprise.HubLinkState
import com.transfer.flash.core.network.enterprise.UnenrolResult
import com.transfer.flash.core.network.enterprise.UnenrollOutcome
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenarios (d) revocation, (e) renewal and (f) unenrol of the client <-> server contract (ADR-124), driven through the
 * real `EnterpriseHub` and the hub's service layer (what the admin API calls after its step-up check).
 */
class E2eLifecycleContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()
    private val http = HttpClient(CIO)

    @AfterTest
    fun cleanUp() {
        http.close()
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private fun newHub(): E2eHub = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision(validDays = 120).start().also { hubs += it }
    private fun newDevice(hub: E2eHub, id: String): E2eDevice = E2eDevice(id, hub).also { devices += it }
    private fun envelope(id: String, sender: String) =
        FlashEnvelope(id = id, type = "MSG_TEXT", payloadJson = "opaque-$id", senderId = sender, sentAt = System.currentTimeMillis())

    /** What the admin API does after the step-up check: revoke in the service layer, then drop the device's bearer tokens. */
    private fun adminRevokes(hub: E2eHub, deviceId: String) {
        hub.services.revocations!!.revokeDevice(deviceId, "test", "admin")
        hub.services.bearerTokens.revokeDevice(deviceId)
    }

    // ---------------------------------------------------------------------------------------------- (d) revocation

    @Test
    fun aRevokedDeviceIsKickedWithinTheBoundSeesRevokedAndCanEnrolAgainOnTheSameInstall() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        val bearerBefore = assertNotNull(b.client.session.bearer(forceRefresh = true))
        val revokedCert = assertNotNull(b.trust.credentials.value).deviceCert
        assertTrue(hub.services.engine.connectionPool.isConnected("dev-b"))

        val startedAt = System.currentTimeMillis()
        adminRevokes(hub, "dev-b")

        assertTrue(eventually(10_000) { !b.hubSeesOnline() }, "the hub closes the revoked device's session")
        assertTrue(eventually(10_000) { b.client.status.value.state == HubLinkState.REVOKED }, "the client says Revoked: ${b.client.status.value}")
        val tookMs = System.currentTimeMillis() - startedAt
        assertTrue(tookMs < 10_000, "kick + confirmation inside the bound, took $tookMs ms")
        assertTrue(eventually(10_000) { b.trust.credentials.value == null }, "the verified revocation clears the credentials")

        // Bearer calls with the old token are refused.
        val me = http.get("${hub.url}${HubContract.PATH_DIRECTORY_ME}") { header("Authorization", "Bearer $bearerBefore") }
        assertEquals(401, me.status.value, "a revoked device's bearer is dead")

        // A colleague fetches the hub's signed list: the client verifies the root signature and finds the serial.
        val list = assertNotNull(a.client.session.fetchRevocations(), "GET /revocations verifies on the client")
        assertTrue(list.isRevoked(revokedCert), "the revoked certificate is on the signed list")

        // The id stays blocked until an administrator lifts it; then the same install enrols again.
        assertIs<EnrollmentOutcome.Failed>(b.client.enroll(hub.invite()), "a revoked id cannot enrol")
        hub.services.revocations!!.unrevokeDevice("dev-b", "admin")
        b.client.dismissRevokedNotice()
        assertIs<EnrollmentOutcome.Enrolled>(b.client.enroll(hub.invite()))
        b.awaitConnected()
        assertTrue(a.client.relay.sendEnvelope("dev-b", envelope("after-reenrol", "dev-a")))
        assertNotNull(awaitItem(b.received) { it.id == "after-reenrol" }, "relay works again")
    }

    @Test
    fun theSignedRevocationListNeedsNoBearerBecauseARevokedDeviceHasNone() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        assertEquals(404, http.get("${hub.url}${HubContract.PATH_REVOCATIONS}").status.value, "no list is signed before the first revocation")
        adminRevokes(hub, "dev-a")
        val r = http.get("${hub.url}${HubContract.PATH_REVOCATIONS}")
        assertEquals(200, r.status.value, "served without a bearer (the revoked device cannot get one)")
        assertTrue(eventually(10_000) { a.trust.credentials.value == null }, "the device confirmed its own revocation from that list")
    }

    // ------------------------------------------------------------------------------------------------ (e) renewal

    @Test
    fun aCertificateInsideItsRenewalWindowIsRenewedWithANewSerialAndTheRelayReconnects() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        val before = assertNotNull(a.trust.credentials.value).deviceCert
        assertTrue(a.client.renewIfDue() == null, "nothing is due right after enrolment")

        hub.clock.advance(before.validTo - 6 * 3_600_000L - hub.clock.now()) // 6 h before the end: inside the 24 h window

        assertTrue(eventually(15_000) { assertNotNull(a.trust.credentials.value).deviceCert.serial != before.serial }, "renewed by the maintenance tick: failure=${a.client.session.renewalFailure.value} direct=${runBlocking { a.client.renewIfDue() }} status=${a.client.status.value}")
        val after = assertNotNull(a.trust.credentials.value).deviceCert
        assertNotEquals(before.serial, after.serial, "a new serial")
        assertEquals(before.subjectPublicKey, after.subjectPublicKey, "same device key")
        assertTrue(after.validTo > before.validTo)
        assertEquals(after.serial, hub.services.identities.device("dev-a")!!.serial, "the hub's registry follows the renewal")
        assertTrue(!hub.services.revocations!!.isRevoked(before.serial, "dev-a"), "the old serial is not revoked: it simply expires")

        a.awaitConnected()
        assertTrue(eventually { a.hubSeesOnline() }, "the relay session is back on the new certificate")
        assertTrue(a.client.relay.sendEnvelope("dev-b", envelope("after-renew", "dev-a")))
        assertNotNull(awaitItem(b.received) { it.id == "after-renew" })
    }

    @Test
    fun aCertificateExpiredBeyondTheGraceIsNotRenewedAndOnlyANewInviteRecovers() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val before = assertNotNull(a.trust.credentials.value).deviceCert

        hub.clock.advance(before.validTo + 4 * 24 * 3_600_000L - hub.clock.now()) // 96 h past the end, grace is 72 h

        val outcome = a.client.renewIfDue()
        assertIs<EnrollmentOutcome.Failed>(outcome, "the hub refuses a renewal past the grace: $outcome")
        assertTrue(eventually(10_000) { a.client.status.value.state == HubLinkState.CERT_EXPIRED }, "status: ${a.client.status.value}")
        assertEquals(before.serial, assertNotNull(a.trust.credentials.value).deviceCert.serial, "nothing was swapped")

        assertIs<EnrollmentOutcome.Enrolled>(a.client.enroll(hub.invite()), "a fresh invite re-enrols the same device id")
        assertNotEquals(before.serial, assertNotNull(a.trust.credentials.value).deviceCert.serial)
        a.awaitConnected()
    }

    // ------------------------------------------------------------------------------------------------- (f) unenrol

    @Test
    fun unenrollingRemovesTheDirectoryEntryClosesTheSessionAndAnAlreadyDoneUnenrolIsNotAnError() = runBlocking<Unit> {
        val hub = newHub()
        val dir = hub.services.directory
        dir.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        dir.createGroup("g-open", "Open", "eng", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, emptyList(), DirectoryActor.CONSOLE)
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        assertEquals(com.transfer.flash.core.network.enterprise.EnterpriseJoinStatus.JOINED, (b.client.directory.requestJoin(hub.url, "g-open") as com.transfer.flash.core.common.result.FlashResult.Success).value.status)
        assertTrue("dev-b" in dir.getGroup("g-open")!!.memberDeviceIds)
        val bCreds = assertNotNull(b.trust.credentials.value)

        assertEquals(UnenrolResult.DONE, b.client.unenrol())

        assertNull(b.trust.credentials.value, "credentials cleared")
        assertTrue(eventually { !b.hubSeesOnline() }, "session closed")
        assertTrue("dev-b" !in dir.getGroup("g-open")!!.memberDeviceIds, "the hub removed the directory entry")
        assertEquals(com.transfer.flash.server.identity.DeviceStatus.UNENROLLED, hub.services.identities.device("dev-b")!!.status)
        assertTrue(a.client.relay.sendEnvelope("dev-b", envelope("to-gone", "dev-a")))
        assertTrue(eventually { a.acks.any { it.id == "to-gone" } })
        assertEquals(AckStatus.REJECTED, a.acks.first { it.id == "to-gone" }.status, "an unenrolled device is not a recipient: ${a.acks}")

        // Telling the hub again (the pending-notice path of the client) is "already done", not a failure.
        assertEquals(UnenrollOutcome.CONFIRMED, a.client.session.unenroll(forCredentials = bCreds), "an unenrolled device cannot sign in, which the client reads as done")
    }
}
