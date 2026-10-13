@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.network.relay.KtorRelayTransport
import com.transfer.flash.core.network.relay.RelaySessionState
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.core.FlashServerEngine
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.HubTestFixture
import com.transfer.flash.core.security.crypto.FlashCrypto
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
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract test of "Hub relay wire v2" (ADR-109): the REAL client transport (`:core:network` `KtorRelayTransport`,
 * `testImplementation` only) against the REAL server handshake (`FlashServerEngine` + `CertChainRelayAuthenticator`),
 * in one `testApplication`. If either half drifts from `:core:hub-protocol`, this fails.
 *
 * Covered: handshake success, bad signature, foreign root, pinned-realm mismatch, expired certificate, revoked
 * certificate, handshake timeout, and the sender stamp (SEC-02).
 * Not covered here (Phase 2 owns the behaviour): kicking an already connected device when it is revoked (SEC-05).
 */
class RelayHandshakeContractTest {

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private fun ApplicationTestBuilder.hub(
        fixture: HubTestFixture,
        handshakeTimeoutMs: Long = HubContract.HANDSHAKE_TIMEOUT_MS,
    ): FlashServerEngine {
        val engine = FlashServerEngine(
            config = ServerConfig(),
            challengeAuth = fixture.auth,
            enrollmentService = fixture.enrollment,
            adminAuthService = AdminAuthService(printSetupTokenToConsole = false),
            revocationListProvider = { fixture.revocationList },
            relayHandshakeTimeoutMs = handshakeTimeoutMs,
        )
        application { engine.configureApplication(this) }
        return engine
    }

    private fun transport(client: HttpClient) =
        KtorRelayTransport(client = client, handshakeTimeoutMs = 3_000L, connectTimeoutMs = 5_000L)

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (!condition()) {
                if (System.nanoTime() > deadline) fail("timed out waiting for $what")
                Thread.sleep(10)
            }
        }
    }

    private fun collect(scope: CoroutineScope, t: KtorRelayTransport): CopyOnWriteArrayList<RelayFrame> {
        val frames = CopyOnWriteArrayList<RelayFrame>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { t.incomingFrames.collect { frames.add(it) } }
        return frames
    }

    @Test
    fun handshakeSucceedsAndWelcomeNamesTheAuthenticatedDevice() = testApplication {
        val fixture = HubTestFixture()
        val engine = hub(fixture)
        val alice = fixture.enrol("alice")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t = transport(wsClient())
        val frames = collect(scope, t)

        assertTrue(t.connect("http://localhost", fixture.identity(alice)), "handshake must succeed: ${t.lastFailure}")
        assertEquals(RelaySessionState.CONNECTED, t.state.value)

        awaitUntil("WELCOME") { frames.any { it is RelayFrame.Welcome } }
        val welcome = frames.filterIsInstance<RelayFrame.Welcome>().first()
        assertEquals("alice", welcome.principal.deviceId)
        assertEquals(alice.cert.serial, welcome.principal.certSerial)
        assertEquals(HubContract.RELAY_WIRE_VERSION, welcome.v)
        assertTrue(engine.connectionPool.isConnected("alice"))

        t.disconnect()
        scope.cancel()
    }

    @Test
    fun badSignatureIsRefusedWithErrAuthAndNoSession() = testApplication {
        val fixture = HubTestFixture()
        val engine = hub(fixture)
        val alice = fixture.enrol("alice")
        val t = transport(wsClient())

        val stranger = FlashCrypto.generateEcKeyPair()
        val accepted = t.connect("http://localhost", fixture.identity(alice, signingKey = stranger))

        assertEquals(false, accepted)
        assertEquals("hub refused: ${HubErrorCodes.ERR_AUTH}", t.lastFailure)
        assertEquals(false, engine.connectionPool.isConnected("alice"))
    }

    @Test
    fun certificateFromAForeignRootIsRefused() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val foreign = HubTestFixture() // another organisation, another root
        val mallory = foreign.enrol("mallory")
        val t = transport(wsClient())

        // The client pins THIS hub's realm (so it talks to it), but presents a chain issued by another root.
        val accepted = t.connect("http://localhost", foreign.identity(mallory, pinnedServerId = fixture.serverId))

        assertEquals(false, accepted)
        assertEquals("hub refused: ${HubErrorCodes.ERR_AUTH}", t.lastFailure)
    }

    @Test
    fun clientRefusesAHubWhoseRealmIsNotThePinnedOne() = testApplication {
        val fixture = HubTestFixture()
        val engine = hub(fixture)
        val other = HubTestFixture()
        val alice = fixture.enrol("alice")
        val t = transport(wsClient())

        // The device was enrolled with `fixture` but pinned to `other`'s fingerprint: it must not answer the challenge.
        val accepted = t.connect("http://localhost", fixture.identity(alice, pinnedServerId = other.serverId))

        assertEquals(false, accepted)
        assertEquals("serverId does not match the pinned realm", t.lastFailure)
        assertEquals(false, engine.connectionPool.isConnected("alice"))
    }

    @Test
    fun expiredCertificateIsRefusedWithErrExpired() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val alice = fixture.enrol("alice")
        val expired = fixture.issueWithWindow(
            deviceId = "alice",
            key = alice.key,
            validFrom = fixture.now - 7_200_000L,
            validTo = fixture.now - 3_600_000L, // beyond the 60 s skew tolerance
        )
        val t = transport(wsClient())

        val accepted = t.connect("http://localhost", fixture.identity(alice, cert = expired))

        assertEquals(false, accepted)
        assertEquals("hub refused: ${HubErrorCodes.ERR_EXPIRED}", t.lastFailure)
    }

    @Test
    fun revokedCertificateIsRefusedWithErrRevokedAndTheClientStopsRetrying() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val alice = fixture.enrol("alice")
        fixture.revocationList = com.transfer.flash.core.hubprotocol.certs.RevocationList.issue(
            issuerPrivateKey = fixture.rootKey.private,
            issuerPublicKey = fixture.rootPublicKey,
            version = 1L,
            revokedSerials = setOf(alice.cert.serial),
            revokedDeviceIds = emptySet(),
        )
        val t = transport(wsClient())

        val accepted = t.connect("http://localhost", fixture.identity(alice))

        assertEquals(false, accepted)
        assertEquals("hub refused: ${HubErrorCodes.ERR_REVOKED}", t.lastFailure)
        assertEquals(RelaySessionState.REVOKED, t.state.value, "REVOKED is terminal on the client")
    }

    @Test
    fun aSilentClientIsCutOffWithErrHandshakeTimeout() = testApplication {
        val fixture = HubTestFixture()
        val engine = hub(fixture, handshakeTimeoutMs = 300L)
        val raw = wsClient()

        raw.webSocket(HubContract.PATH_RELAY) {
            val challenge = assertIs<RelayFrame.Challenge>(
                assertNotNull(RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())),
            )
            assertEquals(fixture.serverId, challenge.serverId)
            assertTrue(challenge.serverNonce.isNotBlank())
            // Say nothing. The hub must give up on its own, with a fixed sentence.
            val error = assertIs<RelayFrame.Error>(
                assertNotNull(RelayFrameCodec.decode((incoming.receive() as Frame.Text).readText())),
            )
            assertEquals(HubErrorCodes.ERR_HANDSHAKE_TIMEOUT, error.code)
            assertEquals(RelayFrameRouter.MESSAGE_HANDSHAKE_TIMEOUT, error.message)
        }
        assertEquals(0, engine.connectionPool.activeCount)
    }

    @Test
    fun theHubStampsTheAuthenticatedSenderAndIgnoresAClaimedOne() = testApplication {
        val fixture = HubTestFixture()
        hub(fixture)
        val alice = fixture.enrol("alice")
        val bob = fixture.enrol("bob")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val bobTransport = transport(wsClient())
        val aliceTransport = transport(wsClient())
        val bobFrames = collect(scope, bobTransport)
        val aliceFrames = collect(scope, aliceTransport)
        assertTrue(bobTransport.connect("http://localhost", fixture.identity(bob)), "bob: ${bobTransport.lastFailure}")
        assertTrue(aliceTransport.connect("http://localhost", fixture.identity(alice)), "alice: ${aliceTransport.lastFailure}")

        // 1. An honest send.
        assertTrue(
            aliceTransport.sendFrame(RelayFrame.Send(to = "bob", id = "m-1", type = "MSG_TEXT", sentAt = fixture.now, payload = "{\"text\":\"hi\"}")),
        )
        awaitUntil("DELIVER m-1") { bobFrames.any { it is RelayFrame.Deliver && it.id == "m-1" } }
        val honest = bobFrames.filterIsInstance<RelayFrame.Deliver>().first { it.id == "m-1" }
        assertEquals("alice", honest.from)
        assertEquals("{\"text\":\"hi\"}", honest.payload)
        awaitUntil("ACK m-1") { aliceFrames.any { it is RelayFrame.Ack && it.id == "m-1" } }
        val ack = aliceFrames.filterIsInstance<RelayFrame.Ack>().first { it.id == "m-1" }
        assertEquals(AckStatus.DELIVERED, ack.status)
        assertEquals("bob", ack.to)

        // 2. A frame that tries to name another sender: the hub reads no such field, so bob still sees alice.
        val forged = "{\"t\":\"FLASH_RELAY\",\"v\":2,\"to\":\"bob\",\"id\":\"m-2\",\"type\":\"MSG_TEXT\"," +
            "\"sentAt\":${fixture.now},\"payload\":\"{}\",\"from\":\"carol\",\"sender\":\"carol\"}"
        assertTrue(aliceTransport.sendRaw(forged))
        awaitUntil("DELIVER m-2") { bobFrames.any { it is RelayFrame.Deliver && it.id == "m-2" } }
        assertEquals("alice", bobFrames.filterIsInstance<RelayFrame.Deliver>().first { it.id == "m-2" }.from)

        aliceTransport.disconnect()
        bobTransport.disconnect()
        scope.cancel()
    }

    @Test
    fun anOfflineRecipientGetsQueuedAndTheSenderIsToldSo() = testApplication {
        val fixture = HubTestFixture()
        val engine = FlashServerEngine(
            config = ServerConfig(),
            mailboxQueue = MailboxQueue(ServerDatabase.inMemory(poolSize = 1)),
            challengeAuth = fixture.auth,
            enrollmentService = fixture.enrollment,
            adminAuthService = AdminAuthService(printSetupTokenToConsole = false),
        )
        application { engine.configureApplication(this) }
        val alice = fixture.enrol("alice")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val t = transport(wsClient())
        val frames = collect(scope, t)
        assertTrue(t.connect("http://localhost", fixture.identity(alice)), "alice: ${t.lastFailure}")

        t.sendFrame(RelayFrame.Send(to = "bob", id = "m-q", type = "MSG_TEXT", sentAt = fixture.now, payload = "{}"))
        awaitUntil("ACK m-q") { frames.any { it is RelayFrame.Ack && it.id == "m-q" } }
        assertEquals(AckStatus.QUEUED, frames.filterIsInstance<RelayFrame.Ack>().first { it.id == "m-q" }.status)

        t.disconnect()
        scope.cancel()
    }
}
