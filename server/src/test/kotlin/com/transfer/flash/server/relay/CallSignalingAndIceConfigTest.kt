@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubIceConfigDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.mailbox.DeviceRegistry
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.fakeSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CallSignalingAndIceConfigTest {

    private val databases = mutableListOf<ServerDatabase>()

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
    }

    private fun revocationsFor(isRevokedFn: (Long?, String) -> Boolean): RevocationService =
        object : RevocationService {
            override fun isRevoked(serial: Long?, deviceId: String): Boolean = isRevokedFn(serial, deviceId)
            override fun current(): RevocationList? = null
            override fun onRevoked(handler: suspend (deviceId: String, serial: Long?, reason: String) -> Unit) = Unit
        }

    @Test
    fun iceServiceMintsValidTimeLimitedTurnCredentialsAndHidesWhenUnconfigured() {
        val unconfigured = CallIceService(clockMs = { 1_700_000_000_000L })
        assertFalse(unconfigured.isSupported)
        assertFalse(unconfigured.configFor("dev-a").offLanCallingSupported)
        assertTrue(unconfigured.configFor("dev-a").iceServers.isEmpty())

        val relayOnlyMissingTurn = CallIceService(
            stunUrls = listOf("stun:stun.example:3478"),
            turnUrls = emptyList(),
            mediaPolicy = "RELAY_ONLY",
        )
        assertFalse(relayOnlyMissingTurn.isSupported)

        val secret = "super-secret-coturn-key-for-test"
        val configured = CallIceService(
            stunUrls = listOf("stun:stun.flash.local:3478"),
            turnUrls = listOf("turn:turn.flash.local:3478?transport=udp", "turns:turn.flash.local:5349?transport=tcp"),
            turnSharedSecret = secret,
            ttlSeconds = 300L,
            mediaPolicy = "DIRECT_PREFERRED_RELAY_FALLBACK",
            clockMs = { 1_700_000_000_000L },
        )
        assertTrue(configured.isSupported)
        val cfg = configured.configFor("alice-phone")
        assertTrue(cfg.offLanCallingSupported)
        assertEquals(300L, cfg.ttlSeconds)
        assertEquals(2, cfg.iceServers.size)
        val turn = cfg.iceServers[1]
        assertEquals("${1_700_000_000L + 300L}:alice-phone", turn.username)
        assertEquals(CallIceService.hmacSha1Base64(secret, turn.username!!), turn.credential)
    }

    @Test
    fun iceConfigRouteEnforcesBearerAuthAndRevocation() = testApplication {
        val tokens = BearerTokenStore()
        val aliceToken = tokens.issue(HubPrincipal("alice", DeviceCert.ROLE_MEMBER, "eng", 10L)).first
        val revokedToken = tokens.issue(HubPrincipal("eve-revoked", DeviceCert.ROLE_MEMBER, "eng", 99L)).first
        val revocations = revocationsFor { serial, id -> serial == 99L || id == "eve-revoked" }
        val iceService = CallIceService(
            stunUrls = listOf("stun:stun.flash.local:3478"),
            turnUrls = listOf("turn:turn.flash.local:3478"),
            turnSharedSecret = "coturn-test-secret",
            clockMs = { 1_700_000_000_000L },
        )
        routing {
            callConfigRoutes(iceService, BearerPrincipalResolver(tokens), revocations)
        }

        val unauth = client.get(HubContract.PATH_CALL_ICE)
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val revokedResp = client.get(HubContract.PATH_CALL_ICE) {
            header("Authorization", "Bearer $revokedToken")
        }
        assertEquals(HttpStatusCode.Forbidden, revokedResp.status)
        assertEquals(HubErrorCodes.ERR_REVOKED, HubRestCodec.decodeOrNull<ErrorBody>(revokedResp.bodyAsText())?.code)

        val okResp = client.get(HubContract.PATH_CALL_ICE) {
            header("Authorization", "Bearer $aliceToken")
        }
        assertEquals(HttpStatusCode.OK, okResp.status)
        val dto = HubRestCodec.decodeOrNull<HubIceConfigDto>(okResp.bodyAsText())
        assertTrue(dto != null && dto.offLanCallingSupported)
        assertEquals(2, dto.iceServers.size)
        assertTrue(dto.iceServers[1].username!!.endsWith(":alice"))
    }

    @Test
    fun callSignalRoutesLiveWithAuthenticatedFromAndNeverWritesMailbox() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val db = ServerDatabase.inMemory(poolSize = 1).also { databases += it }
        val mailbox = MailboxQueue(db, MailboxLimits(), registry = DeviceRegistry.AcceptAll, clock = { nowMs })
        val router = RelayFrameRouter(
            connectionPool = RelayConnectionPool(),
            mailboxQueue = mailbox,
            clock = { nowMs },
        )
        val (aliceSession, _) = fakeSession("alice", Channel.UNLIMITED, 64)
        val (bobSession, bobWs) = fakeSession("bob", Channel.UNLIMITED, 64)
        aliceSession.draining = false
        bobSession.draining = false
        router.register(aliceSession)
        router.register(bobSession)

        val spoofedInvite = RelayFrame.CallSignal(
            to = "bob",
            from = "impersonated-admin",
            callId = "call-1",
            signalId = "sig-1",
            seq = 1L,
            issuedAt = nowMs,
            ttlMs = 15_000L,
            payload = "FLASH_CALL action=invite callId=call-1 from=alice name=Alice video=true",
            capabilities = listOf(HubContract.FEATURE_HUB_CALLING),
        )
        router.processFrame(aliceSession, RelayFrameCodec.encode(spoofedInvite))

        val delivered = bobWs.nextOf<RelayFrame.CallSignal>()
        assertEquals("alice", delivered.from, "hub must stamp authenticated sender deviceId")
        assertEquals("call-1", delivered.callId)
        assertEquals(0, mailbox.pendingCount("bob"), "call signals must never persist into offline mailbox")
    }

    @Test
    fun offlineRecipientExpiredTtlAndRevokedDevicesAreRefusedImmediately() = runBlocking {
        val nowMs = 1_700_000_000_000L
        val db = ServerDatabase.inMemory(poolSize = 1).also { databases += it }
        val mailbox = MailboxQueue(db, MailboxLimits(), registry = DeviceRegistry.AcceptAll, clock = { nowMs })
        val revokedIds = mutableSetOf("bob-revoked")
        val router = RelayFrameRouter(
            connectionPool = RelayConnectionPool(),
            mailboxQueue = mailbox,
            revocation = revocationsFor { _, id -> id in revokedIds },
            clock = { nowMs },
        )
        val (aliceSession, aliceWs) = fakeSession("alice", Channel.UNLIMITED, 64)
        aliceSession.draining = false
        router.register(aliceSession)

        // 1. Offline recipient -> immediate ERR_NOT_FOUND, 0 mailbox rows
        router.processFrame(
            aliceSession,
            RelayFrameCodec.encode(
                RelayFrame.CallSignal(
                    to = "bob-offline",
                    callId = "call-offline",
                    signalId = "sig-off",
                    seq = 1L,
                    issuedAt = nowMs,
                    ttlMs = 10_000L,
                    payload = "FLASH_CALL action=invite callId=call-offline from=alice name=Alice video=false",
                ),
            ),
        )
        val offErr = aliceWs.nextOf<RelayFrame.Error>()
        assertEquals(HubErrorCodes.ERR_NOT_FOUND, offErr.code)
        assertEquals(0, mailbox.pendingCount("bob-offline"))

        // 2. Expired signal -> ERR_EXPIRED even if recipient is online
        val (charlieSession, charlieWs) = fakeSession("charlie", Channel.UNLIMITED, 64)
        charlieSession.draining = false
        router.register(charlieSession)

        router.processFrame(
            aliceSession,
            RelayFrameCodec.encode(
                RelayFrame.CallSignal(
                    to = "charlie",
                    callId = "call-stale",
                    signalId = "sig-stale",
                    seq = 1L,
                    issuedAt = nowMs - 60_000L,
                    ttlMs = 10_000L,
                    payload = "FLASH_CALL action=invite callId=call-stale from=alice name=Alice video=false",
                ),
            ),
        )
        val expErr = aliceWs.nextOf<RelayFrame.Error>()
        assertEquals(HubErrorCodes.ERR_EXPIRED, expErr.code)
        assertTrue(charlieWs.drainNow().isEmpty())

        // 3. Revoked recipient -> ERR_REVOKED
        val (revokedSession, _) = fakeSession("bob-revoked", Channel.UNLIMITED, 64)
        revokedSession.draining = false
        router.register(revokedSession)

        router.processFrame(
            aliceSession,
            RelayFrameCodec.encode(
                RelayFrame.CallSignal(
                    to = "bob-revoked",
                    callId = "call-rev",
                    signalId = "sig-rev",
                    seq = 1L,
                    issuedAt = nowMs,
                    ttlMs = 10_000L,
                    payload = "FLASH_CALL action=invite callId=call-rev from=alice name=Alice video=false",
                ),
            ),
        )
        val revErr = aliceWs.nextOf<RelayFrame.Error>()
        assertEquals(HubErrorCodes.ERR_REVOKED, revErr.code)
    }

    @Test
    fun malformedCallSignalFramesAreRejectedByCodec() {
        val overTtlJson = RelayFrameCodec.encode(
            RelayFrame.CallSignal(
                to = "bob",
                callId = "c1",
                signalId = "s1",
                seq = 1L,
                issuedAt = 1_700_000_000_000L,
                ttlMs = HubContract.CALL_SIGNAL_MAX_TTL_MS + 1L,
                payload = "FLASH_CALL action=invite callId=c1 from=alice name=A video=false",
            ),
        )
        assertEquals(null, RelayFrameCodec.decode(overTtlJson))

        val blankPayloadJson = RelayFrameCodec.encode(
            RelayFrame.CallSignal(
                to = "bob",
                callId = "c1",
                signalId = "s1",
                seq = 1L,
                issuedAt = 1_700_000_000_000L,
                ttlMs = 5_000L,
                payload = "   ",
            ),
        )
        assertEquals(null, RelayFrameCodec.decode(blankPayloadJson))
    }
}
