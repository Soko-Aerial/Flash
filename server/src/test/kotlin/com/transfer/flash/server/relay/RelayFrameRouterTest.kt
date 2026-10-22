@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.mailbox.DeviceRegistry
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxMessage
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.mailbox.MailboxStore
import com.transfer.flash.server.mailbox.QueuedMessage
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.FakeWebSocketSession
import com.transfer.flash.server.testsupport.fakeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Router behaviour on already authenticated sessions ("Hub relay wire v2", ADR-109; delivery semantics ADR-112). The
 * handshake itself and everything that needs a real socket is covered by `RelayHandshakeContractTest` and
 * `RelayDeliveryContractTest`; here the sessions are fakes so that timing and failures are deterministic.
 */
class RelayFrameRouterTest {

    private val databases = mutableListOf<ServerDatabase>()

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
    }

    private fun mailbox(
        limits: MailboxLimits = MailboxLimits(),
        registry: DeviceRegistry = DeviceRegistry.AcceptAll,
    ): MailboxQueue {
        val db = ServerDatabase.inMemory(poolSize = 1).also { databases += it }
        return MailboxQueue(db, limits, registry = registry)
    }

    private fun send(to: String, id: String, payload: String = "{\"text\":\"hi\"}", sentAt: Long = System.currentTimeMillis()) =
        RelayFrameCodec.encode(RelayFrame.Send(to = to, id = id, type = "MSG_TEXT", sentAt = sentAt, payload = payload))

    private fun rawSend(to: String, id: String, payload: String) =
        "{\"t\":\"FLASH_RELAY\",\"v\":2,\"to\":\"$to\",\"id\":\"$id\",\"type\":\"MSG_TEXT\",\"sentAt\":1728670000000,\"payload\":\"$payload\"}"

    private fun recvAck(id: String, from: String? = null) = RelayFrameCodec.encode(RelayFrame.RecvAck(msgId = id, from = from))

    private class Rig(val router: RelayFrameRouter) {
        val pool get() = router.connectionPool

        fun connect(deviceId: String, outgoingCapacity: Int = Channel.UNLIMITED, outboundQueue: Int = 256, startDrain: Boolean = false): Pair<RelaySession, FakeWebSocketSession> {
            val (session, ws) = fakeSession(deviceId, outgoingCapacity, outboundQueue)
            router.register(session)
            if (startDrain) router.requestDrain(session) else session.draining = false
            return session to ws
        }
    }

    private fun rig(store: MailboxStore = MailboxStore.None, directory: OrganizationDirectory? = null, policy: RelayPolicy = RelayPolicy(), revocation: RevocationService = RevocationService.None, revocationList: (() -> RevocationList?)? = null) =
        Rig(
            RelayFrameRouter(
                connectionPool = RelayConnectionPool(),
                mailboxQueue = store,
                directory = directory,
                policy = policy,
                revocation = revocation,
                revocationListProvider = revocationList,
            ),
        )

    // ---- live routing and sender stamping ----------------------------------------------------------------

    @Test
    fun liveDeliveryStampsTheAuthenticatedSenderAndAcksHandOver() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (alice, aliceWs) = rig.connect("alice")
        val (_, bobWs) = rig.connect("bob")

        rig.router.processFrame(alice, send("bob", "msg-101", sentAt = 1728670000000L))

        val deliver = bobWs.nextOf<RelayFrame.Deliver>()
        assertEquals("alice", deliver.from, "SEC-02: the sender is the authenticated device")
        assertEquals("msg-101", deliver.id)
        assertEquals(1728670000000L, deliver.sentAt)
        assertEquals("{\"text\":\"hi\"}", deliver.payload)
        assertFalse(deliver.replay)
        val ack = aliceWs.nextOf<RelayFrame.Ack>()
        assertEquals(AckStatus.DELIVERED, ack.status)
        assertEquals("bob", ack.to)
    }

    @Test
    fun aClientCanNotChooseTheSenderByAddingAFromField() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (mallory, _) = rig.connect("mallory")
        val (_, bobWs) = rig.connect("bob")

        val forged = "{\"t\":\"FLASH_RELAY\",\"v\":2,\"to\":\"bob\",\"id\":\"m1\",\"type\":\"MSG_TEXT\"," +
            "\"sentAt\":1728670000000,\"payload\":\"{}\",\"from\":\"alice\",\"sender\":\"alice\"}"
        rig.router.processFrame(mallory, forged)

        assertEquals("mallory", bobWs.nextOf<RelayFrame.Deliver>().from)
    }

    /** SEC-02: there is no client-originated ACK; a forged one must neither be forwarded nor trusted. */
    @Test
    fun clientSentServerFramesAreRefusedAndNeverForwarded() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (mallory, malloryWs) = rig.connect("mallory")
        val (_, bobWs) = rig.connect("bob")

        val forgeries = listOf(
            RelayFrame.Ack(id = "m1", to = "bob", status = AckStatus.DELIVERED),
            RelayFrame.QueueState(id = "m1", to = "bob", state = QueueEvent.DELIVERED),
            RelayFrame.Deliver(from = "alice", id = "m2", sentAt = 1, payload = "{}"),
            RelayFrame.Kick(reason = "go away"),
            RelayFrame.Revoked(reason = "x"),
        )
        for (frame in forgeries) {
            rig.router.processFrame(mallory, RelayFrameCodec.encode(frame))
            val error = malloryWs.nextOf<RelayFrame.Error>()
            assertEquals(HubErrorCodes.ERR_PROTOCOL, error.code, "forged ${frame::class.simpleName}")
            assertEquals(RelayFrameRouter.MESSAGE_UNEXPECTED_FRAME, error.message)
        }
        assertEquals(emptyList(), bobWs.drainNow(), "nothing reached bob")
    }

    @Test
    fun aLiveMessageStaysInFlightUntilTheRecipientAcknowledgesItThenTheSenderIsTold() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, aliceWs) = rig.connect("alice")
        val (bob, bobWs) = rig.connect("bob")

        rig.router.processFrame(alice, send("bob", "m1"))
        bobWs.nextOf<RelayFrame.Deliver>()
        assertEquals(AckStatus.DELIVERED, aliceWs.nextOf<RelayFrame.Ack>().status)
        assertEquals(listOf("m1"), bob.inFlight.keys.toList())
        assertEquals(emptyList(), aliceWs.drainNow().filterIsInstance<RelayFrame.QueueState>(), "no DELIVERED confirmation before the recipient acked")

        rig.router.processFrame(bob, recvAck("m1"))

        val state = aliceWs.nextOf<RelayFrame.QueueState>()
        assertEquals("m1", state.id)
        assertEquals("bob", state.to)
        assertEquals(QueueEvent.DELIVERED, state.state)
        assertTrue(bob.inFlight.isEmpty())
    }

    /** REV-08: one acknowledgement must not confirm (or destroy) another sender's message that happens to use the same id. */
    @Test
    fun anAcknowledgementNamingTheSenderConfirmsOnlyThatSendersMessage() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, aliceWs) = rig.connect("alice")
        val (mallory, malloryWs) = rig.connect("mallory")
        val (bob, bobWs) = rig.connect("bob")

        rig.router.processFrame(alice, send("bob", "dup"))
        bobWs.nextOf<RelayFrame.Deliver>()
        rig.router.processFrame(mallory, send("bob", "dup"))
        assertEquals(1, bob.inFlight.size, "the second sender's message does not overwrite the first one's in-flight entry")
        aliceWs.drainNow()
        malloryWs.drainNow()

        rig.router.processFrame(bob, recvAck("dup", from = "alice"))

        assertEquals(QueueEvent.DELIVERED, aliceWs.nextOf<RelayFrame.QueueState>().state)
        assertEquals(emptyList(), malloryWs.drainNow().filterIsInstance<RelayFrame.QueueState>(), "mallory was not confirmed by alice's acknowledgement")
        assertEquals(1, store.pendingCount("bob"), "mallory's copy is still stored for bob")
    }

    /** REL-02: a half-open recipient never acknowledges; its message must not be lost when the session dies. */
    @Test
    fun anUnacknowledgedLiveMessageIsSpilledToTheMailboxWhenTheSessionEnds() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, _) = rig.connect("alice")
        val (bob, bobWs) = rig.connect("bob")

        rig.router.processFrame(alice, send("bob", "m1"))
        rig.router.processFrame(alice, send("bob", "m2"))
        bobWs.nextOf<RelayFrame.Deliver>(); bobWs.nextOf<RelayFrame.Deliver>()
        bob.fail("socket_died")

        awaitCondition("both rows spilled") { store.pendingCount("bob") == 2 }
        assertEquals(listOf("m1", "m2"), store.fetchPage("bob", 0, 10).map { it.msgId }, "in the order they were handed over")
        assertFalse(rig.pool.isConnected("bob"))
    }

    /** RVW22 F18: a reconnect must not wait for the displaced socket to time out before its unacknowledged messages come back. */
    @Test
    fun aReconnectGetsTheDisplacedSessionsUnacknowledgedMessagesAtOnce() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, _) = rig.connect("alice")
        // The first bob connection never reads, so closing it would take the whole send timeout.
        val (_, _) = rig.connect("bob", outgoingCapacity = 0)

        rig.router.processFrame(alice, send("bob", "m1"))
        rig.router.processFrame(alice, send("bob", "m2"))

        val (_, bob2Ws) = rig.connect("bob")
        val first = bob2Ws.nextOf<RelayFrame.Deliver>(timeoutMs = 3_000L)
        val second = bob2Ws.nextOf<RelayFrame.Deliver>(timeoutMs = 3_000L)
        assertEquals(listOf("m1", "m2"), listOf(first.id, second.id), "in the order they were handed over")
    }

    // ---- offline / mailbox -------------------------------------------------------------------------------

    @Test
    fun offlineRecipientGetsQueuedAndARetryIsADuplicateNotASecondCopy() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, aliceWs) = rig.connect("alice")

        rig.router.processFrame(alice, send("bob", "m1"))
        rig.router.processFrame(alice, send("bob", "m1"))

        assertEquals(AckStatus.QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status)
        assertEquals(AckStatus.DUPLICATE_QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status)
        assertEquals(1, store.pendingCount("bob"))
    }

    @Test
    fun offlineRecipientWithNoMailboxIsReportedUndeliverable() = runBlocking<Unit> {
        val rig = rig()
        val (alice, aliceWs) = rig.connect("alice")
        rig.router.processFrame(alice, send("nobody", "m1"))
        assertEquals(AckStatus.OFFLINE_UNDELIVERABLE, aliceWs.nextOf<RelayFrame.Ack>().status)
    }

    @Test
    fun aRecipientThatWasNeverEnrolledIsRejectedByName() = runBlocking<Unit> {
        val rig = rig(mailbox(registry = DeviceRegistry { it == "bob" }))
        val (alice, aliceWs) = rig.connect("alice")
        rig.router.processFrame(alice, send("bobb", "m1"))
        val ack = aliceWs.nextOf<RelayFrame.Ack>()
        assertEquals(AckStatus.REJECTED, ack.status)
        assertEquals(HubErrorCodes.ERR_UNKNOWN_RECIPIENT, ack.reason)
    }

    @Test
    fun aFullMailboxAnswersQuotaExceededNotQueued() = runBlocking<Unit> {
        val rig = rig(mailbox(MailboxLimits(perRecipientRows = 2)))
        val (alice, aliceWs) = rig.connect("alice")
        repeat(3) { rig.router.processFrame(alice, send("bob", "m$it")) }
        assertEquals(AckStatus.QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status)
        assertEquals(AckStatus.QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status)
        val third = aliceWs.nextOf<RelayFrame.Ack>()
        assertEquals(AckStatus.QUOTA_EXCEEDED, third.status)
        assertEquals(HubErrorCodes.ERR_QUOTA_EXCEEDED, third.reason)
    }

    @Test
    fun aStorageFailureIsAnErrorAckNotASilentDrop() = runBlocking<Unit> {
        val broken = object : MailboxStore by MailboxStore.None {
            override suspend fun enqueue(message: MailboxMessage, ttlOverrideMs: Long?) =
                com.transfer.flash.server.mailbox.EnqueueResult.Failed(IllegalStateException("disk"))
        }
        val rig = rig(broken)
        val (alice, aliceWs) = rig.connect("alice")
        rig.router.processFrame(alice, send("bob", "m1"))
        val ack = aliceWs.nextOf<RelayFrame.Ack>()
        assertEquals(AckStatus.ERROR, ack.status)
        assertEquals(HubErrorCodes.ERR_INTERNAL, ack.reason)
    }

    // ---- drain (REL-02, REL-07) --------------------------------------------------------------------------

    @Test
    fun aReconnectingDeviceIsDrainedWithReplayAndRowsStayUntilItAcknowledges() = runBlocking<Unit> {
        val store = mailbox()
        store.enqueue(MailboxMessage("alice", "bob", "o1", "MSG_TEXT", "{\"n\":1}", 1000L))
        store.enqueue(MailboxMessage("alice", "bob", "o2", "MSG_TEXT", "{\"n\":2}", 2000L))
        val rig = rig(store)
        val (alice, aliceWs) = rig.connect("alice")

        val (bob, bobWs) = rig.connect("bob", startDrain = true)
        val first = bobWs.nextOf<RelayFrame.Deliver>()
        val second = bobWs.nextOf<RelayFrame.Deliver>()
        assertEquals(listOf("o1", "o2"), listOf(first.id, second.id))
        assertTrue(first.replay && second.replay, "mailbox frames are marked replay")
        assertEquals("alice", first.from)

        awaitCondition("drain finished") { !bob.draining }
        assertEquals(2, store.pendingCount("bob"), "REL-02: nothing is deleted just because it was written to the socket")

        rig.router.processFrame(bob, recvAck("o1"))
        assertEquals(1, store.pendingCount("bob"))
        val state = aliceWs.nextOf<RelayFrame.QueueState>()
        assertEquals("o1", state.id)
        assertEquals(QueueEvent.DELIVERED, state.state)
        rig.router.processFrame(bob, recvAck("o2"))
        assertEquals(0, store.pendingCount("bob"))
        assertEquals(listOf("o2"), aliceWs.nextOf<RelayFrame.QueueState>().let { listOf(it.id) })
        assertTrue(alice.isOpen)
    }

    @Test
    fun anUnacknowledgedRowIsDeliveredAgainOnTheNextConnection() = runBlocking<Unit> {
        val store = mailbox()
        store.enqueue(MailboxMessage("alice", "bob", "o1", "MSG_TEXT", "{}", 1000L))
        val rig = rig(store)

        val (bob1, bob1Ws) = rig.connect("bob", startDrain = true)
        assertEquals("o1", bob1Ws.nextOf<RelayFrame.Deliver>().id)
        bob1.fail("wifi_switch") // dropped before it could acknowledge

        awaitCondition("first connection gone") { !rig.pool.isConnected("bob") }
        val (_, bob2Ws) = rig.connect("bob", startDrain = true)
        val again = bob2Ws.nextOf<RelayFrame.Deliver>()
        assertEquals("o1", again.id)
        assertTrue(again.replay)
        assertEquals(1, store.pendingCount("bob"))
    }

    @Test
    fun aMailboxLargerThanOnePageIsDrainedCompletelyInOrderWithoutDuplicates() = runBlocking<Unit> {
        val store = mailbox(MailboxLimits(perRecipientRows = 1000, perSenderRows = 1000))
        repeat(250) { store.enqueue(MailboxMessage("alice", "bob", "m%03d".format(it), "MSG_TEXT", "{\"i\":$it}", 1000L + it)) }
        val rig = rig(store, policy = RelayPolicy(drainPageSize = 100))

        val (_, bobWs) = rig.connect("bob", startDrain = true)
        val ids = (1..250).map { bobWs.nextOf<RelayFrame.Deliver>().id }

        assertEquals((0 until 250).map { "m%03d".format(it) }, ids)
        delay(100)
        assertEquals(emptyList(), bobWs.drainNow(), "no row is sent twice on one connection")
    }

    /** REL-07: a live message must not overtake the older ones that are still being replayed. */
    @Test
    fun aLiveMessageDuringTheDrainIsQueuedBehindTheOlderRows() = runBlocking<Unit> {
        val real = mailbox()
        repeat(3) { real.enqueue(MailboxMessage("alice", "bob", "old$it", "MSG_TEXT", "{}", 1000L + it)) }
        val slow = object : MailboxStore by real {
            override suspend fun fetchPage(target: String, afterSeq: Long, limit: Int): List<QueuedMessage> {
                delay(300)
                return real.fetchPage(target, afterSeq, limit)
            }
        }
        val rig = rig(slow)
        val (alice, aliceWs) = rig.connect("alice")

        val (_, bobWs) = rig.connect("bob", startDrain = true) // the drain's first page takes 300 ms
        rig.router.processFrame(alice, send("bob", "live1"))
        assertEquals(AckStatus.QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status, "bob is still catching up: the live message is stored, not handed over")

        val order = (1..4).map { bobWs.nextOf<RelayFrame.Deliver>().id }
        assertEquals(listOf("old0", "old1", "old2", "live1"), order)

        // Once caught up, delivery is live again.
        awaitCondition("caught up") { !rig.pool.get("bob")!!.draining }
        rig.router.processFrame(alice, send("bob", "live2"))
        assertEquals(AckStatus.DELIVERED, aliceWs.nextOf<RelayFrame.Ack>(matching = { it.id == "live2" }).status)
    }

    /** REL-07 no lost wake-up: rows stored while a drain is finishing are still delivered, and in order. */
    @Test
    fun messagesSentWhileTheRecipientConnectsAreAllDeliveredInOrderExactlyOnce() = runBlocking<Unit> {
        val store = mailbox(MailboxLimits(perRecipientRows = 5000, perSenderRows = 5000))
        // The sender must not be rate limited here (the default budget is 120 frames per burst).
        val rig = rig(store, policy = RelayPolicy(frameBurst = 100_000, framesPerSecond = 100_000.0))
        val (alice, _) = rig.connect("alice")
        val total = 300

        val sender = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            repeat(total) { i ->
                rig.router.processFrame(alice, send("bob", "m%04d".format(i)))
                if (i % 7 == 0) delay(1)
            }
        }
        delay(30)
        val (bob, bobWs) = rig.connect("bob", startDrain = true)

        val received = ArrayList<String>()
        try {
            while (received.size < total) received += bobWs.nextOf<RelayFrame.Deliver>(timeoutMs = 5_000).id
        } catch (e: Exception) {
            val missing = (0 until total).map { "m%04d".format(it) } - received.toSet()
            error("stalled after ${received.size}/$total (${e::class.simpleName}); first missing ${missing.take(5)}; pending=${store.pendingCount("bob")} cursor=${bob.cursor} draining=${bob.draining} inFlight=${bob.inFlight.size}")
        }
        sender.join()
        delay(200)
        val extra = bobWs.drainNow().filterIsInstance<RelayFrame.Deliver>().map { it.id }

        assertEquals((0 until total).map { "m%04d".format(it) }, received, "in order, none missing, none duplicated")
        assertEquals(emptyList(), extra)
        assertFalse(bob.draining)
    }

    // ---- slow consumer (REL-05) ------------------------------------------------------------------------

    @Test
    fun aSlowRecipientIsClosedAndItsMessagesFallBackToTheMailbox() = runBlocking<Unit> {
        val store = mailbox(MailboxLimits(perRecipientRows = 1000, perSenderRows = 1000))
        val rig = rig(store, policy = RelayPolicy(maxInFlightPerSession = 1000))
        val (alice, aliceWs) = rig.connect("alice")
        val (bob, _) = rig.connect("bob", outgoingCapacity = Channel.RENDEZVOUS, outboundQueue = 3)

        val started = System.nanoTime()
        repeat(12) { rig.router.processFrame(alice, send("bob", "m$it")) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 3_000, "the sender never waited for the stuck recipient ($tookMs ms)")
        assertFalse(bob.isOpen)
        assertEquals("slow_consumer", bob.reasonClosed)
        awaitCondition("everything accounted for") { store.pendingCount("bob") >= 8 }
        // Every message is either stored or was handed to bob's (now spilled) in-flight table: nothing vanished.
        awaitCondition("all twelve stored") { store.pendingCount("bob") == 12 }
        repeat(12) { assertIs<RelayFrame.Ack>(aliceWs.next()) }
    }

    @Test
    fun theInFlightCapPushesTheExcessThroughTheMailbox() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store, policy = RelayPolicy(maxInFlightPerSession = 2))
        val (alice, aliceWs) = rig.connect("alice")
        val (bob, _) = rig.connect("bob")

        repeat(5) { rig.router.processFrame(alice, send("bob", "m$it")) }

        val statuses = (1..5).map { aliceWs.nextOf<RelayFrame.Ack>().status }
        assertEquals(listOf(AckStatus.DELIVERED, AckStatus.DELIVERED, AckStatus.QUEUED, AckStatus.QUEUED, AckStatus.QUEUED), statuses)
        assertEquals(2, bob.inFlight.size)
    }

    // ---- rate limit and abuse (REL-04) ---------------------------------------------------------------------

    @Test
    fun aFloodIsAnsweredWithRateLimitOncePerSecondAndFinallyDisconnected() = runBlocking<Unit> {
        val policy = RelayPolicy(frameBurst = 5, framesPerSecond = 0.001, abusiveRejectionsBeforeClose = 20)
        val rig = rig(mailbox(), policy = policy)
        val (alice, aliceWs) = rig.connect("alice")

        repeat(40) { rig.router.processFrame(alice, send("bob", "m$it")) }

        val errors = aliceWs.drainNow().filterIsInstance<RelayFrame.Error>()
        assertEquals(1, errors.count { it.code == HubErrorCodes.ERR_RATE_LIMIT }, "the notice itself is rate limited")
        awaitCondition("flooder closed") { !alice.isOpen }
        assertEquals("rate_limit", alice.reasonClosed)
    }

    @Test
    fun anOversizedFrameIsRefusedWithTooLargeAndTheSessionIsClosed() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (alice, aliceWs) = rig.connect("alice")
        // Built by hand: the shared codec refuses to ENCODE such a frame, a hostile client does not use it.
        val huge = rawSend("bob", "m1", "x".repeat(com.transfer.flash.core.hubprotocol.HubContract.MAX_FRAME_BYTES + 10))

        rig.router.processFrame(alice, huge)

        val error = aliceWs.nextOf<RelayFrame.Error>()
        assertEquals(HubErrorCodes.ERR_TOO_LARGE, error.code)
        assertEquals(RelayFrameRouter.MESSAGE_TOO_LARGE, error.message)
        awaitCondition("closed") { !alice.isOpen }
    }

    @Test
    fun aPayloadOverTheCapButInsideTheFrameLimitIsRefusedAsTooLarge() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (alice, aliceWs) = rig.connect("alice")
        val big = rawSend("bob", "m1", "x".repeat(com.transfer.flash.core.hubprotocol.HubContract.MAX_PAYLOAD_BYTES + 1))
        rig.router.processFrame(alice, big)
        assertEquals(HubErrorCodes.ERR_TOO_LARGE, aliceWs.nextOf<RelayFrame.Error>().code)
    }

    @Test
    fun garbageIsRefusedWithAFixedSentenceAndNoDetail() = runBlocking<Unit> {
        val rig = rig()
        val (alice, aliceWs) = rig.connect("alice")
        rig.router.processFrame(alice, "FLASH_RELAY\nrecipient=bob\nid=1") // the old v1 line format
        val error = aliceWs.nextOf<RelayFrame.Error>()
        assertEquals(HubErrorCodes.ERR_PROTOCOL, error.code)
        assertEquals(RelayFrameRouter.MESSAGE_BAD_FRAME, error.message)
    }

    @Test
    fun pingIsAnsweredWithPong() = runBlocking<Unit> {
        val router = RelayFrameRouter(RelayConnectionPool(), clock = { 1234L })
        val (alice, aliceWs) = fakeSession("alice")
        router.register(alice)
        router.processFrame(alice, RelayFrameCodec.encode(RelayFrame.Ping(ts = 1L)))
        assertEquals(1234L, aliceWs.nextOf<RelayFrame.Pong>().ts)
    }

    // ---- META (SEC-02) ---------------------------------------------------------------------------------

    private fun directoryWithGroup(): OrganizationDirectory {
        val directory = OrganizationDirectory()
        directory.createDepartment("eng", "Engineering", isOrgAdmin = true)
        directory.createGroup(
            id = "grp-dev", name = "Developers", departmentId = "eng", visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE, callerDeviceId = "alice", isOrgAdmin = true,
        )
        directory.addMemberToGroup("grp-dev", "bob", isOrgAdmin = true)
        directory.addMemberToGroup("grp-dev", "charlie", isOrgAdmin = true)
        return directory
    }

    @Test
    fun directMetaOverwritesFrom() = runBlocking<Unit> {
        val rig = rig()
        val (alice, _) = rig.connect("alice")
        val (_, bobWs) = rig.connect("bob")
        rig.router.processFrame(
            alice,
            RelayFrameCodec.encode(RelayFrame.Meta(action = RelayFrame.Meta.ACTION_TYPING, conversationId = "conv-1", to = "bob", from = "mallory")),
        )
        val meta = bobWs.nextOf<RelayFrame.Meta>()
        assertEquals("alice", meta.from, "a claimed `from` never survives the hub")
    }

    /** REV-09: typing / presence is fire-and-forget; whether the recipient exists, is online or is enrolled is never visible to the sender. */
    @Test
    fun directMetaGivesTheSenderTheSameSilenceWhetherTheRecipientIsOnlineOfflineOrUnknown() = runBlocking<Unit> {
        val rig = rig()
        val (alice, aliceWs) = rig.connect("alice")
        val (_, bobWs) = rig.connect("bob")
        for (to in listOf("bob", "offline-device", "no-such-device-ever")) {
            rig.router.processFrame(alice, RelayFrameCodec.encode(RelayFrame.Meta(action = RelayFrame.Meta.ACTION_TYPING, conversationId = "c", to = to)))
        }
        assertEquals("alice", bobWs.nextOf<RelayFrame.Meta>().from)
        assertEquals(emptyList(), aliceWs.drainNow(), "no answer, no error, nothing that tells an online recipient from an unknown one")
    }

    @Test
    fun groupMetaReachesTheMembersAndNobodyElse() = runBlocking<Unit> {
        val rig = rig(directory = directoryWithGroup())
        val (alice, _) = rig.connect("alice")
        val (_, bobWs) = rig.connect("bob")
        val (_, charlieWs) = rig.connect("charlie")
        val (_, outsiderWs) = rig.connect("outsider")

        rig.router.processFrame(alice, RelayFrameCodec.encode(RelayFrame.Meta(action = RelayFrame.Meta.ACTION_ONLINE, conversationId = "grp-dev")))

        assertEquals("alice", bobWs.nextOf<RelayFrame.Meta>().from)
        assertIs<RelayFrame.Meta>(charlieWs.nextOf<RelayFrame.Meta>())
        delay(100)
        assertEquals(emptyList(), outsiderWs.drainNow())
    }

    @Test
    fun aNonMemberCanNotInjectMetaIntoAGroup() = runBlocking<Unit> {
        val rig = rig(directory = directoryWithGroup())
        val (mallory, malloryWs) = rig.connect("mallory")
        val (_, bobWs) = rig.connect("bob")

        rig.router.processFrame(mallory, RelayFrameCodec.encode(RelayFrame.Meta(action = RelayFrame.Meta.ACTION_TYPING, conversationId = "grp-dev")))

        assertEquals(HubErrorCodes.ERR_NOT_MEMBER, malloryWs.nextOf<RelayFrame.Error>().code)
        delay(100)
        assertEquals(emptyList(), bobWs.drainNow(), "SEC-02: nothing reached the group")
    }

    // ---- group relay (REL-14) ------------------------------------------------------------------------------

    @Test
    fun aGroupMessageFansOutToEveryMemberAndTheSenderGetsOneAggregateAck() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store, directory = directoryWithGroup())
        val (alice, aliceWs) = rig.connect("alice")
        val (_, bobWs) = rig.connect("bob") // charlie is offline

        // alice is the creator, but only bob and charlie were added: make alice a member too.
        val directory = rig.router.directory!!
        directory.addMemberToGroup("grp-dev", "alice", isOrgAdmin = true)

        rig.router.processFrame(alice, RelayFrameCodec.encode(RelayFrame.Send(id = "g1", type = "MSG_TEXT", sentAt = 1L, payload = "{\"t\":1}", group = "grp-dev")))

        val deliver = bobWs.nextOf<RelayFrame.Deliver>()
        assertEquals("alice", deliver.from)
        assertEquals("grp-dev", deliver.group)
        val ack = aliceWs.nextOf<RelayFrame.Ack>()
        assertEquals("grp-dev", ack.to)
        assertEquals(1, ack.delivered)
        assertEquals(1, ack.queued)
        assertEquals(0, ack.failed)
        assertEquals(AckStatus.QUEUED, ack.status, "one member is only stored, so the aggregate is QUEUED")
        assertEquals(1, store.pendingCount("charlie"))
        assertEquals("grp-dev", store.fetchPage("charlie", 0, 5).single().group)
    }

    @Test
    fun aNonMemberCanNotSendToAGroupAndLearnsNothingAboutIt() = runBlocking<Unit> {
        val rig = rig(mailbox(), directory = directoryWithGroup())
        val (mallory, malloryWs) = rig.connect("mallory")
        val (_, bobWs) = rig.connect("bob")

        rig.router.processFrame(mallory, RelayFrameCodec.encode(RelayFrame.Send(id = "g1", sentAt = 1L, payload = "{}", group = "grp-dev")))
        rig.router.processFrame(mallory, RelayFrameCodec.encode(RelayFrame.Send(id = "g2", sentAt = 1L, payload = "{}", group = "no-such-group")))

        val real = malloryWs.nextOf<RelayFrame.Ack>()
        val missing = malloryWs.nextOf<RelayFrame.Ack>()
        assertEquals(AckStatus.REJECTED, real.status)
        assertEquals(HubErrorCodes.ERR_NOT_MEMBER, real.reason)
        assertEquals(real.status, missing.status, "same answer for a group that does not exist: no membership oracle")
        assertEquals(real.reason, missing.reason)
        delay(100)
        assertEquals(emptyList(), bobWs.drainNow())
    }

    // ---- revocation (SEC-02, SEC-05) ----------------------------------------------------------------------

    private class MutableRevocation : RevocationService {
        val revoked = mutableSetOf<String>()
        private var handler: (suspend (String, Long?, String) -> Unit)? = null
        override fun isRevoked(serial: Long?, deviceId: String) = deviceId in revoked
        override fun current(): RevocationList? = null
        override fun onRevoked(handler: suspend (deviceId: String, serial: Long?, reason: String) -> Unit) {
            this.handler = handler
        }
        suspend fun revoke(deviceId: String) {
            revoked += deviceId
            handler?.invoke(deviceId, null, "admin_revoked")
        }
    }

    @Test
    fun aDeviceRevokedWhileConnectedIsToldAndKickedAtOnce() = runBlocking<Unit> {
        val revocation = MutableRevocation()
        val rig = rig(mailbox(), revocation = revocation)
        val (carol, carolWs) = rig.connect("carol")
        val (_, bobWs) = rig.connect("bob")

        revocation.revoke("carol")

        assertIs<RelayFrame.Revoked>(carolWs.nextOf<RelayFrame.Revoked>())
        awaitCondition("closed") { !carol.isOpen }
        assertFalse(rig.pool.isConnected("carol"))
        assertTrue(rig.pool.isConnected("bob"), "only the revoked device is dropped")
        assertEquals(emptyList(), bobWs.drainNow())
    }

    @Test
    fun aRevokedDeviceCanNotSendEvenIfNobodyKickedItYet() = runBlocking<Unit> {
        val revocation = MutableRevocation()
        val rig = rig(mailbox(), revocation = revocation)
        val (carol, carolWs) = rig.connect("carol")
        val (_, bobWs) = rig.connect("bob")
        revocation.revoked += "carol" // revoked, but the kick has not run

        rig.router.processFrame(carol, send("bob", "m1"))

        assertIs<RelayFrame.Revoked>(carolWs.nextOf<RelayFrame.Revoked>())
        delay(100)
        assertEquals(emptyList(), bobWs.drainNow(), "a revoked device's frame is never routed")
    }

    @Test
    fun theRevocationListAlsoRevokesByDeviceIdWithoutASerial() = runBlocking<Unit> {
        val list = RevocationList(version = 1, revokedDeviceIds = setOf("carol"), issuerPublicKey = "", signature = "")
        val rig = rig(mailbox(), revocationList = { list })
        val (carol, carolWs) = rig.connect("carol")
        rig.router.processFrame(carol, send("bob", "m1"))
        assertIs<RelayFrame.Revoked>(carolWs.nextOf<RelayFrame.Revoked>())
    }

    @Test
    fun kickByDeviceIdSendsTheReasonAndCloses() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (dave, daveWs) = rig.connect("dave")
        assertTrue(rig.router.kick("dave", "admin_kick"))
        assertEquals("admin_kick", daveWs.nextOf<RelayFrame.Kick>().reason)
        awaitCondition("closed") { !dave.isOpen }
        assertFalse(rig.router.kick("dave", "again"), "kicking an offline device reports false")
    }

    // ---- reconnect race (REL-01) ------------------------------------------------------------------------

    @Test
    fun theOldSessionsCleanupNeverTouchesTheReconnectedOne() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, _) = rig.connect("alice")
        val (a1, _) = rig.connect("dev-a")
        val (a2, a2Ws) = rig.connect("dev-a") // the reconnect: a1 is displaced

        // a1's read loop notices the dead socket only now (the classic race).
        rig.router.sessionEnded(a1, "disconnected")

        assertTrue(rig.pool.isConnected("dev-a"), "A2 must still be registered")
        assertTrue(a2.isOpen, "A2 must not have been closed by A1's cleanup")
        rig.router.processFrame(alice, send("dev-a", "after-race"))
        assertEquals("after-race", a2Ws.nextOf<RelayFrame.Deliver>().id, "and it still receives")
    }

    @Test
    fun theDisplacedSessionsUnacknowledgedMessagesMoveToTheNewConnection() = runBlocking<Unit> {
        val store = mailbox()
        val rig = rig(store)
        val (alice, _) = rig.connect("alice")
        val (a1, a1Ws) = rig.connect("dev-a")
        rig.router.processFrame(alice, send("dev-a", "m1"))
        a1Ws.nextOf<RelayFrame.Deliver>()

        val (_, a2Ws) = rig.connect("dev-a") // reconnects before acknowledging m1

        val replay = a2Ws.nextOf<RelayFrame.Deliver>(timeoutMs = 10_000)
        assertEquals("m1", replay.id)
        assertTrue(replay.replay)
        assertFalse(a1.isOpen)
    }

    // ---- misc ------------------------------------------------------------------------------------------

    @Test
    fun withoutAnAuthenticatorEveryConnectionIsRefused() {
        // Fail closed: a router built without an authenticator must never register a session. (The handshake path is
        // exercised end to end in RelayHandshakeContractTest; this pins the default.)
        val router = RelayFrameRouter(RelayConnectionPool())
        assertEquals(null, router.authenticator)
    }

    @Test
    fun expiryAndDropNoticesReachAnOnlineSender() = runBlocking<Unit> {
        val rig = rig(mailbox())
        val (alice, aliceWs) = rig.connect("alice")
        rig.router.notifyExpired("alice", "bob", "m-old")
        val state = aliceWs.nextOf<RelayFrame.QueueState>()
        assertEquals(QueueEvent.EXPIRED, state.state)
        assertEquals("m-old", state.id)
        rig.router.notifyExpired("nobody-online", "bob", "m-x") // must not throw
        assertTrue(alice.isOpen)
    }

    private suspend fun awaitCondition(what: String, timeoutMs: Long = 5_000L, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            delay(10)
        }
    }
}
