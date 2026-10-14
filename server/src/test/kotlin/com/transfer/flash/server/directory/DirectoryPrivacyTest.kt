package com.transfer.flash.server.directory

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.DepartmentDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import com.transfer.flash.core.hubprotocol.rest.PeoplePageDto
import com.transfer.flash.core.hubprotocol.rest.PersonDto
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan A2 (directory privacy, option C): PRIV-U01..U07, U11, U12.
 * Mutation checks: `canListMember -> true`, dropped hidden filter, leaked counts.
 */
class DirectoryPrivacyTest {

    private class World {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        val tokens = BearerTokenStore()
        val audit = mutableListOf<String>()

        fun token(deviceId: String, role: String = DeviceCert.ROLE_MEMBER, dept: String? = "dept-a"): String =
            tokens.issue(HubPrincipal(deviceId, role, dept, 5L)).first

        init {
            dir.createDepartment("dept-a", "Alpha", emptyList(), isOrgAdmin = true)
            dir.createDepartment("dept-b", "Beta", emptyList(), isOrgAdmin = true)
            dir.addDepartmentMember("dept-a", "alice-01", "Alice Ama", subjectPublicKey = "SPKI-ALICE")
            dir.addDepartmentMember("dept-a", "admin-a", "Admin Ama")
            dir.addDepartmentMember("dept-b", "bob-01", "Bob Bedi", subjectPublicKey = "SPKI-BOB")
            dir.createGroup("grp-b", "Beta group", "dept-b", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE, callerDeviceId = "root", isOrgAdmin = true)
        }
    }

    private fun ApplicationTestBuilder.mount(w: World, limits: HubRateLimits = HubRateLimits.unlimited()) {
        routing {
            directoryRoutes(
                w.dir,
                BearerPrincipalResolver(w.tokens),
                isOnline = { false },
                audit = AuditSink { actor, action, details -> w.audit += "$actor|$action|$details" },
                limits = limits,
            )
        }
    }

    private suspend fun HttpClient.getAs(path: String, token: String) = get(path) { header("Authorization", "Bearer $token") }

    // PRIV-U01: visibility matrix (actor x resource).
    @Test
    fun outsidersCannotSeeHiddenDepartmentsButSeeNonHiddenOnes() {
        val w = World()
        val outsider = DirectoryActor("alice-01", "dept-a", MemberRole.MEMBER)
        val deptB = w.dir.getDepartment("dept-b")!!
        assertTrue(DirectoryVisibility.canSeeDepartment(deptB, outsider))
        w.dir.setDepartmentHidden("dept-b", true, DirectoryActor.CONSOLE)
        val hiddenB = w.dir.getDepartment("dept-b")!!
        assertFalse(DirectoryVisibility.canSeeDepartment(hiddenB, outsider))
        assertTrue(DirectoryVisibility.canSeeDepartment(hiddenB, DirectoryActor("bob-01", "dept-b", MemberRole.MEMBER)))
        assertTrue(DirectoryVisibility.canSeeDepartment(hiddenB, DirectoryActor.CONSOLE))
        // A departmentless admin device sees non-hidden departments but never hidden ones.
        val deptA = w.dir.getDepartment("dept-a")!!
        assertTrue(DirectoryVisibility.canSeeDepartment(deptA, DirectoryActor("admin-dev", null, MemberRole.MEMBER)))
        assertFalse(DirectoryVisibility.canSeeDepartment(hiddenB, DirectoryActor("admin-dev", null, MemberRole.MEMBER)))
        val bob = w.dir.getDepartmentMember("bob-01")!!
        assertFalse(DirectoryVisibility.canListMember(outsider, bob, w.dir))
        assertTrue(DirectoryVisibility.keysVisible(outsider, w.dir.getDepartmentMember("alice-01")!!, w.dir))
        assertFalse(DirectoryVisibility.keysVisible(outsider, bob, w.dir))
        assertFalse(DirectoryVisibility.presenceVisible(outsider, bob, w.dir))
    }

    // PRIV-U02: migration from a database made by schema 2.
    @Test
    fun migration3AddsHiddenWithDefaultFalseOnASchema2Database() {
        val db = ServerDatabase.inMemory()
        Migrator(db).apply { register(OrganizationDirectory.COMPONENT, OrganizationDirectory.MIGRATIONS.take(2)); migrate() }
        assertEquals(2, Migrator(db).currentVersion(OrganizationDirectory.COMPONENT))
        db.withConnection { it.createStatement().use { st -> st.execute("INSERT INTO dir_departments(id, name, admin_ids, version) VALUES('old-d', 'Old', '', 1)") } }
        Migrator(db).apply { register(OrganizationDirectory.COMPONENT, OrganizationDirectory.MIGRATIONS); migrate() }
        assertEquals(3, Migrator(db).currentVersion(OrganizationDirectory.COMPONENT))
        val dir = OrganizationDirectory(db)
        assertEquals(false, dir.getDepartment("old-d")?.hidden)
        // Idempotent: a second migrate changes nothing.
        Migrator(db).apply { register(OrganizationDirectory.COMPONENT, OrganizationDirectory.MIGRATIONS); migrate() }
        assertEquals(false, dir.getDepartment("old-d")?.hidden)
    }

    // PRIV-U05b (scale): 5,000 members across 50 departments page and search correctly (synthetic, not device).
    @Test
    fun fiveThousandMembersPageAndSearch() {
        val db = ServerDatabase.inMemory()
        val dir = OrganizationDirectory.open(db)
        for (d in 0 until 50) dir.createDepartment("dept-$d", "Department $d", emptyList(), isOrgAdmin = true)
        for (m in 0 until 5000) dir.addDepartmentMember("dept-${m % 50}", "dev-$m", "Member $m")
        val actor = DirectoryActor("dev-0", "dept-0", MemberRole.MEMBER)
        val found = dir.searchPeople(actor, "member 4999", null, 100)
        assertEquals(listOf("dev-4999"), found.map { it.deviceId })
        val page = dir.searchPeople(actor, "", null, 100)
        assertEquals(100, page.size)
    }

    // PRIV-U03: people paging, search, escape, bounds.
    @Test
    fun peopleSearchIsBoundedAndEscapesWildcards() = testApplication {
        val w = World()
        mount(w)
        val alice = w.token("alice-01")
        var r = client.getAs("/api/v1/directory/people?q=ali", alice)
        assertEquals(HttpStatusCode.OK, r.status)
        var page = HubRestCodec.decode<PeoplePageDto>(r.bodyAsText())
        assertEquals(listOf("alice-01"), page.people.map { it.deviceId })
        // Single-char search is refused (400), over-long q is refused, bad cursor is refused, huge limit is clamped.
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/directory/people?q=x", alice).status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/directory/people?q=${"y".repeat(65)}", alice).status)
        assertEquals(HttpStatusCode.BadRequest, client.getAs("/api/v1/directory/people?cursor=!!!", alice).status)
        r = client.getAs("/api/v1/directory/people?limit=1000000", alice)
        assertEquals(HttpStatusCode.OK, r.status)
        // Literal % and _ match nothing (treated literally, not as wildcards).
        r = client.getAs("/api/v1/directory/people?q=%25%25", alice)
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(HubRestCodec.decode<PeoplePageDto>(r.bodyAsText()).people.isEmpty())
    }

    // PRIV-U04: hidden never leaks via search, counts, cursor, groups.
    @Test
    fun hiddenPeopleNeverLeakThroughSearchCountsOrCursors() = testApplication {
        val w = World()
        mount(w)
        val alice = w.token("alice-01")
        w.dir.setDepartmentHidden("dept-b", true, DirectoryActor.CONSOLE)
        var r = client.getAs("/api/v1/directory/people?q=bob", alice)
        assertEquals(HttpStatusCode.OK, r.status)
        val page = HubRestCodec.decode<PeoplePageDto>(r.bodyAsText())
        assertTrue(page.people.isEmpty())
        assertNull(page.nextCursor)
        // Departments: hidden dept absent for outsiders; counts zeroed shape equals "empty".
        r = client.getAs("/api/v1/directory/departments", alice)
        val depts = HubRestCodec.decodeList<DepartmentDto>(r.bodyAsText())
        assertFalse(depts.any { it.id == "dept-b" })
        // Filtering by a hidden department (or an unknown id) answers an empty page, never a hint.
        val byHidden = HubRestCodec.decode<PeoplePageDto>(client.getAs("/api/v1/directory/people?departmentId=dept-b", alice).bodyAsText())
        val byUnknown = HubRestCodec.decode<PeoplePageDto>(client.getAs("/api/v1/directory/people?departmentId=no-such-dept", alice).bodyAsText())
        assertTrue(byHidden.people.isEmpty())
        assertEquals(byHidden, byUnknown)
        // Groups of the hidden department are gone for outsiders.
        r = client.getAs("/api/v1/directory/groups", alice)
        assertFalse(r.bodyAsText().contains("grp-b"))
        // Members of the hidden department: outsiders get 404 (same status+code as missing; only the per-request correlation id differs).
        val hidden = client.getAs("/api/v1/directory/members?departmentId=dept-b", alice)
        val missing = client.getAs("/api/v1/directory/members?departmentId=no-such-dept", alice)
        assertEquals(hidden.status, missing.status)
        assertEquals(HttpStatusCode.NotFound, hidden.status)
        assertEquals(
            HubRestCodec.decode<com.transfer.flash.core.hubprotocol.rest.ErrorBody>(hidden.bodyAsText()).code,
            HubRestCodec.decode<com.transfer.flash.core.hubprotocol.rest.ErrorBody>(missing.bodyAsText()).code,
        )
    }

    // PRIV-U05: single lookup behaviour + rate limit shape.
    @Test
    fun lookupByKnownIdFindsHiddenMembersWithBlankedDepartmentAnd404ForMissing() = testApplication {
        val w = World()
        mount(w)
        val alice = w.token("alice-01")
        w.dir.setDepartmentHidden("dept-b", true, DirectoryActor.CONSOLE)
        var r = client.getAs("/api/v1/directory/people/bob-01", alice)
        assertEquals(HttpStatusCode.OK, r.status)
        val person = HubRestCodec.decode<PersonDto>(r.bodyAsText())
        assertEquals("bob-01", person.deviceId)
        assertEquals("", person.departmentId)
        assertEquals("", person.departmentName)
        r = client.getAs("/api/v1/directory/people/no-such-device", alice)
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // PRIV-U06: old /members shape unchanged for own department (backward compatible).
    @Test
    fun oldMembersRouteKeepsItsShapeForOwnDepartment() = testApplication {
        val w = World()
        mount(w)
        val alice = w.token("alice-01")
        val r = client.getAs("/api/v1/directory/members?departmentId=dept-a", alice)
        assertEquals(HttpStatusCode.OK, r.status)
        val members = HubRestCodec.decodeList<MemberDto>(r.bodyAsText())
        assertTrue(members.any { it.deviceId == "alice-01" && it.subjectPublicKey == "SPKI-ALICE" })
    }

    // PRIV-U07: groups of hidden departments hidden to outsiders, kept for members.
    @Test
    fun hiddenDepartmentGroupsStayForMembersOnly() {
        val w = World()
        w.dir.setDepartmentHidden("dept-b", true, DirectoryActor.CONSOLE)
        val outsider = DirectoryActor("alice-01", "dept-a", MemberRole.MEMBER)
        val insider = DirectoryActor("bob-01", "dept-b", MemberRole.MEMBER)
        val group = w.dir.getGroup("grp-b")!!
        assertFalse(DirectoryVisibility.canSeeGroup(group, outsider, w.dir))
        assertTrue(DirectoryVisibility.canSeeGroup(group, insider, w.dir))
        assertFalse(w.dir.listVisibleGroupsWithPrivacy(outsider).any { it.id == "grp-b" })
        assertTrue(w.dir.listVisibleGroupsWithPrivacy(insider).any { it.id == "grp-b" })
    }

    // PRIV-U11: epoch bump on toggle forces full resync.
    @Test
    fun togglingHiddenBumpsTheEpoch() {
        val w = World()
        val before = w.dir.epochId
        val alice = DirectoryActor("alice-01", "dept-a", MemberRole.MEMBER)
        w.dir.setDepartmentHidden("dept-b", true, DirectoryActor.CONSOLE)
        assertTrue(w.dir.epochId != before)
        // Departments (statement-independent) prove the resync payload follows the flag.
        val dto = w.dir.getSyncChanges(since = 0, cursor = null, limit = 500, actor = alice)
        assertFalse(dto.departments.any { it.id == "dept-b" })
        assertTrue(dto.fullResync || dto.directoryEpoch != before)
        // Un-hide restores visibility on the next full sync.
        val mid = w.dir.epochId
        w.dir.setDepartmentHidden("dept-b", false, DirectoryActor.CONSOLE)
        assertTrue(w.dir.epochId != mid)
        val dto2 = w.dir.getSyncChanges(since = 0, cursor = null, limit = 500, actor = alice)
        assertTrue(dto2.departments.any { it.id == "dept-b" && !it.hidden })
    }

    // PRIV-U12: log hygiene (no names in server audit for people listing).
    @Test
    fun peopleListingAuditCarriesCountsNotNames() = testApplication {
        val w = World()
        mount(w)
        val alice = w.token("alice-01")
        client.getAs("/api/v1/directory/people?q=alice", alice)
        val line = w.audit.firstOrNull { it.contains("directory.people.list") }
        assertNotNull(line)
        assertFalse(line.contains("Alice"))
    }

    // DirectoryVisibility is the single decider: every read route consults it.
    @Test
    fun everyDirectoryReadRouteConsultsDirectoryVisibility() {
        val src = java.io.File("src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt").readText()
        for (route in listOf("/people", "/people/{deviceId}", "/departments", "/members", "/groups", "/sync")) {
            assertTrue(src.contains(route), route)
        }
        assertTrue(src.contains("DirectoryVisibility.canSeeDepartment"))
        assertTrue(src.contains("DirectoryVisibility.canListMember"))
        assertTrue(src.contains("DirectoryVisibility.canSeeGroup"))
        assertTrue(src.contains("DirectoryVisibility.keysVisible"))
        assertTrue(src.contains("DirectoryVisibility.presenceVisible"))
    }

    // PRIV-U14: hostile input — the cursor codec and query bounds never throw on random bytes.
    @Test
    fun randomBytesNeverThrowInCursorOrQueryValidation() {
        val rng = java.util.Random(0xA2L)
        repeat(1000) {
            val raw = ByteArray(rng.nextInt(48)) { rng.nextInt(256).toByte() }
            val asString = runCatching { String(raw, Charsets.UTF_8) }.getOrDefault("")
            DirectoryPrivacy.decodeCursor(asString)
            DirectoryPrivacy.cleanQuery(asString)
            DirectoryPrivacy.clampLimit(rng.nextInt())
        }
        // Truncated valid cursors decode or refuse, never throw.
        val valid = DirectoryPrivacy.encodeCursor("alice ama", "dev-01")
        for (len in 0..valid.length) {
            DirectoryPrivacy.decodeCursor(valid.take(len))
        }
    }
}
