@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Scenario (c) of the client <-> server contract (ADR-124): the relay, with the real `FlashRelayClient` /
 * `KtorRelayTransport` through `EnterpriseHub` against the real hub.
 */
class E2eRelayContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private fun newHub(): E2eHub = E2eHub().provision().start().also { hubs += it }
    private fun newDevice(hub: E2eHub, id: String): E2eDevice = E2eDevice(id, hub).also { devices += it }

    private fun envelope(id: String, sender: String, body: String = "opaque-$id") =
        FlashEnvelope(id = id, type = "MSG_TEXT", payloadJson = body, senderId = sender, sentAt = System.currentTimeMillis())

    @Test
    fun aLiveMessageIsDeliveredAckedAndTheMailboxIsEmptyAfterTheReceiptOnly() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        b.autoAck = false

        assertTrue(a.client.relay.sendEnvelope("dev-b", envelope("m-1", "dev-a")))

        val delivered = assertNotNull(awaitItem(b.received) { it.id == "m-1" }, "B receives the message")
        assertEquals("dev-a", delivered.from, "the hub stamps the authenticated sender")
        assertEquals("opaque-m-1", delivered.payload)
        assertEquals(false, delivered.replay)
        val ack = assertNotNull(awaitItem(a.acks) { it.id == "m-1" }, "FLASH_ACK follows every FLASH_RELAY")
        assertEquals(AckStatus.DELIVERED, ack.status)
        assertEquals("dev-b", ack.to)

        // Not acknowledged yet: no QUEUE_STATE for the sender; once B stores it and sends RECV_ACK, the hub says DELIVERED.
        delay(300)
        assertTrue(a.queueStates.none { it.id == "m-1" }, "no delivery receipt before the recipient's own RECV_ACK")
        assertTrue(b.client.relay.acknowledgeReceipt("m-1"))
        val state = assertNotNull(awaitItem(a.queueStates) { it.id == "m-1" }, "FLASH_QUEUE_STATE follows the recipient's RECV_ACK")
        assertEquals(QueueEvent.DELIVERED, state.state)
        assertEquals(0, hub.mailboxRows("dev-b"))
    }

    @Test
    fun anOfflineRecipientGetsTheMailboxReplayedInOrderAndTheSenderSeesQueuedThenDelivered() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        b.client.relay.stop()
        assertTrue(eventually { !b.hubSeesOnline() }, "the hub notices B left")

        listOf("m-1", "m-2", "m-3").forEach { assertTrue(a.client.relay.sendEnvelope("dev-b", envelope(it, "dev-a"))) }
        assertTrue(eventually { a.acks.size == 3 })
        assertEquals(setOf(AckStatus.QUEUED), a.acks.map { it.status }.toSet(), "an offline recipient is QUEUED: ${a.acks}")
        assertEquals(3, hub.mailboxRows("dev-b"))

        b.client.relay.start()
        b.awaitConnected()
        assertTrue(eventually { b.received.size >= 3 }, "B gets the backlog after its handshake")
        assertEquals(listOf("m-1", "m-2", "m-3"), b.received.map { it.id }.take(3), "oldest first")
        assertTrue(b.received.take(3).all { it.replay }, "mailbox deliveries carry replay=true")
        assertTrue(eventually { a.queueStates.count { it.state == QueueEvent.DELIVERED } == 3 }, "the sender learns each delivery")
        assertTrue(eventually { hub.mailboxRows("dev-b") == 0 }, "rows are deleted by the recipient's RECV_ACK")
    }

    @Test
    fun anUnacknowledgedLiveMessageIsSpilledToTheMailboxWhenTheSessionEndsAndReplayedOnce() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        b.autoAck = false

        a.client.relay.sendEnvelope("dev-b", envelope("m-1", "dev-a"))
        assertNotNull(awaitItem(b.received) { it.id == "m-1" })
        b.client.relay.stop()
        assertTrue(eventually { !b.hubSeesOnline() })
        assertTrue(eventually { hub.mailboxRows("dev-b") == 1 }, "never acknowledged = never lost: the hub keeps it")

        b.autoAck = true
        b.received.clear()
        b.client.relay.start()
        assertNotNull(awaitItem(b.received) { it.id == "m-1" && it.replay }, "replayed after the reconnect")
        assertTrue(eventually { hub.mailboxRows("dev-b") == 0 })
    }

    @Test
    fun theSameMessageIdSentTwiceIsStoredOnce() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        b.client.relay.stop()
        assertTrue(eventually { !b.hubSeesOnline() })

        a.client.relay.sendEnvelope("dev-b", envelope("m-1", "dev-a"))
        a.client.relay.sendEnvelope("dev-b", envelope("m-1", "dev-a"))
        assertTrue(eventually { a.acks.size == 2 })
        assertEquals(listOf(AckStatus.QUEUED, AckStatus.DUPLICATE_QUEUED), a.acks.map { it.status })
        assertEquals(1, hub.mailboxRows("dev-b"), "no second copy")

        b.client.relay.start()
        b.awaitConnected()
        assertTrue(eventually { b.received.isNotEmpty() })
        delay(400)
        assertEquals(1, b.received.count { it.id == "m-1" }, "B receives it exactly once")
    }

    @Test
    fun theMailboxLifetimeFollowsTheHubClockAndTheConfiguredTtlNotTheSendersClock() = runBlocking<Unit> {
        val hub = E2eHub(configure = { it.copy(mailboxTtlDays = 2) }).provision().start().also { hubs += it }
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        b.client.relay.stop()
        assertTrue(eventually { !b.hubSeesOnline() })

        // A phone whose clock is decades behind (REL-09): the message must still be stored with a hub-clock lifetime.
        val stale = FlashEnvelope(id = "old-clock", type = "MSG_TEXT", payloadJson = "x", senderId = "dev-a", sentAt = 1L)
        assertTrue(a.client.relay.sendEnvelope("dev-b", stale))
        assertEquals(AckStatus.QUEUED, assertNotNull(awaitItem(a.acks) { it.id == "old-clock" }).status)

        val (created, expires) = hub.services.database.withConnection { c ->
            c.prepareStatement("SELECT created_at, expires_at FROM mailbox_envelopes WHERE msg_id = ?").use { st ->
                st.setString(1, "old-clock")
                st.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) }
            }
        }
        val now = hub.clock.now()
        assertTrue(kotlin.math.abs(created - now) < 60_000L, "created_at is the hub's time, not the sender's: $created vs $now")
        assertEquals(2L * 24 * 3_600_000L, expires - created, "the configured mailboxTtlDays applies")
        b.client.relay.start()
        assertNotNull(awaitItem(b.received) { it.id == "old-clock" }, "and it is delivered")
    }

    @Test
    fun aSenderCannotNameAnotherSenderNeitherInARelayFrameNorInMeta() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        newDevice(hub, "dev-c").enrolAndConnect()

        val spoofedRelay =
            """{"t":"FLASH_RELAY","to":"dev-b","from":"dev-c","senderId":"dev-c","id":"spoof-1","type":"MSG_TEXT","sentAt":${System.currentTimeMillis()},"payload":"x"}"""
        assertTrue(a.client.relay.transport.sendRaw(spoofedRelay))
        val got = assertNotNull(awaitItem(b.received) { it.id == "spoof-1" })
        assertEquals("dev-a", got.from, "the hub ignores a client-supplied identity (SEC-02)")

        val spoofedMeta = """{"t":"FLASH_META","action":"TYPING","conversationId":"c","to":"dev-b","from":"dev-c","ts":1}"""
        assertTrue(a.client.relay.transport.sendRaw(spoofedMeta))
        val meta = assertNotNull(awaitItem(b.metas) { it.action == RelayFrame.Meta.ACTION_TYPING })
        assertEquals("dev-a", meta.from)
    }

    @Test
    fun aPingIsAnsweredWithAPongAndKeepsTheSessionAlive() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val frames = java.util.concurrent.CopyOnWriteArrayList<RelayFrame>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).let { scope ->
            scope.launch { a.client.relay.transport.incomingFrames.collect { frames += it } }
        }
        try {
            assertTrue(a.client.relay.transport.sendFrame(RelayFrame.Ping(ts = 42L)))
            assertNotNull(awaitItem(frames) { it is RelayFrame.Pong }, "FLASH_PING -> FLASH_PONG")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun aReconnectStormLeavesExactlyOneLiveSession() = runBlocking<Unit> {
        val hub = newHub()
        val a = newDevice(hub, "dev-a").enrolAndConnect()
        val b = newDevice(hub, "dev-b").enrolAndConnect()
        repeat(20) {
            a.client.relay.stop()
            a.client.relay.start()
        }
        a.awaitConnected()
        val pool = hub.services.engine.connectionPool
        assertTrue(eventually { pool.getAllActive().count { it.deviceId == "dev-a" } == 1 && pool.activeCount == 2 }, "one session per device, none leaked: ${pool.getAllActive().map { it.deviceId }}")
        // And the surviving session is a working one.
        assertTrue(a.client.relay.sendEnvelope("dev-b", envelope("after-storm", "dev-a")))
        assertNotNull(awaitItem(b.received) { it.id == "after-storm" })
    }
}
