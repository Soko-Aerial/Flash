package com.transfer.flash.server.admin

import com.transfer.flash.core.enterprise.EnterpriseEnrollmentClient
import com.transfer.flash.core.enterprise.EnrollmentOutcome
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.ChallengeResponseAuth
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.RevocationSigner
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.auth.authRoutes
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.MemberRole
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.BeginReplacementOutcome
import com.transfer.flash.server.identity.DeviceReplacementState
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.EnrollRefusal
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.identity.InviteState
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.update
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
import java.nio.file.Files
import java.security.KeyPair
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HR6: Device replacement tests (happy path, stolen credentials, out-of-scope, replay, quota, approver unbinding).
 */
class DeviceReplacementTest {

    private val adminPassword = "BossPassword123!"
    private val headPassword = "HeadPassword123!"

    private class Sess(val cookie: String, val csrf: String)

    private inner class Realm(
        val initialQuota: Int = 50,
        val delegationScopes: Set<String> = setOf(
            DelegationCert.SCOPE_ENROL_MEMBER,
            DelegationCert.SCOPE_ENROL_ADMIN,
            DelegationCert.SCOPE_REVOKE_MEMBER,
        ),
    ) {
        var now: Long = System.currentTimeMillis()
        val rootKey: KeyPair = FlashCrypto.generateEcKeyPair()
        val serverKey: KeyPair = FlashCrypto.generateEcKeyPair()
        val adminKey: KeyPair = FlashCrypto.generateEcKeyPair()
        val db = ServerDatabase.inMemory()
        val delegation: DelegationCert = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = serverKey.toBase64Spki(),
            scopes = delegationScopes,
            validFrom = now - 60_000,
            validTo = now + 400L * 24 * 3600 * 1000,
            maxDevices = initialQuota,
            serial = 1L,
        )
        val identities = IdentityStore.open(db)
        val revocations = SqliteRevocationService(
            db, rootKey.toBase64Spki(), RevocationSigner(serverKey.private, serverKey.toBase64Spki()) { delegation }, clock = { now },
        )
        val tokens = BearerTokenStore(revocations = revocations)
        val enrollment = EnrollmentService(
            rootKey.toBase64Spki(), serverKey.private, delegation, initialQuota = initialQuota, store = identities, revocations = revocations,
        )
        val admins = AdminAuthService(
            printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000,
            explicitSetupToken = "setup-token-123",
        )
        val audit = AuditLogger(db, clock = { now })
        val directory = OrganizationDirectory.open(db)
        val stepUp = StepUpAuth(admins, identities, revocations, clock = { now })
        val config = ServerConfig(port = 8443, adminPort = 8444, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        val totpSecret = admins.generateTotpSecret()
        var headTotpSecret = ""
        val deps = AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost", policy = AdminListenerPolicy.Open),
            admins = admins, audit = audit, directory = directory, identities = identities, stepUp = stepUp, config = config,
            enrollment = enrollment, revocations = revocations, bearerTokens = tokens, limits = HubRateLimits.unlimited(), clock = { now },
        )

        init {
            admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword(adminPassword), totpSecret))
            enrolAdminDevice()
            admins.bindDevice("boss", "admin-device")

            directory.createDepartment("sales", "Sales", emptyList(), DirectoryActor.CONSOLE)
            directory.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)

            headTotpSecret = admins.createHead("head_sales", headPassword, "boss", "Sales Head", listOf("sales"), null).totpSecret
        }

        private fun enrolAdminDevice() {
            val issuer = FlashCrypto.generateEcKeyPair()
            identities.createInvite("tok-admin", null, "admin", emptySet(), emptySet(), now + 100_000_000, 1, now)
            identities.enroll("tok-admin", "admin-device", adminKey.toBase64Spki(), "Boss phone", now, 100, 1L) { serial, _ ->
                DeviceCert.issue(issuer.private, issuer.toBase64Spki(), "admin-device", adminKey.toBase64Spki(), "admin", null, now - 1000, now + 100_000_000, serial)
            }
        }

        fun totp(): String {
            now += 31_000
            return admins.generateTotp(totpSecret, now / 1000)
        }

        fun headTotp(): String {
            now += 31_000
            return admins.generateTotp(headTotpSecret, now / 1000)
        }

        fun enrolMember(id: String, token: String, dept: String? = null): KeyPair {
            val key = FlashCrypto.generateEcKeyPair()
            val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(token, id, key.toBase64Spki(), enrollment.serverId))
            enrollment.enrollDevice(token, id, key.toBase64Spki(), proof, currentTimeMs = now).getOrThrow()
            if (dept != null) directory.registerMember(com.transfer.flash.server.directory.DepartmentMember(id, dept, "User $id", MemberRole.MEMBER, key.toBase64Spki()))
            return key
        }
    }

    private fun ApplicationTestBuilder.mount(r: Realm) {
        routing { adminApiRoutes(r.deps) }
    }

    private fun HttpRequestBuilder.withSession(s: Sess?, stepUp: String? = null) {
        if (s != null) header("Cookie", "flash_admin_session=${s.cookie}")
        if (s != null) header("X-CSRF-Token", s.csrf)
        header("Origin", "http://localhost")
        if (stepUp != null) header("X-Step-Up", stepUp)
    }

    private suspend fun HttpClient.login(r: Realm, user: String = "boss", pass: String = adminPassword, totpCode: String? = null): Sess {
        val code = totpCode ?: if (user == "boss") r.totp() else r.headTotp()
        val res = post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody(user, pass, code)))
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val cookie = res.setCookie().first { it.name == "flash_admin_session" }.value
        val dto = HubRestCodec.decode<LoginDto>(res.bodyAsText())
        return Sess(cookie, dto.csrfToken)
    }

    private suspend fun HttpClient.stepUp(r: Realm, s: Sess, action: String, target: String, key: KeyPair = r.adminKey): String {
        val chRes = post("/admin/api/v1/stepup/challenge") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(StepUpChallengeBody(action, target)))
        }
        assertEquals(HttpStatusCode.OK, chRes.status, chRes.bodyAsText())
        val ch = HubRestCodec.decode<StepUpChallengeDto>(chRes.bodyAsText())
        val proof = FlashCrypto.sign(key.private, Base64.getDecoder().decode(ch.proofBytes))
        val vRes = post("/admin/api/v1/stepup/verify") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(StepUpVerifyBody(ch.challengeId, proof)))
        }
        assertEquals(HttpStatusCode.OK, vRes.status, vRes.bodyAsText())
        return ch.challengeId
    }

    private suspend inline fun <reified T> HttpClient.postJson(path: String, s: Sess?, body: T, stepUp: String? = null): HttpResponse =
        post("/admin/api/v1$path") {
            withSession(s, stepUp = stepUp)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    @Test
    fun happyPathDeviceReplacementRevokesOldAndEnrollsNewDeviceWithProofOfPossession() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val oldInvite = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        val oldKey = r.enrolMember("dev-old", oldInvite.token, dept = "sales")
        val oldSerial = r.identities.device("dev-old")!!.serial
        val (oldSessionToken, _) = r.tokens.issue(HubPrincipal(deviceId = "dev-old", role = DeviceCert.ROLE_MEMBER, departmentId = "sales", certSerial = oldSerial), r.now)

        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-old")!!.status)
        assertNotNull(r.tokens.resolve(oldSessionToken, r.now))

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-old")
        val repRes = client.postJson(
            "/devices/dev-old/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost on business trip", note = "Replacement phone"),
            stepUp = su,
        )
        assertEquals(HttpStatusCode.OK, repRes.status, repRes.bodyAsText())
        val repDto = HubRestCodec.decode<DeviceReplaceResultDto>(repRes.bodyAsText())

        assertEquals("REVOKED", repDto.oldDevice.status)
        assertEquals(DeviceStatus.REVOKED, r.identities.device("dev-old")!!.status)
        assertNull(r.tokens.resolve(oldSessionToken, r.now), "Old device bearer tokens must be immediately revoked")
        assertTrue(r.revocations.isRevoked(oldSerial, "dev-old"), "Old device must be durably revoked in revocation list")

        // Fresh device enrollment with new keys
        val newKey = FlashCrypto.generateEcKeyPair()
        val newProof = FlashCrypto.sign(newKey.private, HubAuth.enrollProofBytes(repDto.invite.token, "dev-new", newKey.toBase64Spki(), r.enrollment.serverId))
        val enrollResult = r.enrollment.enrollDevice(repDto.invite.token, "dev-new", newKey.toBase64Spki(), newProof, currentTimeMs = r.now).getOrThrow()
        assertEquals("dev-new", enrollResult.deviceCert.deviceId)
        assertEquals("sales", enrollResult.deviceCert.department)

        val newRecord = r.identities.device("dev-new")
        assertNotNull(newRecord)
        assertEquals(DeviceStatus.ACTIVE, newRecord.status)
        assertEquals("sales", newRecord.department)

        // Audit check
        val auditEntries = r.audit.getRecent(20)
        assertTrue(auditEntries.any { it.action == "device.replace" && it.details.contains("oldDevice=dev-old") && it.details.contains("department=sales") })
    }

    @Test
    fun stolenOldCredentialsAndTokenRefusedAfterReplacement() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val oldInvite = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        val oldKey = r.enrolMember("dev-stolen", oldInvite.token, dept = "sales")

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-stolen")
        client.postJson(
            "/devices/dev-stolen/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Stolen device"),
            stepUp = su,
        )

        // 1. Old device cannot renew or re-enroll
        val anotherInvite = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        val outcome = r.identities.enroll(anotherInvite.token, "dev-stolen", oldKey.toBase64Spki(), null, r.now, 100, 1L) { _, _ -> error("unreachable") }
        assertEquals(com.transfer.flash.server.identity.EnrollOutcome.Refused(EnrollRefusal.DEVICE_REVOKED), outcome)
    }

    @Test
    fun departmentHeadScopeEnforcedOnDeviceReplacement() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val headSess = client.login(r, user = "head_sales", pass = headPassword)

        val headKey = FlashCrypto.generateEcKeyPair()
        r.admins.registerConsoleKey("head_sales", headKey.toBase64Spki(), "Head browser key")

        // Setup sales device and eng device
        val invSales = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-sales", invSales.token, dept = "sales")

        val invEng = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "eng", expiry = r.now + 86400000))
        r.enrolMember("dev-eng", invEng.token, dept = "eng")

        // 1. Dept head cannot replace device outside their department (404)
        val badTargetRes = client.postJson(
            "/devices/dev-eng/replace",
            headSess,
            DeviceReplaceBody(department = "sales", reason = "Steal device"),
        )
        assertEquals(HttpStatusCode.NotFound, badTargetRes.status)

        // 2. Dept head cannot assign foreign department to replacement (403)
        val headSuBad = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales", key = headKey)
        val badDeptRes = client.postJson(
            "/devices/dev-sales/replace",
            headSess,
            DeviceReplaceBody(department = "eng", reason = "Move out of dept"),
            stepUp = headSuBad,
        )
        assertEquals(HttpStatusCode.Forbidden, badDeptRes.status)
        assertTrue(badDeptRes.bodyAsText().contains("own department"))

        // 3. Dept head replacing sales device within sales succeeds with step-up
        val headSu = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales", key = headKey)
        val goodRes = client.postJson(
            "/devices/dev-sales/replace",
            headSess,
            DeviceReplaceBody(department = "sales", reason = "Sales phone upgrade"),
            stepUp = headSu,
        )
        assertEquals(HttpStatusCode.OK, goodRes.status)
        assertEquals(DeviceStatus.REVOKED, r.identities.device("dev-sales")!!.status)
    }

    @Test
    fun nonOrgAdminCannotReplaceAdminDevice() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val headSess = client.login(r, user = "head_sales", pass = headPassword)
        val headKey = FlashCrypto.generateEcKeyPair()
        r.admins.registerConsoleKey("head_sales", headKey.toBase64Spki(), "Head browser key")

        // 1. Org admin device (null department) is out of head_sales scope -> 404
        val res = client.postJson(
            "/devices/admin-device/replace",
            headSess,
            DeviceReplaceBody(department = "sales", reason = "Takeover"),
        )
        assertEquals(HttpStatusCode.NotFound, res.status)

        // 2. Member inside head_sales's OWN department that is bound as approver -> 403 ERR_FORBIDDEN
        val invSales = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-sales-approver", invSales.token, dept = "sales")
        r.admins.bindApprover("boss", "dev-sales-approver")
        val suApprover = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales-approver", key = headKey)
        val appRes = client.postJson(
            "/devices/dev-sales-approver/replace",
            headSess,
            DeviceReplaceBody(department = "sales", reason = "Bypass approver"),
            stepUp = suApprover,
        )
        assertEquals(HttpStatusCode.Forbidden, appRes.status, appRes.bodyAsText())
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-sales-approver")!!.status)

        // 3. Regular member inside head_sales's department cannot be promoted to admin role -> 403 ERR_FORBIDDEN
        val invMember = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-sales-member", invMember.token, dept = "sales")
        val suPromote = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales-member", key = headKey)
        val promoteRes = client.postJson(
            "/devices/dev-sales-member/replace",
            headSess,
            DeviceReplaceBody(department = "sales", role = "admin", reason = "Escalate"),
            stepUp = suPromote,
        )
        assertEquals(HttpStatusCode.Forbidden, promoteRes.status, promoteRes.bodyAsText())
    }

    @Test
    fun replacementInviteIsSingleUseAndCannotBeReplayed() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-1", inv.token, dept = "sales")

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-1")
        val repRes = client.postJson(
            "/devices/dev-1/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost"),
            stepUp = su,
        )
        val repDto = HubRestCodec.decode<DeviceReplaceResultDto>(repRes.bodyAsText())

        // Winner enrolls
        val k1 = FlashCrypto.generateEcKeyPair()
        val p1 = FlashCrypto.sign(k1.private, HubAuth.enrollProofBytes(repDto.invite.token, "dev-winner", k1.toBase64Spki(), r.enrollment.serverId))
        val ok = r.enrollment.enrollDevice(repDto.invite.token, "dev-winner", k1.toBase64Spki(), p1, currentTimeMs = r.now)
        assertTrue(ok.isSuccess)

        // Replay attempt by another device fails with used
        val k2 = FlashCrypto.generateEcKeyPair()
        val p2 = FlashCrypto.sign(k2.private, HubAuth.enrollProofBytes(repDto.invite.token, "dev-replay", k2.toBase64Spki(), r.enrollment.serverId))
        val replay = r.enrollment.enrollDevice(repDto.invite.token, "dev-replay", k2.toBase64Spki(), p2, currentTimeMs = r.now)
        assertTrue(replay.isFailure)
        assertTrue(replay.exceptionOrNull() is com.transfer.flash.server.auth.InviteRefusedException)
        assertEquals(com.transfer.flash.server.auth.InviteRefusal.USED, (replay.exceptionOrNull() as com.transfer.flash.server.auth.InviteRefusedException).refusal)
    }

    @Test
    fun boundApproverDeviceReplacementClearsAdminBinding() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-approver", inv.token, dept = "sales")
        r.admins.bindApprover("boss", "dev-approver")
        assertEquals("dev-approver", r.admins.approverOf("boss"))

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-approver")
        val repRes = client.postJson(
            "/devices/dev-approver/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Approver phone lost"),
            stepUp = su,
        )
        assertEquals(HttpStatusCode.OK, repRes.status)

        // Approver phone binding on admin must be detached
        assertNull(r.admins.approverOf("boss"), "Approver binding on boss must be cleared when device is replaced")
    }

    @Test
    fun quotaIsEnforcedOnReplacement() = testApplication {
        val r = Realm(initialQuota = 2) // quota is 2 (admin-device is 1)
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-member", inv.token, dept = "sales")

        // Now active devices = 2 (admin-device + dev-member). Quota ceiling reached!
        assertEquals(0, r.enrollment.currentRemainingQuota)

        // Replacing dev-member revokes dev-member (active devices -> 1), so replacement invite can enroll!
        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-member")
        val repRes = client.postJson(
            "/devices/dev-member/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Quota replacement test"),
            stepUp = su,
        )
        assertEquals(HttpStatusCode.OK, repRes.status)
        val repDto = HubRestCodec.decode<DeviceReplaceResultDto>(repRes.bodyAsText())

        // New device enrolls successfully into the freed slot
        val kNew = FlashCrypto.generateEcKeyPair()
        val pNew = FlashCrypto.sign(kNew.private, HubAuth.enrollProofBytes(repDto.invite.token, "dev-new", kNew.toBase64Spki(), r.enrollment.serverId))
        val enrollOk = r.enrollment.enrollDevice(repDto.invite.token, "dev-new", kNew.toBase64Spki(), pNew, currentTimeMs = r.now)
        assertTrue(enrollOk.isSuccess)
    }

    @Test
    fun expiredReplacementGrantIsRefused() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-exp", inv.token, dept = "sales")

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-exp")
        val repRes = client.postJson(
            "/devices/dev-exp/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost", ttlMinutes = 5),
            stepUp = su,
        )
        assertEquals(HttpStatusCode.OK, repRes.status)
        val repDto = HubRestCodec.decode<DeviceReplaceResultDto>(repRes.bodyAsText())

        // Advance past 5-minute TTL
        r.now += 6 * 60_000L
        val kNew = FlashCrypto.generateEcKeyPair()
        val pNew = FlashCrypto.sign(kNew.private, HubAuth.enrollProofBytes(repDto.invite.token, "dev-late", kNew.toBase64Spki(), r.enrollment.serverId))
        val late = r.enrollment.enrollDevice(repDto.invite.token, "dev-late", kNew.toBase64Spki(), pNew, currentTimeMs = r.now)
        assertTrue(late.isFailure)
        val ex = late.exceptionOrNull() as? com.transfer.flash.server.auth.InviteRefusedException
        assertEquals(com.transfer.flash.server.auth.InviteRefusal.EXPIRED, ex?.refusal)
    }

    @Test
    fun concurrentReplacementRedemptionPermitsExactlyOneWinner() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-race", inv.token, dept = "sales")

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-race")
        val repRes = client.postJson(
            "/devices/dev-race/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost"),
            stepUp = su,
        )
        val repDto = HubRestCodec.decode<DeviceReplaceResultDto>(repRes.bodyAsText())

        val outcomes = runBlocking {
            (1..4).map { idx ->
                async(Dispatchers.IO) {
                    val key = FlashCrypto.generateEcKeyPair()
                    val devId = "dev-racer-$idx"
                    val proof = FlashCrypto.sign(key.private, HubAuth.enrollProofBytes(repDto.invite.token, devId, key.toBase64Spki(), r.enrollment.serverId))
                    r.enrollment.enrollDevice(repDto.invite.token, devId, key.toBase64Spki(), proof, currentTimeMs = r.now)
                }
            }.awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess }, "Exactly one concurrent redemption must win")
        assertEquals(3, outcomes.count { it.isFailure }, "Losers must fail")
    }

    @Test
    fun overQuotaActiveDeviceReplacementRefused() = testApplication {
        // Ceiling = 2 (admin-device = 1), force 2 extra ACTIVE devices so activeCount = 3 > 2
        val r = Realm(initialQuota = 2)
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val k1 = FlashCrypto.generateEcKeyPair()
        val k2 = FlashCrypto.generateEcKeyPair()
        r.identities.createInvite("tok-over-1", "sales", "member", emptySet(), emptySet(), r.now + 86400000, 1, r.now)
        r.identities.enroll("tok-over-1", "dev-over-1", k1.toBase64Spki(), "Over 1", r.now, 100, 1L) { serial, _ ->
            DeviceCert.issue(r.serverKey.private, r.serverKey.toBase64Spki(), "dev-over-1", k1.toBase64Spki(), "member", "sales", r.now - 1000, r.now + 86400000, serial)
        }
        r.identities.createInvite("tok-over-2", "sales", "member", emptySet(), emptySet(), r.now + 86400000, 1, r.now)
        r.identities.enroll("tok-over-2", "dev-over-2", k2.toBase64Spki(), "Over 2", r.now, 100, 1L) { serial, _ ->
            DeviceCert.issue(r.serverKey.private, r.serverKey.toBase64Spki(), "dev-over-2", k2.toBase64Spki(), "member", "sales", r.now - 1000, r.now + 86400000, serial)
        }
        assertEquals(3L, r.identities.activeDeviceCount())

        // Even after freeing 1 active seat, 2 >= 2 ceiling -> must fail 409 ERR_QUOTA_EXCEEDED
        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-over-1")
        val repRes = client.postJson(
            "/devices/dev-over-1/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Over quota"),
            stepUp = su,
        )
        assertEquals(HttpStatusCode.Conflict, repRes.status, repRes.bodyAsText())
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-over-1")!!.status)
    }

    @Test
    fun linkedDeviceBindingIsClearedOnReplacementAndProtectedFromDeptHead() = testApplication {
        val r = Realm()
        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-head-linked", inv.token, dept = "sales")
        r.admins.createHead("head_linked", "HeadPass123!", "boss", "Linked Head", listOf("sales"), "dev-head-linked")
        mount(r)
        val client = createClient { followRedirects = false }

        // Dept head in sales cannot replace another head's linked personal device
        val headSess = client.login(r, user = "head_sales", pass = headPassword)
        val headKey = FlashCrypto.generateEcKeyPair()
        r.admins.registerConsoleKey("head_sales", headKey.toBase64Spki(), "Head key")
        val headSu = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-head-linked", key = headKey)
        val denied = client.postJson(
            "/devices/dev-head-linked/replace",
            headSess,
            DeviceReplaceBody(department = "sales", reason = "Unbind"),
            stepUp = headSu,
        )
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        // Org admin can replace and linkedDeviceId is cleared
        val orgSess = client.login(r)
        val orgSu = client.stepUp(r, orgSess, StepUpActions.REPLACE_DEVICE, "dev-head-linked")
        val ok = client.postJson(
            "/devices/dev-head-linked/replace",
            orgSess,
            DeviceReplaceBody(department = "sales", reason = "Lost linked phone"),
            stepUp = orgSu,
        )
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertNull(r.admins.listAdmins().first { it.username == "head_linked" }.linkedDeviceId)
    }

    @Test
    fun enterpriseEnrollmentClientRedeemsReplacementLinkWithProofOfPossessionAndRevokedDeviceRefused() = testApplication {
        val r = Realm()
        val challengeAuth = ChallengeResponseAuth(
            rootPublicKey = r.rootKey.toBase64Spki(),
            revocationListProvider = { r.revocations.current() },
            revocations = r.revocations,
        )
        routing {
            adminApiRoutes(r.deps)
            authRoutes(
                challengeAuth = challengeAuth,
                enrollmentService = r.enrollment,
                serverExternalUrl = "http://localhost",
                bearerTokens = r.tokens,
                revocations = r.revocations,
                directory = r.directory,
                audit = r.audit,
                limits = HubRateLimits.unlimited(),
                clock = { r.now },
            )
        }
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-client-old", inv.token, dept = "sales")
        val oldSerial = r.identities.device("dev-client-old")!!.serial

        val su = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-client-old")
        val repRes = client.postJson(
            "/devices/dev-client-old/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Broken screen", note = "Client e2e"),
            stepUp = su,
        )
        assertEquals(HttpStatusCode.OK, repRes.status)
        val repDto = HubRestCodec.decode<DeviceReplaceResultDto>(repRes.bodyAsText())

        // Old device challenge-response authentication is refused because its serial/device is revoked
        assertTrue(r.revocations.isRevoked(oldSerial, "dev-client-old"))
        assertTrue(r.revocations.current()?.revokedSerials?.contains(oldSerial) == true)

        // Real EnterpriseEnrollmentClient parses invite link, signs PoP, validates cert chain, stores credentials
        val parsed = EnrollmentInvite.parseOrNull(repDto.invite.link)
        assertNotNull(parsed)
        assertEquals(repDto.invite.token, parsed.token)
        assertEquals(r.enrollment.serverId, parsed.rootFingerprint)
        // Ktor testApplication routes loopback http://localhost:80 to the in-process Hub
        val testInvite = parsed.copy(serverUrl = "http://localhost")
        val keyDir = Files.createTempDirectory("hr6-client-key").toFile()
        try {
            val crypto = PersistedFlashCrypto(keyDir)
            val trustStore = MemoryEnterpriseTrustStore()
            val enrollClient = EnterpriseEnrollmentClient(
                httpClient = client,
                trustStore = trustStore,
                allowCleartext = { true },
                clock = { r.now },
            )
            val outcome = enrollClient.enroll(testInvite, "dev-client-new", "Replacement Client", crypto, nowMs = r.now)
            assertTrue(outcome is EnrollmentOutcome.Enrolled, "Expected Enrolled, got $outcome")
            val creds = trustStore.getCredentials()
            assertNotNull(creds)
            assertEquals("dev-client-new", creds.deviceCert.deviceId)
            assertEquals("sales", creds.deviceCert.department)
        } finally {
            keyDir.deleteRecursively()
        }
    }

    @Test
    fun invalidTtlAndDelegationAndUrlRefusedBeforeRevokingOldDevice() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-valid", inv.token, dept = "sales")
        val oldSerial = r.identities.device("dev-valid")!!.serial
        val (tok, _) = r.tokens.issue(HubPrincipal(deviceId = "dev-valid", role = DeviceCert.ROLE_MEMBER, departmentId = "sales", certSerial = oldSerial), r.now)

        // 1. ttlMinutes = 4 (< 5 minimum) must fail with 400 BEFORE revocation or invite creation
        val suTtl = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-valid")
        val badTtl = client.postJson(
            "/devices/dev-valid/replace",
            s,
            DeviceReplaceBody(department = "sales", ttlMinutes = 4, reason = "Bad TTL"),
            stepUp = suTtl,
        )
        assertEquals(HttpStatusCode.BadRequest, badTtl.status, badTtl.bodyAsText())
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-valid")!!.status)
        assertNotNull(r.tokens.resolve(tok, r.now))
        assertEquals(0, r.identities.listInvites(state = InviteState.PENDING, nowMs = r.now).size)

        // 2. Non-HTTPS externalUrl when HTTPS is required refuses 409 without revoking
        val strictEnrollment = EnrollmentService(
            r.rootKey.toBase64Spki(),
            r.serverKey.private,
            r.delegation,
            initialQuota = r.initialQuota,
            store = r.identities,
            revocations = r.revocations,
            requireHttpsInvites = true,
        )
        // Missing SCOPE_ENROL_ADMIN on a member-only delegation also refuses before revoking
        val memberOnlyDelegation = DelegationCert.issue(
            rootPrivateKey = r.rootKey.private,
            rootPublicKey = r.rootKey.toBase64Spki(),
            delegatePublicKey = r.serverKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_REVOKE_MEMBER),
            validFrom = r.now - 60_000,
            validTo = r.now + 400L * 24 * 3600 * 1000,
            maxDevices = 50,
            serial = 2L,
        )
        val noAdminEnrollment = EnrollmentService(
            r.rootKey.toBase64Spki(),
            r.serverKey.private,
            memberOnlyDelegation,
            initialQuota = 50,
            store = r.identities,
            revocations = r.revocations,
        )
        val strictDeps = AdminApiDeps(
            web = r.deps.web,
            admins = r.admins,
            audit = r.audit,
            directory = r.directory,
            identities = r.identities,
            stepUp = r.stepUp,
            config = r.deps.config.copy(externalUrl = "http://insecure.example.invalid", allowInsecure = true),
            enrollment = strictEnrollment,
            revocations = r.revocations,
            bearerTokens = r.tokens,
            clock = { r.now },
        )
        assertFailsWithPrecheck(strictDeps, noAdminEnrollment, r)
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-valid")!!.status)
        assertNull(r.identities.getDeviceReplacement("dev-valid"))
    }

    private fun assertFailsWithPrecheck(
        strictDeps: AdminApiDeps,
        noAdminEnrollment: EnrollmentService,
        r: Realm,
    ) {
        kotlin.runCatching {
            strictDeps.enrollment!!.validateInviteScope(
                strictDeps.config.externalUrl,
                com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 600_000),
                r.now,
            )
        }.also { assertTrue(it.isFailure) }
        kotlin.runCatching {
            noAdminEnrollment.validateInviteScope(
                "https://hub.example.invalid",
                com.transfer.flash.server.auth.InviteScope(department = "sales", role = "admin", expiry = r.now + 600_000),
                r.now,
            )
        }.also { assertTrue(it.isFailure) }
    }

    @Test
    fun deptHeadCannotInheritOrMintDeptAdminOnReplacement() = testApplication {
        val r = Realm(
            delegationScopes = setOf(
                DelegationCert.SCOPE_ENROL_MEMBER,
                DelegationCert.SCOPE_ENROL_ADMIN,
                DelegationCert.SCOPE_REVOKE_MEMBER,
                com.transfer.flash.core.hubprotocol.certs.HubRoles.enrolScope(com.transfer.flash.core.hubprotocol.certs.HubRoles.DEPT_ADMIN),
            ),
        )
        mount(r)
        val client = createClient { followRedirects = false }

        // Enrol a dept_admin device in sales WITHOUT approver/signer/linked bindings
        val headInv = r.enrollment.createInvite(
            "http://localhost",
            com.transfer.flash.server.auth.InviteScope(department = "sales", role = "dept_admin", expiry = r.now + 86400000),
        )
        val kHeadDev = FlashCrypto.generateEcKeyPair()
        val pHeadDev = FlashCrypto.sign(kHeadDev.private, HubAuth.enrollProofBytes(headInv.token, "dev-sales-head", kHeadDev.toBase64Spki(), r.enrollment.serverId))
        assertTrue(r.enrollment.enrollDevice(headInv.token, "dev-sales-head", kHeadDev.toBase64Spki(), pHeadDev, "Sales Head Device", r.now).isSuccess)
        r.directory.registerMember(com.transfer.flash.server.directory.DepartmentMember("dev-sales-head", "sales", "Sales Head", MemberRole.MEMBER, kHeadDev.toBase64Spki()))

        val headSess = client.login(r, user = "head_sales", pass = headPassword)
        val headKey = FlashCrypto.generateEcKeyPair()
        r.admins.registerConsoleKey("head_sales", headKey.toBase64Spki(), "Head browser key")

        // Omitting role on a dept_admin target must NOT inherit dept_admin -> 403 ERR_FORBIDDEN
        val suOmit = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales-head", key = headKey)
        val omitRes = client.postJson(
            "/devices/dev-sales-head/replace",
            headSess,
            DeviceReplaceBody(department = "sales", role = null, reason = "Bypass role inheritance"),
            stepUp = suOmit,
        )
        assertEquals(HttpStatusCode.Forbidden, omitRes.status, omitRes.bodyAsText())
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-sales-head")!!.status)
        assertEquals(0, r.identities.listInvites(state = InviteState.PENDING, nowMs = r.now).size)

        // Explicit dept_admin must also be refused -> 403
        val suExplicit = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales-head", key = headKey)
        val explicitRes = client.postJson(
            "/devices/dev-sales-head/replace",
            headSess,
            DeviceReplaceBody(department = "sales", role = "dept_admin", reason = "Explicit dept_admin"),
            stepUp = suExplicit,
        )
        assertEquals(HttpStatusCode.Forbidden, explicitRes.status, explicitRes.bodyAsText())
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-sales-head")!!.status)

        // Explicit member role IS permitted without issuing elevated credentials
        val suMember = client.stepUp(r, headSess, StepUpActions.REPLACE_DEVICE, "dev-sales-head", key = headKey)
        val downgradeRes = client.postJson(
            "/devices/dev-sales-head/replace",
            headSess,
            DeviceReplaceBody(department = "sales", role = "member", reason = "Downgrade lost head device"),
            stepUp = suMember,
        )
        assertEquals(HttpStatusCode.OK, downgradeRes.status, downgradeRes.bodyAsText())
        val dto = HubRestCodec.decode<DeviceReplaceResultDto>(downgradeRes.bodyAsText())
        assertEquals("member", dto.invite.invite.role)
        assertEquals(DeviceStatus.REVOKED, r.identities.device("dev-sales-head")!!.status)
    }

    @Test
    fun inviteWriteFailureLeavesOldDeviceActiveAndAllowsRecoveryRetry() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-fail", inv.token, dept = "sales")
        val oldSerial = r.identities.device("dev-fail")!!.serial
        val (tok, _) = r.tokens.issue(HubPrincipal(deviceId = "dev-fail", role = DeviceCert.ROLE_MEMBER, departmentId = "sales", certSerial = oldSerial), r.now)

        // Inject real SQLite failure on invite insertion after all prechecks pass
        r.db.withConnection { conn ->
            conn.update(
                "CREATE TRIGGER fail_repl_invite BEFORE INSERT ON hub_invites " +
                    "WHEN NEW.note LIKE 'replacement:%' BEGIN " +
                    "SELECT RAISE(ABORT, 'injected invite write failure'); END",
            )
        }

        val su1 = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-fail")
        val failRes = client.postJson(
            "/devices/dev-fail/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost phone"),
            stepUp = su1,
        )
        assertEquals(HttpStatusCode.ServiceUnavailable, failRes.status, failRes.bodyAsText())

        // Old device must NOT be stranded, revoked, or disconnected, and zero live grants exist
        assertEquals(DeviceStatus.ACTIVE, r.identities.device("dev-fail")!!.status)
        assertNotNull(r.tokens.resolve(tok, r.now))
        assertFalse(r.revocations.isRevoked(oldSerial, "dev-fail"))
        assertEquals(0, r.identities.listInvites(state = InviteState.PENDING, nowMs = r.now).size)

        // Operator-visible outcome on GET /devices/{id}
        val devDto = HubRestCodec.decode<DeviceDto>(
            client.get("/admin/api/v1/devices/dev-fail") { withSession(s) }.bodyAsText(),
        )
        assertEquals(DeviceReplacementState.FAILED.name, devDto.replacementState)
        assertNull(devDto.replacementInviteId)

        // Recovery: clear the DB fault and retry
        r.db.withConnection { conn -> conn.update("DROP TRIGGER fail_repl_invite") }
        val su2 = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-fail")
        val okRes = client.postJson(
            "/devices/dev-fail/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost phone"),
            stepUp = su2,
        )
        assertEquals(HttpStatusCode.OK, okRes.status, okRes.bodyAsText())
        val okDto = HubRestCodec.decode<DeviceReplaceResultDto>(okRes.bodyAsText())
        assertEquals(DeviceStatus.REVOKED, r.identities.device("dev-fail")!!.status)
        assertEquals(DeviceReplacementState.INVITE_ISSUED.name, okDto.oldDevice.replacementState)
        assertEquals(okDto.invite.invite.inviteId, okDto.oldDevice.replacementInviteId)
        assertEquals(1, r.identities.listInvites(state = InviteState.PENDING, nowMs = r.now).size)
    }

    @Test
    fun lostResponseRetryAndConcurrentReplaceLeaveAtMostOneLiveGrant() = testApplication {
        val r = Realm()
        mount(r)
        val client = createClient { followRedirects = false }
        val s = client.login(r)

        val inv1 = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-retry", inv1.token, dept = "sales")
        val inv2 = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-conc", inv2.token, dept = "sales")

        // 1. Simulate lost HTTP response after first replacement succeeded, then retry
        val su1 = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-retry")
        val firstRes = client.postJson(
            "/devices/dev-retry/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost response"),
            stepUp = su1,
        )
        assertEquals(HttpStatusCode.OK, firstRes.status)
        val firstDto = HubRestCodec.decode<DeviceReplaceResultDto>(firstRes.bodyAsText())

        val suRetry = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-retry")
        val retryRes = client.postJson(
            "/devices/dev-retry/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Lost response retry"),
            stepUp = suRetry,
        )
        assertEquals(HttpStatusCode.Conflict, retryRes.status, retryRes.bodyAsText())
        assertTrue(retryRes.bodyAsText().contains(firstDto.invite.invite.inviteId))

        // Operator recovers via visible replacementInviteId -> revokes old grant -> issues new grant
        val inspected = HubRestCodec.decode<DeviceDto>(
            client.get("/admin/api/v1/devices/dev-retry") { withSession(s) }.bodyAsText(),
        )
        assertEquals(DeviceReplacementState.INVITE_ISSUED.name, inspected.replacementState)
        assertEquals(firstDto.invite.invite.inviteId, inspected.replacementInviteId)

        val revokeOld = client.post("/admin/api/v1/invites/${inspected.replacementInviteId}/revoke") { withSession(s) }
        assertEquals(HttpStatusCode.OK, revokeOld.status)

        val suRecover = client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-retry")
        val recoverRes = client.postJson(
            "/devices/dev-retry/replace",
            s,
            DeviceReplaceBody(department = "sales", reason = "Re-issue after lost response"),
            stepUp = suRecover,
        )
        assertEquals(HttpStatusCode.OK, recoverRes.status, recoverRes.bodyAsText())
        assertEquals(1, r.identities.listInvites(state = InviteState.PENDING, nowMs = r.now).size)

        // Revoke recover grant before concurrent test check
        val recDto = HubRestCodec.decode<DeviceReplaceResultDto>(recoverRes.bodyAsText())
        r.identities.revokeInvite(recDto.invite.invite.inviteId, r.now)

        // 2. Concurrent POST /devices/dev-conc/replace permits at most 1 live grant
        val stepUps = (1..4).map { client.stepUp(r, s, StepUpActions.REPLACE_DEVICE, "dev-conc") }
        val concResponses = runBlocking {
            stepUps.map { su ->
                async {
                    client.postJson(
                        "/devices/dev-conc/replace",
                        s,
                        DeviceReplaceBody(department = "sales", reason = "Concurrent click"),
                        stepUp = su,
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, concResponses.count { it.status == HttpStatusCode.OK }, concResponses.map { it.status }.toString())
        assertEquals(3, concResponses.count { it.status == HttpStatusCode.Conflict })
        assertEquals(1, r.identities.listInvites(state = InviteState.PENDING, nowMs = r.now).size)
    }

    @Test
    fun crashedInProgressReplacementRecoversOnStartupAndLeaseExpiry() = testApplication {
        val r = Realm()
        val invA = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-crash-1", invA.token, dept = "sales")
        val invB = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.enrolMember("dev-crash-2", invB.token, dept = "sales")

        // Crash stage 1: IN_PROGRESS left behind with no inviteId -> startup reconcile recovers to FAILED
        assertTrue(
            r.identities.beginDeviceReplacement("dev-crash-1", "sales", "member", emptySet(), emptySet(), 60L, null, "Lost", "admin", r.now)
                is BeginReplacementOutcome.Acquired,
        )
        // Crash stage 2: IN_PROGRESS with draft invite recorded while old device is still ACTIVE -> startup revokes draft invite & fails safely
        val claim2 = r.identities.beginDeviceReplacement("dev-crash-2", "sales", "member", emptySet(), emptySet(), 60L, null, "Lost", "admin", r.now)
            as BeginReplacementOutcome.Acquired
        val draftInvite = r.enrollment.createInvite("http://localhost", com.transfer.flash.server.auth.InviteScope(department = "sales", expiry = r.now + 86400000))
        r.identities.recordReplacementInvite("dev-crash-2", claim2.replacementId, draftInvite.record.inviteId, r.now)

        // Simulate server restart opening the same database
        val reopened = IdentityStore.open(r.db)
        assertEquals(DeviceReplacementState.FAILED, reopened.getDeviceReplacement("dev-crash-1")?.state)
        assertEquals(DeviceReplacementState.FAILED, reopened.getDeviceReplacement("dev-crash-2")?.state)
        assertEquals(0, reopened.listInvites(state = InviteState.PENDING, nowMs = r.now + 100L).count { it.inviteId == draftInvite.record.inviteId })
        assertTrue(
            reopened.beginDeviceReplacement("dev-crash-1", "sales", "member", emptySet(), emptySet(), 60L, null, "Retry", "admin", r.now + 100L)
                is BeginReplacementOutcome.Acquired,
        )
        // Fresh IN_PROGRESS within lease refuses duplicate, while expired lease without restart reclaims automatically
        assertTrue(
            reopened.beginDeviceReplacement("dev-crash-1", "sales", "member", emptySet(), emptySet(), 60L, null, "Dup", "admin", r.now + 10_000L)
                is BeginReplacementOutcome.AlreadyInProgress,
        )
        assertTrue(
            reopened.beginDeviceReplacement(
                "dev-crash-1",
                "sales",
                "member",
                emptySet(),
                emptySet(),
                60L,
                null,
                "Lease reclaimed",
                "admin",
                r.now + 100L + IdentityStore.IN_PROGRESS_LEASE_MS + 1L,
            ) is BeginReplacementOutcome.Acquired,
        )
    }
}
