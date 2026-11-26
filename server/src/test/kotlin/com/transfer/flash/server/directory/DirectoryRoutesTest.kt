package com.transfer.flash.server.directory

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.CreateDepartmentBody
import com.transfer.flash.core.hubprotocol.rest.CreateGroupBody
import com.transfer.flash.core.hubprotocol.rest.CreateGroupWithMembersBody
import com.transfer.flash.core.hubprotocol.rest.CreateGroupJoinLinkBody
import com.transfer.flash.core.hubprotocol.rest.GroupJoinLinkTarget
import com.transfer.flash.core.hubprotocol.rest.GroupJoinLinkToken
import com.transfer.flash.server.auth.SecretBox
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
import com.transfer.flash.core.hubprotocol.rest.UpdateGroupBody
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
import kotlin.test.assertNull
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

    private fun ApplicationTestBuilder.mount(w: World, joinLinks: HubGroupJoinLinks? = null) {
        routing {
            directoryRoutes(w.dir, BearerPrincipalResolver(w.tokens), isOnline = { it in w.online }, audit = AuditSink { actor, action, details -> w.audit += "$actor|$action|$details" }, groupJoinLinks = joinLinks)
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
    fun atomicGroupCreateRejectsAnInvalidRosterAndReplaysTheSameRequest() = testApplication {
        val w = World()
        w.dir.addDepartmentMember("dept-gh", "dadmin-01", "Admin")
        mount(w)
        val admin = w.token("dadmin-01", DeviceCert.ROLE_ADMIN)
        val path = "/api/v1/groups/create-with-members"
        val request = CreateGroupWithMembersBody("new-group", "Project", "dept-gh", setOf("member-01"))
        assertEquals(HttpStatusCode.BadRequest, client.postAs(path, admin, request.copy(memberDeviceIds = setOf("missing"))).status)
        assertNull(w.dir.getGroup(request.id))
        assertEquals(HttpStatusCode.Created, client.postAs(path, admin, request).status)
        val original = assertNotNull(w.dir.getGroup(request.id))
        assertEquals(setOf("dadmin-01", "member-01"), original.memberDeviceIds.toSet())
        assertEquals(HttpStatusCode.Created, client.postAs(path, admin, request).status)
        assertEquals(original.version, w.dir.getGroup(request.id)?.version)
        assertEquals(HttpStatusCode.Conflict, client.postAs(path, admin, request.copy(name = "Other")).status)
        assertEquals(original, w.dir.getGroup(request.id))
    }

    @Test
    fun groupJoinLinkRequiresEnrollmentAndPreservesApprovalPolicy() = testApplication {
        val w = World()
        var now = 1_000L
        mount(w, HubGroupJoinLinks(SecretBox.ofKey(ByteArray(32) { 7 }), clock = { now }))
        val issue = "/api/v1/groups/join-links"
        val resolve = "$issue/resolve"
        val admin = w.token("dadmin-01", DeviceCert.ROLE_ADMIN)
        val member = w.token("member-01")
        val body = CreateGroupJoinLinkBody("grp-approval")
        assertEquals(HttpStatusCode.NotFound, client.postAs(issue, member, body).status)
        val issued = client.postAs(issue, admin, body)
        assertEquals(HttpStatusCode.Created, issued.status)
        val token = HubRestCodec.decode<GroupJoinLinkToken>(issued.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.postAs(resolve, null, token).status)
        assertEquals(HttpStatusCode.NotFound, client.postAs(resolve, member, GroupJoinLinkToken("invalid")).status)
        val resolved = client.postAs(resolve, member, token)
        assertEquals(HttpStatusCode.OK, resolved.status)
        val target = HubRestCodec.decode<GroupJoinLinkTarget>(resolved.bodyAsText())
        val request = client.postAs("/api/v1/groups/join-request", member, JoinRequestBody(target.groupId))
        assertEquals(HttpStatusCode.Accepted, request.status)
        assertFalse(w.dir.getGroup(target.groupId)!!.hasMember("member-01"))
        now += 24 * 60 * 60 * 1000L
        assertEquals(HttpStatusCode.NotFound, client.postAs(resolve, member, token).status)
    }

    @Test
    fun groupLinkSurvivesMultipleJoinsAndIsInvalidatedByPolicyChange() = testApplication {
        val w = World()
        mount(w, HubGroupJoinLinks(SecretBox.ofKey(ByteArray(32) { 7 }), clock = { 1_000L }))
        val issuer = w.token("dadmin-01", DeviceCert.ROLE_ADMIN)
        val path = "/api/v1/groups/join-links"
        val groupId = "grp-public"
        val issued = client.postAs(path, issuer, CreateGroupJoinLinkBody(groupId))
        assertEquals(HttpStatusCode.Created, issued.status)
        val token = HubRestCodec.decode<GroupJoinLinkToken>(issued.bodyAsText())

        for (id in listOf("member-01", "member-02")) {
            val member = w.token(id)
            assertEquals(HttpStatusCode.OK, client.postAs("$path/resolve", member, token).status)
            assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-request", member, JoinRequestBody(groupId)).status)
        }

        w.dir.updateGroup(groupId, null, null, JoinMode.ADMIN_ONLY, DirectoryActor.CONSOLE)
        assertEquals(HttpStatusCode.NotFound, client.postAs("$path/resolve", issuer, token).status)
        w.dir.updateGroup(groupId, null, null, JoinMode.OPEN_IN_SCOPE, DirectoryActor.CONSOLE)
        assertEquals(HttpStatusCode.NotFound, client.postAs("$path/resolve", issuer, token).status)
    }

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
        // Plan A2 (option C, §3.3.4): another NON-HIDDEN department's roster is listable org-wide, with keys (D9).
        val cross = client.getAs("/api/v1/directory/members?departmentId=dept-ng", w.token("member-01"))
        assertEquals(HttpStatusCode.OK, cross.status)
        assertTrue(HubRestCodec.decodeList<MemberDto>(cross.bodyAsText()).any { it.deviceId == "outsider-01" })
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

    @Test
    fun joinLinkSurvivesDataKeyRotationAndSupportsPerLinkRevocationAndMaxUses() = testApplication {
        val w = World()
        val now = 1_000L
        val oldDataKey = com.transfer.flash.server.auth.SecretBox.ofKey(ByteArray(32) { 1 })
        val independentLinkKey = com.transfer.flash.server.auth.SecretBox.ofKey(ByteArray(32) { 7 })
        val links = HubGroupJoinLinks(
            secrets = independentLinkKey,
            clock = { now },
            fallbackSecrets = listOf(oldDataKey),
            database = w.db,
        )
        mount(w, links)
        val admin = w.token("dadmin-01", DeviceCert.ROLE_DEPT_ADMIN)
        val member1 = w.token("member-01")
        val member2 = w.token("member-02")

        // Legacy v2 token sealed with old data.key still resolves via fallbackSecrets.
        val group = w.dir.getGroup("grp-public")!!
        val legacyV2Payload = "${group.id}\n${group.joinLinkRevision}\n${now + 60_000L}".encodeToByteArray()
        val legacyV2Token = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(oldDataKey.sealBytes(legacyV2Payload, "flash-hub:group-join-link:v2"))
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-links/resolve", member1, GroupJoinLinkToken(legacyV2Token)).status)

        // Independent v3 tokens: issue two reusable tokens for grp-public; revoking one leaves the other active.
        val tokA = HubRestCodec.decode<GroupJoinLinkToken>(client.postAs("/api/v1/groups/join-links", admin, CreateGroupJoinLinkBody("grp-public")).bodyAsText())
        val tokB = HubRestCodec.decode<GroupJoinLinkToken>(client.postAs("/api/v1/groups/join-links", admin, CreateGroupJoinLinkBody("grp-public")).bodyAsText())
        val linkIdA = links.linkIdOf(tokA.token)!!

        // Simulate data.key rotation (drop oldDataKey from fallbackSecrets); independent v3 tokens still work!
        val rotated = HubGroupJoinLinks(
            secrets = independentLinkKey,
            clock = { now },
            fallbackSecrets = emptyList(),
            database = w.db,
        )
        assertNotNull(rotated.resolve(tokA.token, w.dir))
        assertNotNull(rotated.resolve(tokB.token, w.dir))

        // Revoke only link A via PUT /api/v1/groups/{id} -> tokA fails, tokB stays valid for multi-join.
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/groups/grp-public") {
            header("Authorization", "Bearer $admin"); contentType(ContentType.Application.Json)
            setBody(HubRestCodec.encode(UpdateGroupBody(revokeLinkId = linkIdA)))
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.postAs("/api/v1/groups/join-links/resolve", member1, tokA).status)
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-links/resolve", member1, tokB).status)
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-links/resolve", member2, tokB).status)

        // Single-use link (maxUses = 1) rejects invalid maxUses <= 0 and stops after 1 resolve.
        assertEquals(HttpStatusCode.BadRequest, client.postAs("/api/v1/groups/join-links", admin, CreateGroupJoinLinkBody("grp-public", maxUses = 0)).status)
        val singleUse = HubRestCodec.decode<GroupJoinLinkToken>(
            client.postAs("/api/v1/groups/join-links", admin, CreateGroupJoinLinkBody("grp-public", maxUses = 1)).bodyAsText(),
        )
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-links/resolve", member1, singleUse).status)
        assertEquals(HttpStatusCode.NotFound, client.postAs("/api/v1/groups/join-links/resolve", member2, singleUse).status)
    }

    @Test
    fun joinLinkRevocationIsScopedToAuthorisedGroupAndDeniedResolvePreservesUseCount() = testApplication {
        val w = World()
        val now = 1_000L
        val dbLinks = HubGroupJoinLinks(
            secrets = SecretBox.ofKey(ByteArray(32) { 11 }),
            clock = { now },
            database = w.db,
        )
        mount(w, dbLinks)

        val ghAdmin = w.token("dadmin-01", DeviceCert.ROLE_DEPT_ADMIN, dept = "dept-gh")
        val orgAdmin = w.token("root-admin", DeviceCert.ROLE_ADMIN, dept = null)
        val ghMember1 = w.token("member-01", dept = "dept-gh")
        val ghMember2 = w.token("member-02", dept = "dept-gh")
        val ngOutsider = w.token("outsider-01", dept = "dept-ng")

        val ghToken = HubRestCodec.decode<GroupJoinLinkToken>(
            client.postAs("/api/v1/groups/join-links", ghAdmin, CreateGroupJoinLinkBody("grp-public", maxUses = 1)).bodyAsText(),
        )
        val orgToken = HubRestCodec.decode<GroupJoinLinkToken>(
            client.postAs("/api/v1/groups/join-links", orgAdmin, CreateGroupJoinLinkBody("grp-org", maxUses = 1)).bodyAsText(),
        )
        val approvalToken = HubRestCodec.decode<GroupJoinLinkToken>(
            client.postAs("/api/v1/groups/join-links", ghAdmin, CreateGroupJoinLinkBody("grp-approval")).bodyAsText(),
        )
        val ghLinkId = dbLinks.linkIdOf(ghToken.token)!!
        val orgLinkId = dbLinks.linkIdOf(orgToken.token)!!
        val approvalLinkId = dbLinks.linkIdOf(approvalToken.token)!!

        // 1. Denied resolution by an out-of-scope outsider returns 404 and does NOT consume the single use.
        assertEquals(HttpStatusCode.NotFound, client.postAs("/api/v1/groups/join-links/resolve", ngOutsider, ghToken).status)
        assertEquals(0, dbLinks.listLinks("grp-public").single { it.linkId == ghLinkId }.useCount)

        // 2. Cross-group revocation via an authorised group fails 404 without mutating either group or the target link.
        assertEquals(
            HttpStatusCode.NotFound,
            client.put("/api/v1/groups/grp-public") {
                header("Authorization", "Bearer $ghAdmin"); contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(UpdateGroupBody(name = "ShouldNotRename", revokeLinkId = orgLinkId)))
            }.status,
        )
        assertEquals("Public", w.dir.getGroup("grp-public")!!.name)
        assertNull(dbLinks.listLinks("grp-org").single { it.linkId == orgLinkId }.revokedAt)
        assertEquals(0, dbLinks.listLinks("grp-org").single { it.linkId == orgLinkId }.useCount)

        // Even a second group the same caller manages cannot revoke a link belonging to another group.
        assertEquals(
            HttpStatusCode.NotFound,
            client.put("/api/v1/groups/grp-public") {
                header("Authorization", "Bearer $ghAdmin"); contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(UpdateGroupBody(revokeLinkId = approvalLinkId)))
            }.status,
        )
        assertNull(dbLinks.listLinks("grp-approval").single { it.linkId == approvalLinkId }.revokedAt)

        // 3. Direct store revocation also enforces group binding on both persistent and in-memory implementations.
        assertFalse(dbLinks.revokeLink("grp-public", orgLinkId, "dadmin-01"))
        assertFalse(dbLinks.revokeToken("grp-public", orgToken.token, "dadmin-01"))
        assertNull(dbLinks.listLinks("grp-org").single { it.linkId == orgLinkId }.revokedAt)

        val memLinks = HubGroupJoinLinks(SecretBox.ofKey(ByteArray(32) { 13 }), clock = { now })
        val memOrgToken = memLinks.issue(w.dir.getGroup("grp-org")!!, issuerDeviceId = "root-admin", maxUses = 1)
        val memOrgLinkId = memLinks.linkIdOf(memOrgToken)!!
        assertFalse(memLinks.belongsToGroup("grp-public", memOrgLinkId))
        assertFalse(memLinks.revokeLink("grp-public", memOrgLinkId, "dadmin-01"))
        assertFalse(memLinks.revokeToken("grp-public", memOrgToken, "dadmin-01"))
        assertNull(memLinks.resolve(memOrgToken, w.dir) { false })
        assertEquals(0, memLinks.listLinks("grp-org").single { it.linkId == memOrgLinkId }.useCount)
        assertNotNull(memLinks.resolve(memOrgToken, w.dir) { true })

        // 4. Authorised member still consumes the preserved single use on grp-public; second member then gets 404.
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-links/resolve", ghMember1, ghToken).status)
        assertEquals(1, dbLinks.listLinks("grp-public").single { it.linkId == ghLinkId }.useCount)
        assertEquals(HttpStatusCode.NotFound, client.postAs("/api/v1/groups/join-links/resolve", ghMember2, ghToken).status)

        // 5. Authorised caller can revoke grp-approval's own link (and retry idempotently), while grp-org stays active.
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/v1/groups/grp-approval") {
                header("Authorization", "Bearer $ghAdmin"); contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(UpdateGroupBody(revokeLinkId = approvalLinkId)))
            }.status,
        )
        assertNotNull(dbLinks.listLinks("grp-approval").single { it.linkId == approvalLinkId }.revokedAt)
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/v1/groups/grp-approval") {
                header("Authorization", "Bearer $ghAdmin"); contentType(ContentType.Application.Json)
                setBody(HubRestCodec.encode(UpdateGroupBody(revokeLinkId = approvalLinkId)))
            }.status,
        )
        assertEquals(1, w.audit.count { it == "dadmin-01|directory.group.join_link.revoke|group=grp-approval link=$approvalLinkId" })
        assertEquals(HttpStatusCode.NotFound, client.postAs("/api/v1/groups/join-links/resolve", ghMember1, approvalToken).status)
        assertEquals(HttpStatusCode.OK, client.postAs("/api/v1/groups/join-links/resolve", ngOutsider, orgToken).status)
    }
}
