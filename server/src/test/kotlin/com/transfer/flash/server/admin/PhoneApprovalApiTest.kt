package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.HubStepUp
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.ApprovalDto
import com.transfer.flash.core.hubprotocol.rest.ApprovalSubmitBody
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
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.relay.HubPrincipalResolver
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.KeyPair
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-131 (FO-14) over HTTP: the approver phone, the approval routes, and the dangerous routes that now need it. */
class PhoneApprovalApiTest {

    private val adminPassword = "BossPassword123!"

    private class Sess(val cookie: String, val csrf: String)

    private inner class Realm {
        var now: Long = System.currentTimeMillis()
        val rootKey = FlashCrypto.generateEcKeyPair()
        val serverKey = FlashCrypto.generateEcKeyPair()
        val browserKey: KeyPair = FlashCrypto.generateEcKeyPair()
        val phoneKey: KeyPair = FlashCrypto.generateEcKeyPair()
        val spareKey: KeyPair = FlashCrypto.generateEcKeyPair()
        val memberKey: KeyPair = FlashCrypto.generateEcKeyPair()
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
            printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000, explicitSetupToken = "setup-token-for-tests-1234567890",
        )
        val audit = AuditLogger(db, clock = { now })
        val directory = OrganizationDirectory.open(db)
        val stepUp = StepUpAuth(admins, identities, revocations, clock = { now })
        val config = ServerConfig(port = 8443, adminPort = 8444, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        val totpSecret = admins.generateTotpSecret()
        val deps = AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost", policy = AdminListenerPolicy.Open),
            admins = admins, audit = audit, directory = directory, identities = identities, stepUp = stepUp, config = config,
            enrollment = enrollment, revocations = revocations, bearerTokens = tokens, limits = HubRateLimits.unlimited(), clock = { now },
        )

        init {
            admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword(adminPassword), totpSecret))
            val issuer = FlashCrypto.generateEcKeyPair()
            fun enrol(id: String, key: KeyPair, role: String) {
                identities.createInvite("tok-$id", null, role, emptySet(), emptySet(), now + 100_000_000, 1, now)
                identities.enroll("tok-$id", id, key.toBase64Spki(), id, now, 100, 1L) { serial, _ ->
                    DeviceCert.issue(issuer.private, issuer.toBase64Spki(), id, key.toBase64Spki(), role, null, now - 1000, now + 100_000_000, serial)
                }
            }
            enrol("dev-browser", browserKey, "admin")
            enrol("dev-phone", phoneKey, "admin")
            enrol("dev-spare", spareKey, "admin")
            enrol("dev-member", memberKey, "member")
            admins.bindDevice("boss", "dev-browser")
        }

        fun totp(): String = admins.generateTotp(totpSecret, now / 1000)
    }

    /** Stands in for the bearer: the test names the calling device in `X-Test-Device`. */
    private val resolver = HubPrincipalResolver { call -> call.request.headers["X-Test-Device"]?.let { HubPrincipal(it, "admin") } }

    private fun ApplicationTestBuilder.mount(r: Realm) {
        routing {
            adminApiRoutes(r.deps)
            approvalRoutes(r.stepUp, resolver, HubRateLimits.unlimited())
        }
    }

    private fun HttpRequestBuilder.withSession(s: Sess?, stepUp: String? = null) {
        if (s != null) header("Cookie", "flash_admin_session=${s.cookie}")
        if (s != null) header("X-CSRF-Token", s.csrf)
        header("Origin", "http://localhost")
        if (stepUp != null) header("X-Step-Up", stepUp)
    }

    private suspend fun HttpClient.signIn(r: Realm): Sess {
        r.now += 31_000
        val resp = post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody("boss", adminPassword, r.totp())))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val cookie = resp.setCookie().first { it.name == "flash_admin_session" }.value
        return Sess(cookie, HubRestCodec.decode<LoginDto>(resp.bodyAsText()).csrfToken)
    }

    private suspend inline fun <reified T> HttpClient.postJson(path: String, s: Sess?, body: T, stepUp: String? = null): HttpResponse =
        post("/admin/api/v1$path") {
            withSession(s, stepUp)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    private suspend fun HttpClient.getA(path: String, s: Sess?): HttpResponse = get("/admin/api/v1$path") { withSession(s) }

    private suspend fun HttpClient.openChallenge(s: Sess, action: String, target: String): StepUpChallengeDto {
        val created = postJson("/stepup/challenge", s, StepUpChallengeBody(action, target))
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        return HubRestCodec.decode(created.bodyAsText())
    }

    private suspend fun HttpClient.browserSigns(r: Realm, s: Sess, ch: StepUpChallengeDto, key: KeyPair = r.browserKey) {
        val signature = FlashCrypto.sign(key.private, Base64.getDecoder().decode(ch.proofBytes))
        assertEquals(HttpStatusCode.OK, postJson("/stepup/verify", s, StepUpVerifyBody(ch.challengeId, signature)).status)
    }

    private suspend fun HttpClient.phoneList(device: String): List<ApprovalDto> {
        val resp = get("/api/v1/approvals") { header("X-Test-Device", device) }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return HubRestCodec.decodeList(resp.bodyAsText())
    }

    private suspend fun HttpClient.phoneSubmit(device: String, id: String, signatureBase64: String): HttpResponse =
        post("/api/v1/approvals/$id") {
            header("X-Test-Device", device)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(ApprovalSubmitBody(signatureBase64)))
        }

    private fun phoneSignature(key: KeyPair, a: ApprovalDto): String =
        FlashCrypto.sign(key.private, HubStepUp.approvalBytes(a.challengeId, a.action, a.admin, a.target, a.nonce))

    /** Binds dev-phone as the approver through the real route: a fresh authenticator code plus the browser key's confirmation. */
    private suspend fun HttpClient.bindPhone(r: Realm, s: Sess) {
        val ch = openChallenge(s, StepUpActions.APPROVER_CHANGE, "approver:dev-phone")
        assertEquals(false, ch.approvalRequired, "no phone is bound yet")
        browserSigns(r, s, ch)
        r.now += 31_000
        val resp = postJson("/admins/me/approver", s, ApproverBindBody("dev-phone", r.totp()), stepUp = ch.challengeId)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals("dev-phone", r.admins.approverOf("boss"))
    }

    @Test
    fun theWholeFlowAnAdministratorCreationNeedsTheBrowserKeyAndThePhone() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.bindPhone(r, s)

        val ch = client.openChallenge(s, StepUpActions.ADMIN_CREATE, "second")
        assertTrue(ch.approvalRequired)
        assertEquals(6, ch.approvalCode.length)
        client.browserSigns(r, s, ch)
        val body = AdminCreateBody("second", "A-second-admin-password")
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins", s, body, stepUp = ch.challengeId).status, "the browser key alone")
        assertNull(r.admins.getAdmin("second"))

        // The failed attempt burned that challenge (as before). Do it properly.
        val ok = client.openChallenge(s, StepUpActions.ADMIN_CREATE, "second")
        client.browserSigns(r, s, ok)
        val status0 = HubRestCodec.decode<StepUpStatusDto>(client.getA("/stepup/${ok.challengeId}", s).bodyAsText())
        assertTrue(status0.verified && status0.approvalRequired && !status0.approved)

        val pending = client.phoneList("dev-phone").single { it.challengeId == ok.challengeId }
        assertEquals(ok.approvalCode, pending.code, "the console and the phone show the same digits")
        assertEquals("admin.create", pending.action)
        assertEquals("boss", pending.admin)
        assertEquals("second", pending.target)
        assertEquals(HttpStatusCode.OK, client.phoneSubmit("dev-phone", ok.challengeId, phoneSignature(r.phoneKey, pending)).status)

        val status1 = HubRestCodec.decode<StepUpStatusDto>(client.getA("/stepup/${ok.challengeId}", s).bodyAsText())
        assertTrue(status1.approved)
        assertEquals(HttpStatusCode.Created, client.postJson("/admins", s, body, stepUp = ok.challengeId).status)
        assertTrue(client.phoneList("dev-phone").none { it.challengeId == ok.challengeId })
    }

    @Test
    fun onlyTheApproverPhoneSeesAndAnswersAndABadSignatureIsRefused() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.bindPhone(r, s)
        val ch = client.openChallenge(s, StepUpActions.DELEGATION_INSTALL, "serial:9")

        assertTrue(client.phoneList("dev-spare").isEmpty(), "another admin device sees nothing")
        assertTrue(client.phoneList("dev-member").isEmpty())
        val pending = client.phoneList("dev-phone").single()
        // Another device, even with a valid signature of its own, is refused.
        assertEquals(HttpStatusCode.Forbidden, client.phoneSubmit("dev-spare", ch.challengeId, phoneSignature(r.spareKey, pending)).status)
        // The right device with the wrong key, wrong bytes, or garbage is refused.
        assertEquals(HttpStatusCode.Forbidden, client.phoneSubmit("dev-phone", ch.challengeId, phoneSignature(r.spareKey, pending)).status)
        val browserBytes = FlashCrypto.sign(r.phoneKey.private, Base64.getDecoder().decode(ch.proofBytes))
        assertEquals(HttpStatusCode.Forbidden, client.phoneSubmit("dev-phone", ch.challengeId, browserBytes).status, "the browser bytes are not an approval")
        assertEquals(HttpStatusCode.BadRequest, client.phoneSubmit("dev-phone", ch.challengeId, "not base64 !!").status)
        assertEquals(HttpStatusCode.Forbidden, client.phoneSubmit("dev-phone", "no-such-challenge", phoneSignature(r.phoneKey, pending)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/approvals").status, "no bearer, no list")
        assertEquals(HttpStatusCode.OK, client.phoneSubmit("dev-phone", ch.challengeId, phoneSignature(r.phoneKey, pending)).status)
        assertEquals(HttpStatusCode.Forbidden, client.phoneSubmit("dev-phone", ch.challengeId, phoneSignature(r.phoneKey, pending)).status, "once")
    }

    @Test
    fun theFirstPhoneNeedsAFreshAuthenticatorCodeAndAnAdminDevice() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        val ch = client.openChallenge(s, StepUpActions.APPROVER_CHANGE, "approver:dev-phone")
        client.browserSigns(r, s, ch)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/approver", s, ApproverBindBody("dev-phone", "000000"), stepUp = ch.challengeId).status)
        assertNull(r.admins.approverOf("boss"))
        val ch2 = client.openChallenge(s, StepUpActions.APPROVER_CHANGE, "approver:dev-member")
        client.browserSigns(r, s, ch2)
        r.now += 31_000
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/admins/me/approver", s, ApproverBindBody("dev-member", r.totp()), stepUp = ch2.challengeId).status, "a member device")
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/admins/me/approver", s, ApproverBindBody("nobody", r.totp()), stepUp = ch2.challengeId).status)
        // A stolen session with a guessed code but no browser key confirmation cannot name its own phone either.
        r.now += 31_000
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/approver", s, ApproverBindBody("dev-phone", r.totp())).status)
        assertNull(r.admins.approverOf("boss"))
    }

    @Test
    fun replacingThePhoneNeedsTheOldPhoneToo() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.bindPhone(r, s)
        val ch = client.openChallenge(s, StepUpActions.APPROVER_CHANGE, "approver:dev-spare")
        assertTrue(ch.approvalRequired)
        client.browserSigns(r, s, ch)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/approver", s, ApproverBindBody("dev-spare"), stepUp = ch.challengeId).status)
        assertEquals("dev-phone", r.admins.approverOf("boss"))

        val ch2 = client.openChallenge(s, StepUpActions.APPROVER_CHANGE, "approver:dev-spare")
        client.browserSigns(r, s, ch2)
        val pending = client.phoneList("dev-phone").single { it.challengeId == ch2.challengeId }
        client.phoneSubmit("dev-phone", ch2.challengeId, phoneSignature(r.phoneKey, pending))
        assertEquals(HttpStatusCode.OK, client.postJson("/admins/me/approver", s, ApproverBindBody("dev-spare"), stepUp = ch2.challengeId).status)
        assertEquals("dev-spare", r.admins.approverOf("boss"))
    }

    @Test
    fun aLostPhoneIsRemovedWithARecoveryCodeAndNothingElse() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.bindPhone(r, s)
        val codes = r.admins.generateRecoveryCodes("boss")
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/admins/me/approver/remove", s, ApproverRemoveBody("wrong-code")).status)
        assertEquals("dev-phone", r.admins.approverOf("boss"))
        assertEquals(HttpStatusCode.OK, client.postJson("/admins/me/approver/remove", s, ApproverRemoveBody(codes.first())).status)
        assertNull(r.admins.approverOf("boss"))
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/admins/me/approver/remove", s, ApproverRemoveBody(codes[1])).status, "nothing left to remove")
        // With no phone bound the dangerous actions are browser-only again.
        val ch = client.openChallenge(s, StepUpActions.ADMIN_CREATE, "second")
        assertEquals(false, ch.approvalRequired)
    }

    @Test
    fun replacingTheSignerNeedsThePhoneApprovalBeforeARecoveryCodeIsSpent() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        client.bindPhone(r, s)
        val codes = r.admins.generateRecoveryCodes("boss")

        // No approval: refused, and the recovery code is NOT consumed.
        val refused = client.postJson("/admins/me/device", s, BindDeviceBody("dev-spare", codes.first()))
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("dev-browser", r.admins.getAdmin("boss")!!.enrolledDeviceId)

        // The browser key is not asked for this one: its loss is the reason to be here.
        val ch = client.openChallenge(s, StepUpActions.SIGNER_REPLACE, "device")
        assertTrue(ch.approvalRequired)
        assertEquals(false, ch.browserKeyRequired)
        val pending = client.phoneList("dev-phone").single { it.challengeId == ch.challengeId }
        client.phoneSubmit("dev-phone", ch.challengeId, phoneSignature(r.phoneKey, pending))
        val done = client.postJson("/admins/me/device", s, BindDeviceBody("dev-spare", codes.first()), stepUp = ch.challengeId)
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        assertEquals("dev-spare", r.admins.getAdmin("boss")!!.enrolledDeviceId)
    }

    @Test
    fun theSessionAndAccountListsShowWhichPhoneIsBound() = testApplication {
        val r = Realm()
        mount(r)
        val s = client.signIn(r)
        assertTrue(!client.getA("/session", s).bodyAsText().contains("dev-phone"))
        client.bindPhone(r, s)
        assertTrue(client.getA("/session", s).bodyAsText().contains("\"approverDeviceId\":\"dev-phone\""))
        assertTrue(client.getA("/admins", s).bodyAsText().contains("\"approverDeviceId\":\"dev-phone\""))
    }
}
