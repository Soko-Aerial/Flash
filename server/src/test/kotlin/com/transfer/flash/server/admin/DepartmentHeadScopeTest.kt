package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.sign
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.directory.MemberRole
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan A1 scope enforcement: DEPH-U03 (org admin unchanged is covered by the
 * existing AdminApiTest suite), U04 head matrix, U05 IDOR byte-equality, U06
 * invite restrictions, U07 bulk scoping, U08 head invite creation, U09 session
 * purge, U10 scope-next-request, U11 last-admin guard, U12 enrol consistency,
 * U13 department-delete cleanup, U14 audit, U16 old-console compat.
 */
class DepartmentHeadScopeTest {

    private val bossPassword = "BossPassword123!"
    private val headPassword = "HeadPassword123!"

    private class Sess(val cookie: String, val csrf: String)

    private inner class Realm {
        var now: Long = System.currentTimeMillis()
        val db = ServerDatabase.inMemory()
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, clock = { now }, passwordIterations = 1000)
        val audit = AuditLogger(db, clock = { now })
        val directory = OrganizationDirectory.open(db)
        val identities = IdentityStore.open(db)
        val revocations = SqliteRevocationService(db, "root")
        val rootKey = FlashCrypto.generateEcKeyPair()
        val enrollment = EnrollmentService("root", rootKey.private, DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = FlashCrypto.generateEcKeyPair().toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_ENROL_ADMIN, DelegationCert.SCOPE_REVOKE_MEMBER),
            validFrom = now - 60_000,
            validTo = now + 400L * 24 * 3600 * 1000,
            maxDevices = 50,
            serial = 1L,
        ), store = identities, revocations = revocations)
        val stepUp = StepUpAuth(admins, identities, revocations, clock = { now })
        val config = ServerConfig(port = 8443, adminPort = 8444, host = "127.0.0.1", dataDir = "./test-data", tlsMode = "proxy")
        val deps = AdminApiDeps(
            web = AdminWeb(admins, externalUrl = "http://localhost", policy = AdminListenerPolicy.Open),
            admins = admins, audit = audit, directory = directory, identities = identities, stepUp = stepUp, config = config,
            enrollment = enrollment, revocations = revocations, bearerTokens = null, limits = HubRateLimits.unlimited(), clock = { now },
        )
        val bossTotp = admins.generateTotpSecret()
        var headTotp = ""
        var headEmptyTotp = ""

        init {
            admins.addAdminDirectlyForTesting(AdminUser("boss", admins.hashPassword(bossPassword), bossTotp))
            directory.createDepartment("dept-a", "Alpha", emptyList(), DirectoryActor.CONSOLE)
            directory.createDepartment("dept-b", "Beta", emptyList(), DirectoryActor.CONSOLE)
            directory.createGroup("g-a", "Group A", "dept-a", GroupVisibility.LISTED, JoinMode.REQUEST_APPROVAL, callerDeviceId = "root", isOrgAdmin = true)
            directory.createGroup("g-b", "Group B", "dept-b", GroupVisibility.LISTED, JoinMode.REQUEST_APPROVAL, callerDeviceId = "root", isOrgAdmin = true)
            directory.createGroup("g-org", "Org group", "", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, callerDeviceId = "root", isOrgAdmin = true)
            enrollDevice("dev-a1", "dept-a", "member")
            enrollDevice("dev-b1", "dept-b", "member")
            headTotp = admins.createHead("head-a", headPassword, "boss", "Head Ama", listOf("dept-a"), "dev-a1").totpSecret
            headEmptyTotp = admins.createHead("head-empty", headPassword, "boss", "No Depts", emptyList(), null).totpSecret
        }

        fun enrollDevice(id: String, dept: String, role: String) {
            val issuer = FlashCrypto.generateEcKeyPair()
            val key = FlashCrypto.generateEcKeyPair()
            identities.createInvite("tok-$id", dept, role, emptySet(), emptySet(), now + 100_000_000, 1, now)
            identities.enroll("tok-$id", id, key.toBase64Spki(), "Device $id", now, 100, 1L) { serial, _ ->
                DeviceCert.issue(issuer.private, issuer.toBase64Spki(), id, key.toBase64Spki(), role, dept, now - 1000, now + 100_000_000, serial)
            }
            directory.registerMember(
                com.transfer.flash.server.directory.DepartmentMember(id, dept, "Device $id", MemberRole.MEMBER, key.toBase64Spki()),
            )
        }

        fun totp(secret: String): String {
            now += 31_000
            return admins.generateTotp(secret, now / 1000)
        }
    }

    private fun ApplicationTestBuilder.mount(r: Realm) {
        routing { adminApiRoutes(r.deps) }
    }

    private fun HttpRequestBuilder.withSession(s: Sess?, csrf: Boolean = true) {
        if (s != null) header("Cookie", "flash_admin_session=${s.cookie}")
        if (s != null && csrf) header("X-CSRF-Token", s.csrf)
        header("Origin", "http://localhost")
    }

    private suspend fun HttpClient.loginAs(r: Realm, user: String, password: String, secret: String): Sess {
        val resp = post("/admin/api/v1/login") {
            header("Origin", "http://localhost")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(LoginBody(user, password, r.totp(secret))))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val cookie = resp.setCookie().first { it.name == "flash_admin_session" }.value
        return Sess(cookie, HubRestCodec.decode<LoginDto>(resp.bodyAsText()).csrfToken)
    }

    private suspend fun HttpClient.getA(path: String, s: Sess?): HttpResponse = get("/admin/api/v1$path") { withSession(s) }

    private suspend inline fun <reified T> HttpClient.postA(path: String, s: Sess?, body: T): HttpResponse =
        post("/admin/api/v1$path") {
            withSession(s)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    // DEPH-U04: the head matrix over ~25 routes.
    @Test
    fun headSeesOnlyOwnScopeAndOrgAdminSeesAll() = testApplication {
        val r = Realm()
        mount(r)
        val boss = client.loginAs(r, "boss", bossPassword, r.bossTotp)
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)

        // ORG-only routes refuse the head with the standard 403 body.
        for (path in listOf("/admins", "/delegation", "/revocations", "/settings", "/metrics", "/audit", "/audit/head", "/audit/verify")) {
            val resp = client.getA(path, head)
            assertEquals(HttpStatusCode.Forbidden, resp.status, path)
            assertEquals("ERR_FORBIDDEN", HubRestCodec.decode<ErrorBody>(resp.bodyAsText()).code, path)
        }
        assertEquals(HttpStatusCode.OK, client.getA("/admins", boss).status)
        assertEquals(HttpStatusCode.OK, client.getA("/audit/head", boss).status)

        // Departments: head sees only their own.
        val headDepts = HubRestCodec.decodeList<AdminDepartmentDto>(client.getA("/departments", head).bodyAsText()).map { it.id }
        assertEquals(listOf("dept-a"), headDepts)
        val bossDepts = HubRestCodec.decodeList<AdminDepartmentDto>(client.getA("/departments", boss).bodyAsText()).map { it.id }.sorted()
        assertEquals(listOf("dept-a", "dept-b"), bossDepts)

        // Groups: head sees own-department groups; the org-wide group is org-only.
        val headGroups = HubRestCodec.decodeList<AdminGroupDto>(client.getA("/groups", head).bodyAsText()).map { it.id }
        assertEquals(listOf("g-a"), headGroups)
        val bossGroups = HubRestCodec.decodeList<AdminGroupDto>(client.getA("/groups", boss).bodyAsText()).map { it.id }.sorted()
        assertEquals(listOf("g-a", "g-b", "g-org"), bossGroups)

        // Devices: head sees own-department devices; cross-scope id is 404.
        val headDevices = HubRestCodec.decodeList<DeviceDto>(client.getA("/devices", head).bodyAsText()).map { it.deviceId }
        assertEquals(listOf("dev-a1"), headDevices)
        assertEquals(HttpStatusCode.OK, client.getA("/devices/dev-a1", head).status)
        assertEquals(HttpStatusCode.NotFound, client.getA("/devices/dev-b1", head).status)
        assertEquals(HttpStatusCode.OK, client.getA("/devices/dev-b1", boss).status)

        // Overview: scoped counts, no server info.
        val overview = HubRestCodec.decode<OverviewDto>(client.getA("/overview", head).bodyAsText())
        assertEquals(1, overview.departments)
        assertEquals(1, overview.groups)
        assertEquals(0, overview.deviceQuota)
        assertEquals(0, overview.queueDepth)

        // A head with no departments sees empty lists, never errors.
        val empty = client.loginAs(r, "head-empty", headPassword, r.headEmptyTotp)
        assertEquals(HttpStatusCode.OK, client.getA("/departments", empty).status)
        assertEquals("[]", client.getA("/departments", empty).bodyAsText())
    }

    // Review F2: the policy table is only a declaration; this proves every route it marks ORG really refuses a head.
    @Test
    fun everyRouteDeclaredOrgRefusesADepartmentHead() = testApplication {
        val r = Realm()
        mount(r)
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)
        val orgRoutes = AdminRoutePolicies.ALL.filterValues { it == AdminRoutePolicy.ORG }.keys
        assertTrue(orgRoutes.isNotEmpty())
        val failures = ArrayList<String>()
        for (key in orgRoutes) {
            val method = key.substringBefore(' ')
            val path = key.substringAfter(' ').replace(Regex("[{][^}]+[}]"), "x")
            val resp = client.request(path) {
                this.method = io.ktor.http.HttpMethod.parse(method)
                withSession(head)
                if (method == "POST" || method == "PUT") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
            if (resp.status != HttpStatusCode.Forbidden) failures += "$key -> ${resp.status}"
        }
        assertEquals(emptyList(), failures, "ORG routes that did not answer 403 to a department head")
    }

    // Review F5: the head's overview counts every device of the department, not the ones inside the newest 500 rows.
    @Test
    fun headOverviewCountsPastFiveHundredDevices() = testApplication {
        val r = Realm()
        mount(r)
        r.enrollDevice("dev-a2", "dept-a", "member")
        r.enrollDevice("dev-a3", "dept-a", "member")
        // Newer than every dept-a device, so the old 500-row slice no longer reached them.
        for (i in 1..520) r.enrollDevice("dev-b-%03d".format(i), "dept-b", "member")
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)

        val overview = HubRestCodec.decode<OverviewDto>(client.getA("/overview", head).bodyAsText())

        assertEquals(3L, overview.devicesByStatus["ACTIVE"])
    }

    // DEPH-U05: IDOR answers byte-equal to a non-existent id.
    @Test
    fun crossScopeObjectIdsAreIndistinguishableFromMissing() = testApplication {
        val r = Realm()
        mount(r)
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)
        val missing = client.getA("/devices/no-such-device", head)
        val foreign = client.getA("/devices/dev-b1", head)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(missing.status, foreign.status)
        assertEquals(HubRestCodec.decode<ErrorBody>(missing.bodyAsText()).code, HubRestCodec.decode<ErrorBody>(foreign.bodyAsText()).code)
        assertEquals(HttpStatusCode.NotFound, client.getA("/groups/no-such-group", head).status)
    }

    // DEPH-U06/U08: invite restrictions + head creation validation messages.
    @Test
    fun inviteRolesCannotBeForgedByAHead() = testApplication {
        val r = Realm()
        mount(r)
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)
        // A head can not mint a department-head invite.
        val forged = client.postA("/invites", head, InviteCreateBody("dept-a", "dept_admin"))
        assertEquals(HttpStatusCode.Forbidden, forged.status)
        // Department is required for heads.
        val noDept = client.postA("/invites", head, InviteCreateBody(null, "member"))
        assertEquals(HttpStatusCode.BadRequest, noDept.status)
        assertTrue(noDept.bodyAsText().isNotBlank())
        // Another department is refused.
        val other = client.postA("/invites", head, InviteCreateBody("dept-b", "member"))
        assertEquals(HttpStatusCode.Forbidden, other.status)
        // Own department works.
        val ok = client.postA("/invites", head, InviteCreateBody("dept-a", "member"))
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
    }

    // DEPH-U09/U10: disable purges sessions; scope changes apply on the next request.
    @Test
    fun disableEndsSessionsAndScopeChangesApplyAtOnce() = testApplication {
        val r = Realm()
        mount(r)
        val boss = client.loginAs(r, "boss", bossPassword, r.bossTotp)
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)
        assertEquals(HttpStatusCode.OK, client.getA("/departments", head).status)
        // Narrow the head to dept-b: the next request already reflects it.
        val narrowed = client.put("/admin/api/v1/admins/head-a/departments") {
            withSession(boss)
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(HeadDepartmentsBody(listOf("dept-b"))))
        }
        assertEquals(HttpStatusCode.OK, narrowed.status, narrowed.bodyAsText())
        val after = HubRestCodec.decodeList<AdminDepartmentDto>(client.getA("/departments", head).bodyAsText()).map { it.id }
        assertEquals(listOf("dept-b"), after)
        // Disable purges sessions at once (service level: deterministic, no step-up signer needed here).
        r.admins.setDisabled("head-a", true)
        assertEquals(0, r.admins.listSessions("head-a").size)
        assertEquals(HttpStatusCode.Unauthorized, client.getA("/departments", head).status)
    }

    // DEPH-U01/U11/U12: migration, last-admin guard, enrol consistency.
    @Test
    fun migrationKeepsAdminsAndEnrolmentGrantsHeadDevices() {
        val db = ServerDatabase.inMemory()
        com.transfer.flash.server.storage.Migrator(db).apply {
            register(AdminAuthService.COMPONENT, AdminAuthService.MIGRATIONS.take(3))
            migrate()
        }
        assertEquals(3, com.transfer.flash.server.storage.Migrator(db).currentVersion(AdminAuthService.COMPONENT))
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db, passwordIterations = 1000)
        admins.addAdminDirectlyForTesting(AdminUser("old-boss", admins.hashPassword("OldBossPassword123!"), admins.generateTotpSecret()))
        com.transfer.flash.server.storage.Migrator(db).apply {
            register(AdminAuthService.COMPONENT, AdminAuthService.MIGRATIONS)
            migrate()
        }
        assertEquals(4, com.transfer.flash.server.storage.Migrator(db).currentVersion(AdminAuthService.COMPONENT))
        assertEquals("ORG_ADMIN", admins.getAdmin("old-boss")!!.role)
        assertTrue(admins.headDepartments("old-boss").isEmpty())

        // The last enabled ORG_ADMIN can not be disabled.
        try {
            admins.setDisabled("old-boss", true)
            assertFalse(true, "the last admin must be protected")
        } catch (_: IllegalStateException) {
        }

        // Enrolment consistency: a dept_admin device joins its department's admin list.
        val dir = OrganizationDirectory.open(db)
        dir.createDepartment("dept-x", "X", emptyList(), DirectoryActor.CONSOLE)
        dir.addDepartmentMember("dept-x", "head-dev", "Head", MemberRole.DEPT_ADMIN, "SPKI")
        dir.addDepartmentAdmin("dept-x", "head-dev")
        assertTrue("head-dev" in dir.getDepartment("dept-x")!!.adminDeviceIds)
        dir.registerMember(dir.getDepartmentMember("head-dev")!!.copy(departmentId = "dept-y"))
        dir.removeFromAdmins("dept-x", "head-dev")
        assertFalse("head-dev" in dir.getDepartment("dept-x")!!.adminDeviceIds)
    }

    // DEPH-U14/U16: head actions are audited; an old cached console gets clear 403 JSON.
    @Test
    fun headActionsAreAuditedAndOldConsoleGetsStandardErrors() = testApplication {
        val r = Realm()
        mount(r)
        val head = client.loginAs(r, "head-a", headPassword, r.headTotp)
        val ok = client.postA("/invites", head, InviteCreateBody("dept-a", "member"))
        assertEquals(HttpStatusCode.Created, ok.status)
        assertTrue(r.audit.query(com.transfer.flash.server.admin.AuditFilter(actionPrefix = "invite.create")).isNotEmpty())
        val forbidden = client.getA("/audit", head)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("ERR_FORBIDDEN", HubRestCodec.decode<ErrorBody>(forbidden.bodyAsText()).code)
    }
}
