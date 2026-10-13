@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.enterprise.EnrollmentOutcome
import com.transfer.flash.core.network.enterprise.EnterpriseJoinStatus
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scenario (j) of the client <-> server contract (ADR-124): the hub is stopped and a NEW hub starts on the same data
 * directory. Everything that must be durable is, and the real client finds its way back by itself.
 */
class E2eRestartContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    @Test
    fun enrolmentDirectoryRevocationsSerialsAndTheMailboxSurviveARestartAndClientsComeBack() = runBlocking<Unit> {
        val hub = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        val dir = hub.services.directory
        dir.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        dir.createGroup("g-open", "Open", "eng", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, emptyList(), DirectoryActor.CONSOLE)
        val a = E2eDevice("dev-a", hub).also { devices += it }.enrolAndConnect()
        val b = E2eDevice("dev-b", hub).also { devices += it }.enrolAndConnect()
        val gone = E2eDevice("dev-gone", hub).also { devices += it }.enrolAndConnect()
        assertEquals(EnterpriseJoinStatus.JOINED, (a.client.directory.requestJoin(hub.url, "g-open") as FlashResult.Success).value.status)
        val goneCert = assertNotNull(gone.trust.credentials.value).deviceCert
        hub.services.revocations!!.revokeDevice("dev-gone", "test", "admin")
        hub.services.bearerTokens.revokeDevice("dev-gone")
        val maxSerialBefore = listOf("dev-a", "dev-b", "dev-gone").maxOf { hub.services.identities.device(it)!!.serial }

        // B is away; A leaves it a message that only the mailbox holds.
        b.client.relay.stop()
        assertTrue(eventually { !b.hubSeesOnline() })
        val envelope = FlashEnvelope(id = "durable-1", type = "MSG_TEXT", payloadJson = "opaque", senderId = "dev-a", sentAt = System.currentTimeMillis())
        assertTrue(a.client.relay.sendEnvelope("dev-b", envelope))
        assertTrue(eventually { hub.mailboxRows("dev-b") == 1 })
        val jittersBefore = a.jitterCalls.size

        hub.restart()

        // Durable state on the new hub instance.
        assertEquals("dev-a", hub.services.identities.device("dev-a")?.deviceId, "enrolment survived")
        assertTrue("dev-a" in hub.services.directory.getGroup("g-open")!!.memberDeviceIds, "directory membership survived")
        assertEquals(1, hub.mailboxRows("dev-b"), "the mailbox row survived")
        assertTrue(hub.services.revocations!!.isRevoked(goneCert.serial, "dev-gone"), "the revocation survived")

        // The real client reconnects on its own, with jittered backoff, and its dead bearer is refreshed on the first 401.
        a.awaitConnected(30_000L)
        assertTrue(a.jitterCalls.size > jittersBefore, "the reconnect used the injected jitter: ${a.jitterCalls}")
        val groups = a.client.directory.fetchVisibleGroups(hub.url, "dev-a")
        assertTrue(groups is FlashResult.Success, "a request with the pre-restart bearer recovers: $groups")

        // B comes back and gets the message that waited through the restart.
        b.client.relay.start()
        b.awaitConnected(30_000L)
        val replay = assertNotNull(awaitItem(b.received, 15_000L) { it.id == "durable-1" })
        assertTrue(replay.replay)
        assertTrue(eventually { hub.mailboxRows("dev-b") == 0 })

        // The serial counter continued: a new enrolment gets a higher serial than anything issued before.
        val fresh = E2eDevice("dev-new", hub).also { devices += it }
        assertIs<EnrollmentOutcome.Enrolled>(fresh.client.enroll(hub.invite()))
        assertTrue(assertNotNull(fresh.trust.credentials.value).deviceCert.serial > maxSerialBefore, "serials never repeat across restarts")

        // The revoked device still cannot come back after the restart.
        assertIs<EnrollmentOutcome.Failed>(gone.client.enroll(hub.invite()))
    }
}
