@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.enterprise.EnrollFailureReason
import com.transfer.flash.core.enterprise.EnrollmentOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenario (a) of the client <-> server contract (ADR-124): the delegated bootstrap and the enrolment, with the REAL
 * `EnterpriseHub` against the REAL `HubServices`.
 */
class E2eEnrollmentContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()

    private fun hub(configure: (com.transfer.flash.server.config.ServerConfig) -> com.transfer.flash.server.config.ServerConfig = { it }): E2eHub =
        E2eHub(configure = configure).provision().start().also { hubs += it }

    private fun device(hub: E2eHub, id: String, allowCleartext: Boolean = true): E2eDevice =
        E2eDevice(id, hub, allowCleartext = allowCleartext).also { devices += it }

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    @Test
    fun delegatedBootstrapThenEnrolPinsTheOrganisationAndStoresVerifiedCredentials() = runBlocking<Unit> {
        val hub = hub()
        assertTrue(!hub.services.awaitingDelegation, "a delegation issued offline by the root activates the hub")
        val phone = device(hub, "phone-a")

        val outcome = phone.client.enroll(hub.invite())

        val enrolled = assertIs<EnrollmentOutcome.Enrolled>(outcome, outcome.toString())
        val credentials = assertNotNull(phone.trust.credentials.value, "the credentials are stored")
        assertEquals(hub.serverId, credentials.rootFingerprint, "the stored pin is the hub's realm id")
        assertEquals("phone-a", credentials.deviceCert.deviceId)
        assertEquals(enrolled.credentials.deviceCert.serial, credentials.deviceCert.serial)
        assertEquals(hub.url, credentials.hubUrl.trimEnd('/'))
        assertTrue(credentials.delegationChain.isNotEmpty(), "the delegation chain is stored for the relay handshake")
        assertEquals("phone-a", hub.services.identities.device("phone-a")?.deviceId, "the hub registered the device")
    }

    @Test
    fun aHubWhoseRootDoesNotMatchTheInvitePinIsRefusedAndNothingIsStored() = runBlocking<Unit> {
        val hub = hub()
        val phone = device(hub, "phone-a")
        val real = hub.invite()
        val forged = real.copy(rootFingerprint = "00".repeat(32))

        val outcome = phone.client.enroll(forged)

        // The hub verifies the proof against ITS realm id: a proof bound to another pin is refused by the hub (400/403)
        // or, if the hub answers anyway, the client's own pin check refuses it. Either way nothing is stored.
        val failed = assertIs<EnrollmentOutcome.Failed>(outcome)
        assertTrue(
            failed.reason == EnrollFailureReason.WRONG_ORGANISATION || failed.reason == EnrollFailureReason.INVITE_REJECTED,
            "unexpected reason ${failed.reason}",
        )
        assertNull(phone.trust.credentials.value, "a refused enrolment stores nothing")
    }

    @Test
    fun aLinkWithoutAPinIsRefusedBeforeAnythingIsSent() = runBlocking<Unit> {
        val hub = hub()
        val phone = device(hub, "phone-a")
        val unpinned = EnrollmentInvite(hub.url, hub.invite().token, "")
        val failed = assertIs<EnrollmentOutcome.Failed>(phone.client.enroll(unpinned))
        assertEquals(EnrollFailureReason.INVITE_NOT_PINNED, failed.reason)
        assertNull(hub.services.identities.device("phone-a"), "the hub never saw the device")
    }

    @Test
    fun anExpiredInviteIsSaidToBeExpired() = runBlocking<Unit> {
        val hub = hub()
        val phone = device(hub, "phone-a")
        val invite = hub.invite(expiresInMs = 5_000L)
        hub.clock.advance(60_000L)

        val failed = assertIs<EnrollmentOutcome.Failed>(phone.client.enroll(invite))

        assertEquals(EnrollFailureReason.INVITE_EXPIRED, failed.reason, "the hub's answer must map to INVITE_EXPIRED: ${failed.detail}")
        assertNull(phone.trust.credentials.value)
    }

    @Test
    fun aReusedSingleUseInviteIsSaidToBeUsed() = runBlocking<Unit> {
        val hub = hub()
        val first = device(hub, "phone-a")
        val second = device(hub, "phone-b")
        val invite = hub.invite()

        assertIs<EnrollmentOutcome.Enrolled>(first.client.enroll(invite))
        val failed = assertIs<EnrollmentOutcome.Failed>(second.client.enroll(invite))

        assertEquals(EnrollFailureReason.INVITE_USED, failed.reason, "detail=${failed.detail}")
        assertNull(second.trust.credentials.value)
    }

    @Test
    fun anExhaustedDeviceQuotaIsSaidToBeFull() = runBlocking<Unit> {
        val hub = hub { it.copy(deviceQuota = 1) }
        val first = device(hub, "phone-a")
        val second = device(hub, "phone-b")

        assertIs<EnrollmentOutcome.Enrolled>(first.client.enroll(hub.invite()))
        val failed = assertIs<EnrollmentOutcome.Failed>(second.client.enroll(hub.invite()))

        assertEquals(EnrollFailureReason.QUOTA_FULL, failed.reason, "detail=${failed.detail}")
    }

    @Test
    fun aCleartextHubOnAnotherHostIsRefusedUnlessTheHostOptedIn() = runBlocking<Unit> {
        val hub = hub()
        val strict = device(hub, "phone-a", allowCleartext = false)
        val remote = EnrollmentInvite("http://192.0.2.10:${hub.port}", hub.invite().token, hub.serverId)

        val failed = assertIs<EnrollmentOutcome.Failed>(strict.client.enroll(remote))

        assertEquals(EnrollFailureReason.CLEARTEXT_REFUSED, failed.reason)
        assertNull(hub.services.identities.device("phone-a"), "nothing was sent")
        // Loopback is the one cleartext address that needs no opt-in (development and tests).
        assertIs<EnrollmentOutcome.Enrolled>(strict.client.enroll(hub.invite()))
    }
}
