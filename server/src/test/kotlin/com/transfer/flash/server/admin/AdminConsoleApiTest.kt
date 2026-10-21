package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.RevocationSigner
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.relay.RelayFrameRouter
import com.transfer.flash.server.testsupport.fakeSession
import io.ktor.client.request.put
import kotlinx.coroutines.runBlocking
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.KeyPair
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The API additions of the console rewrite (ADR-125): the browser step-up key (enrolment, proof of possession, WebCrypto's IEEE P1363
 * signatures next to DER), setup with a console key, authenticator reset, the metrics snapshot, newest-first audit and the
 * small DTO additions the pages depend on. Everything runs the real routes over the Ktor test host.
 */
class AdminConsoleApiTest {

    private val adminPassword = "BossPassword123!"

    private class Sess(val cookie: String, val csrf: String)

    /** A hub with ONE administrator who has no phone: the browser key is the only thing that can confirm a dangerous action. */
    private inner class Realm(val withAdmin: Boolean = true) {
        var now: Long = System.currentTimeMillis()
        val rootKey = FlashCrypto.generateEcKeyPair()
        val serverKey = FlashCrypto.generateEcKeyPair()
        val db = ServerDatabase.inMemory()
        val delegation: DelegationCert = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = serverKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_ENROL_ADMIN, DelegationCert.SCOPE_REVOKE_MEMBER),
            validFrom = now - 60_000,
            validTo = now + 400L * 24 * 3600 * 1000,
            maxDevices = 50,
            serial = 1L,
        )
        val identities = IdentityStore.open(db)
        val revocations = SqliteRevocationService(
            db, rootKey.toBase64Spki(), RevocationSigner(serverKey.private, serverKey.toBase64Spki()) { delegation }, clock = { now },
        )
        val tokens = BearerTokenStore(revocations = revocations)
        val enrollment = EnrollmentService(rootKey.toBase64Spki(), serverKey.private, delegation, store = identities, revocations = revocations)
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000, explicitSetupToken = SETUP_TOKEN)
        val audit = AuditLogger(db, clock = { now })
        val directory = OrganizationDirectory.open(db)
        val stepUp = StepUpAuth(admins, identities, revocations, clock = { now })
        val config = ServerConfig(port = 8443, adminPort = 8444, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        val totpSecret = admins.generateTotpSecret()
        val hubKey = serverKey.toBase64Spki()
        val router = RelayFrameRouter(RelayConnectionPool(), clock = { now })
        val deps = AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost"),
            admins = admins, audit = audit, directory = directory, identities = identities, stepUp = stepUp, config = config,
            enrollment = enrollment, revocations = revocations, bearerTokens = tokens, limits = HubRateLimits.unlimited(), clock = { now },
            hubPublicKey = hubKey, router = router,
        )

        init {
            if (withAdmin) admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword(adminPassword), totpSecret))
        }

        /** A fresh authenticator code (a code is single use, so time moves on first). */
        fun freshCode(secret: String = totpSecret): String {
            now += 31_000
            return admins.generateTotp(secret, now / 1000)
        }
    }

    private fun ApplicationTestBuilder.mount(r: Realm) {
        routing { adminApiRoutes(r.deps) }
    }

    private fun HttpRequestBuilder.withSession(s: Sess?, stepUp: String? = null) {
        if (s != null) {
            header("Cookie", "flash_admin_session=${s.cookie}")
            header("X-CSRF-Token", s.csrf)
        }
        header("Origin", "http://localhost")
        if (stepUp != null) header("X-Step-Up", stepUp)
    }

    private suspend inline fun <reified T> HttpClient.postJson(path: String, s: Sess?, body: T, stepUp: String? = null): HttpResponse =
        post("/admin/api/v1$path") {
            withSession(s, stepUp)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    private suspend fun HttpClient.getA(path: String, s: Sess?): HttpResponse = get("/admin/api/v1$path") { withSession(s) }

    private suspend fun HttpClient.signIn(r: Realm): Sess {
        val resp = post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody("boss", adminPassword, r.freshCode())))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val cookie = resp.setCookie().first { it.name == "flash_admin_session" }.value
        return Sess(cookie, HubRestCodec.decode<LoginDto>(resp.bodyAsText()).csrfToken)
    }

    private suspend fun errorOf(r: HttpResponse): ErrorBody = HubRestCodec.decode(r.bodyAsText())

    /** What WebCrypto's `crypto.subtle.sign({name:"ECDSA", hash:"SHA-256"}, ...)` returns: `r || s`, 64 bytes, no DER. */
    private fun signP1363(key: KeyPair, data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format")
        sig.initSign(key.private)
        sig.update(data)
        val raw = sig.sign()
        assertEquals(64, raw.size)
        return Base64.getEncoder().encodeToString(raw)
    }

    private fun proof(key: KeyPair, username: String): String =
        signP1363(key, ConsoleKeys.proofMessage(username, key.toBase64Spki()))

    private suspend fun HttpClient.enrolKey(r: Realm, s: Sess, key: KeyPair, label: String? = "Test browser"): HttpResponse =
        postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(key.toBase64Spki(), proof(key, "boss"), r.freshCode(), label))

    /** Runs the whole step-up dance with a browser key and returns the challenge id for `X-Step-Up`. [p1363] chooses WebCrypto's format. */
    private suspend fun HttpClient.confirm(s: Sess, key: KeyPair, action: String, target: String, p1363: Boolean): String {
        val created = postJson("/stepup/challenge", s, StepUpChallengeBody(action, target))
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val ch = HubRestCodec.decode<StepUpChallengeDto>(created.bodyAsText())
        val bytes = Base64.getDecoder().decode(ch.proofBytes)
        val signature = if (p1363) signP1363(key, bytes) else FlashCrypto.sign(key.private, bytes)
        val verified = postJson("/stepup/verify", s, StepUpVerifyBody(ch.challengeId, signature))
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        return ch.challengeId
    }

    // ------------------------------------------------------------------------------------------------ the browser key

    @Test
    fun aBrowserKeyNeedsAFreshCodeAndAProofThatItHoldsThePrivateHalf() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val key = FlashCrypto.generateEcKeyPair()

        val before = HubRestCodec.decode<SessionDto>(client.getA("/session", s).bodyAsText())
        assertFalse(before.stepUpAvailable, "no phone and no browser key: nothing can confirm yet")

        val wrongCode = client.postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(key.toBase64Spki(), proof(key, "boss"), "000000", "x"))
        assertEquals(HttpStatusCode.Forbidden, wrongCode.status, "a stolen cookie alone can not install a key")

        val other = FlashCrypto.generateEcKeyPair()
        val stolenKey = client.postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(key.toBase64Spki(), proof(other, "boss"), r.freshCode(), "x"))
        assertEquals(HttpStatusCode.BadRequest, stolenKey.status, "a public key whose private half the caller does not hold is refused")

        val wrongUser = client.postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(key.toBase64Spki(), proof(key, "someone-else"), r.freshCode(), "x"))
        assertEquals(HttpStatusCode.BadRequest, wrongUser.status, "the proof is bound to the user name")

        val ok = client.enrolKey(r, s, key)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val keyId = HubRestCodec.decode<ConsoleKeyDto>(ok.bodyAsText()).keyId
        assertTrue(keyId.startsWith("console-"), keyId)
        assertTrue(HubRestCodec.decode<SessionDto>(client.getA("/session", s).bodyAsText()).stepUpAvailable)
        assertEquals(keyId, r.admins.getAdmin("boss")!!.enrolledDeviceId)
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "admin.consolekey.")).any { it.action == "admin.consolekey.enrol" && it.details.contains(keyId) })
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "admin.consolekey.")).any { it.action == "admin.consolekey.refused" })
    }

    @Test
    fun onceASignerExistsOnlyARecoveryCodeCanReplaceItAndNewRecoveryCodesNeedTheSigner() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val first = FlashCrypto.generateEcKeyPair()
        assertEquals(HttpStatusCode.OK, client.enrolKey(r, s, first).status)

        // A stolen cookie plus a valid authenticator code can not swap the signer for the attacker's key.
        val attacker = FlashCrypto.generateEcKeyPair()
        val swap = client.postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(attacker.toBase64Spki(), proof(attacker, "boss"), r.freshCode(), "evil"))
        assertEquals(HttpStatusCode.Forbidden, swap.status)
        assertEquals("ERR_SIGNER_EXISTS", errorOf(swap).code)
        assertEquals(first.toBase64Spki(), r.admins.consoleKey(r.admins.getAdmin("boss")!!.enrolledDeviceId!!, "boss"), "the signer is unchanged")

        // ... nor can it mint fresh recovery codes to do so: that needs the signer's confirmation.
        val regen = client.postJson("/admins/me/recovery-codes", s, FreshCodeBody(r.freshCode()))
        assertEquals(HttpStatusCode.Forbidden, regen.status)
        assertEquals("ERR_STEP_UP", errorOf(regen).code)

        // The legitimate path: a recovery code replaces a lost key (and is used up).
        val codes = r.admins.generateRecoveryCodes("boss")
        val replacement = FlashCrypto.generateEcKeyPair()
        val ok = client.postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(replacement.toBase64Spki(), proof(replacement, "boss"), codes.first(), "new laptop"))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(replacement.toBase64Spki(), r.admins.consoleKey(r.admins.getAdmin("boss")!!.enrolledDeviceId!!, "boss"))
        val again = FlashCrypto.generateEcKeyPair()
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/console-key", s, ConsoleKeyEnrolBody(again.toBase64Spki(), proof(again, "boss"), codes.first(), "x")).status, "a recovery code works once")
    }

    @Test
    fun aBrowserKeyConfirmsADangerousActionWithAP1363OrADerSignatureButNotWithAnyoneElsesKey() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val key = FlashCrypto.generateEcKeyPair()
        assertEquals(HttpStatusCode.OK, client.enrolKey(r, s, key).status)

        // A dangerous call without a confirmation is refused.
        val bare = client.postJson("/revocations/serial", s, RevokeSerialBody(4242, "test"))
        assertEquals(HttpStatusCode.Forbidden, bare.status)
        assertEquals("ERR_STEP_UP", errorOf(bare).code)

        // WebCrypto format (r||s, 64 bytes).
        val p1363 = client.confirm(s, key, StepUpActions.REVOKE_DEVICE, "serial:4242", p1363 = true)
        val first = client.postJson("/revocations/serial", s, RevokeSerialBody(4242, "test"), stepUp = p1363)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())

        // DER (what the phones and the desktop app produce) is accepted by the very same verifier.
        val der = client.confirm(s, key, StepUpActions.REVOKE_DEVICE, "serial:4243", p1363 = false)
        val second = client.postJson("/revocations/serial", s, RevokeSerialBody(4243, "test"), stepUp = der)
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())

        // A confirmation is bound to its target and is single use.
        val reuse = client.postJson("/revocations/serial", s, RevokeSerialBody(4243, "test"), stepUp = der)
        assertEquals(HttpStatusCode.Forbidden, reuse.status, "a confirmation is spent by the action it approved")
        val aimedElsewhere = client.confirm(s, key, StepUpActions.REVOKE_DEVICE, "serial:1", p1363 = true)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/revocations/serial", s, RevokeSerialBody(9999, "test"), stepUp = aimedElsewhere).status)

        // A signature from a different key never verifies.
        val intruder = FlashCrypto.generateEcKeyPair()
        val created = client.postJson("/stepup/challenge", s, StepUpChallengeBody(StepUpActions.REVOKE_DEVICE, "serial:7"))
        val ch = HubRestCodec.decode<StepUpChallengeDto>(created.bodyAsText())
        val forged = signP1363(intruder, Base64.getDecoder().decode(ch.proofBytes))
        val rejected = client.postJson("/stepup/verify", s, StepUpVerifyBody(ch.challengeId, forged))
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "stepup.")).any { it.action == "stepup.bad-signature" })
    }

    @Test
    fun p1363ConversionProducesTheDerThatTheJvmVerifier() {
        val key = FlashCrypto.generateEcKeyPair()
        val data = "flash".toByteArray()
        repeat(40) { // r and s with leading zero bytes and with the high bit set both occur within a few dozen signatures
            val raw = Base64.getDecoder().decode(signP1363(key, data + byteArrayOf(it.toByte())))
            assertTrue(ConsoleKeys.verify(Base64.getDecoder().decode(key.toBase64Spki()), data + byteArrayOf(it.toByte()), raw))
            assertFalse(ConsoleKeys.verify(Base64.getDecoder().decode(key.toBase64Spki()), data, raw), "another message does not verify")
        }
        assertNull(ConsoleKeys.p1363ToDer(ByteArray(63)))
        assertFalse(ConsoleKeys.verify(Base64.getDecoder().decode(key.toBase64Spki()), data, ByteArray(64)), "an all-zero signature is no signature")
        assertFalse(ConsoleKeys.isValidKey("not a key"))
        assertTrue(ConsoleKeys.isValidKey(key.toBase64Spki()))
    }

    // ------------------------------------------------------------------------------------------------ setup

    @Test
    fun setupRegistersTheBrowserKeyOnlyWithAValidProofAndDoesNotBurnTheSetupTokenOnABadOne() = testApplication {
        val r = Realm(withAdmin = false)
        mount(r)
        suspend fun begin() = HubRestCodec.decode<SetupBeginDto>(client.post("/admin/api/v1/setup/begin") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupBeginBody(SETUP_TOKEN)))
        }.bodyAsText())
        val key = FlashCrypto.generateEcKeyPair()
        val begun = begin()
        assertTrue(begun.qrSvg.startsWith("<svg"), "the authenticator QR is drawn by the server")
        assertFalse(begun.qrSvg.contains("<script", ignoreCase = true))

        suspend fun complete(setupId: String, secret: String, consoleKey: ConsoleKeyProof?) = client.post("/admin/api/v1/setup/complete") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupCompleteBody(setupId, SETUP_TOKEN, "owner", "A-long-owner-password", r.freshCode(secret), consoleKey)))
        }
        val bad = complete(begun.setupId, begun.totpSecret, ConsoleKeyProof(key.toBase64Spki(), proof(FlashCrypto.generateEcKeyPair(), "owner"), "x"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertFalse(r.admins.hasAdmins())
        assertTrue(r.admins.isSetupOpen(), "a bad key proof must not consume the one-shot setup token")

        val again = begin()
        val good = complete(again.setupId, again.totpSecret, ConsoleKeyProof(key.toBase64Spki(), proof(key, "owner"), "First browser"))
        assertEquals(HttpStatusCode.Created, good.status, good.bodyAsText())
        val owner = r.admins.getAdmin("owner")!!
        assertNotNull(owner.enrolledDeviceId)
        assertTrue(owner.enrolledDeviceId!!.startsWith("console-"))
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "admin.consolekey.")).any { it.action == "admin.consolekey.enrol" && it.details.contains("during setup") })
    }

    // ------------------------------------------------------------------------------------------------ the other additions

    @Test
    fun resettingAnAuthenticatorNeedsAConfirmationReturnsOneNewSecretWithItsQrAndEndsTheSessions() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val key = FlashCrypto.generateEcKeyPair()
        assertEquals(HttpStatusCode.OK, client.enrolKey(r, s, key).status)

        val unconfirmed = client.postJson("/admins/boss/totp-reset", s, OkDto())
        assertEquals(HttpStatusCode.Forbidden, unconfirmed.status)
        assertEquals(r.totpSecret, r.admins.getAdmin("boss")!!.totpSecret, "nothing changed without the confirmation")

        val id = client.confirm(s, key, StepUpActions.ADMIN_CREATE, "totp:boss", p1363 = true)
        val done = client.postJson("/admins/boss/totp-reset", s, OkDto(), stepUp = id)
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        val creds = HubRestCodec.decode<AdminCredentialsDto>(done.bodyAsText())
        assertTrue(creds.totpSecret != r.totpSecret)
        assertEquals(10, creds.recoveryCodes.size)
        assertTrue(creds.qrSvg.startsWith("<svg"))
        assertEquals(HttpStatusCode.Unauthorized, client.getA("/session", s).status, "the sessions of the reset account ended")
        assertEquals(HttpStatusCode.Unauthorized, client.getA("/session", s).status)
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "admin.totp.")).isNotEmpty())
    }

    @Test
    fun theMetricsSnapshotNeedsASessionAndCarriesTheCounters() = testApplication {
        val r = Realm()
        mount(r)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/v1/metrics").status)
        val s = client.signIn(r)
        val resp = client.getA("/metrics", s)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val m = HubRestCodec.decode<MetricsDto>(resp.bodyAsText())
        assertEquals(0, m.onlineDevices)
        assertTrue(m.serverTimeMs >= r.now - 100_000)
        assertTrue(m.uptimeSeconds >= 0)
    }

    @Test
    fun theAuditLogCanBeReadNewestFirstAndTheDelegationNamesTheHubKey() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        r.audit.log("boss", "test.one", "first")
        r.now += 1000
        r.audit.log("boss", "test.two", "second")
        val asc = HubRestCodec.decode<AuditPageDto>(client.getA("/audit?action=test.", s).bodyAsText()).entries
        val desc = HubRestCodec.decode<AuditPageDto>(client.getA("/audit?action=test.&order=desc", s).bodyAsText()).entries
        assertEquals(listOf("test.one", "test.two"), asc.map { it.action })
        assertEquals(listOf("test.two", "test.one"), desc.map { it.action })

        val delegation = HubRestCodec.decode<DelegationDto>(client.getA("/delegation", s).bodyAsText())
        assertEquals(r.hubKey, delegation.hubPublicKey, "the console prints the keytool command with the hub's own key")
        val session = HubRestCodec.decode<SessionDto>(client.getA("/session", s).bodyAsText())
        assertTrue(session.sessionId.isNotBlank())
        assertTrue(kotlin.math.abs(session.serverTimeMs - r.now) < 5_000, "the console corrects its clock with the server time")
    }

    @Test
    fun aRejectedJoinRequestKeepsItsReasonInTheAuditLogAndTheBodyIsOptional() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        r.directory.createDepartment("dept-gh", "Ghana", emptyList(), isOrgAdmin = true)
        r.directory.addDepartmentMember("dept-gh", "radio-01", "Sgt Mensah")
        r.directory.addDepartmentMember("dept-gh", "radio-02", "Cpl Owusu")
        r.directory.createGroup("grp-1", "Signals", "dept-gh", com.transfer.flash.server.directory.GroupVisibility.LISTED, com.transfer.flash.server.directory.JoinMode.REQUEST_APPROVAL, emptyList(), com.transfer.flash.server.directory.DirectoryActor.CONSOLE)
        r.directory.requestJoinGroup("grp-1", "radio-01", "dept-gh", "please")
        r.directory.requestJoinGroup("grp-1", "radio-02", "dept-gh", "please")
        val pending = HubRestCodec.decode<List<AdminJoinRequestDto>>(client.getA("/join-requests", s).bodyAsText())
        assertEquals(2, pending.size)
        val (a, b) = pending
        assertEquals(HttpStatusCode.OK, client.postJson("/join-requests/${a.requestId}/reject", s, RejectBody("not on the roster\nsecond line")).status)
        assertEquals(HttpStatusCode.OK, client.post("/admin/api/v1/join-requests/${b.requestId}/reject") { withSession(s) }.status, "no body at all is fine")
        val entries = r.audit.query(AuditFilter(actionPrefix = "join.reject"), 10)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.details.contains("reason=not on the roster") && !it.details.contains('\n') }, entries.toString())
        assertTrue(entries.any { !it.details.contains("reason=") })
    }

    private fun Realm.enrolIn(deviceId: String, department: String) {
        val key = FlashCrypto.generateEcKeyPair()
        val token = enrollment.generateInviteLink("https://hub.test", InviteScope(department = department, expiry = now + 3_600_000), now)
            .substringAfter("token=").substringBefore("&")
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, deviceId, key.toBase64Spki(), enrollment.serverId))
        enrollment.enrollDevice(token, deviceId, key.toBase64Spki(), proof, currentTimeMs = now).getOrThrow()
    }

    private suspend fun HttpClient.putDevice(id: String, s: Sess, body: DeviceUpdateBody): HttpResponse =
        put("/admin/api/v1/devices/$id") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    /** SRVHUB-04 (DEV-01): a move takes effect at once: the old bearer is gone and the live session is kicked to reconnect. */
    @Test
    fun movingADeviceToAnotherDepartmentDropsItsBearersAndKicksItsSessionOnce() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        r.directory.createDepartment("dept-a", "Alpha", emptyList(), isOrgAdmin = true)
        r.directory.createDepartment("dept-b", "Bravo", emptyList(), isOrgAdmin = true)
        r.enrolIn("dev-1", "dept-a")
        val (token, _) = r.tokens.issue(HubPrincipal(deviceId = "dev-1", role = "member", departmentId = "dept-a", certSerial = 1L), r.now)
        assertNotNull(r.tokens.resolve(token, r.now))
        val (session, ws) = fakeSession("dev-1")
        r.router.register(session)

        val rename = client.putDevice("dev-1", s, DeviceUpdateBody(name = "Renamed"))
        assertEquals(HttpStatusCode.OK, rename.status, rename.bodyAsText())
        assertNotNull(r.tokens.resolve(token, r.now), "a rename changes no authorisation")
        assertTrue(session.isOpen, "and does not kick")

        val same = client.putDevice("dev-1", s, DeviceUpdateBody(department = "dept-a", moveToDepartment = true))
        assertEquals(HttpStatusCode.OK, same.status, same.bodyAsText())
        assertTrue(session.isOpen, "moving to the department it already has is not a move")

        val moved = client.putDevice("dev-1", s, DeviceUpdateBody(department = "dept-b", moveToDepartment = true))
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        assertEquals("dept-b", r.identities.device("dev-1")!!.department)
        assertNull(r.tokens.resolve(token, r.now), "the old department's bearer is forgotten")
        val kick = runBlocking { ws.nextOf<RelayFrame.Kick>() }
        assertEquals("department_changed", kick.reason)
    }

    private companion object {
        const val SETUP_TOKEN = "test-setup-token-0123456789abcdef"
    }
}
