@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.enterprise.relay.KtorRelayTransport
import com.transfer.flash.core.enterprise.relay.RelaySessionState
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.core.FlashServerEngine
import com.transfer.flash.server.mailbox.DeviceRegistry
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.HubTestFixture
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Delivery semantics of the relay over REAL WebSockets: the real client transport (`KtorRelayTransport`) against the
 * real hub engine, real signed handshakes, a real SQLite mailbox (ADR-112). These are the scenarios of the hub audit
 * that only show up when a socket is on the other end: the reconnect race, acknowledgement-driven deletion, redelivery,
 * revocation of a live connection, abuse limits, graceful shutdown.
 */
class RelayDeliveryContractTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private val databases = mutableListOf<ServerDatabase>()
    private val now = AtomicLong(System.currentTimeMillis())

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        databases.forEach { runCatching { it.close() } }
    }

    private class Hub(val engine: FlashServerEngine, val mailbox: MailboxQueue)

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private fun ApplicationTestBuilder.hub(
        fixture: HubTestFixture,
        policy: RelayPolicy = RelayPolicy(),
        revocation: RevocationService = RevocationService.None,
        limits: MailboxLimits = MailboxLimits(),
        sweepIntervalMs: Long = 60_000L,
        groups: GroupMembershipView? = null,
        trustedProxies: List<String> = emptyList(),
    ): Hub {
        val db = ServerDatabase.inMemory(poolSize = 1).also { databases += it }
        val mailbox = MailboxQueue(db, limits, clock = { now.get() }, registry = DeviceRegistry.AcceptAll)
        val engine = FlashServerEngine(
            config = ServerConfig(),
            mailboxQueue = mailbox,
            challengeAuth = fixture.auth,
            enrollmentService = fixture.enrollment,
            adminAuthService = AdminAuthService(printSetupTokenToConsole = false),
            revocationListProvider = { fixture.revocationList },
            relayPolicy = policy,
            revocationService = revocation,
            groupMembership = groups ?: GroupMembershipView.None,
            sweepIntervalMs = sweepIntervalMs,
            clock = { now.get() },
            clientAddresses = com.transfer.flash.server.auth.ClientAddressResolver(com.transfer.flash.server.auth.TrustedProxies(trustedProxies)) { "127.0.0.1" },
        )
        application { engine.configureApplication(this) }
        return Hub(engine, mailbox)
    }

    private class Dev(val id: String, val transport: KtorRelayTransport, val frames: CopyOnWriteArrayList<RelayFrame>) {
        inline fun <reified T : RelayFrame> all(): List<T> = frames.filterIsInstance<T>()
        suspend fun send(frame: RelayFrame) = transport.sendFrame(frame)
    }

    private suspend fun ApplicationTestBuilder.connect(fixture: HubTestFixture, id: String, device: HubTestFixture.Device = fixture.enrol(id)): Dev {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val t = KtorRelayTransport(client = wsClient(), handshakeTimeoutMs = 3_000L, connectTimeoutMs = 5_000L)
        val frames = CopyOnWriteArrayList<RelayFrame>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { t.incomingFrames.collect { frames.add(it) } }
        assertTrue(t.connect("http://localhost", fixture.identity(device)), "$id: ${t.lastFailure}")
        awaitUntil("$id welcomed") { frames.any { it is RelayFrame.Welcome } }
        return Dev(id, t, frames)
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 8_000L, condition: suspend () -> Boolean) {
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (!condition()) {
                if (System.nanoTime() > deadline) fail("timed out waiting for $what")
                Thread.sleep(10)
            }
        }
    }

    private fun text(id: String, to: String, payload: String = "{\"text\":\"$id\"}") =
        RelayFrame.Send(to = to, id = id, type = "MSG_TEXT", sentAt = now.get(), payload = payload)

    // ---- REL-01 -----------------------------------------------------------------------------------------

    @Test
    fun aReconnectedDeviceIsNotKnockedOutByItsOldConnectionsCleanup() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture)
        val bobDevice = fixture.enrol("bob")
        val alice = connect(fixture, "alice")

        val bob1 = connect(fixture, "bob", bobDevice)
        val bob2 = connect(fixture, "bob", bobDevice) // the reconnect: the hub supersedes bob1

        awaitUntil("bob1 told it was superseded") { bob1.all<RelayFrame.Kick>().any { it.reason == "superseded" } }
        awaitUntil("bob1 gone") { bob1.transport.state.value != RelaySessionState.CONNECTED }
        // bob1's read loop has exited and its cleanup has run. Give any late cleanup time to do damage.
        Thread.sleep(500)

        assertTrue(hub.engine.connectionPool.isConnected("bob"), "REL-01: bob2 must still be registered")
        assertEquals(RelaySessionState.CONNECTED, bob2.transport.state.value)
        alice.send(text("after-race", "bob"))
        awaitUntil("bob2 receives") { bob2.all<RelayFrame.Deliver>().any { it.id == "after-race" } }
        // The hub hands the message to bob2 and answers alice on separate writes: wait for the ack instead of assuming it already arrived.
        awaitUntil("alice is acknowledged") { alice.all<RelayFrame.Ack>().any { it.id == "after-race" } }
        assertEquals(AckStatus.DELIVERED, alice.all<RelayFrame.Ack>().first { it.id == "after-race" }.status)
    }

    // ---- REL-02 / REL-07: acknowledgement-driven delivery ---------------------------------------------

    @Test
    fun theSenderLearnsDeliveredOnlyWhenTheRecipientAcknowledges() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val alice = connect(fixture, "alice")
        val bob = connect(fixture, "bob")

        alice.send(text("m1", "bob"))
        awaitUntil("bob gets it") { bob.all<RelayFrame.Deliver>().any { it.id == "m1" } }
        assertEquals(AckStatus.DELIVERED, alice.all<RelayFrame.Ack>().first { it.id == "m1" }.status)
        Thread.sleep(200)
        assertTrue(alice.all<RelayFrame.QueueState>().isEmpty(), "a hand-off is not a confirmation")

        bob.send(RelayFrame.RecvAck(msgId = "m1"))
        awaitUntil("QUEUE_STATE DELIVERED") { alice.all<RelayFrame.QueueState>().any { it.id == "m1" && it.state == QueueEvent.DELIVERED } }
    }

    @Test
    fun aQueuedMessageIsReplayedUntilAcknowledgedThenDeletedAndTheSenderIsTold() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture)
        val bobDevice = fixture.enrol("bob")
        val alice = connect(fixture, "alice")

        alice.send(text("q1", "bob"))
        alice.send(text("q2", "bob"))
        awaitUntil("both acked QUEUED") { alice.all<RelayFrame.Ack>().count { it.status == AckStatus.QUEUED } == 2 }
        assertEquals(2, hub.mailbox.pendingCount("bob"))

        // First connection: bob receives both and "crashes" before acknowledging.
        val bob1 = connect(fixture, "bob", bobDevice)
        awaitUntil("replay 1") { bob1.all<RelayFrame.Deliver>().map { it.id } == listOf("q1", "q2") }
        assertTrue(bob1.all<RelayFrame.Deliver>().all { it.replay })
        bob1.transport.disconnect("crash")
        awaitUntil("bob offline") { !hub.engine.connectionPool.isConnected("bob") }
        assertEquals(2, hub.mailbox.pendingCount("bob"), "REL-02: nothing was deleted by merely writing to the socket")

        // Second connection: the same two come again; now bob acknowledges one.
        val bob2 = connect(fixture, "bob", bobDevice)
        awaitUntil("replay 2") { bob2.all<RelayFrame.Deliver>().map { it.id } == listOf("q1", "q2") }
        bob2.send(RelayFrame.RecvAck(msgId = "q1"))
        awaitUntil("q1 deleted") { hub.mailbox.pendingCount("bob") == 1 }
        awaitUntil("sender told") { alice.all<RelayFrame.QueueState>().any { it.id == "q1" && it.state == QueueEvent.DELIVERED } }
        assertFalse(alice.all<RelayFrame.QueueState>().any { it.id == "q2" })

        bob2.send(RelayFrame.RecvAck(msgId = "q2"))
        awaitUntil("mailbox empty") { hub.mailbox.pendingCount("bob") == 0 }
    }

    @Test
    fun aMessageThatArrivesAfterTheDeviceWasDrainedIsStillDeliveredInOrder() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val alice = connect(fixture, "alice")
        val bob = connect(fixture, "bob")

        repeat(40) { alice.send(text("n%02d".format(it), "bob")) }
        awaitUntil("all 40 delivered") { bob.all<RelayFrame.Deliver>().size == 40 }
        assertEquals((0 until 40).map { "n%02d".format(it) }, bob.all<RelayFrame.Deliver>().map { it.id })
    }

    // ---- SEC-02 -----------------------------------------------------------------------------------------

    @Test
    fun aForgedAckIsRefusedAndNeverReachesThePeer() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val mallory = connect(fixture, "mallory")
        val bob = connect(fixture, "bob")

        val forged = "{\"t\":\"FLASH_ACK\",\"v\":2,\"id\":\"m9\",\"to\":\"bob\",\"status\":\"DELIVERED\",\"from\":\"alice\"}"
        assertTrue(mallory.transport.sendRaw(forged))

        awaitUntil("error back") { mallory.all<RelayFrame.Error>().any { it.code == HubErrorCodes.ERR_PROTOCOL } }
        Thread.sleep(200)
        assertTrue(bob.all<RelayFrame.Ack>().isEmpty() && bob.all<RelayFrame.QueueState>().isEmpty())
    }

    // ---- revocation (SEC-05) ----------------------------------------------------------------------------

    private class TestRevocations : RevocationService {
        val byDevice = mutableSetOf<String>()
        val bySerial = mutableSetOf<Long>()
        private var handler: (suspend (String, Long?, String) -> Unit)? = null
        override fun isRevoked(serial: Long?, deviceId: String) = deviceId in byDevice || (serial != null && serial in bySerial)
        override fun current(): RevocationList? = null
        override fun onRevoked(handler: suspend (deviceId: String, serial: Long?, reason: String) -> Unit) {
            this.handler = handler
        }
        suspend fun revokeDevice(id: String) {
            byDevice += id
            handler?.invoke(id, null, "admin_revoked")
        }
    }

    @Test
    fun revokingAConnectedDeviceKicksItImmediately() = testApplication {
        val fixture = HubTestFixture()
        val revocations = TestRevocations()
        val hub = hub(fixture, revocation = revocations)
        val carol = connect(fixture, "carol")
        val bob = connect(fixture, "bob")

        revocations.revokeDevice("carol")

        awaitUntil("carol REVOKED") { carol.transport.state.value == RelaySessionState.REVOKED }
        awaitUntil("carol dropped from the pool") { !hub.engine.connectionPool.isConnected("carol") }
        assertEquals(RelaySessionState.CONNECTED, bob.transport.state.value)
    }

    @Test
    fun aDeviceRevokedByIdIsRefusedAtTheHandshakeEvenWithAValidCertificate() = testApplication {
        val fixture = HubTestFixture()
        val revocations = TestRevocations().also { it.byDevice += "dave" }
        hub(fixture, revocation = revocations)
        val dave = fixture.enrol("dave")
        val t = KtorRelayTransport(client = wsClient(), handshakeTimeoutMs = 3_000L, connectTimeoutMs = 5_000L)

        assertFalse(t.connect("http://localhost", fixture.identity(dave)))
        assertEquals("hub refused: ${HubErrorCodes.ERR_REVOKED}", t.lastFailure)
        assertEquals(RelaySessionState.REVOKED, t.state.value)
    }

    @Test
    fun aDeviceRevokedBySerialIsRefusedAtTheHandshake() = testApplication {
        val fixture = HubTestFixture()
        val revocations = TestRevocations()
        hub(fixture, revocation = revocations)
        val erin = fixture.enrol("erin")
        revocations.bySerial += erin.cert.serial
        val t = KtorRelayTransport(client = wsClient(), handshakeTimeoutMs = 3_000L, connectTimeoutMs = 5_000L)

        assertFalse(t.connect("http://localhost", fixture.identity(erin)))
        assertEquals("hub refused: ${HubErrorCodes.ERR_REVOKED}", t.lastFailure)
    }

    // ---- limits (REL-04) --------------------------------------------------------------------------------

    @Test
    fun aFloodOfFramesIsAnsweredWithErrRateLimit() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture, policy = RelayPolicy(frameBurst = 10, framesPerSecond = 0.5, abusiveRejectionsBeforeClose = 1000))
        val mallory = connect(fixture, "mallory")

        repeat(60) { mallory.send(RelayFrame.Ping(ts = it.toLong())) }

        awaitUntil("ERR_RATE_LIMIT") { mallory.all<RelayFrame.Error>().any { it.code == HubErrorCodes.ERR_RATE_LIMIT } }
        assertEquals(RelayFrameRouter.MESSAGE_RATE_LIMIT, mallory.all<RelayFrame.Error>().first { it.code == HubErrorCodes.ERR_RATE_LIMIT }.message)
    }

    @Test
    fun anOversizedWebSocketFrameClosesTheConnection() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture)
        val mallory = connect(fixture, "mallory")

        mallory.transport.sendRaw("x".repeat(HubContract.MAX_FRAME_BYTES + 1024))

        awaitUntil("mallory dropped") { !hub.engine.connectionPool.isConnected("mallory") }
        assertNotEquals(RelaySessionState.CONNECTED, mallory.transport.state.value)
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?) = assertTrue(unexpected != actual, "unexpected $actual")

    @Test
    fun theConnectionCapPerAddressRefusesTheExcessWithErrRateLimit() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture, policy = RelayPolicy(maxConnectionsPerIp = 2))
        val client = wsClient()
        val opened = CopyOnWriteArrayList<RelayFrame>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

        // Two sockets stay open (silent, mid-handshake) ...
        val holders = (1..2).map {
            scope.launch {
                client.webSocket(HubContract.PATH_RELAY) {
                    opened += RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())!!
                    gate.await()
                }
            }
        }
        awaitUntil("two challenges") { opened.count { it is RelayFrame.Challenge } == 2 }
        awaitUntil("both counted") { hub.engine.relayFrameRouter.openConnections == 2 }

        // ... so the third is turned away at once.
        var refusal: RelayFrame? = null
        client.webSocket(HubContract.PATH_RELAY) {
            refusal = RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())
        }
        val error = assertIs<RelayFrame.Error>(refusal)
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, error.code)
        assertEquals(RelayFrameRouter.MESSAGE_TOO_MANY_CONNECTIONS, error.message)

        gate.complete(Unit)
        holders.forEach { it.join() }
        awaitUntil("counters released") { hub.engine.relayFrameRouter.openConnections == 0 }
    }

    /** REV-01: behind a trusted proxy the cap counts the CLIENT (right-most untrusted X-Forwarded-For), not the proxy and not a spoofed first entry. */
    @Test
    fun theConnectionCapBehindATrustedProxyCountsTheClientAddressNotTheProxy() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture, policy = RelayPolicy(maxConnectionsPerIp = 2), trustedProxies = listOf("127.0.0.1/32", "::1/128"))
        val client = wsClient()
        val opened = CopyOnWriteArrayList<RelayFrame>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

        fun holder(forwardedFor: String) = scope.launch {
            client.webSocket(HubContract.PATH_RELAY, request = { headers.append("X-Forwarded-For", forwardedFor) }) {
                opened += RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())!!
                gate.await()
            }
        }
        suspend fun firstFrame(forwardedFor: String): RelayFrame? {
            var first: RelayFrame? = null
            client.webSocket(HubContract.PATH_RELAY, request = { headers.append("X-Forwarded-For", forwardedFor) }) {
                first = RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())
            }
            return first
        }

        val holders = listOf(holder("10.0.0.1"), holder("10.0.0.1"))
        awaitUntil("two challenges") { opened.count { it is RelayFrame.Challenge } == 2 }
        awaitUntil("both counted") { hub.engine.relayFrameRouter.openConnections == 2 }

        // Another client behind the same proxy is NOT throttled by the first client's two sockets.
        assertIs<RelayFrame.Challenge>(firstFrame("10.0.0.2"))
        // The first client's third socket is refused, and prepending a fake entry does not change who it is.
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, assertIs<RelayFrame.Error>(firstFrame("10.0.0.1")).code)
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, assertIs<RelayFrame.Error>(firstFrame("6.6.6.6, 10.0.0.1")).code)

        gate.complete(Unit)
        holders.forEach { it.join() }
        awaitUntil("counters released") { hub.engine.relayFrameRouter.openConnections == 0 }
    }

    /** REV-01: from a peer that is NOT a trusted proxy the header is attacker input and changes nothing (the TCP peer is the key). */
    @Test
    fun anUntrustedPeerCannotEscapeTheCapByForgingXForwardedFor() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture, policy = RelayPolicy(maxConnectionsPerIp = 2), trustedProxies = emptyList())
        val client = wsClient()
        val opened = CopyOnWriteArrayList<RelayFrame>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val holders = (1..2).map { n ->
            scope.launch {
                client.webSocket(HubContract.PATH_RELAY, request = { headers.append("X-Forwarded-For", "10.9.9.$n") }) {
                    opened += RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())!!
                    gate.await()
                }
            }
        }
        awaitUntil("two challenges") { opened.count { it is RelayFrame.Challenge } == 2 }
        awaitUntil("both counted") { hub.engine.relayFrameRouter.openConnections == 2 }
        var third: RelayFrame? = null
        client.webSocket(HubContract.PATH_RELAY, request = { headers.append("X-Forwarded-For", "10.9.9.77") }) {
            third = RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())
        }
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, assertIs<RelayFrame.Error>(third).code)
        gate.complete(Unit)
        holders.forEach { it.join() }
    }

    // ---- REV-05: a session does not outlive its certificate ----------------------------------------------

    private fun shortLived(fixture: HubTestFixture, id: String, validForMs: Long): HubTestFixture.Device {
        val base = fixture.enrol(id)
        val cert = fixture.issueWithWindow(id, base.key, validFrom = fixture.now - 60_000L, validTo = fixture.now + validForMs, serial = 901L)
        return HubTestFixture.Device(id, base.key, cert, base.chain)
    }

    @Test
    fun aConnectedSessionIsClosedWithErrExpiredWhenItsCertificateEnds() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture, policy = RelayPolicy(sessionExpirySkewMs = 0L))
        val carol = connect(fixture, "carol", shortLived(fixture, "carol", 30_000L))
        assertEquals(RelaySessionState.CONNECTED, carol.transport.state.value)

        now.addAndGet(120_000L) // the certificate ended two minutes ago
        awaitUntil("carol told her enrolment expired", timeoutMs = 12_000L) { carol.all<RelayFrame.Error>().any { it.code == HubErrorCodes.ERR_EXPIRED } }
        awaitUntil("carol disconnected") { carol.transport.state.value != RelaySessionState.CONNECTED }
    }

    @Test
    fun anExpiredSessionThatKeepsTalkingIsCutOffAtItsNextFrame() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture, policy = RelayPolicy(sessionExpirySkewMs = 0L))
        val dave = connect(fixture, "dave", shortLived(fixture, "dave", 30_000L))
        connect(fixture, "erin")
        now.addAndGet(120_000L)
        dave.send(text("late", "erin"))
        awaitUntil("dave cut off", timeoutMs = 12_000L) { !hub.engine.connectionPool.isConnected("dave") }
        assertTrue(dave.all<RelayFrame.Error>().any { it.code == HubErrorCodes.ERR_EXPIRED })
    }

    // ---- expiry notifications (REL-11) -----------------------------------------------------------------

    @Test
    fun anOnlineSenderIsToldWhenItsQueuedMessageExpires() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture, limits = MailboxLimits(ttlMs = 1_000L), sweepIntervalMs = 50L)
        val alice = connect(fixture, "alice")

        alice.send(text("will-expire", "nobody-online"))
        awaitUntil("queued") { alice.all<RelayFrame.Ack>().any { it.id == "will-expire" && it.status == AckStatus.QUEUED } }

        now.addAndGet(5_000)
        hub.engine.ttlSweeper!!.start()
        awaitUntil("EXPIRED notice") { alice.all<RelayFrame.QueueState>().any { it.id == "will-expire" && it.state == QueueEvent.EXPIRED } }
        assertEquals(0, hub.mailbox.pendingCount("nobody-online"))
        hub.engine.ttlSweeper!!.stop()
    }

    // ---- graceful shutdown (REL-13) --------------------------------------------------------------------

    @Test
    fun shutdownKicksEverySessionKeepsUnacknowledgedMessagesAndRefusesNewOnes() = testApplication {
        val fixture = HubTestFixture()
        val hub = hub(fixture)
        val alice = connect(fixture, "alice")
        val bob = connect(fixture, "bob")
        val carol = connect(fixture, "carol")

        alice.send(text("unacked", "bob")) // handed to bob, never acknowledged
        awaitUntil("bob has it") { bob.all<RelayFrame.Deliver>().any { it.id == "unacked" } }

        val started = System.nanoTime()
        hub.engine.relayFrameRouter.shutdown(5_000)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        for (dev in listOf(alice, bob, carol)) {
            awaitUntil("${dev.id} kicked") { dev.all<RelayFrame.Kick>().any { it.reason == "server_shutdown" } }
        }
        assertTrue(tookMs < 4_000, "shutdown took $tookMs ms")
        assertEquals(0, hub.engine.connectionPool.activeCount)
        assertEquals(1, hub.mailbox.pendingCount("bob"), "the message bob never acknowledged was moved to the mailbox")

        // A device that connects during/after shutdown is told to go away rather than registered.
        val raw = wsClient()
        var seen: RelayFrame? = null
        raw.webSocket(HubContract.PATH_RELAY) { seen = RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText()) }
        assertEquals("server_shutdown", assertIs<RelayFrame.Kick>(seen).reason)
        assertEquals(0, hub.engine.connectionPool.activeCount)
    }

    @Test
    fun aGroupMessageOverTheWireReachesMembersWithTheGroupIdAndOneAggregateAck() = testApplication {
        val fixture = HubTestFixture()
        val groups = GroupMembershipView { if (it == "team") setOf("alice", "bob", "carol") else null }
        val hub = hub(fixture, groups = groups)
        val alice = connect(fixture, "alice")
        val bob = connect(fixture, "bob") // carol stays offline

        alice.send(RelayFrame.Send(id = "g1", type = "MSG_TEXT", sentAt = now.get(), payload = "{\"t\":\"hello team\"}", group = "team"))

        awaitUntil("bob delivery") { bob.all<RelayFrame.Deliver>().any { it.id == "g1" } }
        assertEquals("team", bob.all<RelayFrame.Deliver>().first().group)
        awaitUntil("aggregate ack") { alice.all<RelayFrame.Ack>().any { it.id == "g1" } }
        val ack = alice.all<RelayFrame.Ack>().first { it.id == "g1" }
        assertEquals("team", ack.to)
        assertEquals(1, ack.delivered)
        assertEquals(1, ack.queued)
        assertEquals(1, hub.mailbox.pendingCount("carol"))

        val outsider = connect(fixture, "outsider")
        outsider.send(RelayFrame.Send(id = "g2", type = "MSG_TEXT", sentAt = now.get(), payload = "{}", group = "team"))
        awaitUntil("refused") { outsider.all<RelayFrame.Ack>().any { it.id == "g2" } }
        assertEquals(AckStatus.REJECTED, outsider.all<RelayFrame.Ack>().first { it.id == "g2" }.status)
        assertEquals(1, hub.mailbox.pendingCount("carol"), "the outsider's message went nowhere")
    }

    @Suppress("unused")
    private fun unusedRunBlocking() = runBlocking { }
}
