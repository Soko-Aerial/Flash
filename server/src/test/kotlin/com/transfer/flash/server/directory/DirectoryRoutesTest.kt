package com.transfer.flash.server.directory

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.CreateDepartmentBody
import com.transfer.flash.core.hubprotocol.rest.CreateGroupBody
import com.transfer.flash.core.hubprotocol.rest.DepartmentDto
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.GroupDto
import com.transfer.flash.core.hubprotocol.rest.GroupMemberBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.JoinDecisionBody
import com.transfer.flash.core.hubprotocol.rest.JoinRequestBody
import com.transfer.flash.core.hubprotocol.rest.JoinRequestDto
import com.transfer.flash.core.hubprotocol.rest.JoinResponse
import com.transfer.flash.core.hubprotocol.rest.MeDto
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SEC-03, REL-10: the directory trusts only the authenticated principal. */
class DirectoryRoutesTest {

    private class World {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        val tokens = BearerTokenStore()
        val audit = mutableListOf<String>()
        val online = mutableSetOf<String>()

        fun token(deviceId: String, role: String = DeviceCert.ROLE_MEMBER, dept: String? = "dept-gh"): String =
            tokens.issue(HubPrincipal(deviceId, role, dept, 5L)).first

        init {
            dir.createDepartment("dept-gh", "Ghana", listOf("dadmin-01"), isOrgAdmin = true)
            dir.createDepartment("dept-ng", "Nigeria", emptyList(), isOrgAdmin = true)
            dir.addDepartmentMember("dept-gh", "member-01", "Sergeant Mensah", subjectPublicKey = "SPKI-01")
            dir.addDepartmentMember("dept-gh", "member-02", "Corporal Owusu")
            dir.addDepartmentMember("dept-ng", "outsider-01", "Chief Obi")
            dir.createGroup("grp-public", "Public", "dept-gh", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, callerDeviceId = "dadmin-01", isOrgAdmin = true)
            dir.createGroup("grp-secret", "Secret", "dept-gh", GroupVisibility.HIDDEN, JoinMode.ADMIN_ONLY, callerDeviceId = "dadmin-01", isOrgAdmin = true)
            dir.createGroup("grp-approval", "Approval", "dept-gh", GroupVisibility.LISTED, JoinMode.REQUEST_APPROVAL, callerDeviceId = "dadmin-01", isOrgAdmin = true)
            dir.createGroup("grp-org", "Whole organisation", "", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, callerDeviceId = "root-admin", isOrgAdmin = true)
        }
    }

    private fun ApplicationTestBuilder.mount(w: World) {
        routing {
            directoryRoutes(w.dir, BearerPrincipalResolver(w.tokens), isOnline = { it in w.online }, audit = AuditSink { actor, action, details -> w.audit += "$actor|$action|$details" })
        }
    }

    private suspend fun HttpClient.getAs(path: String, token: String?): HttpResponse =
        get(path) { if (token != null) header("Authorization", "Bearer $token") }

    private suspend inline fun <reified T> HttpClient.postAs(path: String, token: String?, body: T): HttpResponse =
        post(path) {
            if (token != null) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(body))
        }

    private fun groupIds(r: HttpResponse, text: String) = HubRestCodec.decodeList<GroupDto>(text).map { it.id }.sorted()

    @Test
    fun everyDirectoryRouteRefusesAnUnauthenticatedCaller() = testApplication {
        val w = World()
        mount(w)
        for (path in listOf("/api/v1/directory/me", "/api/v1/directory/groups", "/api/v1/directory/members?departmentId=dept-gh", "/api/v1/directory/departments", "/api/v1/groups/join-requests?groupId=grp-public")) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.Unauthorized, r.status, path)
            assertEquals("ERR_AUTH", HubRestCodec.decode<ErrorBody>(r.bodyAsText()).code)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/departments", null, CreateDepartmentBody("d", "n")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/groups", null, CreateGroupBody("g", "n")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postAs("/api/v1/groups/join-request", null, JoinRequestBody("grp-public")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/groups/grp-public").status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/directory/groups", "not-a-token").status)
    }

    @Test
    fun theLegacyHeaderAndQueryIdentityIsIgnored() = testApplication {
        val w = World()
        mount(w)
        // Claiming to be the department admin through the old inputs gets nothing.
        val r = client.get("/api/v1/directory/groups?deviceId=dadmin-01&department=dept-gh") { header("X-Device-Id", "dadmin-01") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val asMember = client.getAs("/api/v1/directory/groups?deviceId=dadmin-01&department=dept-ng", w.token("member-01"))
        val ids = groupIds(asMember, asMember.bodyAsText())
        assertFalse("grp-secret" in ids)
        assertTrue("grp-public" in ids)
    }

    @Test
    fun hiddenGroupsExistOnlyForTheirMembersAndNeverTellAStrangerApartFromAMissingOne() = testApplication {
        val w = World()
        mount(w)
        val member = w.token("member-01")
        assertFalse(client.getAs("/api/v1/directory/groups", member).bodyAsText().contains("grp-secret"))
        val strangerHidden = client.postAs("/api/v1/groups/members", member, GroupMemberBody("grp-secret", "member-01"))
        val missing = client.postAs("/api/v1/groups/members", member, GroupMemberBody("grp-does-not-exist", "member-01"))
        assertEquals(HttpStatusCode.NotFound, strangerHidden.status)
        assertEquals(strangerHidden.status, missing.status)
        assertEquals(strangerHidden.bodyAsText().replace(Regex("\"correlationId\":\"[^\"]*\""), ""), missing.bodyAsText().replace(Regex("\"correlationId\":\"[^\"]*\""), ""))
        assertEquals(HttpStatusCode.NotFound, client.getAs("/api/v1/directory/members?groupId=grp-secret", member).status)
        assertEquals(HttpStatusCode.NotFound, client.put("/api/v1/groups/grp-secret") {
            header("Authorization", "Bearer $member"); contentType(ContentType.Application.Json); setBody("""{"name":"x"}""")
        }.status)
        // The department admin sees it.
        val admin = w.token("dadmin-01", DeviceCert.ROLE_DEPT_ADMIN)
        assertTrue(client.getAs("/api/v1/directory/groups", admin).bodyAsText().contains("grp-secret"))
    }

    @Test
    fun aDepartmentlessMemberSeesTheOrganisationWideOpenGroups() = testApplication {
        val w = World()
        mount(w)
        val none = w.token("floating-01", dept = null)
        val ids = groupIds(client.getAs("/api/v1/directory/groups", none), client.getAs("/api/v1/directory/groups", none).bodyAsText())
        assertEquals(listOf("grp-org"), ids)
    }

    @Test
    fun callerIdentityComesFromTheCertificateNotFromTheBody() = testApplication {
        val w = World()
        mount(w)
        w.online += "member-02"
        val me = HubRestCodec.decode<MeDto>(client.getAs("/api/v1/directory/me", w.token("member-01")).bodyAsText())
        assertEquals("member-01", me.deviceId)
        assertEquals("MEMBER", me.role)
        assertEquals("dept-gh", me.departmentId)
        assertEquals("Sergeant Mensah", me.displayName)

        // A join request names only the group: the device is the authenticated one.
        val joined = HubRestCodec.decode<JoinResponse>(client.postAs("/api/v1/groups/join-request", w.token("member-02"), JoinRequestBody("grp-public")).bodyAsText())
        assertEquals("JOINED", joined.status)
        assertTrue("member-02" in w.dir.getGroup("grp-public")!!.memberDeviceIds)
        assertTrue(w.audit.any { it.startsWith("member-02|directory.group.join|") })
    }

    @Test
    fun membersCarryTheirIdentityKeyAndLivePresence() = testApplication {
        val w = World()
        mount(w)
        w.online += "member-02"
        val members = HubRestCodec.decodeList<MemberDto>(client.getAs("/api/v1/directory/members", w.token("member-01")).bodyAsText())
        val byId = members.associateBy { it.deviceId }
        assertEquals("SPKI-01", byId["member-01"]!!.subjectPublicKey)
        assertFalse(byId["member-01"]!!.online)
        assertTrue(byId["member-02"]!!.online)
        // Another department's roster is refused.
        assertEquals(HttpStatusCode.Forbidden, client.getAs("/api/v1/directory/members?departmentId=dept-ng", w.token("member-01")).status)
        // No department and no group: a fixed bad-request sentence.
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/directory/members", w.token("floating-01", dept = null)).status)
    }

    @Test
    fun creatingAnExistingIdIsAConflictAndNeverOverwrites() = testApplication {
        val w = World()
        mount(w)
        val admin = w.token("root-admin", DeviceCert.ROLE_ADMIN, dept = null)
        val dup = client.postAs("/api/v1/departments", admin, CreateDepartmentBody("dept-gh", "Hijacked"))
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals("Ghana", w.dir.getDepartment("dept-gh")!!.name)
        val dupGroup = client.postAs("/api/v1/groups", admin, CreateGroupBody("grp-public", "Hijacked", "dept-gh"))
        assertEquals(HttpStatusCode.Conflict, dupGroup.status)
        assertEquals("Public", w.dir.getGroup("grp-public")!!.name)
        val created = client.postAs("/api/v1/departments", admin, CreateDepartmentBody("dept-new", "New"))
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("dept-new", HubRestCodec.decode<DepartmentDto>(created.bodyAsText()).id)
    }

    @Test
    fun onlyAnOrganisationAdminMayCreateDepartmentsAndOrganisationWideGroups() = testApplication {
        val w = World()
        mount(w)
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/departments", w.token("member-01"), CreateDepartmentBody("dept-x", "X")).status)
        val deptAdmin = w.token("dadmin-01", DeviceCert.ROLE_DEPT_ADMIN)
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/departments", deptAdmin, CreateDepartmentBody("dept-x", "X")).status)
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/groups", deptAdmin, CreateGroupBody("grp-all", "All", departmentId = "")).status)
        assertEquals(HttpStatusCode.Created, client.postAs("/api/v1/groups", deptAdmin, CreateGroupBody("grp-mine", "Mine", departmentId = "dept-gh")).status)
        // ...but not in someone else's department.
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/groups", deptAdmin, CreateGroupBody("grp-theirs", "Theirs", departmentId = "dept-ng")).status)
    }

    @Test
    fun approvalWorksOnceAndOnlyForPendingRequests() = testApplication {
        val w = World()
        mount(w)
        val pending = client.postAs("/api/v1/groups/join-request", w.token("member-01"), JoinRequestBody("grp-approval", "please"))
        assertEquals(HttpStatusCode.Accepted, pending.status)
        val requestId = HubRestCodec.decode<JoinResponse>(pending.bodyAsText()).requestId!!
        val admin = w.token("dadmin-01", DeviceCert.ROLE_DEPT_ADMIN)
        // A plain member can not read or decide.
        assertEquals(HttpStatusCode.Forbidden, client.getAs("/api/v1/groups/join-requests?groupId=grp-approval", w.token("member-02")).status)
        assertEquals(HttpStatusCode.Forbidden, client.postAs("/api/v1/groups/join-requests/decision", w.token("member-02"), JoinDecisionBody(requestId, true)).status)
        val listed = HubRestCodec.decodeList<JoinRequestDto>(client.getAs("/api/v1/groups/join-requests?groupId=grp-approval", admin).bodyAsText())
        assertEquals(listOf(requestId), listed.map { it.requestId })
        assertEquals("Sergeant Mensah", listed.single().displayName)

        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-requests/decision", admin, JoinDecisionBody(requestId, true)).status)
        assertTrue("member-01" in w.dir.getGroup("grp-approval")!!.memberDeviceIds)
        // A second decision (approve or reject) on a decided request is a conflict and changes nothing.
        assertEquals(HttpStatusCode.Conflict, client.postAs("/api/v1/groups/join-requests/decision", admin, JoinDecisionBody(requestId, false)).status)
        assertEquals(HttpStatusCode.Conflict, client.postAs("/api/v1/groups/join-requests/decision", admin, JoinDecisionBody(requestId, true)).status)
        assertTrue("member-01" in w.dir.getGroup("grp-approval")!!.memberDeviceIds)
    }

    @Test
    fun adminOnlyGroupsRefuseSelfJoinAndAHiddenGroupRefusesStrangers() = testApplication {
        val w = World()
        mount(w)
        val r = client.postAs("/api/v1/groups/join-request", w.token("member-01"), JoinRequestBody("grp-secret"))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("REJECTED", HubRestCodec.decode<JoinResponse>(r.bodyAsText()).status)
        assertFalse("member-01" in w.dir.getGroup("grp-secret")!!.memberDeviceIds)
    }

    @Test
    fun oversizedAndMalformedBodiesAreRefusedWithFixedSentences() = testApplication {
        val w = World()
        mount(w)
        val admin = w.token("root-admin", DeviceCert.ROLE_ADMIN, dept = null)
        val big = client.post("/api/v1/departments") {
            header("Authorization", "Bearer $admin"); contentType(ContentType.Application.Json)
            setBody("""{"id":"d","name":"${"x".repeat(70_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        val bad = client.post("/api/v1/departments") {
            header("Authorization", "Bearer $admin"); contentType(ContentType.Application.Json); setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertFalse(bad.bodyAsText().contains("Unexpected"), "no parser text leaks")
        val badId = client.postAs("/api/v1/departments", admin, CreateDepartmentBody("../etc", "x"))
        assertEquals(HttpStatusCode.BadRequest, badId.status)
    }

    @Test
    fun theDirectorySurvivesARestart() = testApplication {
        val w = World()
        mount(w)
        val reopened = OrganizationDirectory.open(w.db)
        assertEquals(setOf("dept-gh", "dept-ng"), reopened.listDepartments().map { it.id }.toSet())
        assertEquals(setOf("grp-public", "grp-secret", "grp-approval", "grp-org"), reopened.listGroups().map { it.id }.toSet())
        assertNotNull(reopened.getDepartmentMember("member-01"))
        assertEquals("SPKI-01", reopened.getDepartmentMember("member-01")!!.subjectPublicKey)
    }

    @Test
    fun deletingAGroupNeedsAManagerAndIsAudited() = testApplication {
        val w = World()
        mount(w)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/groups/grp-public") { header("Authorization", "Bearer ${w.token("member-01")}") }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/groups/grp-public") { header("Authorization", "Bearer ${w.token("dadmin-01", DeviceCert.ROLE_DEPT_ADMIN)}") }.status)
        assertEquals(null, w.dir.getGroup("grp-public"))
        assertTrue(w.audit.any { it == "dadmin-01|directory.group.delete|id=grp-public" })
    }
}
