@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.server

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.EnrollRequest
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.DelegationStatus
import com.transfer.flash.server.auth.HubKeyStore
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.auth.SecureFiles
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.mailbox.MailboxMessage
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.update
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
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.security.KeyPair
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wired hub: root off the server, awaiting-delegation mode, the gate, enrolment, revocation and hot reload. */
class HubServicesTest {

    private val dir: File = Files.createTempDirectory("flash-hub-services").toFile()
    private val built = ArrayList<HubServices>()

    @AfterTest
    fun cleanUp() {
        built.forEach { runCatching { it.database.close() } }
        dir.deleteRecursively()
    }

    private fun config(port: Int = 18443, dev: Boolean = false) = if (dev) {
        ServerConfig(
            port = port, adminPort = port, adminOnPublicPort = true, host = "127.0.0.1", dataDir = dir.path,
            tlsMode = "none", allowInsecure = true, externalUrl = "http://localhost:$port", devEmbeddedRoot = true,
        )
    } else {
        ServerConfig(
            port = port, adminPort = port, adminOnPublicPort = true, host = "127.0.0.1", dataDir = dir.path,
            tlsMode = "proxy", externalUrl = "https://hub.test",
        )
    }

    private fun build(config: ServerConfig = config()): HubServices = HubServices.build(config).also { built += it }

    /** What an operator does offline: init-root, then issue-delegation for the hub's public key. Returns the root key dir. */
    private fun provision(serial: Long? = null, maxDevices: Int = 50, validDays: Long = 30): KeyTool.IssueResult {
        val keys = HubKeyStore(dir.toPath()).loadOrCreateDelegateKey()
        val tool = KeyTool(PrintStream(ByteArrayOutputStream()), PassphraseSource { _, _ -> null })
        val rootDir = File(dir.parentFile, "root-${dir.name}").toPath()
        val rootKey = rootDir.resolve(KeyTool.ROOT_PRIV)
        if (!Files.exists(rootKey)) tool.initRoot(rootDir, encrypt = false)
        return tool.issueDelegation(
            rootKey, keys.publicKey, setOf("enrol:member", "revoke:member"), maxDevices, validDays, serial,
            outFile = dir.toPath().resolve("delegation.cert"),
        )
    }

    private suspend fun ApplicationTestBuilder.enrol(hub: HubServices, deviceId: String, key: KeyPair): EnrollResponse {
        val enrollment = hub.enrollment!!
        val token = enrollment.createInvite("https://hub.test", InviteScope(department = "eng", expiry = System.currentTimeMillis() + 60_000))
            .token
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), enrollment.serverId))
        val res = client.post(HubContract.PATH_ENROLL) {
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(EnrollRequest(token, deviceId, key.toBase64Spki(), friendlyName = "Test $deviceId", proof = proof)))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return HubRestCodec.decode<EnrollResponse>(res.bodyAsText())
    }

    private suspend fun HttpClient.login(hub: HubServices, deviceId: String, key: KeyPair, enrolled: EnrollResponse): String {
        val challenge = HubRestCodec.decode<AuthChallengeResponse>(get(HubContract.PATH_AUTH_CHALLENGE).bodyAsText())
        val serverId = hub.challengeAuth!!.serverId
        val signature = FlashCrypto.sign(key.private, HubAuth.authSigningBytes(serverId, challenge.serverNonce, "cn-$deviceId", deviceId, enrolled.deviceCert.serial))
        val res = post(HubContract.PATH_AUTH_VERIFY) {
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(AuthVerifyRequest(challenge.serverNonce, "cn-$deviceId", deviceId, enrolled.deviceCert, enrolled.delegationChain, signature)))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return HubRestCodec.decode<AuthVerifyResponse>(res.bodyAsText()).token
    }

    // ------------------------------------------------------------------------------------------ root off the server

    @Test
    fun withoutADelegationTheHubAwaitsAndRefusesToEnrolOrRelay() = testApplication {
        val hub = build()
        assertTrue(hub.awaitingDelegation)
        assertNull(hub.enrollment)
        assertNull(hub.challengeAuth)
        assertEquals(DelegationStatus.AWAITING, hub.delegations.status)
        assertFalse(File(dir, "root-identity.pk8").exists(), "the hub never mints a root key (SEC-04)")
        assertTrue(File(dir, "server-identity.pk8").exists(), "it only has its own delegate key")
        application { hub.engine.configureApplication(this) }

        assertEquals(HttpStatusCode.OK, client.get("/health").status, "liveness works while awaiting")
        assertEquals(HttpStatusCode.NotFound, client.post(HubContract.PATH_ENROLL) { setBody("{}") }.status, "enrolment is not mounted")
        assertEquals(HttpStatusCode.Unauthorized, client.get(HubContract.PATH_DIRECTORY_ME).status, "the gate is up anyway")
    }

    @Test
    fun theOperationalPagesNeedTheManagementTokenOnceItIsConfiguredAndAProxyHeaderIsNotLoopback() = testApplication {
        val token = "management-token-0123456789"
        val hub = build(config().copy(managementToken = token))
        application { hub.engine.configureApplication(this) }
        val viaProxy = { b: io.ktor.client.request.HttpRequestBuilder -> b.headers.append("X-Forwarded-For", "203.0.113.5") }
        assertEquals(HttpStatusCode.Forbidden, client.get("/metrics") { viaProxy(this) }.status, "a proxied caller is not loopback")
        assertEquals(HttpStatusCode.OK, client.get("/metrics") { viaProxy(this); header("Authorization", "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/metrics") { viaProxy(this); header("Authorization", "Bearer wrong-token-0123456789") }.status)
    }

    @Test
    fun theEnrolmentLandingPageIsNoOracleAndIgnoresACallerChosenHub() = testApplication {
        provision()
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val enrollment = hub.enrollment!!
        val now = System.currentTimeMillis()
        val valid = enrollment.createInvite("https://hub.test", InviteScope(department = "eng", expiry = now + 60_000)).token
        val used = enrollment.createInvite("https://hub.test", InviteScope(department = "eng", expiry = now + 60_000)).token
        val key = FlashCrypto.generateEcKeyPair()
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(used, "landing-dev", key.toBase64Spki(), enrollment.serverId))
        assertEquals(
            HttpStatusCode.OK,
            client.post(HubContract.PATH_ENROLL) {
                contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(EnrollRequest(used, "landing-dev", key.toBase64Spki(), friendlyName = "x", proof = proof)))
            }.status,
        )

        val ok = client.get("/enroll?token=$valid&server=https://evil.example")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertFalse(ok.bodyAsText().contains("evil.example"), "a caller-chosen hub is never echoed into the deep link")
        assertTrue(ok.bodyAsText().contains("hub.test"), "the CONFIGURED hub address is shown")

        val unknown = client.get("/enroll?token=not-a-token")
        val spent = client.get("/enroll?token=$used")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(HttpStatusCode.BadRequest, spent.status)
        assertEquals(unknown.bodyAsText(), spent.bodyAsText(), "unknown and used tokens look identical")
        assertFalse(spent.bodyAsText().contains("eng"), "the department of a spent token is not revealed")
        assertEquals("no-store", spent.headers["Cache-Control"])
    }

    @Test
    fun aRootKeyFoundOnTheServerRefusesTheStartUnlessItIsTheDevFlag() {
        HubKeyStore(dir.toPath()).loadOrCreateDelegateKey()
        Files.writeString(File(dir, "root-identity.pk8").toPath(), "leftover")
        val e = assertFailsWith<HubStartupException> { HubServices.build(config()) }
        assertTrue(e.message!!.contains("ROOT"), e.message)
    }

    @Test
    fun aCorruptDelegateKeyRefusesTheStartAndIsLeftForTheOperator() {
        HubKeyStore(dir.toPath()).loadOrCreateDelegateKey()
        File(dir, "server-identity.pk8").writeText("garbage")
        assertFailsWith<HubStartupException> { HubServices.build(config()) }
        assertEquals("garbage", File(dir, "server-identity.pk8").readText(), "a corrupt key is never regenerated")
    }

    @Test
    fun aBoundDatabaseRefusesAMissingOrDifferentDataKeyAndCanRecoverWithItsOriginal() {
        val hub = build()
        val key = File(dir, "data.key").toPath()
        val original = Files.readAllBytes(key)
        val admin = hub.admins.breakGlassReset("restore-admin")
        hub.database.close()

        Files.delete(key)
        val missing = assertFailsWith<HubStartupException> { build() }
        assertTrue(missing.message.orEmpty().contains("matching data.key"))
        assertFalse(Files.exists(key), "a failed start must not create a key")
        val wrong = Base64.getEncoder().encode(ByteArray(32) { 42 })
        SecureFiles.writeOwnerOnly(key, wrong)
        assertFailsWith<HubStartupException> { build() }
        assertTrue(wrong.contentEquals(Files.readAllBytes(key)))

        SecureFiles.writeOwnerOnly(key, original)
        assertEquals(admin.totpSecret, build().admins.getAdmin(admin.username)?.totpSecret)
    }

    @Test
    fun legacySealedMailboxAuthenticatesItsKeyBeforeBinding() = runBlocking<Unit> {
        val hub = build()
        val key = File(dir, "data.key").toPath()
        val original = Files.readAllBytes(key)
        val mailbox = MailboxQueue(hub.database, secrets = SecretBox.loadOrCreate(key))
        assertTrue(mailbox.enqueue(MailboxMessage("origin", "target", "test", "MSG_TEXT", "queued", 1)).isStored)
        hub.database.withConnection { it.update("DELETE FROM hub_meta WHERE k = 'data-key.verifier.v1'") }
        hub.database.close()

        Files.delete(key)
        assertFailsWith<HubStartupException> { build() }
        assertFalse(Files.exists(key))
        SecureFiles.writeOwnerOnly(key, Base64.getEncoder().encode(ByteArray(32) { 43 }))
        assertFailsWith<HubStartupException> { build() }

        SecureFiles.writeOwnerOnly(key, original)
        val reopened = build()
        assertEquals("queued", MailboxQueue(reopened.database, secrets = SecretBox.loadOrCreate(key)).fetchPage("target", 0, 1).single().payload)
        assertTrue(reopened.identities.meta("data-key.verifier.v1").orEmpty().startsWith(SecretBox.PREFIX))
    }

    @Test
    fun aLegacyUnsealedDatabaseCanCreateAKeyAndRetainItsAuthenticator() {
        val hub = build()
        val old = com.transfer.flash.server.admin.AdminAuthService(printSetupTokenToConsole = false, db = hub.database)
        val admin = old.breakGlassReset("legacy-admin")
        hub.database.withConnection { it.update("DELETE FROM hub_meta WHERE k = 'data-key.verifier.v1'") }
        hub.database.close()
        Files.delete(File(dir, "data.key").toPath())

        val reopened = build()
        assertEquals(admin.totpSecret, reopened.admins.getAdmin(admin.username)?.totpSecret)
        assertTrue(reopened.identities.meta("data-key.verifier.v1").orEmpty().startsWith(SecretBox.PREFIX))
    }

    @Test
    fun theDevEmbeddedRootStartsActiveButOnlyWithTheExplicitFlag() {
        val hub = build(config(dev = true))
        assertFalse(hub.awaitingDelegation, "dev mode issues its own delegation")
        assertTrue(File(dir, "root-identity.pk8").exists())
        hub.database.close()
        // The very same data dir without the flag: the leftover root key is now an error.
        assertFailsWith<HubStartupException> { HubServices.build(config()) }
    }

    // ------------------------------------------------------------------------------------------ the integrated flow

    @Test
    fun anOfflineDelegationActivatesTheHubAndTheWholeEnrolAuthenticateFlowWorks() = testApplication {
        provision()
        val hub = build()
        assertFalse(hub.awaitingDelegation)
        assertEquals(DelegationStatus.ACTIVE, hub.delegations.status)
        application { hub.engine.configureApplication(this) }

        val key = FlashCrypto.generateEcKeyPair()
        val enrolled = enrol(hub, "dev-one", key)
        val token = client.login(hub, "dev-one", key, enrolled)

        assertEquals(HttpStatusCode.Unauthorized, client.get(HubContract.PATH_DIRECTORY_ME).status, "no bearer, no directory")
        val me = client.get(HubContract.PATH_DIRECTORY_ME) { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
        assertNotNull(hub.directory.getDepartmentMember("dev-one"), "an enrolled device appears in the directory")
    }

    @Test
    fun revokingADeviceKillsItsBearerAtOnceAndRemovesItFromTheDirectory() = testApplication {
        provision()
        val hub = build()
        application { hub.engine.configureApplication(this) }
        val key = FlashCrypto.generateEcKeyPair()
        val enrolled = enrol(hub, "dev-two", key)
        val token = client.login(hub, "dev-two", key, enrolled)
        assertEquals(HttpStatusCode.OK, client.get(HubContract.PATH_DIRECTORY_ME) { header("Authorization", "Bearer $token") }.status)

        hub.revocations!!.revokeDevice("dev-two", "lost", "boss")
        assertEquals(HttpStatusCode.Unauthorized, client.get(HubContract.PATH_DIRECTORY_ME) { header("Authorization", "Bearer $token") }.status)
        assertNull(hub.directory.getDepartmentMember("dev-two"))
        assertEquals(HttpStatusCode.OK, client.get(HubContract.PATH_REVOCATIONS).status, "the list is public: a revoked device has no bearer to fetch it with (ERROR-335)")
        val otherKey = FlashCrypto.generateEcKeyPair()
        val otherToken = client.login(hub, "dev-three", otherKey, enrol(hub, "dev-three", otherKey))
        val list = client.get(HubContract.PATH_REVOCATIONS) { header("Authorization", "Bearer $otherToken") }
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        val body = list.bodyAsText()
        assertFalse(body.contains("dev-two"), "ADR-129: the public list names no device id")
        val parsed = HubRestCodec.decodeOrNull<RevocationList>(body)
        assertTrue(parsed != null && parsed.revokesDevice("dev-two"), "an authenticated device can fetch the signed list too, and finds its hash in it")
    }

    @Test
    fun theDirectoryIsRebuiltFromTheRegistryAfterARestart() = testApplication {
        provision()
        val first = build()
        application { first.engine.configureApplication(this) }
        enrol(first, "dev-keep", FlashCrypto.generateEcKeyPair())
        first.directory.removeDevice("dev-keep") // simulate a crash between the registry write and the directory write
        first.database.close()

        val second = build()
        assertNotNull(second.directory.getDepartmentMember("dev-keep"), "startup reconciliation re-adds ACTIVE devices")
    }

    @Test
    fun startupRootRevocationsRetireGroupCopiesBeforeServingAndStayRetiredOnRestart() = testApplication {
        provision()
        val first = build()
        application { first.engine.configureApplication(this) }
        first.directory.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        first.directory.createGroup("g-eng", "Engineering", "eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, emptyList(), DirectoryActor.CONSOLE)
        enrol(first, "by-id", FlashCrypto.generateEcKeyPair())
        val serial = enrol(first, "by-serial", FlashCrypto.generateEcKeyPair()).deviceCert.serial
        enrol(first, "keep", FlashCrypto.generateEcKeyPair())
        for (device in listOf("by-id", "by-serial", "keep")) {
            first.directory.addMemberToGroup("g-eng", device, DirectoryActor.CONSOLE)
        }
        val initialVersion = assertNotNull(first.directory.getGroup("g-eng")).version
        val secrets = SecretBox.loadOrCreate(File(dir, "data.key").toPath())
        val queued = MailboxQueue(first.database, secrets = secrets, enforceGroupMembership = true)
        assertTrue(queued.enqueue(MailboxMessage("keep", "by-id", "group", "MSG_TEXT", "queued", 1, group = "g-eng")).isStored)
        assertTrue(queued.enqueue(MailboxMessage("keep", "by-id", "direct", "MSG_TEXT", "queued", 1)).isStored)
        first.database.close()

        val rootKey = File(dir.parentFile, "root-${dir.name}/${KeyTool.ROOT_PRIV}").toPath()
        KeyTool(PrintStream(ByteArrayOutputStream()), PassphraseSource { _, _ -> null }).revoke(
            rootKeyFile = rootKey,
            delegationSerials = emptySet(),
            deviceSerials = setOf(serial),
            deviceIds = setOf("by-id"),
            outFile = File(dir, "revocations/startup.signed").toPath(),
        )

        val second = build()
        assertNull(second.directory.getDepartmentMember("by-id"))
        assertNull(second.directory.getDepartmentMember("by-serial"))
        val retired = assertNotNull(second.directory.getGroup("g-eng"))
        assertEquals(setOf("keep"), retired.memberDeviceIds.toSet())
        assertTrue(retired.version > initialVersion)
        val remaining = MailboxQueue(second.database, secrets = secrets, enforceGroupMembership = true).fetchPage("by-id", 0, 10)
        assertEquals(listOf("direct"), remaining.map { it.msgId })
        second.database.close()

        val third = build()
        assertEquals(retired, third.directory.getGroup("g-eng"))
        assertNull(third.directory.getDepartmentMember("by-serial"), "an ACTIVE registry row with a revoked serial must not reappear")
    }

    // ------------------------------------------------------------------------------------------ delegation lifecycle

    @Test
    fun aReplacementDelegationDroppedOnDiskIsPickedUpByTheHousekeepingTick() {
        provision()
        val hub = build()
        assertEquals(1L, hub.delegations.current?.serial)
        provision()
        val file = File(dir, "delegation.cert")
        file.setLastModified(System.currentTimeMillis() + 10_000)
        hub.housekeeping()
        assertEquals(2L, hub.delegations.current?.serial, "hot reload without a restart")
    }

    @Test
    fun aBogusReplacementIsRefusedAndTheActiveDelegationKeepsWorking() {
        provision()
        val hub = build()
        val file = File(dir, "delegation.cert")
        file.writeText("{\"not\":\"a delegation\"}")
        file.setLastModified(System.currentTimeMillis() + 10_000)
        hub.housekeeping()
        assertEquals(1L, hub.delegations.current?.serial)
        assertEquals(DelegationStatus.ACTIVE, hub.delegations.status)
    }

    @Test
    fun theSetupTokenGoesToAnOwnerOnlyFileAndNeverToTheConsole() {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out))
        try {
            build()
        } finally {
            System.setOut(original)
        }
        val tokenFile = File(dir, ServerConfig.SETUP_TOKEN_FILENAME)
        assertTrue(tokenFile.isFile)
        val token = tokenFile.readText().trim()
        assertTrue(token.length >= 16)
        assertFalse(out.toString().contains(token), "the production console never shows the setup token")
        assertTrue(com.transfer.flash.server.auth.SecureFiles.isOwnerOnly(tokenFile.toPath()))
    }
}
