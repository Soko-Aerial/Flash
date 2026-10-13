package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.RevocationSigner
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.auth.TokenBucketLimiter
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The administration JSON API (`/admin/api/v1`): sessions, CSRF, step-up, the audit trail and the real services behind it
 * (SEC-09/10/12/13/17, REL-16). Every test drives the real routes over the Ktor test host.
 */
class AdminApiTest {

    private val adminPassword = "BossPassword123!"

    private class Sess(val cookie: String, val csrf: String)

    private inner class Realm(
        val withAdmin: Boolean = true,
        val policy: AdminListenerPolicy = AdminListenerPolicy.Open,
        val limits: HubRateLimits = HubRateLimits.unlimited(),
    ) {
        var now: Long = System.currentTimeMillis()
        val rootKey = FlashCrypto.generateEcKeyPair()
        val serverKey = FlashCrypto.generateEcKeyPair()
        val adminKey: KeyPair = FlashCrypto.generateEcKeyPair()
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
        val admins = AdminAuthService(
            printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000,
            explicitSetupToken = SETUP_TOKEN,
        )
        val audit = AuditLogger(db, clock = { now })
        val directory = OrganizationDirectory.open(db)
        val stepUp = StepUpAuth(admins, identities, revocations, clock = { now })
        val config = ServerConfig(port = 8443, adminPort = 8444, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        val totpSecret = admins.generateTotpSecret()
        val deps = AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost", policy = policy),
            admins = admins, audit = audit, directory = directory, identities = identities, stepUp = stepUp, config = config,
            enrollment = enrollment, revocations = revocations, bearerTokens = tokens, limits = limits, clock = { now },
        )

        init {
            if (withAdmin) {
                admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword(adminPassword), totpSecret))
                enrolAdminDevice()
                admins.bindDevice("boss", ADMIN_DEVICE)
            }
        }

        private fun enrolAdminDevice() {
            val issuer = FlashCrypto.generateEcKeyPair()
            identities.createInvite("tok-admin", null, "admin", emptySet(), emptySet(), now + 100_000_000, 1, now)
            identities.enroll("tok-admin", ADMIN_DEVICE, adminKey.toBase64Spki(), "Boss phone", now, 100, 1L) { serial, _ ->
                DeviceCert.issue(issuer.private, issuer.toBase64Spki(), ADMIN_DEVICE, adminKey.toBase64Spki(), "admin", null, now - 1000, now + 100_000_000, serial)
            }
        }

        fun totp(): String = admins.generateTotp(totpSecret, now / 1000)

        /** Enrols a member device through the REAL invite + enrolment service; returns its id and key. */
        fun enrolMember(id: String, token: String): KeyPair {
            val key = FlashCrypto.generateEcKeyPair()
            val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, id, key.toBase64Spki(), enrollment.serverId))
            enrollment.enrollDevice(token, id, key.toBase64Spki(), proof, currentTimeMs = now).getOrThrow()
            return key
        }
    }

    private fun ApplicationTestBuilder.mount(r: Realm) {
        routing { adminApiRoutes(r.deps) }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun HttpRequestBuilder.withSession(s: Sess?, csrf: Boolean = true, stepUp: String? = null) {
        if (s != null) header("Cookie", "flash_admin_session=${s.cookie}")
        if (s != null && csrf) header("X-CSRF-Token", s.csrf)
        header("Origin", "http://localhost")
        if (stepUp != null) header("X-Step-Up", stepUp)
    }

    private suspend fun HttpClient.login(r: Realm, code: String? = null, password: String = adminPassword): HttpResponse {
        r.now += 31_000 // a fresh TOTP step: a code is single use
        return post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody("boss", password, code ?: r.totp())))
        }
    }

    private suspend fun HttpClient.signIn(r: Realm): Sess {
        val resp = login(r)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val cookie = resp.setCookie().first { it.name == "flash_admin_session" }.value
        return Sess(cookie, HubRestCodec.decode<LoginDto>(resp.bodyAsText()).csrfToken)
    }

    private suspend inline fun <reified T> HttpClient.postJson(path: String, s: Sess?, body: T, csrf: Boolean = true, stepUp: String? = null): HttpResponse =
        post("/admin/api/v1$path") {
            withSession(s, csrf, stepUp)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    private suspend fun HttpClient.getA(path: String, s: Sess?): HttpResponse = get("/admin/api/v1$path") { withSession(s) }

    private suspend fun HttpClient.deleteA(path: String, s: Sess?, csrf: Boolean = true): HttpResponse =
        delete("/admin/api/v1$path") { withSession(s, csrf) }

    private suspend fun errorOf(r: HttpResponse): ErrorBody = HubRestCodec.decode(r.bodyAsText())

    /** Performs the whole step-up dance with the admin's device key and returns the challenge id to put in `X-Step-Up`. */
    private suspend fun HttpClient.stepUp(r: Realm, s: Sess, action: String, target: String, key: KeyPair = r.adminKey): String {
        val created = postJson("/stepup/challenge", s, StepUpChallengeBody(action, target))
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val ch = HubRestCodec.decode<StepUpChallengeDto>(created.bodyAsText())
        val signature = FlashCrypto.sign(key.private, Base64.getDecoder().decode(ch.proofBytes))
        val verified = postJson("/stepup/verify", s, StepUpVerifyBody(ch.challengeId, signature))
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        return ch.challengeId
    }

    /** Creates a member invite through the API and enrols [id] with it; returns the device key. */
    private suspend fun HttpClient.enrolThroughApi(r: Realm, s: Sess, id: String): KeyPair {
        val created = postJson("/invites", s, InviteCreateBody())
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = HubRestCodec.decode<InviteCreatedDto>(created.bodyAsText())
        return r.enrolMember(id, dto.token)
    }

    // ------------------------------------------------------------------------------------------------ sessions

    @Test
    fun everyRouteExceptSetupAndLoginRefusesARequestWithoutASession() = testApplication {
        val r = Realm()
        mount(r)
        for (path in listOf("/session", "/overview", "/devices", "/invites", "/revocations", "/delegation", "/settings", "/departments", "/groups", "/join-requests", "/admins", "/sessions", "/audit", "/audit/verify", "/audit/export", "/audit/head")) {
            val resp = client.get("/admin/api/v1$path")
            assertEquals(HttpStatusCode.Unauthorized, resp.status, path)
            assertEquals("ERR_AUTH", errorOf(resp).code, path)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/invites", null, InviteCreateBody()).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/stepup/challenge", null, StepUpChallengeBody("device.revoke", "x")).status)
        // the public ones answer
        assertEquals(HttpStatusCode.OK, client.get("/admin/api/v1/setup/status").status)
    }

    @Test
    fun loginNeedsThePasswordAndAFreshCodeAndSetsAHardenedCookie() = testApplication {
        val r = Realm()
        mount(r)
        val wrongCode = client.login(r, code = "000000")
        assertEquals(HttpStatusCode.Unauthorized, wrongCode.status)
        assertNull(wrongCode.setCookie().firstOrNull { it.name == "flash_admin_session" })
        assertEquals(HttpStatusCode.Unauthorized, client.login(r, password = "not the password").status)

        val ok = client.login(r)
        assertEquals(HttpStatusCode.OK, ok.status)
        val cookie = ok.setCookie().first { it.name == "flash_admin_session" }
        assertTrue(cookie.httpOnly)
        assertEquals("Strict", cookie.extensions["SameSite"])
        assertEquals("/admin", cookie.path)
        val body = ok.bodyAsText()
        assertFalse(body.contains(adminPassword) || body.contains(r.totpSecret) || body.contains("pbkdf2"), "the login answer carries no secret")
    }

    @Test
    fun aTotpCodeCannotBeUsedForASecondLogin() = testApplication {
        val r = Realm()
        mount(r)
        r.now += 31_000
        val code = r.totp()
        suspend fun attempt() = client.post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody("boss", adminPassword, code)))
        }
        assertEquals(HttpStatusCode.OK, attempt().status)
        assertEquals(HttpStatusCode.Unauthorized, attempt().status, "replaying the same code must fail")
    }

    @Test
    fun aLoginPostedFromAnotherSiteIsRefused() = testApplication {
        val r = Realm()
        mount(r)
        r.now += 31_000
        val resp = client.post("/admin/api/v1/login") {
            header("Origin", "https://evil.example")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody("boss", adminPassword, r.totp())))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals("ERR_CSRF", errorOf(resp).code)
        val fetch = client.post("/admin/api/v1/login") {
            header("Sec-Fetch-Site", "cross-site")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody("boss", adminPassword, r.totp())))
        }
        assertEquals(HttpStatusCode.Forbidden, fetch.status)
    }

    @Test
    fun theLoginAttemptIsCountedBeforeThePasswordIsChecked() = testApplication {
        val r = Realm()
        mount(r)
        // 5 failures lock the account: the right credentials are then refused too (HTTP 429), and the attempt is audited.
        repeat(AdminAuthService.MAX_FAILED_ATTEMPTS) { assertEquals(HttpStatusCode.Unauthorized, client.login(r, password = "wrong-$it").status) }
        val locked = client.login(r)
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertNotNull(locked.headers["Retry-After"])
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "admin.login.")).isNotEmpty())
    }

    @Test
    fun loginIsRateLimitedPerClientAddress() = testApplication {
        val r = Realm(limits = HubRateLimits(adminLogin = TokenBucketLimiter(2, 0.001, clock = { 0L })))
        mount(r)
        assertNotEquals(HttpStatusCode.TooManyRequests, client.login(r, code = "000000").status)
        assertNotEquals(HttpStatusCode.TooManyRequests, client.login(r, code = "000000").status)
        val third = client.login(r, code = "000000")
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals("ERR_RATE_LIMIT", errorOf(third).code)
    }

    @Test
    fun aRequestBodyOverTheCapIsRefusedWith413() = testApplication {
        val r = Realm()
        mount(r)
        val resp = client.post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody("{\"username\":\"boss\",\"password\":\"" + "x".repeat(70_000) + "\",\"code\":\"1\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
    }

    @Test
    fun logoutEndsTheSessionOnTheServer() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        assertEquals(HttpStatusCode.OK, client.getA("/session", s).status)
        assertEquals(HttpStatusCode.OK, client.postJson("/logout", s, OkDto()).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getA("/session", s).status, "the old cookie is dead server-side, not just cleared")
    }

    // ------------------------------------------------------------------------------------------------ CSRF

    @Test
    fun aStateChangingCallNeedsTheSessionsCsrfTokenAndASameSiteOrigin() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        // no token
        val missing = client.postJson("/invites", s, InviteCreateBody(), csrf = false)
        assertEquals(HttpStatusCode.Forbidden, missing.status)
        assertEquals("ERR_CSRF", errorOf(missing).code)
        // somebody else's token
        val wrong = client.post("/admin/api/v1/invites") {
            header("Cookie", "flash_admin_session=${s.cookie}")
            header("X-CSRF-Token", "0".repeat(s.csrf.length))
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(InviteCreateBody()))
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        // the right token from the wrong origin
        val foreign = client.post("/admin/api/v1/invites") {
            header("Cookie", "flash_admin_session=${s.cookie}")
            header("X-CSRF-Token", s.csrf)
            header("Origin", "https://evil.example")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(InviteCreateBody()))
        }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        val fetchSite = client.post("/admin/api/v1/invites") {
            header("Cookie", "flash_admin_session=${s.cookie}")
            header("X-CSRF-Token", s.csrf)
            header("Sec-Fetch-Site", "cross-site")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(InviteCreateBody()))
        }
        assertEquals(HttpStatusCode.Forbidden, fetchSite.status)
        assertEquals(0, r.identities.listInvites(100).count { it.createdBy == "boss" }, "none of the refused calls created an invite")
        // and the legitimate one works
        assertEquals(HttpStatusCode.Created, client.postJson("/invites", s, InviteCreateBody()).status)
        // a safe method needs no token
        assertEquals(HttpStatusCode.OK, client.get("/admin/api/v1/overview") { header("Cookie", "flash_admin_session=${s.cookie}") }.status)
    }

    @Test
    fun theConsoleDoesNotExistOnTheOtherListener() = testApplication {
        val r = Realm(policy = AdminListenerPolicy(publicPort = 8443, adminPort = 8444, adminOnPublicPort = false))
        mount(r)
        for (path in listOf("/setup/status", "/session", "/overview", "/audit")) {
            assertEquals(HttpStatusCode.NotFound, client.get("/admin/api/v1$path").status, path)
        }
        assertEquals(HttpStatusCode.NotFound, client.login(r).status, "not even a login is answered")
    }

    // ------------------------------------------------------------------------------------------------ step-up

    @Test
    fun revokingADeviceNeedsAConfirmationFromTheAdministratorsDeviceAndIsSingleUse() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.enrolThroughApi(r, s, "radio-01")
        val principal = HubPrincipal("radio-01", "member", null, r.identities.device("radio-01")!!.serial)
        val token = r.tokens.issue(principal, r.now).first
        assertNotNull(r.tokens.resolve(token, r.now))

        // 1. no confirmation: refused, nothing changes
        val bare = client.postJson("/devices/radio-01/revoke", s, RevokeBody("lost"))
        assertEquals(HttpStatusCode.Forbidden, bare.status)
        assertEquals("ERR_STEP_UP", errorOf(bare).code)
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("radio-01")!!.status)
        assertNotNull(r.tokens.resolve(token, r.now))

        // 2. a confirmation for ANOTHER target does not open this door
        val other = client.stepUp(r, s, StepUpActions.REVOKE_DEVICE, "radio-99")
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/devices/radio-01/revoke", s, RevokeBody("lost"), stepUp = other).status)

        // 3. a confirmation for the right action but made up
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/devices/radio-01/revoke", s, RevokeBody("lost"), stepUp = "made-up-id").status)

        // 4. the real thing
        val good = client.stepUp(r, s, StepUpActions.REVOKE_DEVICE, "radio-01")
        val done = client.postJson("/devices/radio-01/revoke", s, RevokeBody("lost"), stepUp = good)
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        assertEquals(DeviceStatus.REVOKED, r.identities.device("radio-01")!!.status)
        assertTrue(r.revocations.isRevoked(principal.certSerial, "radio-01"))
        assertNull(r.tokens.resolve(token, r.now), "the bearer token of a revoked device stops working at once")
        assertTrue(r.audit.query(AuditFilter(actionPrefix = "device.revoke")).isNotEmpty())

        // 5. single use: the same confirmation does not revoke a second time or anything else
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/devices/radio-01/unrevoke", s, OkDto(), stepUp = good).status)
    }

    @Test
    fun aSignatureFromAnyOtherKeyDoesNotConfirmAnything() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.enrolThroughApi(r, s, "radio-01")
        val created = client.postJson("/stepup/challenge", s, StepUpChallengeBody(StepUpActions.REVOKE_DEVICE, "radio-01"))
        val ch = HubRestCodec.decode<StepUpChallengeDto>(created.bodyAsText())
        val attacker = FlashCrypto.generateEcKeyPair()
        val forged = FlashCrypto.sign(attacker.private, Base64.getDecoder().decode(ch.proofBytes))
        val verified = client.postJson("/stepup/verify", s, StepUpVerifyBody(ch.challengeId, forged))
        assertEquals(HttpStatusCode.Forbidden, verified.status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/devices/radio-01/revoke", s, RevokeBody("x"), stepUp = ch.challengeId).status)
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("radio-01")!!.status)
    }

    @Test
    fun anAdministratorWithoutAnEnrolledDeviceCannotStartAConfirmation() = testApplication {
        val r = Realm()
        mount(r)
        r.admins.bindDevice("boss", null)
        val s = client.signIn(r)
        val resp = client.postJson("/stepup/challenge", s, StepUpChallengeBody(StepUpActions.REVOKE_DEVICE, "radio-01"))
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertFalse(client.getA("/session", s).bodyAsText().contains("\"stepUpAvailable\":true"))
    }

    @Test
    fun unrevokeIsGatedByTheSameConfirmation() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.enrolThroughApi(r, s, "radio-01")
        val id = client.stepUp(r, s, StepUpActions.REVOKE_DEVICE, "radio-01")
        assertEquals(HttpStatusCode.OK, client.postJson("/devices/radio-01/revoke", s, RevokeBody("x"), stepUp = id).status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/devices/radio-01/unrevoke", s, OkDto()).status)
        val again = client.stepUp(r, s, StepUpActions.UNREVOKE_DEVICE, "radio-01")
        assertEquals(HttpStatusCode.OK, client.postJson("/devices/radio-01/unrevoke", s, OkDto(), stepUp = again).status)
        // Lifting the revocation frees the ID; the old certificate stays dead, so the device must enrol again with a new invite.
        assertEquals(DeviceStatus.UNENROLLED, r.identities.device("radio-01")!!.status)
        assertFalse(r.revocations.isRevoked(null, "radio-01"))
        assertTrue(r.revocations.isRevoked(r.identities.serialsOf("radio-01").first(), "elsewhere"))
    }

    @Test
    fun aRevokedAdministratorDeviceCannotConfirmAnything() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        r.revocations.revokeDevice(ADMIN_DEVICE, "lost", "root")
        val resp = client.postJson("/stepup/challenge", s, StepUpChallengeBody(StepUpActions.REVOKE_DEVICE, "radio-01"))
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ------------------------------------------------------------------------------------------------ setup

    @Test
    fun setupBeginNeedsTheSetupTokenAndNeverEvictsAPendingSetup() = testApplication {
        val r = Realm(withAdmin = false)
        mount(r)
        suspend fun beginWith(token: String?) = client.post("/admin/api/v1/setup/begin") {
            header("Origin", "http://localhost")
            if (token != null) {
                contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(SetupBeginBody(token)))
            }
        }
        assertEquals(HttpStatusCode.BadRequest, beginWith(null).status, "no body, no QR")
        assertEquals(HttpStatusCode.Forbidden, beginWith("not-the-setup-token").status, "a wrong token gets no QR and no slot")

        // 16 slots, then a refusal; the first setup is still completable (it was not evicted by the 17th attempt).
        val first = HubRestCodec.decode<SetupBeginDto>(beginWith(SETUP_TOKEN).bodyAsText())
        repeat(15) { assertEquals(HttpStatusCode.OK, beginWith(SETUP_TOKEN).status) }
        assertEquals(HttpStatusCode.Conflict, beginWith(SETUP_TOKEN).status, "a full table refuses instead of evicting")
        val done = client.post("/admin/api/v1/setup/complete") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupCompleteBody(first.setupId, SETUP_TOKEN, "owner", "A-long-owner-password", r.admins.generateTotp(first.totpSecret, r.now / 1000))))
        }
        assertEquals(HttpStatusCode.Created, done.status, done.bodyAsText())
    }

    @Test
    fun theAnonymousSetupStatusRevealsOnlyWhetherSetupIsOpen() = testApplication {
        val r = Realm(withAdmin = true)
        mount(r)
        val text = client.get("/admin/api/v1/setup/status").bodyAsText()
        val status = HubRestCodec.decode<SetupStatusDto>(text)
        assertFalse(status.setupOpen)
        assertFalse(status.hasAdmins, "whether an administrator exists is not for an anonymous caller")
        assertEquals("", status.delegationStatus, "nor is the delegation state")
        assertFalse(text.contains("ACTIVE") || text.contains("AWAITING"), text)
    }

    @Test
    fun firstRunSetupNeedsTheTokenAndNeverReopens() = testApplication {
        val r = Realm(withAdmin = false)
        mount(r)
        val status = HubRestCodec.decode<SetupStatusDto>(client.get("/admin/api/v1/setup/status").bodyAsText())
        assertTrue(status.setupOpen)
        assertFalse(status.hasAdmins)

        suspend fun begin() = client.post("/admin/api/v1/setup/begin") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupBeginBody(SETUP_TOKEN)))
        }
        val begun = HubRestCodec.decode<SetupBeginDto>(begin().bodyAsText())
        suspend fun complete(token: String, code: String) = client.post("/admin/api/v1/setup/complete") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupCompleteBody(begun.setupId, token, "owner", "A-long-owner-password", code)))
        }
        val wrongToken = complete("not-the-setup-token", r.admins.generateTotp(begun.totpSecret, r.now / 1000))
        assertEquals(HttpStatusCode.Forbidden, wrongToken.status)
        assertFalse(r.admins.hasAdmins(), "a wrong token creates nothing")

        val fresh = HubRestCodec.decode<SetupBeginDto>(begin().bodyAsText())
        val ok = client.post("/admin/api/v1/setup/complete") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupCompleteBody(fresh.setupId, SETUP_TOKEN, "owner", "A-long-owner-password", r.admins.generateTotp(fresh.totpSecret, r.now / 1000))))
        }
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        assertEquals(10, HubRestCodec.decode<SetupCompleteDto>(ok.bodyAsText()).recoveryCodes.size)
        assertTrue(r.admins.hasAdmins())

        // never again, whatever is presented
        assertEquals(HttpStatusCode.Conflict, begin().status)
        assertFalse(HubRestCodec.decode<SetupStatusDto>(client.get("/admin/api/v1/setup/status").bodyAsText()).setupOpen)
        val second = client.post("/admin/api/v1/setup/complete") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SetupCompleteBody(fresh.setupId, SETUP_TOKEN, "intruder", "A-long-intruder-password", "123456")))
        }
        assertTrue(second.status == HttpStatusCode.Forbidden || second.status == HttpStatusCode.Conflict)
        assertNull(r.admins.getAdmin("intruder"))
    }

    // ------------------------------------------------------------------------------------------------ invites

    @Test
    fun anInviteMadeInTheConsoleIsTheRealOneAndItsSecretIsShownOnce() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val created = client.postJson("/invites", s, InviteCreateBody(role = "member", maxUses = 2, ttlMinutes = 60, note = "Sgt Mensah"))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = HubRestCodec.decode<InviteCreatedDto>(created.bodyAsText())
        assertTrue(dto.link.startsWith("flash://enroll?"))
        assertTrue(dto.qrSvg.contains("<svg"))
        assertEquals(2, dto.invite.maxUses)

        // the list never shows the secret again
        val listed = client.getA("/invites", s).bodyAsText()
        assertTrue(listed.contains(dto.invite.inviteId))
        assertFalse(listed.contains(dto.token), "the token is not retrievable after creation")

        // it is the registry the enrolment endpoint uses (REL-16: no second invite map)
        r.enrolMember("radio-a", dto.token)
        assertEquals(1, HubRestCodec.decode<List<InviteDto>>(client.getA("/invites", s).bodyAsText()).first { it.inviteId == dto.invite.inviteId }.uses)

        // revoke -> no further enrolment
        val revoked = client.postJson("/invites/${dto.invite.inviteId}/revoke", s, OkDto())
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertEquals("REVOKED", HubRestCodec.decode<InviteDto>(revoked.bodyAsText()).state)
        val key = FlashCrypto.generateEcKeyPair()
        val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(dto.token, "radio-b", key.toBase64Spki(), r.enrollment.serverId))
        assertTrue(r.enrollment.enrollDevice(dto.token, "radio-b", key.toBase64Spki(), proof, currentTimeMs = r.now).isFailure)
    }

    @Test
    fun anInviteThatMakesAnAdministratorDeviceNeedsAConfirmation() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val bare = client.postJson("/invites", s, InviteCreateBody(role = "admin"))
        assertEquals(HttpStatusCode.Forbidden, bare.status)
        assertEquals("ERR_STEP_UP", errorOf(bare).code)
        val ok = client.stepUp(r, s, StepUpActions.ADMIN_CREATE, "invite:admin")
        assertEquals(HttpStatusCode.Created, client.postJson("/invites", s, InviteCreateBody(role = "admin"), stepUp = ok).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/invites", s, InviteCreateBody(role = "root")).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/invites", s, InviteCreateBody(maxUses = 0)).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/invites", s, InviteCreateBody(department = "no-such-department")).status)
    }

    @Test
    fun bulkInvitesAreMadeFromACsvAndBounded() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        r.directory.createDepartment("dept-gh", "Ghana", emptyList(), isOrgAdmin = true)
        val resp = client.postJson("/invites/bulk", s, BulkInviteBody("name,department\nSgt Mensah,dept-gh\nCpl Owusu\n,\nBad Dept,nope\n"))
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val rows = HubRestCodec.decode<BulkInviteDto>(resp.bodyAsText()).rows
        assertEquals(3, rows.size)
        assertNotNull(rows[0].created)
        assertNotNull(rows[1].created)
        assertNotNull(rows[2].error)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/invites/bulk", s, BulkInviteBody((1..201).joinToString("\n") { "n$it" })).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/invites/bulk", s, BulkInviteBody("a", role = "admin")).status)
    }

    // ------------------------------------------------------------------------------------------------ overview, settings

    @Test
    fun theOverviewAndSettingsNeverContainASecret() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.enrolThroughApi(r, s, "radio-01")
        val text = client.getA("/overview", s).bodyAsText() + client.getA("/settings", s).bodyAsText() + client.getA("/delegation", s).bodyAsText()
        assertFalse(text.contains(r.totpSecret))
        assertFalse(text.contains("pbkdf2"))
        assertFalse(text.contains("setupToken", ignoreCase = true))
        assertFalse(text.contains(SETUP_TOKEN))
        val overview = HubRestCodec.decode<OverviewDto>(client.getA("/overview", s).bodyAsText())
        assertEquals(2L, overview.devicesByStatus["ACTIVE"], "the admin phone and the new device")
        assertEquals("ACTIVE", overview.delegation.status)
        assertNotNull(overview.delegation.daysLeft)
    }

    @Test
    fun onlyAllowListedSettingsCanBeChangedAndOnlyWithAConfirmation() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val unconfirmed = client.put("/admin/api/v1/settings") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SettingChangeBody(SETTING_TTL, "120")))
        }
        assertEquals(HttpStatusCode.Forbidden, unconfirmed.status)
        suspend fun put(key: String, value: String, confirm: String?) = client.put("/admin/api/v1/settings") {
            withSession(s, stepUp = confirm)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(SettingChangeBody(key, value)))
        }
        assertEquals(HttpStatusCode.BadRequest, put("tls.mode", "none", null).status, "an unknown key is refused before anything else")
        assertEquals(HttpStatusCode.BadRequest, put(SETTING_TTL, "1", null).status, "out of range")
        assertEquals(HttpStatusCode.BadRequest, put(SETTING_TTL, "abc", null).status)
        // REV-14: the confirmation names the NEW value, so a confirmation given for 120 minutes can not be replayed for 5000.
        val forOtherValue = client.stepUp(r, s, StepUpActions.SETTINGS_CHANGE, "$SETTING_TTL=999")
        assertEquals(HttpStatusCode.Forbidden, put(SETTING_TTL, "120", forOtherValue).status)
        val id = client.stepUp(r, s, StepUpActions.SETTINGS_CHANGE, "$SETTING_TTL=120")
        assertEquals(HttpStatusCode.OK, put(SETTING_TTL, "120", id).status)
        assertEquals(120L, HubRestCodec.decode<SettingsDto>(client.getA("/settings", s).bodyAsText()).inviteDefaultTtlMinutes)
        // the default now applies to a new invite
        val created = HubRestCodec.decode<InviteCreatedDto>(client.postJson("/invites", s, InviteCreateBody()).bodyAsText())
        assertEquals(r.now + 120 * 60_000L, created.invite.expiresAt)
    }

    // ------------------------------------------------------------------------------------------------ accounts

    @Test
    fun creatingAnAdministratorNeedsAConfirmationAndShowsItsSecretsOnlyOnce() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val bare = client.postJson("/admins", s, AdminCreateBody("second", "A-second-admin-password"))
        assertEquals(HttpStatusCode.Forbidden, bare.status)
        assertNull(r.admins.getAdmin("second"))
        val id = client.stepUp(r, s, StepUpActions.ADMIN_CREATE, "second")
        val made = client.postJson("/admins", s, AdminCreateBody("second", "A-second-admin-password"), stepUp = id)
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val creds = HubRestCodec.decode<AdminCredentialsDto>(made.bodyAsText())
        assertNull(creds.password, "the server never echoes a password")
        assertEquals(10, creds.recoveryCodes.size)
        val listed = client.getA("/admins", s).bodyAsText()
        assertTrue(listed.contains("second"))
        assertFalse(listed.contains(creds.totpSecret), "the list never contains a TOTP secret")
        assertFalse(listed.contains("pbkdf2"))
        // a confirmation for one username does not create another
        val id2 = client.stepUp(r, s, StepUpActions.ADMIN_CREATE, "third")
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins", s, AdminCreateBody("fourth", "A-fourth-admin-password"), stepUp = id2).status)
        assertNull(r.admins.getAdmin("fourth"))
    }

    @Test
    fun disablingAnAdministratorEndsTheirSessionsAndTheLastOneCannotBeDisabled() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val make = client.stepUp(r, s, StepUpActions.ADMIN_CREATE, "second")
        client.postJson("/admins", s, AdminCreateBody("second", "A-second-admin-password"), stepUp = make)
        val victimSession = r.admins.createSession("second", "ORG_ADMIN")
        val dis = client.stepUp(r, s, StepUpActions.ADMIN_DISABLE, "second")
        assertEquals(HttpStatusCode.OK, client.postJson("/admins/second/disable", s, OkDto(), stepUp = dis).status)
        assertNull(r.admins.validateSession(victimSession.token), "their session died with the account")
        val self = client.stepUp(r, s, StepUpActions.ADMIN_DISABLE, "boss")
        val resp = client.postJson("/admins/enable-nobody/disable", s, OkDto(), stepUp = self)
        assertEquals(HttpStatusCode.NotFound, resp.status)
        // boss is now the only enabled administrator: disabling boss is refused
        val self2 = client.stepUp(r, s, StepUpActions.ADMIN_DISABLE, "boss")
        assertEquals(HttpStatusCode.Conflict, client.postJson("/admins/boss/disable", s, OkDto(), stepUp = self2).status)
        assertEquals(HttpStatusCode.OK, client.getA("/session", s).status)
    }

    @Test
    fun changingOwnPasswordNeedsTheOldOneAndEndsEverySession() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/password", s, PasswordChangeBody("wrong-old-password", "Another-new-password-1")).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/admins/me/password", s, PasswordChangeBody(adminPassword, "short")).status)
        assertEquals(HttpStatusCode.OK, client.postJson("/admins/me/password", s, PasswordChangeBody(adminPassword, "Another-new-password-1")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getA("/session", s).status)
        assertEquals(HttpStatusCode.Unauthorized, client.login(r).status, "the old password no longer works")
        assertEquals(HttpStatusCode.OK, client.login(r, password = "Another-new-password-1").status)
    }

    @Test
    fun recoveryCodesAndDeviceBindingNeedAFreshAuthenticatorCode() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        // The signer's confirmation is needed as well (REV-11), and a wrong authenticator code is still refused.
        assertEquals("ERR_STEP_UP", HubRestCodec.decode<ErrorBody>(client.postJson("/admins/me/recovery-codes", s, FreshCodeBody(r.totp())).bodyAsText()).code)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/recovery-codes", s, FreshCodeBody("000000"), stepUp = client.stepUp(r, s, StepUpActions.RECOVERY_REGENERATE, "codes")).status)
        r.now += 31_000
        val codes = client.postJson("/admins/me/recovery-codes", s, FreshCodeBody(r.totp()), stepUp = client.stepUp(r, s, StepUpActions.RECOVERY_REGENERATE, "codes"))
        assertEquals(HttpStatusCode.OK, codes.status, codes.bodyAsText())
        assertEquals(10, HubRestCodec.decode<RecoveryCodesDto>(codes.bodyAsText()).recoveryCodes.size)

        // binding: only an ACTIVE device with the admin role
        client.enrolThroughApi(r, s, "radio-01")
        r.now += 31_000
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/admins/me/device", s, BindDeviceBody("radio-01", r.totp())).status, "a member device can not confirm dangerous actions")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/admins/me/device", s, BindDeviceBody("no-such-device", r.totp())).status)
        r.now += 31_000
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/device", s, BindDeviceBody(ADMIN_DEVICE, "000000")).status)
        r.now += 31_000
        // The administrator already has a signer: an authenticator code is not enough to replace it, a recovery code is (REV-11).
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/device", s, BindDeviceBody(ADMIN_DEVICE, r.totp())).status)
        val recovery = HubRestCodec.decode<RecoveryCodesDto>(codes.bodyAsText()).recoveryCodes.first()
        assertEquals(HttpStatusCode.OK, client.postJson("/admins/me/device", s, BindDeviceBody(ADMIN_DEVICE, recovery)).status)
    }

    @Test
    fun sessionsCanBeListedAndRevoked() = testApplication {
        val r = Realm()
        mount(r)
        val a = client.signIn(r)
        val b = client.signIn(r)
        val rows = HubRestCodec.decode<List<SessionRowDto>>(client.getA("/sessions", a).bodyAsText())
        assertEquals(2, rows.size)
        assertFalse(client.getA("/sessions", a).bodyAsText().contains(b.cookie), "a session list never contains a session token")
        val target = r.admins.listSessions("boss").first().sessionId
        assertEquals(HttpStatusCode.OK, client.postJson("/sessions/$target/revoke", a, OkDto()).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/sessions/$target/revoke", a, OkDto()).status)
    }

    // ------------------------------------------------------------------------------------------------ directory

    @Test
    fun theDirectoryCanBeManagedAndEveryChangeIsAudited() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        assertEquals(HttpStatusCode.Created, client.postJson("/departments", s, DepartmentBody("dept-gh", "Ghana")).status)
        assertEquals(HttpStatusCode.Conflict, client.postJson("/departments", s, DepartmentBody("dept-gh", "Ghana again")).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/groups", s, GroupBody("g1", "One", "dept-gh", visibility = "SECRETIVE")).status)
        val group = client.postJson("/groups", s, GroupBody("grp-1", "Signals", "dept-gh", "LISTED", "REQUEST_APPROVAL"))
        assertEquals(HttpStatusCode.Created, group.status, group.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.postJson("/groups/nope/members", s, GroupMemberAddBody("x")).status)
        val added = client.postJson("/groups/grp-1/members", s, GroupMemberAddBody("radio-01"))
        assertEquals(HttpStatusCode.OK, added.status)
        assertTrue(HubRestCodec.decode<AdminGroupDto>(added.bodyAsText()).memberDeviceIds.contains("radio-01"))
        assertEquals(HttpStatusCode.OK, client.deleteA("/groups/grp-1/members/radio-01", s).status)
        assertEquals(HttpStatusCode.Forbidden, client.deleteA("/groups/grp-1", s, csrf = false).status, "DELETE is state changing: it needs the token")
        assertEquals(HttpStatusCode.OK, client.deleteA("/groups/grp-1", s).status)
        assertEquals(HttpStatusCode.OK, client.deleteA("/departments/dept-gh", s).status)
        val actions = r.audit.query(AuditFilter(), 100).map { it.action }
        for (expected in listOf("department.create", "group.create", "group.member.add", "group.member.remove", "group.delete", "department.delete")) {
            assertTrue(expected in actions, "$expected is audited: $actions")
        }
    }

    @Test
    fun joinRequestsAreDecidedFromTheConsole() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        r.directory.createDepartment("dept-gh", "Ghana", emptyList(), isOrgAdmin = true)
        r.directory.addDepartmentMember("dept-gh", "radio-01", "Sgt Mensah")
        r.directory.createGroup("grp-1", "Signals", "dept-gh", com.transfer.flash.server.directory.GroupVisibility.LISTED, com.transfer.flash.server.directory.JoinMode.REQUEST_APPROVAL, emptyList(), com.transfer.flash.server.directory.DirectoryActor.CONSOLE)
        r.directory.requestJoinGroup("grp-1", "radio-01", "dept-gh", "please")
        val pending = HubRestCodec.decode<List<AdminJoinRequestDto>>(client.getA("/join-requests", s).bodyAsText())
        assertEquals(1, pending.size)
        assertEquals("Sgt Mensah", pending[0].displayName)
        assertEquals(HttpStatusCode.OK, client.postJson("/join-requests/${pending[0].requestId}/approve", s, OkDto()).status)
        assertEquals(HttpStatusCode.Conflict, client.postJson("/join-requests/${pending[0].requestId}/reject", s, OkDto()).status, "already decided")
        assertTrue(r.directory.getGroup("grp-1")!!.memberDeviceIds.contains("radio-01"))
    }

    // ------------------------------------------------------------------------------------------------ audit

    @Test
    fun theAuditTrailCanBeFilteredVerifiedAndExportedSafely() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.postJson("/departments", s, DepartmentBody("dept-gh", "=cmd|' /C calc'!A0"))
        val page = HubRestCodec.decode<AuditPageDto>(client.getA("/audit?action=department.", s).bodyAsText())
        assertEquals(1L, page.total)
        assertEquals("department.create", page.entries.single().action)
        assertEquals(HttpStatusCode.BadRequest, client.getA("/audit?from=yesterday", s).status)
        val verify = HubRestCodec.decode<AuditVerifyDto>(client.getA("/audit/verify", s).bodyAsText())
        assertTrue(verify.ok)
        val export = client.getA("/audit/export", s)
        assertEquals(HttpStatusCode.OK, export.status)
        assertTrue(export.headers["Content-Disposition"]!!.startsWith("attachment"))
        assertEquals("nosniff", export.headers["X-Content-Type-Options"])
        assertTrue(export.bodyAsText().lines().first().contains("actor"))
        // a tampered entry is reported
        r.audit.tamperWithEntryForTesting(0, "rewritten")
        assertFalse(HubRestCodec.decode<AuditVerifyDto>(client.getA("/audit/verify", s).bodyAsText()).ok)
    }

    @Test
    fun errorsAreFixedSentencesNeverExceptionText() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val bad = client.post("/admin/api/v1/invites") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody("{ this is not json")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val body = errorOf(bad)
        assertEquals("ERR_BAD_REQUEST", body.code)
        assertTrue(body.correlationId.isNotBlank())
        assertFalse(bad.bodyAsText().contains("Exception") || bad.bodyAsText().contains("kotlinx"))
        val missing = client.getA("/devices/nobody", s)
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    private companion object {
        const val SETUP_TOKEN = "test-setup-token-0123456789abcdef"
        const val ADMIN_DEVICE = "dev-boss"
    }
}
