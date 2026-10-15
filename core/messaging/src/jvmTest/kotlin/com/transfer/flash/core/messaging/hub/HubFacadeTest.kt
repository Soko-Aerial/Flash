@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.OrgPeer
import com.transfer.flash.core.enterprise.OrgPeerResolver
import com.transfer.flash.core.enterprise.OrgSyncOutcome
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.HubGroupRosterEntity
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** HUBT-U01..U30: the Hub tab read model (Plan A4 H6) over a fake directory DAO. No Room, no network. */
class HubFacadeTest {

    private class FakeDao : OrgDirectoryDao {
        var members: List<OrgDirectoryMemberEntity> = emptyList()
        var departments: List<OrgDepartmentEntity> = emptyList()
        var state: OrgSyncStateEntity? = null
        var failReads = false
        var reads = 0
        override suspend fun upsertMembers(members: List<OrgDirectoryMemberEntity>) = Unit
        override suspend fun deleteMembers(deviceIds: List<String>) = Unit
        override suspend fun findMemberByDeviceId(deviceId: String) = members.firstOrNull { it.deviceId == deviceId }
        override suspend fun findMemberByFingerprint(fingerprint: String): OrgDirectoryMemberEntity? = null
        override suspend fun searchMembers(prefix: String, limit: Int): List<OrgDirectoryMemberEntity> = emptyList()
        override suspend fun listMembersByDepartment(departmentId: String) = members.filter { it.departmentId == departmentId }
        override suspend fun listAllMembers(): List<OrgDirectoryMemberEntity> {
            reads++
            if (failReads) error("disk")
            return members
        }
        override suspend fun upsertDepartments(departments: List<OrgDepartmentEntity>) = Unit
        override suspend fun deleteDepartments(ids: List<String>) = Unit
        override suspend fun listDepartments() = departments
        override suspend fun getSyncState() = state
        override suspend fun setSyncState(state: OrgSyncStateEntity) = Unit
        override suspend fun clearMembers() = Unit
        override suspend fun clearDepartments() = Unit
        override suspend fun clearSyncState() = Unit
        override suspend fun allGroupRosters(): List<HubGroupRosterEntity> = emptyList()
        override suspend fun groupRoster(groupId: String): HubGroupRosterEntity? = null
        override suspend fun upsertGroupRosters(rosters: List<HubGroupRosterEntity>) = Unit
        override suspend fun clearGroupRosters() = Unit
    }

    private class Clock(var now: Long = 1_000_000L) : FlashTimeSource {
        override fun nowMs(): Long = now
    }

    private fun creds(role: String = "member", department: String? = "eng") = EnrollmentCredentials(
        hubUrl = "https://hub.example.com",
        rootFingerprint = "FP",
        rootPublicKey = "PK",
        deviceCert = DeviceCert(
            deviceId = "dev-self", subjectPublicKey = "PKS", role = role, department = department,
            validFrom = 0L, validTo = Long.MAX_VALUE, serial = 1L, issuerPublicKey = "PK", issuerSignature = "sig",
        ),
        hubName = "Acme Corp",
    )

    private fun member(id: String, name: String, dept: String = "eng", role: String = "member") = OrgDirectoryMemberEntity(
        deviceId = id, displayName = name, displayNameFolded = com.transfer.flash.core.common.text.foldForSearch(name),
        departmentId = dept, role = role, subjectPublicKeyFingerprint = "fp-$id", statementBytes = ByteArray(0),
        statementSerial = 1, statementIssuedAtMs = 0, statementDelegationSerial = 1, lastSyncedAtMs = 0,
    )

    private fun dept(id: String, name: String, hidden: Boolean = false, heads: String = "[]", count: Int = 0) =
        OrgDepartmentEntity(id, name, count, heads, hidden)

    private class Rig(val facade: HubFacade, val creds: MutableStateFlow<EnrollmentCredentials?>, val live: MutableStateFlow<Set<String>>, val scope: CoroutineScope)

    private fun rig(
        dao: FakeDao,
        credentials: EnrollmentCredentials? = creds(),
        clock: Clock = Clock(),
        sources: HubTabSources? = null,
        resolver: OrgPeerResolver? = null,
        paired: Set<String> = emptySet(),
        announcements: suspend () -> FlashResult<Unit> = { FlashResult.Success(Unit) },
        pending: Map<String, String> = emptyMap(),
        linked: Set<String> = emptySet(),
    ): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val credFlow = MutableStateFlow(credentials)
        val live = MutableStateFlow(emptySet<String>())
        val facade = HubFacade(
            sources = sources ?: HubTabSources(directoryDao = dao),
            credentials = credFlow, resolver = resolver, localDeviceId = "dev-self",
            isPaired = { it in paired }, liveIds = live, reachableIds = flowOf(emptySet()),
            linkedGroupIds = flowOf(linked), pendingJoins = flowOf(pending),
            refreshAnnouncements = announcements, timeSource = clock, scope = scope, ioDispatcher = Dispatchers.Default,
        )
        return Rig(facade, credFlow, live, scope)
    }

    private fun <T> Rig.await(flow: Flow<T>, predicate: (T) -> Boolean = { true }): T = runBlocking { withTimeout(10_000) { flow.first(predicate) } }

    @Test fun `U01 people are sorted by folded name then device id`() {
        val dao = FakeDao().apply {
            members = listOf(member("d2", "Émile"), member("d1", "adam"), member("d4", "Zoë"), member("d3", "Bob"), member("d6", "Bob"))
            departments = listOf(dept("eng", "Engineering"))
        }
        val r = rig(dao)
        val names = r.await(r.facade.people).map { it.deviceId }
        assertEquals(listOf("d1", "d3", "d6", "d2", "d4"), names)
        r.scope.cancel()
    }

    @Test fun `U02 an unenrolled device sees an empty enrolled=false state and no people`() {
        val r = rig(FakeDao().apply { members = listOf(member("d1", "A")) }, credentials = null)
        val st = r.await(r.facade.state)
        assertFalse(st.enrolled); assertTrue(st.loaded)
        assertTrue(r.await(r.facade.people).isEmpty())
        r.scope.cancel()
    }

    @Test fun `U03 un-enrol empties people and departments at once`() {
        val dao = FakeDao().apply { members = listOf(member("d1", "A")); departments = listOf(dept("eng", "Eng")) }
        val r = rig(dao)
        assertEquals(1, r.await(r.facade.people).size)
        r.creds.value = null
        assertTrue(r.await(r.facade.people) { it.isEmpty() }.isEmpty())
        assertTrue(r.await(r.facade.departments) { it.isEmpty() }.isEmpty())
        assertFalse(r.await(r.facade.state) { !it.enrolled }.enrolled)
        r.scope.cancel()
    }

    @Test fun `U04 a hidden department is invisible to a plain member of another department`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Engineering"), dept("sec", "Security", hidden = true))
            members = listOf(member("d1", "Ann", "eng"), member("d2", "Hidden Hal", "sec"))
        }
        val r = rig(dao, credentials = creds("member", "eng"))
        assertEquals(listOf("d1"), r.await(r.facade.people).map { it.deviceId })
        assertEquals(listOf("eng"), r.await(r.facade.departments).map { it.id })
        r.scope.cancel()
    }

    @Test fun `U05 a hidden department is visible to its own members and to administrators`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Engineering"), dept("sec", "Security", hidden = true))
            members = listOf(member("d1", "Ann", "eng"), member("d2", "Hal", "sec"))
        }
        val own = rig(dao, credentials = creds("member", "sec"))
        assertEquals(2, own.await(own.facade.people).size)
        assertTrue(own.await(own.facade.departments).any { it.id == "sec" && it.isMine })
        val admin = rig(dao, credentials = creds("admin", "eng"))
        assertEquals(2, admin.await(admin.facade.departments).size)
        own.scope.cancel(); admin.scope.cancel()
    }

    @Test fun `U06 online presence is withheld for a hidden department you are not part of`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Engineering"), dept("sec", "Security", hidden = true))
            members = listOf(member("d2", "Hal", "sec"))
        }
        val r = rig(dao, credentials = creds("admin", "eng").copy(), sources = HubTabSources(dao, hubPresence = flowOf(setOf("d2"))))
        // admin may see the person; presence is shown (admin is allowed)
        assertEquals(true, r.await(r.facade.people).single().online)
        val other = rig(dao.also { it.departments = listOf(dept("eng", "Engineering"), dept("sec", "Security", hidden = true)) }, credentials = creds("member", "eng"))
        assertTrue(other.await(other.facade.people).isEmpty())
        r.scope.cancel(); other.scope.cancel()
    }

    @Test fun `U07 roles map from the snapshot strings and the heads list`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Eng", heads = "[\"d3\"]"))
            members = listOf(member("d1", "A", role = "admin"), member("d2", "B", role = "DEPT_ADMIN"), member("d3", "C"), member("d4", "D", role = "ORG_ADMIN"), member("d5", "E", role = "weird"))
        }
        val r = rig(dao)
        val roles = r.await(r.facade.people).associate { it.deviceId to it.role }
        assertEquals(HubPersonRole.ADMIN, roles["d1"]); assertEquals(HubPersonRole.HEAD, roles["d2"])
        assertEquals(HubPersonRole.HEAD, roles["d3"]); assertEquals(HubPersonRole.ADMIN, roles["d4"]); assertEquals(HubPersonRole.PERSON, roles["d5"])
        r.scope.cancel()
    }

    @Test fun `U08 search matches word prefixes ignoring case and accents`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Engineering"), dept("fin", "Finance"))
            members = listOf(member("d1", "Zoë Ann Smith", "eng"), member("d2", "Bob Zander", "fin"), member("d3", "Carla", "fin"))
        }
        val r = rig(dao)
        assertEquals(listOf("d1"), r.await(r.facade.search("zoe", null)).map { it.deviceId })
        assertEquals(listOf("d1"), r.await(r.facade.search("SMITH zo", null)).map { it.deviceId })
        assertEquals(listOf("d2"), r.await(r.facade.search("zan", null)).map { it.deviceId }, "word prefix, not substring in the middle of a word")
        assertTrue(r.await(r.facade.search("mith", null)).isEmpty(), "a mid-word fragment must not match")
        assertEquals(listOf("d2", "d3"), r.await(r.facade.search("fin", null)).map { it.deviceId }, "department name is searchable")
        r.scope.cancel()
    }

    @Test fun `U09 blank query keeps only the department filter and a missing department yields nothing`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Eng"), dept("fin", "Fin"))
            members = listOf(member("d1", "A", "eng"), member("d2", "B", "fin"))
        }
        val r = rig(dao)
        assertEquals(2, r.await(r.facade.search("   ", null)).size)
        assertEquals(listOf("d2"), r.await(r.facade.search("", "fin")).map { it.deviceId })
        assertTrue(r.await(r.facade.search("", "nope")).isEmpty())
        r.scope.cancel()
    }

    @Test fun `U10 hostile query strings never throw`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")); members = listOf(member("d1", "Ann")) }
        val r = rig(dao)
        val hostile = listOf("‮ann", "a​nn", "' OR 1=1 --", "%", "_", "\\", "😀", "x".repeat(10_000), "\u0000", "ä̈")
        hostile.forEach { q -> r.await(r.facade.search(q, null)) }
        r.scope.cancel()
    }

    @Test fun `U11 reachability and online come from live sessions and hub presence, never invented`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Eng"))
            members = listOf(member("d1", "A"), member("d2", "B"), member("d3", "C"), member("dev-self", "Me"))
        }
        val noPresence = rig(dao, paired = setOf("d1"))
        noPresence.live.value = setOf("d1")
        val p = noPresence.await(noPresence.facade.people) { l -> l.first { it.deviceId == "d1" }.reachability == HubReachability.LAN }.associateBy { it.deviceId }
        assertEquals(true, p.getValue("d1").online); assertTrue(p.getValue("d1").paired)
        assertEquals(HubReachability.UNKNOWN, p.getValue("d2").reachability); assertNull(p.getValue("d2").online)
        assertTrue(p.getValue("dev-self").isSelf); assertFalse(p.getValue("dev-self").paired); assertNull(p.getValue("dev-self").online)
        val withPresence = rig(dao, sources = HubTabSources(dao, hubPresence = flowOf(setOf("d2"))))
        val q = withPresence.await(withPresence.facade.people).associateBy { it.deviceId }
        assertEquals(HubReachability.HUB, q.getValue("d2").reachability)
        assertEquals(HubReachability.OFFLINE, q.getValue("d3").reachability); assertEquals(false, q.getValue("d3").online)
        noPresence.scope.cancel(); withPresence.scope.cancel()
    }

    @Test fun `U12 orgVerified follows the resolver binding verdict`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")); members = listOf(member("d1", "A"), member("d2", "B")) }
        val resolver = object : OrgPeerResolver {
            override fun resolve(deviceId: String) = if (deviceId == "d1") OrgPeer("d1", "A", "eng", "member", "fp", bindingOk = true) else null
            override fun isKnownColleague(deviceId: String) = true
            override val lease = MutableStateFlow<OrgLease>(OrgLease.Valid(5_000L))
            override suspend fun refreshFromSnapshot() = Unit
            override suspend fun clear() = Unit
        }
        val r = rig(dao, resolver = resolver)
        val m = r.await(r.facade.people).associateBy { it.deviceId }
        assertTrue(m.getValue("d1").orgVerified); assertFalse(m.getValue("d2").orgVerified)
        assertEquals(OrgLease.Valid(5_000L), r.await(r.facade.state).lease)
        r.scope.cancel()
    }

    @Test fun `U13 state carries org name, counts, last sync and clock flag`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Eng")); members = listOf(member("d1", "A"))
            state = OrgSyncStateEntity(1, "FP", "e", 3, 30, lastSyncSuccessMs = 777L, lastSyncAttemptMs = 800L)
        }
        val r = rig(dao, sources = HubTabSources(dao, clockWrong = flowOf(true)))
        val st = r.await(r.facade.state)
        assertEquals("Acme Corp", st.orgName); assertEquals(1, st.peopleCount); assertEquals(777L, st.lastSyncedAtMs)
        assertTrue(st.clockWrong); assertEquals(HubSyncStatus.OK, st.syncStatus); assertEquals("eng", st.myDepartmentId)
        r.scope.cancel()
    }

    @Test fun `U14 never synced shows NEVER and an unreadable directory degrades to FAILED_OTHER without a crash`() {
        val never = rig(FakeDao())
        assertEquals(HubSyncStatus.NEVER, never.await(never.facade.state).syncStatus)
        val broken = FakeDao().apply { failReads = true }
        val r = rig(broken)
        val st = r.await(r.facade.state)
        assertEquals(HubSyncStatus.FAILED_OTHER, st.syncStatus); assertTrue(st.loaded)
        assertTrue(r.await(r.facade.people).isEmpty())
        never.scope.cancel(); r.scope.cancel()
    }

    @Test fun `U15 invalidate re-reads the directory`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")); members = listOf(member("d1", "A")) }
        val r = rig(dao)
        assertEquals(1, r.await(r.facade.people).size)
        dao.members = dao.members + member("d2", "B")
        r.facade.invalidate()
        assertEquals(2, r.await(r.facade.people) { it.size == 2 }.size)
        r.scope.cancel()
    }

    @Test fun `U16 refresh is single-flight, joined callers get the same result and one sync runs`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        var calls = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val src = HubTabSources(dao, requestSync = { _ -> calls++; gate.await(); OrgSyncOutcome.Success(1, 0, 0, 0, false) })
        val r = rig(dao, sources = src)
        runBlocking {
            val a = (1..4).map { async { r.facade.refresh() } }
            delay(150)
            gate.complete(Unit)
            val results = a.awaitAll()
            assertTrue(results.all { it is FlashResult.Success })
        }
        assertEquals(1, calls)
        r.scope.cancel()
    }

    @Test fun `U17 refresh within 15 s makes no second attempt, after 15 s it does`() {
        val dao = FakeDao(); var calls = 0; val clock = Clock()
        val r = rig(dao, clock = clock, sources = HubTabSources(dao, requestSync = { _ -> calls++; OrgSyncOutcome.Success(0, 0, 0, 0, false) }))
        runBlocking {
            r.facade.refresh(); clock.now += 14_999; r.facade.refresh()
            assertEquals(1, calls)
            clock.now += 1; r.facade.refresh()
            assertEquals(2, calls)
        }
        r.scope.cancel()
    }

    @Test fun `U18 a failed refresh inside the interval repeats the failure instead of pretending success`() {
        val dao = FakeDao(); val clock = Clock()
        val r = rig(dao, clock = clock, sources = HubTabSources(dao, requestSync = { _ -> OrgSyncOutcome.Offline }))
        runBlocking {
            val first = r.facade.refresh(); clock.now += 5_000; val second = r.facade.refresh()
            assertTrue(first is FlashResult.Failure && second is FlashResult.Failure)
        }
        r.scope.cancel()
    }

    @Test fun `U19 outcomes map to the sentences for people`() {
        fun map(o: OrgSyncOutcome?) = HubRefreshText.resultOf(o)
        assertTrue(map(null) is FlashResult.Success)
        assertTrue(map(OrgSyncOutcome.Success(0, 0, 0, 0, false)) is FlashResult.Success)
        assertTrue((map(OrgSyncOutcome.Offline) as FlashResult.Failure).error is FlashError.NetworkUnavailable)
        assertEquals(HubRefreshText.UNSUPPORTED, ((map(OrgSyncOutcome.HubUnsupported) as FlashResult.Failure).error as FlashError.Unknown).message)
        assertEquals(HubRefreshText.RATE_LIMITED, ((map(OrgSyncOutcome.RateLimited(10)) as FlashResult.Failure).error as FlashError.Unknown).message)
        assertEquals(HubRefreshText.NOT_ENROLLED, ((map(OrgSyncOutcome.NotEnrolled) as FlashResult.Failure).error as FlashError.Unknown).message)
        listOf(OrgSyncOutcome.AuthFailed(401, "x"), OrgSyncOutcome.ServerError(500, "boom {json}"), OrgSyncOutcome.StorageFailed("disk"), OrgSyncOutcome.HardSecurityError("r")).forEach {
            assertEquals(HubRefreshText.GENERIC, ((map(it) as FlashResult.Failure).error as FlashError.Unknown).message, "raw text must never reach the user")
        }
    }

    @Test fun `U20 a successful sync commit becomes visible and the state turns OK`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        val r = rig(dao, sources = HubTabSources(dao, requestSync = { _ -> dao.members = listOf(member("d1", "New")); OrgSyncOutcome.Success(1, 0, 0, 0, false) }))
        assertEquals(0, r.await(r.facade.people).size)
        runBlocking { r.facade.refresh(true) }
        assertEquals(1, r.await(r.facade.people) { it.isNotEmpty() }.size)
        assertEquals(HubSyncStatus.OK, r.await(r.facade.state) { it.syncStatus == HubSyncStatus.OK }.syncStatus)
        r.scope.cancel()
    }

    @Test fun `U21 offline and unsupported outcomes show in the state while cached people stay`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")); members = listOf(member("d1", "A")) }
        val r = rig(dao, sources = HubTabSources(dao, requestSync = { _ -> OrgSyncOutcome.Offline }))
        runBlocking { r.facade.refresh() }
        assertEquals(HubSyncStatus.FAILED_OFFLINE, r.await(r.facade.state) { it.syncStatus == HubSyncStatus.FAILED_OFFLINE }.syncStatus)
        assertEquals(1, r.await(r.facade.people).size)
        val u = rig(dao, sources = HubTabSources(dao, requestSync = { _ -> OrgSyncOutcome.HubUnsupported }))
        runBlocking { u.facade.refresh() }
        assertEquals(HubSyncStatus.UNSUPPORTED, u.await(u.facade.state) { it.syncStatus == HubSyncStatus.UNSUPPORTED }.syncStatus)
        r.scope.cancel(); u.scope.cancel()
    }

    @Test fun `U22 refresh also refreshes announcements and an announcements failure does not fail the refresh`() {
        val dao = FakeDao(); var ann = 0
        val r = rig(dao, announcements = { ann++; error("boom") })
        val res = runBlocking { r.facade.refresh() }
        assertTrue(res is FlashResult.Success); assertEquals(1, ann)
        r.scope.cancel()
    }

    @Test fun `U23 refresh on an unenrolled device fails with the not enrolled sentence and calls nothing`() {
        val dao = FakeDao(); var calls = 0
        val r = rig(dao, credentials = null, sources = HubTabSources(dao, requestSync = { _ -> calls++; null }))
        val res = runBlocking { r.facade.refresh() }
        assertEquals(HubRefreshText.NOT_ENROLLED, ((res as FlashResult.Failure).error as FlashError.Unknown).message)
        assertEquals(0, calls)
        r.scope.cancel()
    }

    @Test fun `U24 groups carry join state and sort yours first, department counts reach departments`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Engineering"), dept("fin", "Finance")) }
        val fetched = listOf(
            EnterpriseDirectoryGroup("g1", "Zeta", "Finance", "listed", requiresApproval = true, memberCount = 3, pendingCount = 2),
            EnterpriseDirectoryGroup("g2", "Alpha", "Engineering", "listed", memberCount = 9, onlineCount = 4),
            EnterpriseDirectoryGroup("g3", "Beta", "Engineering", "listed"),
        )
        val r = rig(dao, sources = HubTabSources(dao, fetchGroups = { FlashResult.Success(fetched) }), pending = mapOf("g1" to "Zeta"), linked = setOf("g3"))
        runBlocking { r.facade.refresh() }
        val groups = r.await(r.facade.groups) { it.isNotEmpty() }
        assertEquals(listOf("g3", "g2", "g1"), groups.map { it.groupId })
        assertEquals(HubGroupMembership.JOINED, groups[0].membership); assertEquals(HubGroupMembership.NOT_JOINED, groups[1].membership)
        assertEquals(HubGroupMembership.PENDING, groups[2].membership); assertEquals(2, groups[2].pendingRequests)
        val deps = r.await(r.facade.departments) { d -> d.any { it.groupCount > 0 } }.associateBy { it.id }
        assertEquals(2, deps.getValue("eng").groupCount); assertEquals(1, deps.getValue("fin").groupCount)
        r.scope.cancel()
    }

    @Test fun `U25 a failed group fetch keeps the previous groups`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        var fail = false; val clock = Clock()
        val src = HubTabSources(dao, fetchGroups = { if (fail) FlashResult.Failure(FlashError.Unknown("x")) else FlashResult.Success(listOf(EnterpriseDirectoryGroup("g1", "A", "Eng", "listed"))) })
        val r = rig(dao, clock = clock, sources = src)
        runBlocking { r.facade.refresh(); fail = true; clock.now += 20_000; r.facade.refresh() }
        assertEquals(1, r.await(r.facade.groups).size)
        r.scope.cancel()
    }

    @Test fun `U26 person lookup finds a visible colleague and returns null for a hidden or unknown one`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Eng"), dept("sec", "Sec", hidden = true))
            members = listOf(member("d1", "A"), member("d2", "H", "sec"))
        }
        val r = rig(dao)
        assertEquals("A", r.await(r.facade.person("d1"))?.displayName)
        assertNull(r.await(r.facade.person("d2"))); assertNull(r.await(r.facade.person("nope")))
        r.scope.cancel()
    }

    @Test fun `U27 heads parser never throws on hostile or random input and reads good input`() {
        assertEquals(listOf("a", "b"), HubSnapshotBuilder.parseHeadIds("[\"a\", \"b\"]"))
        assertEquals(listOf("a\"b"), HubSnapshotBuilder.parseHeadIds("[\"a\\\"b\"]"))
        assertEquals(emptyList(), HubSnapshotBuilder.parseHeadIds(""))
        assertEquals(emptyList(), HubSnapshotBuilder.parseHeadIds("[\"unterminated"))
        assertEquals(emptyList(), HubSnapshotBuilder.parseHeadIds("[1,2]"))
        assertEquals(emptyList(), HubSnapshotBuilder.parseHeadIds("x".repeat(100_000)))
        val rnd = Random(42)
        val alphabet = "[]\",\\ abc\u0000\uD800"
        repeat(2_000) {
            HubSnapshotBuilder.parseHeadIds(String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] }))
        }
    }

    @Test fun `U28 matcher boundaries`() {
        assertTrue(HubPeopleMatcher.matches("ann smith", emptyList()))
        assertTrue(HubPeopleMatcher.matches("ann smith", HubPeopleMatcher.tokens("  ann   smi ")))
        assertFalse(HubPeopleMatcher.matches("ann smith", HubPeopleMatcher.tokens("nn")))
        assertFalse(HubPeopleMatcher.matches("", HubPeopleMatcher.tokens("a")))
        assertEquals(emptyList(), HubPeopleMatcher.tokens("\t \n"))
    }

    @Test fun `U29 five thousand colleagues are sorted once and read once`() {
        val dao = FakeDao().apply {
            departments = listOf(dept("eng", "Eng"), dept("fin", "Fin"))
            members = (0 until 5_000).map { member("d%05d".format(it), "Person ${(it * 7919) % 5000}", if (it % 2 == 0) "eng" else "fin") }
        }
        val r = rig(dao)
        val list = r.await(r.facade.people) { it.size == 5_000 }
        assertEquals(5_000, list.size)
        assertEquals(list.map { it.displayName.lowercase() to it.deviceId }, list.map { it.displayName.lowercase() to it.deviceId }.sortedWith(compareBy({ it.first }, { it.second })))
        r.live.value = setOf("d00001"); r.await(r.facade.people) { l -> l.any { it.online == true } }
        r.await(r.facade.search("person 4999", null))
        assertEquals(1, dao.reads, "presence ticks and searches must not re-read the directory")
        r.scope.cancel()
    }

    @Test fun `U30 zero and one colleague`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        val r = rig(dao)
        assertTrue(r.await(r.facade.people).isEmpty())
        dao.members = listOf(member("d1", "Solo")); r.facade.invalidate()
        assertEquals(1, r.await(r.facade.people) { it.size == 1 }.size)
        r.scope.cancel()
    }
    @Test fun `U31 a hub without announcements sets newsUnsupported and a later success clears it`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        var unsupported = true; val clock = Clock()
        val r = rig(dao, clock = clock, announcements = {
            if (unsupported) FlashResult.Failure(FlashError.Domain("UNSUPPORTED", "no")) else FlashResult.Success(Unit)
        })
        runBlocking { r.facade.refresh() }
        assertTrue(r.await(r.facade.state) { it.newsUnsupported }.newsUnsupported)
        unsupported = false; clock.now += 20_000
        runBlocking { r.facade.refresh() }
        assertFalse(r.await(r.facade.state) { !it.newsUnsupported }.newsUnsupported)
        r.scope.cancel()
    }

    @Test fun `U32 another announcements failure does not claim the hub lacks announcements`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        val r = rig(dao, announcements = { FlashResult.Failure(FlashError.NetworkUnavailable("offline")) })
        runBlocking { r.facade.refresh() }
        assertFalse(r.await(r.facade.state).newsUnsupported)
        r.scope.cancel()
    }

    @Test fun `U33 groupsLoadFailed is set only while no earlier groups are cached`() {
        val dao = FakeDao().apply { departments = listOf(dept("eng", "Eng")) }
        var fail = true; val clock = Clock()
        val src = HubTabSources(dao, fetchGroups = { if (fail) FlashResult.Failure(FlashError.Unknown("x")) else FlashResult.Success(listOf(EnterpriseDirectoryGroup("g1", "A", "Eng", "listed"))) })
        val r = rig(dao, clock = clock, sources = src)
        runBlocking { r.facade.refresh() }
        assertTrue(r.await(r.facade.state) { it.groupsLoadFailed }.groupsLoadFailed)
        fail = false; clock.now += 20_000
        runBlocking { r.facade.refresh() }
        assertFalse(r.await(r.facade.state) { !it.groupsLoadFailed }.groupsLoadFailed)
        fail = true; clock.now += 20_000
        runBlocking { r.facade.refresh() }
        assertFalse(r.await(r.facade.state).groupsLoadFailed, "a failed refresh keeps the cached groups, so it is not a load failure")
        r.scope.cancel()
    }
}
