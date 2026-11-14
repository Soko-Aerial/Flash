@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.server.ops

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.hubprotocol.rest.AnnouncementFeedDto
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.EnrollRequest
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.HubServices
import com.transfer.flash.server.HubStartupException
import com.transfer.flash.server.KeyTool
import com.transfer.flash.server.PassphraseSource
import com.transfer.flash.server.announcements.AnnouncementStore
import com.transfer.flash.server.auth.HubKeyStore
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.core.FlashServerEngine
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.mailbox.DeviceRegistry
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxMessage
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.observability.DefaultManagementGuard
import com.transfer.flash.server.observability.DiskUsage
import com.transfer.flash.server.observability.OperationalMonitor
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.relay.RelayFrameRouter
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.fakeSession
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HR11 Operational monitoring, runbook alert drills, restore rehearsal, signer rotation,
 * isolated HUBREADY/HUBWORK lifecycle checks, and synthetic PERF-H03 host benchmark.
 */
class OperationsAndRolloutDrillsTest {

    private val dirs = ArrayList<File>()
    private val dbs = ArrayList<ServerDatabase>()
    private val hubs = ArrayList<HubServices>()

    @After
    fun cleanUp() {
        hubs.forEach { runCatching { it.stop() } }
        dbs.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String = "flash-hr11"): File =
        Files.createTempDirectory(prefix).toFile().also { dirs += it }

    private fun provisionRootAndDelegation(
        dataDir: File,
        rootDir: File,
        maxDevices: Int = 50,
        validDays: Long = 30,
        serial: Long? = null,
    ): KeyTool.IssueResult {
        val keys = HubKeyStore(dataDir.toPath()).loadOrCreateDelegateKey()
        val tool = KeyTool(PrintStream(ByteArrayOutputStream()), PassphraseSource { _, _ -> null })
        val rootPriv = rootDir.toPath().resolve(KeyTool.ROOT_PRIV)
        if (!Files.exists(rootPriv)) tool.initRoot(rootDir.toPath(), encrypt = false)
        return tool.issueDelegation(
            rootKeyFile = rootPriv,
            serverPub = keys.publicKey,
            scopes = setOf("enrol:member", "enrol:admin", "revoke:member"),
            maxDevices = maxDevices,
            validDays = validDays,
            serial = serial,
            outFile = dataDir.toPath().resolve("delegation.cert"),
        )
    }

    private fun proxyConfig(dataDir: File, port: Int = 18443): ServerConfig = ServerConfig(
        port = port,
        adminPort = port,
        adminOnPublicPort = true,
        host = "127.0.0.1",
        dataDir = dataDir.path,
        tlsMode = "proxy",
        externalUrl = "https://hub.hr11.test",
        managementToken = "mgmt-hr11-secret-token",
    )

    private suspend fun ApplicationTestBuilder.enrol(
        hub: HubServices,
        deviceId: String,
        key: KeyPair,
        department: String = "eng",
    ): EnrollResponse {
        val enrollment = assertNotNull(hub.enrollment)
        val token = enrollment.createInvite(
            "https://hub.hr11.test",
            InviteScope(department = department, expiry = System.currentTimeMillis() + 60_000),
        ).token
        val proof = FlashCrypto.sign(
            key.private,
            HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), enrollment.serverId),
        )
        val res = client.post(HubContract.PATH_ENROLL) {
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(EnrollRequest(token, deviceId, key.toBase64Spki(), friendlyName = "Device $deviceId", proof = proof)))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return HubRestCodec.decode(res.bodyAsText())
    }

    private suspend fun HttpClient.login(
        hub: HubServices,
        deviceId: String,
        key: KeyPair,
        enrolled: EnrollResponse,
    ): String {
        val challenge = HubRestCodec.decode<AuthChallengeResponse>(get(HubContract.PATH_AUTH_CHALLENGE).bodyAsText())
        val serverId = hub.challengeAuth!!.serverId
        val sig = FlashCrypto.sign(
            key.private,
            HubAuth.authSigningBytes(serverId, challenge.serverNonce, "cn-$deviceId", deviceId, enrolled.deviceCert.serial),
        )
        val res = post(HubContract.PATH_AUTH_VERIFY) {
            contentType(ContentType.Application.Json)
            setBody(
                HubRestCodec.encode(
                    AuthVerifyRequest(
                        serverNonce = challenge.serverNonce,
                        clientNonce = "cn-$deviceId",
                        deviceId = deviceId,
                        deviceCert = enrolled.deviceCert,
                        delegationChain = enrolled.delegationChain,
                        signature = sig,
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return HubRestCodec.decode<AuthVerifyResponse>(res.bodyAsText()).token
    }

    @Test
    fun operationalMonitoringExposesQuarantineMailAgeDiskAndCertAlertsWithRecovery() = testApplication {
        val dir = tempDir("flash-hr11-mon")
        val db = ServerDatabase("jdbc:sqlite:${File(dir, "flash-hub.db").path.replace('\\', '/')}", 2).also { dbs += it }
        val nowMs = AtomicLong(1_760_000_000_000L)
        val badKey = SecretBox.ofKey(ByteArray(32) { 0x55 })
        val liveKey = SecretBox.ofKey(ByteArray(32) { 0x11 })

        // Intentionally inject 12 unreadable mailbox rows sealed under a wrong key + 1 aged readable row
        val oldQueue = MailboxQueue(db, MailboxLimits(), clock = { nowMs.get() }, registry = DeviceRegistry.AcceptAll, secrets = badKey)
        runBlocking {
            repeat(12) { i ->
                oldQueue.enqueue(MailboxMessage("alice", "bob", "bad-$i", "MSG_TEXT", "corrupt-$i", nowMs.get()))
            }
        }
        // Advance clock 145h (> 144h critical oldest-mail threshold, inside default 7d TTL) and enqueue readable row under liveKey
        val firstQueuedAt = nowMs.get()
        val liveQueue = MailboxQueue(db, MailboxLimits(), clock = { nowMs.get() }, registry = DeviceRegistry.AcceptAll, secrets = liveKey)
        runBlocking {
            liveQueue.enqueue(MailboxMessage("alice", "bob", "aged-good", "MSG_TEXT", "waiting-6d", firstQueuedAt))
            nowMs.addAndGet(145L * 3600L * 1000L)
            val page = liveQueue.fetchPageDetailed("bob", 0, 10)
            assertEquals(12, page.quarantined.size)
            assertEquals(1, page.messages.size)
        }

        val diskRef = AtomicReference(DiskUsage(totalBytes = 10_000_000_000L, usableBytes = 400_000_000L)) // 96% used -> CRITICAL
        val delegationDaysRef = AtomicLong(2L) // <= 3d critical
        val tlsDaysRef = AtomicLong(10L) // <= 14d warn

        val engine = FlashServerEngine(
            config = proxyConfig(dir),
            mailboxQueue = liveQueue,
            mailboxStats = liveQueue,
            database = db,
            managementGuard = DefaultManagementGuard(token = "mgmt-secret"),
            delegationDaysRemaining = { delegationDaysRef.get() },
            tlsDaysRemaining = { tlsDaysRef.get() },
            diskUsageProvider = { diskRef.get() },
            clock = { nowMs.get() },
        )
        application { engine.configureApplication(this) }

        val healthRes = client.get("/health")
        assertEquals(HttpStatusCode.OK, healthRes.status)
        assertTrue("\"status\":\"degraded\"" in healthRes.bodyAsText(), healthRes.bodyAsText())

        val detail = client.get("/health/detail") { header("Authorization", "Bearer mgmt-secret") }.bodyAsText()
        assertTrue("\"status\":\"degraded\"" in detail, detail)
        assertTrue("\"quarantinedTotal\":12" in detail, detail)
        assertTrue("\"quarantinedOutstanding\":12" in detail, detail)
        assertTrue(OperationalMonitor.ALERT_UNREADABLE_QUEUE in detail, detail)
        assertTrue(OperationalMonitor.ALERT_OLDEST_QUEUED_MAIL in detail, detail)
        assertTrue(OperationalMonitor.ALERT_DISK_PRESSURE in detail, detail)
        assertTrue(OperationalMonitor.ALERT_DELEGATION_EXPIRY in detail, detail)
        assertTrue(OperationalMonitor.ALERT_TLS_CERT_EXPIRY in detail, detail)

        val metrics = client.get("/metrics") { header("Authorization", "Bearer mgmt-secret") }.bodyAsText()
        assertTrue("flash_mailbox_quarantined_total 12" in metrics, metrics)
        assertTrue("flash_mailbox_quarantined_outstanding 12" in metrics, metrics)
        assertTrue("flash_alert_unreadable_queue 2" in metrics, metrics)
        assertTrue("flash_alert_oldest_mail 2" in metrics, metrics)
        assertTrue("flash_alert_disk_pressure 2" in metrics, metrics)
        assertTrue("flash_alert_delegation_expiry 2" in metrics, metrics)
        assertTrue("flash_alert_tls_expiry 1" in metrics, metrics)
        assertTrue("flash_alerts_critical 4" in metrics, metrics)
        assertTrue("flash_alerts_warning 1" in metrics, metrics)

        // Acknowledge aged mail, expand disk, rotate delegation & TLS certs, then RESTART on the same DB with quarantined rows still unexpired
        runBlocking { liveQueue.acknowledge("bob", "aged-good") }
        diskRef.set(DiskUsage(totalBytes = 10_000_000_000L, usableBytes = 8_000_000_000L))
        delegationDaysRef.set(60L)
        tlsDaysRef.set(60L)

        val restartedQueue = MailboxQueue(db, MailboxLimits(), clock = { nowMs.get() }, registry = DeviceRegistry.AcceptAll, secrets = liveKey)
        assertEquals(0L, restartedQueue.quarantinedTotal(), "Process-lifetime quarantinedTotal resets on restart")
        assertEquals(12L, restartedQueue.quarantinedOutstanding(), "Unexpired quarantined rows remain outstanding across restart")

        testApplication {
            val restartedEngine = FlashServerEngine(
                config = proxyConfig(dir),
                mailboxQueue = restartedQueue,
                mailboxStats = restartedQueue,
                database = db,
                managementGuard = DefaultManagementGuard(token = "mgmt-secret"),
                delegationDaysRemaining = { delegationDaysRef.get() },
                tlsDaysRemaining = { tlsDaysRef.get() },
                diskUsageProvider = { diskRef.get() },
                clock = { nowMs.get() },
            )
            application { restartedEngine.configureApplication(this) }
            val restartDetail = client.get("/health/detail") { header("Authorization", "Bearer mgmt-secret") }.bodyAsText()
            assertTrue("\"quarantinedTotal\":0" in restartDetail, restartDetail)
            assertTrue("\"quarantinedOutstanding\":12" in restartDetail, restartDetail)
            assertTrue(OperationalMonitor.ALERT_UNREADABLE_QUEUE in restartDetail, restartDetail)
            assertTrue("\"status\":\"degraded\"" in restartDetail, restartDetail)

            val restartMetrics = client.get("/metrics") { header("Authorization", "Bearer mgmt-secret") }.bodyAsText()
            assertTrue("flash_mailbox_quarantined_total 0" in restartMetrics, restartMetrics)
            assertTrue("flash_mailbox_quarantined_outstanding 12" in restartMetrics, restartMetrics)
            assertTrue("flash_alert_unreadable_queue 2" in restartMetrics, restartMetrics)

            // Recovery: expire & sweep the unresolved quarantined rows under the documented TTL policy
            nowMs.addAndGet(30L * 3600L * 1000L) // total > 7d default TTL
            runBlocking { restartedQueue.deleteExpired() }
            assertEquals(0L, restartedQueue.quarantinedOutstanding())

            val recoveredDetail = client.get("/health/detail") { header("Authorization", "Bearer mgmt-secret") }.bodyAsText()
            assertTrue("\"status\":\"ok\"" in recoveredDetail, recoveredDetail)
            assertTrue(OperationalMonitor.ALERT_UNREADABLE_QUEUE !in recoveredDetail, recoveredDetail)

            val recoveredMetrics = client.get("/metrics") { header("Authorization", "Bearer mgmt-secret") }.bodyAsText()
            assertTrue("flash_mailbox_quarantined_outstanding 0" in recoveredMetrics, recoveredMetrics)
            assertTrue("flash_alert_unreadable_queue 0" in recoveredMetrics, recoveredMetrics)
        }
    }

    @Test
    fun backupRestoreRefusesMissingOrWrongDataKeyAndRecoversEnrolledClientsAcrossSignerRotation() {
        val dir = tempDir("flash-hr11-restore")
        val rootDir = tempDir("flash-hr11-root")
        val issue1 = provisionRootAndDelegation(dir, rootDir, serial = 1L)
        val hub1 = HubServices.build(proxyConfig(dir)).also { hubs += it }

        val aliceKey = FlashCrypto.generateEcKeyPair()
        val bobKey = FlashCrypto.generateEcKeyPair()
        var aliceEnroll: EnrollResponse? = null
        var bobEnroll: EnrollResponse? = null

        testApplication {
            application { hub1.engine.configureApplication(this) }
            aliceEnroll = enrol(hub1, "alice-dev", aliceKey, department = "eng")
            bobEnroll = enrol(hub1, "bob-dev", bobKey, department = "eng")
            val aliceToken = client.login(hub1, "alice-dev", aliceKey, aliceEnroll!!)
            val dirSync = client.get("${HubContract.PATH_DIRECTORY_SYNC}?since=0") {
                header("Authorization", "Bearer $aliceToken")
            }
            assertEquals(HttpStatusCode.OK, dirSync.status)
            assertTrue("bob-dev" in dirSync.bodyAsText())
        }
        val persistedAlice = assertNotNull(aliceEnroll)
        val persistedBob = assertNotNull(bobEnroll)
        hub1.database.close()

        val keyFile = File(dir, "data.key")
        val savedKeyBytes = keyFile.readBytes()

        // 1. Missing data.key must REFUSE boot rather than silently minting a new key
        assertTrue(keyFile.delete())
        assertFailsWith<HubStartupException>("missing data.key must refuse startup") {
            HubServices.build(proxyConfig(dir))
        }

        // 2. Wrong data.key must REFUSE boot
        val wrongDir = tempDir("flash-hr11-wrongkey")
        SecretBox.loadOrCreate(File(wrongDir, "other.key").toPath())
        keyFile.writeBytes(File(wrongDir, "other.key").readBytes())
        assertFailsWith<HubStartupException>("wrong data.key must refuse startup") {
            HubServices.build(proxyConfig(dir))
        }

        // 3. Matching data.key + rotated delegation cert serial 2 preserves continuity for existing clients
        keyFile.writeBytes(savedKeyBytes)
        val issue2 = provisionRootAndDelegation(dir, rootDir, serial = 2L)
        assertEquals(2L, issue2.cert.serial)

        val restoredHub = HubServices.build(proxyConfig(dir)).also { hubs += it }
        assertEquals(2L, restoredHub.delegations.current?.serial)

        val daveKey = FlashCrypto.generateEcKeyPair()
        lateinit var daveEnroll: EnrollResponse
        testApplication {
            application { restoredHub.engine.configureApplication(this) }
            val aToken = client.login(restoredHub, "alice-dev", aliceKey, persistedAlice)
            val bToken = client.login(restoredHub, "bob-dev", bobKey, persistedBob)
            assertTrue(aToken.isNotBlank() && bToken.isNotBlank())
            daveEnroll = enrol(restoredHub, "dave-dev", daveKey, department = "eng")
            assertEquals(2L, daveEnroll.delegationChain.firstOrNull()?.serial)
            val syncRes = client.get("${HubContract.PATH_DIRECTORY_SYNC}?since=0") {
                header("Authorization", "Bearer $bToken")
            }
            assertEquals(HttpStatusCode.OK, syncRes.status)
            val decodedSync = HubRestCodec.decode<DirectorySyncDto>(syncRes.bodyAsText())
            assertTrue(decodedSync.members.any { it.deviceId == "alice-dev" })
            assertTrue(decodedSync.members.any { it.deviceId == "dave-dev" })
        }
        restoredHub.database.close()

        // 4. Signer revocation after rotation blocks old delegation serial 1 while serial 2 clients continue serving
        val revTool = KeyTool(PrintStream(ByteArrayOutputStream()), PassphraseSource { _, _ -> null })
        revTool.revoke(
            rootKeyFile = rootDir.toPath().resolve(KeyTool.ROOT_PRIV),
            delegationSerials = setOf(issue1.cert.serial),
            deviceSerials = emptySet(),
            deviceIds = emptySet(),
            outFile = File(dir, "revocations/startup.signed").toPath(),
        )
        val postRevokeHub = HubServices.build(proxyConfig(dir)).also { hubs += it }
        assertTrue(1L in (postRevokeHub.revocations?.current()?.revokedDelegationSerials ?: emptySet()))

        val carolKey = FlashCrypto.generateEcKeyPair()
        testApplication {
            application { postRevokeHub.engine.configureApplication(this) }
            val challenge = HubRestCodec.decode<AuthChallengeResponse>(
                client.get(HubContract.PATH_AUTH_CHALLENGE).bodyAsText(),
            )
            val staleSig = FlashCrypto.sign(
                aliceKey.private,
                HubAuth.authSigningBytes(
                    postRevokeHub.challengeAuth!!.serverId,
                    challenge.serverNonce,
                    "cn-stale",
                    "alice-dev",
                    persistedAlice.deviceCert.serial,
                ),
            )
            val revokedRes = client.post(HubContract.PATH_AUTH_VERIFY) {
                contentType(ContentType.Application.Json)
                setBody(
                    HubRestCodec.encode(
                        AuthVerifyRequest(
                            serverNonce = challenge.serverNonce,
                            clientNonce = "cn-stale",
                            deviceId = "alice-dev",
                            deviceCert = persistedAlice.deviceCert,
                            delegationChain = persistedAlice.delegationChain,
                            signature = staleSig,
                        ),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Forbidden, revokedRes.status, revokedRes.bodyAsText())
            assertTrue("ERR_REVOKED" in revokedRes.bodyAsText(), revokedRes.bodyAsText())

            val daveToken = client.login(postRevokeHub, "dave-dev", daveKey, daveEnroll)
            assertTrue(daveToken.isNotBlank())
            val carolEnroll = enrol(postRevokeHub, "carol-dev", carolKey, department = "eng")
            assertEquals(2L, carolEnroll.delegationChain.firstOrNull()?.serial)
            val cToken = client.login(postRevokeHub, "carol-dev", carolKey, carolEnroll)
            val syncRes = client.get("${HubContract.PATH_DIRECTORY_SYNC}?since=0") {
                header("Authorization", "Bearer $cToken")
            }
            assertEquals(HttpStatusCode.OK, syncRes.status)
            val decodedSync = HubRestCodec.decode<DirectorySyncDto>(syncRes.bodyAsText())
            assertEquals(2L, decodedSync.delegationChain.firstOrNull()?.serial)
            assertTrue(
                decodedSync.members.any { it.deviceId == "dave-dev" },
                "Expected dave-dev in synced members=${decodedSync.members.map { it.deviceId }}",
            )
            assertTrue(
                decodedSync.members.any { it.deviceId == "carol-dev" },
                "Expected carol-dev in synced members=${decodedSync.members.map { it.deviceId }}",
            )
        }
    }

    @Test
    fun isolatedHubReadyAndHubWorkEndToEndLifecycleDrills() = testApplication {
        val dir = tempDir("flash-hr11-ready")
        val rootDir = tempDir("flash-hr11-ready-root")
        provisionRootAndDelegation(dir, rootDir, serial = 1L)
        val hub = HubServices.build(proxyConfig(dir)).also { hubs += it }
        application { hub.engine.configureApplication(this) }

        // HUBREADY-01: Health, detail & Prometheus metrics live
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        val detail = client.get("/health/detail") { header("Authorization", "Bearer mgmt-hr11-secret-token") }
        assertEquals(HttpStatusCode.OK, detail.status)
        assertTrue("\"status\":\"ok\"" in detail.bodyAsText())
        val metrics = client.get("/metrics") { header("Authorization", "Bearer mgmt-hr11-secret-token") }
        assertEquals(HttpStatusCode.OK, metrics.status)

        // HUBREADY-03: Directory dept creation + 2 devices enroll + auth
        hub.directory.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        val aliceKey = FlashCrypto.generateEcKeyPair()
        val bobKey = FlashCrypto.generateEcKeyPair()
        val aliceEnroll = enrol(hub, "alice", aliceKey, department = "eng")
        val bobEnroll = enrol(hub, "bob", bobKey, department = "eng")
        val aliceToken = client.login(hub, "alice", aliceKey, aliceEnroll)
        val bobToken = client.login(hub, "bob", bobKey, bobEnroll)

        // Announcements publish + changes feed query (HUBWORK-ANN)
        val annStore = AnnouncementStore.open(hub.database)
        val ann = annStore.createAnnouncement(
            scope = "org",
            departmentId = null,
            title = "Planned Maintenance",
            body = "Hub maintenance Sunday 02:00 UTC.",
            authorKind = "console",
            authorRef = "admin-console",
            authorName = "Admin",
            authorRole = "admin",
        )
        val annFeed = client.get("${HubContract.PATH_ANNOUNCEMENTS}?since=0") {
            header("Authorization", "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.OK, annFeed.status)
        val decodedAnn = HubRestCodec.decode<AnnouncementFeedDto>(annFeed.bodyAsText())
        assertTrue(decodedAnn.items.any { it.id == ann.id && it.title == "Planned Maintenance" })

        // Revoke bob cleanly -> subsequent auth refused (HUBWORK-REVOKE)
        val rev = assertNotNull(hub.revocations)
        rev.revokeDevice("bob", "compromised", "admin-console")
        val challenge = HubRestCodec.decode<AuthChallengeResponse>(client.get(HubContract.PATH_AUTH_CHALLENGE).bodyAsText())
        val bobSig = FlashCrypto.sign(
            bobKey.private,
            HubAuth.authSigningBytes(hub.challengeAuth!!.serverId, challenge.serverNonce, "cn-bob2", "bob", bobEnroll.deviceCert.serial),
        )
        val refused = client.post(HubContract.PATH_AUTH_VERIFY) {
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(AuthVerifyRequest(challenge.serverNonce, "cn-bob2", "bob", bobEnroll.deviceCert, bobEnroll.delegationChain, bobSig)))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertTrue(aliceToken.isNotBlank())
    }

    @Test
    fun syntheticPerfH03CapacityAndLatencyBenchmark() = runBlocking<Unit> {
        val dir = tempDir("flash-hr11-perf")
        val db = ServerDatabase("jdbc:sqlite:${File(dir, "perf-hub.db").path.replace('\\', '/')}", 4).also { dbs += it }
        val secrets = SecretBox.loadOrCreate(File(dir, "perf.key").toPath())
        val clockMs = AtomicLong(1_760_000_000_000L)
        val limits = MailboxLimits(perRecipientRows = 2000, globalRows = 50_000)
        val mailbox = MailboxQueue(db, limits, clock = { clockMs.get() }, registry = DeviceRegistry.AcceptAll, secrets = secrets)
        val router = RelayFrameRouter(RelayConnectionPool(), mailboxQueue = mailbox, clock = { clockMs.get() })

        val deviceCount = 50
        val senders = (0 until 25).map { i -> fakeSession("dev-$i", Channel.UNLIMITED, 256).also { router.register(it.first) } }
        val onlineReceivers = (25 until 40).map { i -> fakeSession("dev-$i", Channel.UNLIMITED, 512).also { router.register(it.first) } }

        val totalMessages = 500
        val latenciesNs = LongArray(totalMessages)
        val payload = "A".repeat(1024)
        val rt = Runtime.getRuntime()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        val startNs = System.nanoTime()

        for (i in 0 until totalMessages) {
            val (senderSession, _) = senders[i % senders.size]
            val target = "dev-${25 + (i % 25)}"
            val frame = RelayFrameCodec.encode(
                RelayFrame.Send(to = target, id = "perf-$i", type = "SEALED_V1", sentAt = clockMs.get(), payload = payload),
            )
            val t0 = System.nanoTime()
            router.processFrame(senderSession, frame)
            latenciesNs[i] = System.nanoTime() - t0
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val sortedUs = latenciesNs.map { it / 1_000.0 }.sorted()
        val p50Us = sortedUs[sortedUs.size / 2]
        val p99Us = sortedUs[(sortedUs.size * 99) / 100]
        val throughputMsgPerSec = (totalMessages / (elapsedMs / 1000.0))
        val heapDeltaKb = ((rt.totalMemory() - rt.freeMemory()) - heapBefore) / 1024L

        var liveAcks = 0
        for ((_, ws) in senders) {
            val acks = ws.drainNow().filterIsInstance<RelayFrame.Ack>()
            for (next in acks) {
                assertTrue(next.status == AckStatus.DELIVERED || next.status == AckStatus.QUEUED, "unexpected ack: ${next.status}")
                liveAcks++
            }
        }
        assertEquals(totalMessages, liveAcks)
        assertTrue(mailbox.queuedCount() > 0L)
        assertTrue(onlineReceivers.isNotEmpty())

        println(
            "PERF-H03 synthetic summary: devices=$deviceCount messages=$totalMessages elapsedMs=${"%.1f".format(elapsedMs)} " +
                "throughput=${"%.1f".format(throughputMsgPerSec)} msg/s p50Us=${"%.1f".format(p50Us)} p99Us=${"%.1f".format(p99Us)} " +
                "mailboxQueued=${mailbox.queuedCount()} heapDeltaKb=$heapDeltaKb",
        )
        assertTrue(elapsedMs < 30_000.0)
    }
}
