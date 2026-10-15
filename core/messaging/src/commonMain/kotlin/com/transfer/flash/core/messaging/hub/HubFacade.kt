@file:OptIn(FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.text.foldForSearch
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.OrgPeerResolver
import com.transfer.flash.core.enterprise.OrgSyncOutcome
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import com.transfer.flash.core.persistence.db.entity.OrgDepartmentEntity
import com.transfer.flash.core.persistence.db.entity.OrgDirectoryMemberEntity
import com.transfer.flash.core.persistence.db.entity.OrgSyncStateEntity
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Hub tab's read model and its one action (UI-058, Plan A4 H6).
 *
 * - People / departments come from the Plan B Room snapshot ([HubTabSources.directoryDao]), read ONCE per change (the DAO has no
 *   Flow, so a change is an [invalidate] fired by the same refresh hook the coordinator already calls after a sync commit), sorted
 *   once, filtered by department visibility, and then only joined with the cheap live data (presence sets). Nothing sorts in composition.
 * - Hidden departments (A2/D2) are filtered here too, defensively: a department that is hidden is visible only to its own members
 *   and to organisation administrators.
 * - [refresh] is single-flight with a minimum interval of [MIN_REFRESH_INTERVAL_MS]; callers inside the interval get the previous result.
 *
 * Complexity: a snapshot load is O(n log n) in members once per change; a presence tick is O(n); a search is O(n * tokens).
 */
public class HubFacade(
    private val sources: HubTabSources,
    private val credentials: Flow<EnrollmentCredentials?>,
    private val resolver: OrgPeerResolver?,
    private val localDeviceId: String,
    private val isPaired: (String) -> Boolean,
    private val liveIds: Flow<Set<String>>,
    private val reachableIds: Flow<Set<String>>,
    private val linkedGroupIds: Flow<Set<String>>,
    private val pendingJoins: Flow<Map<String, String>>,
    /**
     * Refreshes the announcements feed as part of [refresh]. A failure never fails the directory refresh; only the typed
     * `UNSUPPORTED` answer is remembered, so the News segment can say the hub has no announcements instead of looking empty.
     */
    private val refreshAnnouncements: suspend () -> FlashResult<Unit>,
    private val timeSource: FlashTimeSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val invalidation = MutableStateFlow(0L)
    private val refreshMutex = Mutex()
    private val refreshing = MutableStateFlow(false)
    private val lastOutcome = MutableStateFlow<OrgSyncOutcome?>(null)
    private val groupsCache = MutableStateFlow<List<EnterpriseDirectoryGroup>?>(null)
    private val groupsFailed = MutableStateFlow(false)
    private val newsUnsupported = MutableStateFlow(false)

    private var lastAttemptMs: Long = Long.MIN_VALUE
    private var lastResult: FlashResult<Unit> = FlashResult.Success(Unit)
    private var inFlight: CompletableDeferred<FlashResult<Unit>>? = null

    /** The directory changed (a sync committed, or the snapshot was cleared): read it again. */
    public fun invalidate() {
        invalidation.value = invalidation.value + 1
    }

    /** The snapshot was wiped (un-enrol): forget cached groups too; the next read sees an empty directory. */
    public fun onCleared() {
        groupsCache.value = null
        groupsFailed.value = false
        newsUnsupported.value = false
        lastOutcome.value = null
        invalidate()
    }

    private val snapshot: StateFlow<Snapshot?> = combine(credentials, invalidation) { creds, _ -> creds }
        .mapLatest { creds -> if (creds == null) Snapshot.UNENROLLED else load(creds) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val presence: Flow<Presence> = combine(
        liveIds,
        reachableIds,
        sources.hubPresence ?: flowOf(null),
    ) { live, reachable, hub -> Presence(live, reachable, hub) }

    /** State of the whole tab; emits only after the first directory read ([HubUiState.loaded]). */
    public val state: Flow<HubUiState> = combine(
        snapshot.filterNotNull(),
        resolver?.lease ?: flowOf(OrgLease.None),
        resolver?.identityMismatch ?: flowOf(false),
        sources.clockWrong,
        combine(refreshing, lastOutcome, groupsFailed, newsUnsupported, groupsCache) { r, o, gf, nu, gc ->
            RefreshView(r, o, groupsLoadFailed = gf && gc == null, newsUnsupported = nu)
        },
    ) { snap, lease, mismatch, clock, view ->
        if (!snap.enrolled) {
            HubUiState(loaded = true)
        } else {
            HubUiState(
                enrolled = true,
                loaded = true,
                orgName = snap.orgName,
                myDeviceId = localDeviceId,
                myDepartmentId = snap.myDepartmentId,
                myRole = snap.myRole,
                lease = lease,
                identityMismatch = mismatch,
                clockWrong = clock,
                syncStatus = syncStatusOf(snap, view.outcome),
                lastSyncedAtMs = snap.lastSyncedAtMs,
                isRefreshing = view.refreshing,
                peopleCount = snap.people.size,
                groupsLoadFailed = view.groupsLoadFailed,
                newsUnsupported = view.newsUnsupported,
            )
        }
    }.distinctUntilChanged()

    /** Every colleague this device may see, ordered by folded name then device id. Empty after un-enrol. */
    public val people: Flow<List<HubPersonUi>> = combine(snapshot.filterNotNull(), presence) { snap, p ->
        snap.people.map { core -> core.toUi(p) }
    }

    /** One colleague by id (deep link `hub/person/<id>`), or null when not (or no longer) visible. */
    public fun person(deviceId: String): Flow<HubPersonUi?> =
        combine(snapshot.filterNotNull(), presence) { snap, p ->
            snap.people.firstOrNull { it.deviceId == deviceId }?.toUi(p)
        }.distinctUntilChanged()

    /**
     * [people] filtered by a name / department / role query and, optionally, one department. A blank query keeps the
     * department filter only. Matching is by word prefix on folded text (case and diacritics ignored), every token must match.
     */
    public fun search(query: String, departmentId: String?): Flow<List<HubPersonUi>> {
        val tokens = HubPeopleMatcher.tokens(query)
        return combine(snapshot.filterNotNull(), presence) { snap, p ->
            snap.people.asSequence()
                .filter { departmentId == null || it.departmentId == departmentId }
                .filter { HubPeopleMatcher.matches(it.searchText, tokens) }
                .map { it.toUi(p) }
                .toList()
        }
    }

    /** Departments the caller may see, mine first then by folded name. */
    public val departments: Flow<List<HubDepartmentUi>> = combine(snapshot.filterNotNull(), groupsCache) { snap, groups ->
        snap.departments.map { d ->
            val groupCount = groups?.count { g ->
                g.department.equals(d.id, ignoreCase = true) || g.department.equals(d.name, ignoreCase = true)
            } ?: 0
            d.copy(groupCount = groupCount)
        }
    }

    /** Organisation groups last fetched by [refresh] with the caller's join state, "yours" first, then by department and name. */
    public val groups: Flow<List<HubGroupUi>> = combine(
        groupsCache,
        linkedGroupIds,
        pendingJoins,
    ) { fetched, linked, pending ->
        (fetched ?: emptyList())
            .map { g ->
                HubGroupUi(
                    groupId = g.groupId,
                    name = g.name,
                    departmentName = g.department,
                    memberCount = g.memberCount,
                    onlineCount = g.onlineCount,
                    requiresApproval = g.requiresApproval,
                    membership = when {
                        g.groupId in linked -> HubGroupMembership.JOINED
                        g.groupId in pending -> HubGroupMembership.PENDING
                        else -> HubGroupMembership.NOT_JOINED
                    },
                    pendingRequests = g.pendingCount,
                )
            }
            .sortedWith(
                compareBy<HubGroupUi>({ it.membership != HubGroupMembership.JOINED }, { foldForSearch(it.departmentName) }, { foldForSearch(it.name) }, { it.groupId }),
            )
    }

    /**
     * Refreshes the directory, the group list and the announcements. Single-flight: a caller that arrives while one runs waits for
     * that run. Within [MIN_REFRESH_INTERVAL_MS] of the last attempt the previous result is returned without any network call.
     */
    public suspend fun refresh(userInitiated: Boolean = false): FlashResult<Unit> {
        val running = refreshMutex.withLock {
            inFlight?.let { return@withLock it to false }
            val now = timeSource.nowMs()
            if (lastAttemptMs != Long.MIN_VALUE && now - lastAttemptMs in 0 until MIN_REFRESH_INTERVAL_MS) {
                FlashProbe.emit("hub.ui.refresh", "result" to "skipped_recent")
                return@withLock CompletableDeferred(lastResult) to false
            }
            lastAttemptMs = now
            val d = CompletableDeferred<FlashResult<Unit>>()
            inFlight = d
            d to true
        }
        val (deferred, owner) = running
        if (!owner) return deferred.await()
        val result = try {
            runRefresh(userInitiated)
        } catch (e: CancellationException) {
            refreshMutex.withLock { inFlight = null; lastAttemptMs = Long.MIN_VALUE }
            deferred.cancel(e)
            throw e
        } catch (e: Exception) {
            FlashLog.w("HUB_UI", "refresh failed: ${e::class.simpleName}")
            FlashResult.Failure(FlashError.Unknown(HubRefreshText.GENERIC))
        }
        refreshMutex.withLock {
            lastResult = result
            inFlight = null
        }
        deferred.complete(result)
        return result
    }

    private suspend fun runRefresh(userInitiated: Boolean): FlashResult<Unit> {
        refreshing.value = true
        try {
            val creds = credentials.first()
                ?: return FlashResult.Failure(FlashError.Unknown(HubRefreshText.NOT_ENROLLED))
            val sync = sources.requestSync
            val outcome = sync?.invoke(userInitiated)
            if (outcome != null) lastOutcome.value = outcome
            invalidate()
            sources.fetchGroups?.let { fetch ->
                when (val g = fetch(creds)) {
                    is FlashResult.Success -> {
                        groupsCache.value = g.value
                        groupsFailed.value = false
                    }
                    is FlashResult.Failure -> groupsFailed.value = true
                }
            }
            runAnnouncementsRefresh()
            FlashProbe.emit("hub.ui.refresh", "result" to (outcome?.let { it::class.simpleName } ?: "no_sync"))
            return HubRefreshText.resultOf(outcome)
        } finally {
            refreshing.value = false
        }
    }

    private suspend fun runAnnouncementsRefresh() {
        try {
            when (val r = refreshAnnouncements()) {
                is FlashResult.Success -> newsUnsupported.value = false
                is FlashResult.Failure -> {
                    val e = r.error
                    if (e is FlashError.Domain && e.code == "UNSUPPORTED") newsUnsupported.value = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FlashLog.w("HUB_UI", "announcements refresh failed: ${e::class.simpleName}")
        }
    }

    private suspend fun load(creds: EnrollmentCredentials): Snapshot = withContext(ioDispatcher) {
        val started = timeSource.nowMs()
        try {
            val members = sources.directoryDao.listAllMembers()
            val departments = sources.directoryDao.listDepartments()
            val state = sources.directoryDao.getSyncState()
            val built = HubSnapshotBuilder.build(
                creds = creds,
                localDeviceId = localDeviceId,
                members = members,
                departments = departments,
                syncState = state,
            )
            FlashProbe.emit(
                "hub.ui.snapshot",
                "people" to built.people.size,
                "departments" to built.departments.size,
                "ms" to (timeSource.nowMs() - started),
            )
            built
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FlashLog.w("HUB_UI", "directory read failed: ${e::class.simpleName}")
            HubSnapshotBuilder.build(creds, localDeviceId, emptyList(), emptyList(), null).copy(readFailed = true)
        }
    }

    private fun syncStatusOf(snap: Snapshot, outcome: OrgSyncOutcome?): HubSyncStatus = when {
        outcome is OrgSyncOutcome.HubUnsupported -> HubSyncStatus.UNSUPPORTED
        outcome is OrgSyncOutcome.Offline || outcome is OrgSyncOutcome.NetworkError -> HubSyncStatus.FAILED_OFFLINE
        outcome is OrgSyncOutcome.Success -> HubSyncStatus.OK
        outcome != null && outcome !is OrgSyncOutcome.NotEnrolled && outcome !is OrgSyncOutcome.RateLimited -> HubSyncStatus.FAILED_OTHER
        snap.readFailed -> HubSyncStatus.FAILED_OTHER
        snap.lastSyncedAtMs == null -> HubSyncStatus.NEVER
        else -> HubSyncStatus.OK
    }

    private fun PersonCore.toUi(p: Presence): HubPersonUi {
        val self = deviceId == localDeviceId
        val lan = !self && (deviceId in p.live || deviceId in p.reachable)
        val hubOnline = p.hub?.contains(deviceId)
        val reach = when {
            self -> HubReachability.UNKNOWN
            lan -> HubReachability.LAN
            hubOnline == true -> HubReachability.HUB
            hubOnline == false -> HubReachability.OFFLINE
            else -> HubReachability.UNKNOWN
        }
        return HubPersonUi(
            deviceId = deviceId,
            displayName = displayName,
            departmentId = departmentId,
            departmentName = departmentName,
            role = role,
            online = if (hiddenPresence || self) null else if (lan) true else hubOnline,
            reachability = reach,
            paired = !self && isPaired(deviceId),
            orgVerified = !self && resolver?.resolve(deviceId)?.bindingOk == true,
            isSelf = self,
        )
    }

    private class Presence(val live: Set<String>, val reachable: Set<String>, val hub: Set<String>?)

    public companion object {
        /** Minimum time between two real refresh attempts (design doc: pull-to-refresh spam, 15 s). */
        public const val MIN_REFRESH_INTERVAL_MS: Long = 15_000L
    }
}

/** What the last refresh left behind, as the state flow reads it. */
private class RefreshView(
    val refreshing: Boolean,
    val outcome: OrgSyncOutcome?,
    val groupsLoadFailed: Boolean,
    val newsUnsupported: Boolean,
)

/** Plain sentences for the result of a refresh (the UI shows [FlashError.Unknown.message] as is). */
public object HubRefreshText {
    public const val NOT_ENROLLED: String = "This device is not part of an organisation."
    public const val GENERIC: String = "Something went wrong talking to your organisation. Try again."
    public const val UNSUPPORTED: String = "This company server does not support offline colleague chat yet."
    public const val RATE_LIMITED: String = "The company server asked Flash to wait. Try again in a minute."
    public const val OFFLINE: String = "Could not reach your organisation. Check your connection and try again."

    /** Success for a finished sync (or none requested); a Failure with one sentence for everything else. */
    @OptIn(FlashInternalApi::class)
    public fun resultOf(outcome: OrgSyncOutcome?): FlashResult<Unit> = when (outcome) {
        null, is OrgSyncOutcome.Success -> FlashResult.Success(Unit)
        is OrgSyncOutcome.NotEnrolled -> FlashResult.Failure(FlashError.Unknown(NOT_ENROLLED))
        is OrgSyncOutcome.Offline, is OrgSyncOutcome.NetworkError -> FlashResult.Failure(FlashError.NetworkUnavailable(OFFLINE))
        is OrgSyncOutcome.HubUnsupported -> FlashResult.Failure(FlashError.Unknown(UNSUPPORTED))
        is OrgSyncOutcome.RateLimited -> FlashResult.Failure(FlashError.Unknown(RATE_LIMITED))
        else -> FlashResult.Failure(FlashError.Unknown(GENERIC))
    }
}

/** Word-prefix matching over folded text. Pure so it is unit-tested and shared by search and the future online fallback. */
public object HubPeopleMatcher {
    /** Folded, non-empty tokens of [query]; empty list for a blank query. */
    public fun tokens(query: String): List<String> =
        foldForSearch(query).split(' ', '\t', '\n').filter { it.isNotEmpty() }

    /** True when every token is a prefix of some word of [folded] (an empty token list matches everything). */
    public fun matches(folded: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        return tokens.all { token -> wordStarts(folded).any { start -> folded.startsWith(token, start) } }
    }

    private fun wordStarts(folded: String): Sequence<Int> = sequence {
        var atStart = true
        for (i in folded.indices) {
            val c = folded[i]
            if (atStart && c != ' ') yield(i)
            atStart = c == ' '
        }
    }
}

internal data class PersonCore(
    val deviceId: String,
    val displayName: String,
    val departmentId: String,
    val departmentName: String,
    val role: HubPersonRole,
    val hiddenPresence: Boolean,
    val sortKey: String,
    val searchText: String,
)

internal data class Snapshot(
    val enrolled: Boolean,
    val orgName: String,
    val myDepartmentId: String?,
    val myRole: HubPersonRole,
    val people: List<PersonCore>,
    val departments: List<HubDepartmentUi>,
    val lastSyncedAtMs: Long?,
    val readFailed: Boolean = false,
) {
    companion object {
        val UNENROLLED = Snapshot(false, "", null, HubPersonRole.PERSON, emptyList(), emptyList(), null)
    }
}

/** Builds the visible, sorted snapshot from the raw rows. Pure: no DAO, no clock. */
internal object HubSnapshotBuilder {

    fun roleOf(raw: String): HubPersonRole = when (raw.trim().lowercase().replace('-', '_')) {
        "admin", "org_admin" -> HubPersonRole.ADMIN
        "dept_admin" -> HubPersonRole.HEAD
        else -> HubPersonRole.PERSON
    }

    /** Device ids in a stored `["id","id"]` array; malformed text yields an empty list, never an exception. */
    fun parseHeadIds(json: String): List<String> {
        val s = json.trim()
        if (s.length > MAX_HEADS_JSON || !s.startsWith('[') || !s.endsWith(']')) return emptyList()
        val out = ArrayList<String>()
        var i = 1
        val end = s.length - 1
        while (i < end) {
            val c = s[i]
            when {
                c == ' ' || c == ',' -> i++
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    var closed = false
                    while (i < end) {
                        val d = s[i]
                        if (d == '\\') {
                            if (i + 1 >= end) return emptyList()
                            sb.append(s[i + 1])
                            i += 2
                        } else if (d == '"') {
                            closed = true
                            i++
                            break
                        } else {
                            sb.append(d)
                            i++
                        }
                    }
                    if (!closed) return emptyList()
                    if (sb.isNotEmpty()) out += sb.toString()
                }
                else -> return emptyList()
            }
            if (out.size > MAX_HEADS) return out.take(MAX_HEADS)
        }
        return out
    }

    fun build(
        creds: EnrollmentCredentials,
        localDeviceId: String,
        members: List<OrgDirectoryMemberEntity>,
        departments: List<OrgDepartmentEntity>,
        syncState: OrgSyncStateEntity?,
    ): Snapshot {
        val myRole = roleOf(creds.role)
        val myDept = creds.department?.takeIf { it.isNotBlank() }
        val isAdmin = myRole == HubPersonRole.ADMIN
        fun isMine(d: OrgDepartmentEntity) =
            myDept != null && (d.id.equals(myDept, ignoreCase = true) || d.name.equals(myDept, ignoreCase = true))
        val visibleDepts = departments.filter { !it.hidden || isAdmin || isMine(it) }
        val visibleIds = visibleDepts.mapTo(HashSet()) { it.id }
        val hiddenIds = departments.filter { it.hidden && it.id !in visibleIds }.mapTo(HashSet()) { it.id }
        val nameById = departments.associate { it.id to it.name }
        val hiddenAnyone = departments.filter { it.hidden }.mapTo(HashSet()) { it.id }
        val myDeptId = departments.firstOrNull { isMine(it) }?.id ?: myDept

        val headIds: Map<String, List<String>> = visibleDepts.associate { it.id to parseHeadIds(it.headDeviceIdsJson) }
        val headOf = HashSet<String>().also { set -> headIds.values.forEach { set.addAll(it) } }

        val cores = members.asSequence()
            .filter { it.deviceId == localDeviceId || it.departmentId !in hiddenIds }
            .map { m ->
                val role = roleOf(m.role).let { if (it == HubPersonRole.PERSON && m.deviceId in headOf) HubPersonRole.HEAD else it }
                val deptName = nameById[m.departmentId].orEmpty()
                val folded = m.displayNameFolded.ifBlank { foldForSearch(m.displayName) }
                PersonCore(
                    deviceId = m.deviceId,
                    displayName = m.displayName,
                    departmentId = m.departmentId,
                    departmentName = deptName,
                    role = role,
                    hiddenPresence = m.departmentId in hiddenAnyone && !(isAdmin || (myDeptId != null && m.departmentId == myDeptId)),
                    sortKey = folded,
                    searchText = buildString {
                        append(folded)
                        if (deptName.isNotEmpty()) append(' ').append(foldForSearch(deptName))
                        when (role) {
                            HubPersonRole.ADMIN -> append(" admin")
                            HubPersonRole.HEAD -> append(" head")
                            HubPersonRole.PERSON -> Unit
                        }
                    },
                )
            }
            .sortedWith(compareBy<PersonCore>({ it.sortKey }, { it.deviceId }))
            .toList()

        val nameByDevice = members.associate { it.deviceId to it.displayName }
        val memberCounts = cores.groupingBy { it.departmentId }.eachCount()
        val depts = visibleDepts
            .map { d ->
                val heads = headIds[d.id].orEmpty()
                HubDepartmentUi(
                    id = d.id,
                    name = d.name,
                    memberCount = if (memberCounts.containsKey(d.id)) memberCounts.getValue(d.id) else d.memberCount,
                    groupCount = 0,
                    isMine = isMine(d),
                    headIds = heads,
                    headNames = heads.mapNotNull { nameByDevice[it] },
                    hidden = d.hidden,
                )
            }
            .sortedWith(compareBy<HubDepartmentUi>({ !it.isMine }, { foldForSearch(it.name) }, { it.id }))

        return Snapshot(
            enrolled = true,
            orgName = creds.hubName,
            myDepartmentId = myDeptId,
            myRole = myRole,
            people = cores,
            departments = depts,
            lastSyncedAtMs = syncState?.lastSyncSuccessMs?.takeIf { it > 0L },
        )
    }

    private const val MAX_HEADS_JSON = 64 * 1024
    private const val MAX_HEADS = 200
}
