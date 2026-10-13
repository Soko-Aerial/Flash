@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.enterprise.EnterpriseJoinStatus
import com.transfer.flash.core.enterprise.relay.RelaySessionState
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scenarios (h) group fan-out and (i) limits of the client <-> server contract (ADR-124). The handshake timeout is
 * covered against the real client by `RelayHandshakeContractTest`; HubServices does not expose it as a seam.
 */
class E2eGroupAndLimitsContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()
    private val scopes = ArrayList<CoroutineScope>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.cancel() }
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private fun newHub(): E2eHub = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision().start().also { hubs += it }
    private fun newDevice(hub: E2eHub, id: String): E2eDevice = E2eDevice(id, hub).also { devices += it }
    private fun envelope(id: String, sender: String) =
        FlashEnvelope(id = id, type = "MSG_TEXT", payloadJson = "opaque-$id", senderId = sender, sentAt = System.currentTimeMillis())

    private fun groupSend(id: String, group: String) =
        RelayFrame.Send(to = "", id = id, type = "MSG_TEXT", sentAt = System.currentTimeMillis(), payload = "group-$id", group = group)

    @Test
    fun aGroupMessageReachesLiveMembersQueuesForOfflineOnesAndRefusesANonMember() = runBlocking<Unit> {
        val hub = newHub()
        val dir = hub.services.directory
        dir.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        dir.createGroup("g-all", "All", "eng", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, emptyList(), DirectoryActor.CONSOLE)
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        val c = newDevice(hub, "dev-c").enrolAndConnect()
        val outsider = newDevice(hub, "dev-d").enrolAndConnect()
        for (d in listOf(a, b, c)) {
            val joined = d.client.directory.requestJoin(hub.url, "g-all")
            assertEquals(EnterpriseJoinStatus.JOINED, (joined as com.transfer.flash.core.common.result.FlashResult.Success).value.status)
        }
        c.client.relay.stop()
        assertTrue(eventually { !c.hubSeesOnline() })

        assertTrue(a.client.relay.transport.sendFrame(groupSend("grp-1", "g-all")))

        val ack = assertNotNull(awaitItem(a.acks) { it.id == "grp-1" })
        assertEquals("g-all", ack.to, "a group ack names the group")
        assertEquals(1, ack.delivered, "B was online: $ack")
        assertEquals(1, ack.queued, "C was offline: $ack")
        assertEquals(0, ack.failed, "$ack")
        val live = assertNotNull(awaitItem(b.received) { it.id == "grp-1" })
        assertEquals("dev-a", live.from)
        assertEquals("g-all", live.group, "the group travels with the delivery")
        assertEquals(1, hub.mailboxRows("dev-c"))
        assertTrue(a.received.none { it.id == "grp-1" }, "the sender is not a recipient")

        c.client.relay.start()
        val replay = assertNotNull(awaitItem(c.received) { it.id == "grp-1" })
        assertTrue(replay.replay)
        assertEquals("g-all", replay.group)

        // The outsider is not a member: refused, indistinguishable from an unknown group, and nobody gets the message.
        assertTrue(outsider.client.relay.transport.sendFrame(groupSend("grp-2", "g-all")))
        val refused = assertNotNull(awaitItem(outsider.acks) { it.id == "grp-2" })
        assertEquals(AckStatus.REJECTED, refused.status)
        assertEquals(HubErrorCodes.ERR_NOT_MEMBER, refused.reason)
        assertTrue(outsider.client.relay.transport.sendFrame(groupSend("grp-3", "g-nonexistent")))
        assertEquals(HubErrorCodes.ERR_NOT_MEMBER, assertNotNull(awaitItem(outsider.acks) { it.id == "grp-3" }).reason, "no membership oracle")
        kotlinx.coroutines.delay(300)
        assertTrue(b.received.none { it.id == "grp-2" } && c.received.none { it.id == "grp-2" })
    }

    @Test
    fun anOversizePayloadIsAnsweredTooLargeAndTheSessionIsClosed() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val frames = CopyOnWriteArrayList<RelayFrame>()
        val states = CopyOnWriteArrayList<RelaySessionState>()
        val scope = CoroutineScope(Dispatchers.IO).also { scopes += it }
        scope.launch { a.client.relay.transport.incomingFrames.collect { frames += it } }
        scope.launch { a.client.relay.state.collect { states += it } }
        kotlinx.coroutines.delay(100)
        states.clear()

        // Under the 256 KiB frame limit, over the 64 KiB payload limit: the hub says why, then closes.
        val big = """{"t":"FLASH_RELAY","to":"dev-b","id":"big","type":"MSG_TEXT","sentAt":1,"payload":"${"x".repeat(HubContract.MAX_PAYLOAD_BYTES + 1024)}"}"""
        a.client.relay.transport.sendRaw(big)

        assertNotNull(awaitItem(frames) { it is RelayFrame.Error && it.code == HubErrorCodes.ERR_TOO_LARGE }, "ERR_TOO_LARGE frame")
        assertTrue(eventually(10_000) { states.any { it != RelaySessionState.CONNECTED } }, "the hub closed the session: $states")
        a.awaitConnected() // the real client simply reconnects
    }

    @Test
    fun aFrameOverTheWebSocketLimitClosesTheSessionAtOnce() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val states = CopyOnWriteArrayList<RelaySessionState>()
        val scope = CoroutineScope(Dispatchers.IO).also { scopes += it }
        scope.launch { a.client.relay.state.collect { states += it } }
        kotlinx.coroutines.delay(100)
        states.clear()

        a.client.relay.transport.sendRaw("x".repeat(HubContract.MAX_FRAME_BYTES + 1024))

        assertTrue(eventually(10_000) { states.any { it != RelaySessionState.CONNECTED } }, "closed (WebSocket 1009), not served: $states")
        a.awaitConnected()
    }

    @Test
    fun aFrameFloodIsAnsweredWithRateLimitErrorsAndTheHubKeepsServing() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        val frames = CopyOnWriteArrayList<RelayFrame>()
        val scope = CoroutineScope(Dispatchers.IO).also { scopes += it }
        scope.launch { a.client.relay.transport.incomingFrames.collect { frames += it } }
        kotlinx.coroutines.delay(100)

        repeat(400) { a.client.relay.transport.sendFrame(RelayFrame.Ping(ts = it.toLong())) }

        assertNotNull(awaitItem(frames) { it is RelayFrame.Error && it.code == HubErrorCodes.ERR_RATE_LIMIT }, "ERR_RATE_LIMIT after the burst")
        // Another device is not affected by A's flood.
        assertTrue(b.client.relay.sendEnvelope("dev-a", envelope("still-works", "dev-b")))
        assertNotNull(awaitItem(a.received) { it.id == "still-works" })
    }
}
