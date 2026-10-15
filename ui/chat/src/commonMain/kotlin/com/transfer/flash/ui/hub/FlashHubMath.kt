@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.text.foldForSearch
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.core.messaging.model.HubUiState
import com.transfer.flash.ui.settings.FlashHubStatusMath

/** The four parts of the Hub screen, in display order. */
public enum class FlashHubSegment { NEWS, PEOPLE, DEPARTMENTS, GROUPS }

/** Tone of the status strip. Never colour alone: every tone also has an icon and a text. */
public enum class FlashHubStripTone { CALM, INFO, WARNING }

/** What the status strip offers besides its text. */
public enum class FlashHubStripAction { NONE, WHY, RETRY }

/** The status strip as a value: text, tone and the one optional action. */
public data class FlashHubStrip(
    public val text: String,
    public val tone: FlashHubStripTone,
    public val action: FlashHubStripAction = FlashHubStripAction.NONE,
)

/** What the whole tab shows. [Content] is the normal case; the others replace it (design doc "States"). */
public enum class FlashHubMode {
    /** The device is not enrolled: the tab should not exist. The screen renders nothing. */
    HIDDEN,

    /** First directory read has not finished: skeleton rows. */
    LOADING,

    /** The hub revoked this device: the tab is replaced by the notice and the sign-out action. */
    REVOKED,

    /** Never synced and no connection: the "connect once" empty state with Retry. */
    NEVER_SYNCED_OFFLINE,

    /** Normal content (fresh or cached). */
    CONTENT,
}

/** Which people the list shows. */
public sealed interface FlashHubPeopleFilter {
    public data object All : FlashHubPeopleFilter
    public data object Mine : FlashHubPeopleFilter
    public data class Department(public val id: String) : FlashHubPeopleFilter
}

/** One entry of the People list: a sticky letter header or a colleague. */
public sealed interface FlashHubPeopleItem {
    public val key: String

    public data class Header(public val letter: String) : FlashHubPeopleItem {
        override val key: String get() = "h:$letter"
    }

    public data class Person(public val person: HubPersonUi, public val showDepartment: Boolean) : FlashHubPeopleItem {
        override val key: String get() = "p:${person.deviceId}"
    }
}

/** What a person row/sheet shows as the role badge, or null for a plain colleague. */
public enum class FlashHubAction {
    NEW_ANNOUNCEMENT, EDIT_ANNOUNCEMENT, DELETE_ANNOUNCEMENT, PIN_ANNOUNCEMENT, COPY_ANNOUNCEMENT, OPEN_ANNOUNCEMENT,
    OPEN_LINK, SEARCH, SELECT_SEGMENT, PULL_REFRESH, RETRY_REFRESH, SHOW_WHY, SIGN_OUT, OPEN_PERSON, MESSAGE_PERSON,
    CALL_PERSON, PAIR_PERSON, OPEN_DEPARTMENT, MESSAGE_HEAD, JOIN_GROUP, OPEN_GROUP, DISMISS_TIP, FILTER_PEOPLE, MANAGE_GROUP_REQUESTS,
}

/** Which actions a viewer has on one announcement. */
public data class FlashHubAnnouncementRights(
    public val canEdit: Boolean,
    public val canDelete: Boolean,
    public val canPin: Boolean,
) {
    public val any: Boolean get() = canEdit || canDelete || canPin
}

/** One choice of the composer's audience selector. */
public data class FlashHubAudience(public val scope: String, public val departmentId: String?, public val label: String)

/** Why a post / edit did not happen, as a sentence and what the composer should do about it. */
public data class FlashHubComposerFailure(
    public val text: String,
    /** True when the same text may be sent again unchanged (shows Retry). */
    public val retryable: Boolean,
    /** True when the draft must be kept (always, except after success). */
    public val keepDraft: Boolean = true,
    /** Seconds until a rate-limited post may be retried. */
    public val retryAfterSec: Int? = null,
)

/** The outcome of tapping Message on a person. */
public sealed interface FlashHubMessageOutcome {
    public data class Open(public val conversationId: String) : FlashHubMessageOutcome
    public data class Show(public val text: String) : FlashHubMessageOutcome
}

/** Pure presentation rules of the Hub tab. No Compose, no clocks: `nowMs` is always passed in. */
public object FlashHubMath {

    public const val TITLE_MAX: Int = 120
    public const val BODY_MAX: Int = 4000
    public const val READ_VISIBLE_FRACTION: Float = 0.6f
    public const val READ_VISIBLE_MS: Long = 1000L
    public const val CARD_PREVIEW_LINES: Int = 3
    public const val PIN_LIMIT_PER_SCOPE: Int = 3

    // --- Segments ----------------------------------------------------------------------------------------

    public fun segmentLabel(segment: FlashHubSegment): String = when (segment) {
        FlashHubSegment.NEWS -> FlashHubCopy.SEGMENT_NEWS
        FlashHubSegment.PEOPLE -> FlashHubCopy.SEGMENT_PEOPLE
        FlashHubSegment.DEPARTMENTS -> FlashHubCopy.SEGMENT_DEPARTMENTS
        FlashHubSegment.GROUPS -> FlashHubCopy.SEGMENT_GROUPS
    }

    /** News when something is unread, otherwise the segment used last, otherwise People (owner decision, design doc section 9). */
    public fun defaultSegment(unreadAnnouncements: Int, last: FlashHubSegment?): FlashHubSegment = when {
        unreadAnnouncements > 0 -> FlashHubSegment.NEWS
        last != null -> last
        else -> FlashHubSegment.PEOPLE
    }

    /** Reads the persisted `hubLastSegment` value; unknown or old values give null (never a crash). */
    public fun segmentFromName(name: String?): FlashHubSegment? =
        FlashHubSegment.entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

    /** Tab reselect: first reselect scrolls to top, a second one in a row jumps to News (design doc "Interaction"). */
    public enum class ReselectResult { SCROLL_TOP, JUMP_TO_NEWS }

    public fun reselect(alreadyAtTop: Boolean, current: FlashHubSegment): ReselectResult =
        if (alreadyAtTop && current != FlashHubSegment.NEWS) ReselectResult.JUMP_TO_NEWS else ReselectResult.SCROLL_TOP

    // --- Mode and status strip ---------------------------------------------------------------------------

    public fun mode(state: HubUiState, link: HubLinkState): FlashHubMode = when {
        link == HubLinkState.REVOKED -> FlashHubMode.REVOKED
        !state.enrolled -> FlashHubMode.HIDDEN
        !state.loaded -> FlashHubMode.LOADING
        state.syncStatus == HubSyncStatus.NEVER && state.peopleCount == 0 && !hubReachable(link, state.syncStatus) ->
            FlashHubMode.NEVER_SYNCED_OFFLINE
        else -> FlashHubMode.CONTENT
    }

    private fun hubReachable(link: HubLinkState, sync: HubSyncStatus): Boolean =
        link == HubLinkState.ONLINE && sync != HubSyncStatus.FAILED_OFFLINE

    /** The strip for [FlashHubMode.CONTENT] and [FlashHubMode.LOADING]; null in the other modes. Highest priority first. */
    public fun strip(state: HubUiState, link: HubLinkState, nowMs: Long): FlashHubStrip? {
        if (mode(state, link) !in setOf(FlashHubMode.CONTENT, FlashHubMode.LOADING)) return null
        if (state.clockWrong) return FlashHubStrip(FlashHubCopy.STRIP_CLOCK_WRONG, FlashHubStripTone.WARNING)
        if (state.identityMismatch) {
            return FlashHubStrip(
                FlashHubStatusMath.orgTrustStatusText(FlashHubStatusMath.OrgTrustState.IdentityMismatch),
                FlashHubStripTone.WARNING,
            )
        }
        if (state.syncStatus == HubSyncStatus.UNSUPPORTED) {
            return FlashHubStrip(FlashHubCopy.STRIP_UNSUPPORTED, FlashHubStripTone.INFO)
        }
        val trust = FlashHubStatusMath.orgTrustState(
            lease = state.lease,
            clockOk = true,
            hubSupports = true,
            identityMismatch = false,
            nowMs = nowMs,
        )
        if (trust is FlashHubStatusMath.OrgTrustState.Expired && state.syncStatus != HubSyncStatus.NEVER) {
            return FlashHubStrip(FlashHubCopy.STRIP_LEASE_EXPIRED, FlashHubStripTone.WARNING, FlashHubStripAction.WHY)
        }
        if (state.isRefreshing) return FlashHubStrip(FlashHubCopy.STRIP_UPDATING, FlashHubStripTone.CALM)
        val age = state.lastSyncedAtMs?.let { relativeTime(it, nowMs) }
        val offline = state.syncStatus == HubSyncStatus.FAILED_OFFLINE || link != HubLinkState.ONLINE
        return when {
            state.syncStatus == HubSyncStatus.FAILED_OTHER ->
                FlashHubStrip(
                    if (age != null) "${FlashHubCopy.STRIP_UPDATE_FAILED} · showing information from $age" else FlashHubCopy.STRIP_UPDATE_FAILED,
                    FlashHubStripTone.INFO,
                    FlashHubStripAction.RETRY,
                )
            offline ->
                FlashHubStrip(
                    if (age != null) "Offline · showing information from $age" else "Offline",
                    FlashHubStripTone.INFO,
                    FlashHubStripAction.RETRY,
                )
            trust is FlashHubStatusMath.OrgTrustState.Expiring ->
                FlashHubStrip(FlashHubStatusMath.orgTrustStatusText(trust), FlashHubStripTone.WARNING, FlashHubStripAction.WHY)
            else -> FlashHubStrip(if (age != null) "Connected · updated $age" else "Connected", FlashHubStripTone.CALM)
        }
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago". Future times and clock jumps read as "just now". */
    public fun relativeTime(atMs: Long, nowMs: Long): String {
        val s = ((nowMs - atMs).coerceAtLeast(0L)) / 1000L
        return when {
            s < 45L -> "just now"
            s < 90L -> "1 minute ago"
            s < 3600L -> "${(s + 30L) / 60L} minutes ago"
            s < 5400L -> "1 hour ago"
            s < 86_400L -> "${(s + 1800L) / 3600L} hours ago"
            s < 172_800L -> "yesterday"
            else -> "${s / 86_400L} days ago"
        }
    }

    // --- People ------------------------------------------------------------------------------------------

    public fun roleBadge(role: HubPersonRole): String? = when (role) {
        HubPersonRole.PERSON -> null
        HubPersonRole.HEAD -> FlashHubCopy.ROLE_HEAD
        HubPersonRole.ADMIN -> FlashHubCopy.ROLE_ADMIN
    }

    public fun reachabilityLine(reachability: HubReachability): String = when (reachability) {
        HubReachability.LAN -> FlashHubCopy.REACH_LAN
        HubReachability.HUB -> FlashHubCopy.REACH_HUB
        HubReachability.OFFLINE -> FlashHubCopy.REACH_OFFLINE
        HubReachability.UNKNOWN -> FlashHubCopy.REACH_UNKNOWN
    }

    /** "Online" / "Offline" for the row; null when presence is withheld or unknown (the row then shows no dot and no word). */
    public fun onlineWord(online: Boolean?): String? = when (online) {
        true -> FlashHubCopy.ONLINE
        false -> FlashHubCopy.OFFLINE
        null -> null
    }

    public fun initials(name: String): String {
        val words = name.trim().split(' ', '\t').filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> firstCodePoint(words[0])
            else -> firstCodePoint(words.first()) + firstCodePoint(words.last())
        }
    }

    private fun firstCodePoint(word: String): String {
        val c = word[0]
        return if (c.isHighSurrogate() && word.length > 1) word.substring(0, 2) else c.toString()
    }

    /** The talk-back sentence of one row: "<name>, <department>, <role>, <online/offline>". */
    public fun personAccessibility(person: HubPersonUi, includeDepartment: Boolean = true): String = buildList {
        add(if (person.isSelf) "${person.displayName}, ${FlashHubCopy.PEOPLE_YOU}" else person.displayName)
        if (includeDepartment && person.departmentName.isNotBlank()) add(person.departmentName)
        roleBadge(person.role)?.let { add(it) }
        if (person.paired) add(FlashHubCopy.PEOPLE_PAIRED)
        onlineWord(person.online)?.let { add(it) }
    }.joinToString(", ")

    /** Sticky-header letter: the first letter without accents, upper case; anything else groups under "#". */
    public fun letterFor(name: String): String {
        val folded = foldForSearch(name.trim())
        val c = folded.firstOrNull() ?: return "#"
        return if (c in 'a'..'z') c.uppercaseChar().toString() else "#"
    }

    /** Short id tail shown under a name that more than one colleague has (design situation 7). */
    public fun idSuffix(deviceId: String): String = deviceId.takeLast(4)

    /**
     * The list entries with sticky letter headers. Input is already sorted by the data layer; this is O(n) and must run
     * off the main thread for big lists (the screen does that). Names that occur more than once keep their department
     * visible; the department is shown on every row anyway unless the list is filtered to one department.
     */
    public fun peopleItems(people: List<HubPersonUi>, filter: FlashHubPeopleFilter, withHeaders: Boolean = true): List<FlashHubPeopleItem> {
        val out = ArrayList<FlashHubPeopleItem>(people.size + 27)
        var lastLetter: String? = null
        val showDepartment = filter !is FlashHubPeopleFilter.Department && filter !is FlashHubPeopleFilter.Mine
        for (p in people) {
            if (withHeaders) {
                val letter = letterFor(p.displayName)
                if (letter != lastLetter) {
                    out.add(FlashHubPeopleItem.Header(letter))
                    lastLetter = letter
                }
            }
            out.add(FlashHubPeopleItem.Person(p, showDepartment))
        }
        return out
    }

    /** The department id to query for a filter, or null for "everyone". */
    public fun filterDepartmentId(filter: FlashHubPeopleFilter, myDepartmentId: String?): String? = when (filter) {
        FlashHubPeopleFilter.All -> null
        FlashHubPeopleFilter.Mine -> myDepartmentId
        is FlashHubPeopleFilter.Department -> filter.id
    }

    /** Empty text of the People segment for the current query and filter. */
    public fun peopleEmptyText(query: String, filter: FlashHubPeopleFilter): String = when {
        query.isNotBlank() -> FlashHubCopy.noOneNamed(query.trim())
        filter !is FlashHubPeopleFilter.All -> FlashHubCopy.NO_PEOPLE_IN_DEPARTMENT
        else -> FlashHubCopy.NO_PEOPLE
    }

    // --- Person sheet and Message / Call -----------------------------------------------------------------

    public data class PersonSheet(
        public val title: String,
        public val departmentLine: String,
        public val roleBadge: String?,
        public val reachabilityLine: String,
        public val idLine: String,
        public val footer: String?,
        public val honesty: String?,
        public val messageLabel: String,
        public val messageEnabled: Boolean,
        public val showCall: Boolean,
        public val showPair: Boolean,
        public val pairedBadge: Boolean,
    )

    /**
     * What the person sheet shows. [callAllowed] is the host's existing call gate (a call is offered only on the LAN and only
     * when that gate allows it); [pairAvailable] says whether the host wired the pairing hook (H8 leaves it to the pairing track).
     */
    public fun personSheet(person: HubPersonUi, callAllowed: Boolean, pairAvailable: Boolean): PersonSheet = PersonSheet(
        title = if (person.isSelf) "${person.displayName} (${FlashHubCopy.PEOPLE_YOU})" else person.displayName,
        departmentLine = person.departmentName.ifBlank { FlashHubCopy.PERSON_DEPARTMENT_UNKNOWN },
        roleBadge = roleBadge(person.role),
        reachabilityLine = if (person.isSelf) FlashHubCopy.PEOPLE_YOU else reachabilityLine(person.reachability),
        idLine = FlashHubCopy.idSuffixLine(idSuffix(person.deviceId)),
        footer = if (!person.paired && !person.isSelf) FlashHubCopy.PERSON_UNPAIRED_FOOTER else null,
        honesty = if (person.orgVerified && !person.isSelf) FlashHubCopy.PERSON_HONESTY else null,
        messageLabel = if (person.isSelf) FlashHubCopy.PERSON_MESSAGE_SELF else FlashHubCopy.PERSON_MESSAGE,
        messageEnabled = !person.isSelf,
        showCall = !person.isSelf && callAllowed && person.reachability == HubReachability.LAN,
        showPair = !person.isSelf && !person.paired && pairAvailable,
        pairedBadge = person.paired && !person.isSelf,
    )

    /**
     * Turns the repository's answer to "start a conversation" into what the sheet does. A paired colleague is opened
     * directly (their conversation id is their device id; no second conversation is created). Every failure becomes one
     * plain sentence, never a code.
     */
    public fun messageOutcome(person: HubPersonUi, result: FlashResult<String>?): FlashHubMessageOutcome = when {
        person.isSelf -> FlashHubMessageOutcome.Show("That is you.")
        result == null -> FlashHubMessageOutcome.Open(person.deviceId)
        result is FlashResult.Success -> FlashHubMessageOutcome.Open(result.value)
        else -> FlashHubMessageOutcome.Show(failureSentence((result as FlashResult.Failure).error))
    }

    private fun failureSentence(error: FlashError): String = when (error) {
        is FlashError.Unknown -> error.message.takeIf { plain(it) } ?: FlashHubCopy.MESSAGE_FAILED
        is FlashError.NetworkUnavailable, is FlashError.ConnectionTimeout, is FlashError.PeerUnavailable ->
            "Could not reach your organisation. Check your connection and try again."
        else -> FlashHubCopy.MESSAGE_FAILED
    }

    private fun plain(text: String): Boolean =
        text.isNotBlank() && text.length <= 160 &&
            listOf("exception", "http", "{", "}", "stack", "null", "java.", "kotlin.").none { text.contains(it, ignoreCase = true) }

    // --- Departments -------------------------------------------------------------------------------------

    public fun departmentSummary(d: HubDepartmentUi): String {
        val members = if (d.memberCount == 1) "1 person" else "${d.memberCount} people"
        val groups = if (d.groupCount == 1) "1 group" else "${d.groupCount} groups"
        return "$members · $groups"
    }

    public fun headsLine(d: HubDepartmentUi): String =
        if (d.headNames.isEmpty()) FlashHubCopy.DEPT_NO_HEADS else d.headNames.joinToString(", ")

    /** Mine first, then by folded name; the data layer already does this, the sort is stable and cheap for a few dozen. */
    public fun orderDepartments(list: List<HubDepartmentUi>): List<HubDepartmentUi> =
        list.sortedWith(compareByDescending<HubDepartmentUi> { it.isMine }.thenBy { foldForSearch(it.name) }.thenBy { it.id })

    /** A department by id, or null when the caller may not see it (never says whether it exists). */
    public fun visibleDepartment(list: List<HubDepartmentUi>, id: String): HubDepartmentUi? = list.firstOrNull { it.id == id }

    public fun departmentAccessibility(d: HubDepartmentUi): String = buildList {
        add(d.name)
        if (d.isMine) add(FlashHubCopy.DEPT_YOURS)
        add(departmentSummary(d))
        add(headsLine(d))
    }.joinToString(", ")

    // --- Groups ------------------------------------------------------------------------------------------

    public data class GroupSection(public val title: String, public val groups: List<HubGroupUi>)

    /** "Yours" (joined and waiting) first, then one section per department, "Other" last. Order inside a section is kept. */
    public fun groupSections(groups: List<HubGroupUi>): List<GroupSection> {
        if (groups.isEmpty()) return emptyList()
        val (yours, rest) = groups.partition { it.membership != HubGroupMembership.NOT_JOINED }
        val byDept = rest.groupBy { it.departmentName.trim() }
        val sections = ArrayList<GroupSection>()
        if (yours.isNotEmpty()) sections.add(GroupSection(FlashHubCopy.GROUPS_YOURS, yours))
        byDept.keys.filter { it.isNotEmpty() }.sortedBy { foldForSearch(it) }.forEach { sections.add(GroupSection(it, byDept.getValue(it))) }
        byDept[""]?.let { sections.add(GroupSection(FlashHubCopy.GROUPS_OTHER, it)) }
        return sections
    }

    public fun groupButtonLabel(g: HubGroupUi): String = when (g.membership) {
        HubGroupMembership.JOINED -> FlashHubCopy.GROUPS_OPEN
        HubGroupMembership.PENDING -> FlashHubStatusMath.joinButtonLabel(g.requiresApproval, joined = false, pending = true)
        HubGroupMembership.NOT_JOINED -> FlashHubStatusMath.joinButtonLabel(g.requiresApproval, joined = false, pending = false)
    }

    public fun groupButtonEnabled(g: HubGroupUi, busy: Boolean): Boolean = !busy && g.membership != HubGroupMembership.PENDING

    /**
     * Whether the Groups segment offers "Review group join requests". Only an administrator's groups carry a request count
     * ([HubGroupUi.pendingRequests] non-null), and only when the host supplied the sheet that decides them.
     */
    /** Call needs a trusted colleague (paired, or verified through the organisation) on the same network, and the host's own gate. */
    public fun callAllowed(person: HubPersonUi, hostAllows: Boolean): Boolean =
        hostAllows && !person.isSelf && person.reachability == HubReachability.LAN &&
            com.transfer.flash.core.messaging.PeerTrustPolicy.mayRing(isPaired = person.paired, isOrg = person.orgVerified)

    public fun showManageRequests(groups: List<HubGroupUi>, hookAvailable: Boolean): Boolean =
        hookAvailable && groups.any { it.pendingRequests != null }

    public fun groupMeta(g: HubGroupUi): String = buildList {
        add(if (g.memberCount == 1) "1 member" else "${g.memberCount} members")
        g.onlineCount?.takeIf { it > 0 }?.let { add("$it online") }
        if (g.requiresApproval) add("Needs approval")
        g.pendingRequests?.takeIf { it > 0 }?.let { add(if (it == 1) "1 request waiting" else "$it requests waiting") }
    }.joinToString(" · ")

    // --- News --------------------------------------------------------------------------------------------

    public fun scopeLabel(a: HubAnnouncementUi, departmentName: (String) -> String?): String =
        a.departmentId.let { dept ->
            if (a.scope.equals("org", ignoreCase = true) || dept == null) FlashHubCopy.ANN_SCOPE_COMPANY
            else departmentName(dept)?.takeIf { it.isNotBlank() } ?: FlashHubCopy.ANN_SCOPE_DEPARTMENT
        }

    public fun authorRoleChip(role: String): String? {
        val r = role.lowercase()
        return when {
            r.contains("dept") || r.contains("head") -> FlashHubCopy.ANN_ROLE_HEAD
            r.contains("admin") -> FlashHubCopy.ANN_ROLE_ADMIN
            else -> null
        }
    }

    /** Pinned first, then newest first; expired announcements are dropped; stable by id. Small lists only (tens to hundreds). */
    public fun orderNews(list: List<HubAnnouncementUi>, nowMs: Long): List<HubAnnouncementUi> =
        list.filter { isLive(it, nowMs) }
            .sortedWith(compareByDescending<HubAnnouncementUi> { it.pinned }.thenByDescending { it.createdAtMs }.thenBy { it.id })

    private fun isLive(a: HubAnnouncementUi, nowMs: Long): Boolean = a.expiresAtMs.let { it == null || it > nowMs }

    public fun unreadCount(list: List<HubAnnouncementUi>, nowMs: Long): Int =
        list.count { !it.isRead && isLive(it, nowMs) }

    /** The 60 % / 1 s rule of the design doc: an unread card is marked read after it has been mostly visible long enough. */
    public fun shouldMarkRead(isRead: Boolean, visibleFraction: Float, visibleForMs: Long): Boolean =
        !isRead && visibleFraction >= READ_VISIBLE_FRACTION && visibleForMs >= READ_VISIBLE_MS

    /** Share of an item's height inside the viewport, 0..1. Empty items count as not visible. */
    public fun visibleFraction(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Float {
        if (itemSize <= 0) return 0f
        val visible = (minOf(itemOffset + itemSize, viewportEnd) - maxOf(itemOffset, viewportStart)).coerceAtLeast(0)
        return (visible.toFloat() / itemSize).coerceIn(0f, 1f)
    }

    public fun rights(a: HubAnnouncementUi, posting: HubPostingRights): FlashHubAnnouncementRights = when (posting) {
        HubPostingRights.None -> FlashHubAnnouncementRights(false, false, false)
        is HubPostingRights.OrgWide -> FlashHubAnnouncementRights(true, true, true)
        is HubPostingRights.Department -> {
            val own = !a.scope.equals("org", ignoreCase = true) && a.departmentId.let { it != null && it in posting.departmentIds }
            FlashHubAnnouncementRights(own, own, own)
        }
    }

    public fun canPost(posting: HubPostingRights): Boolean = when (posting) {
        HubPostingRights.None -> false
        is HubPostingRights.OrgWide -> true
        is HubPostingRights.Department -> posting.departmentIds.isNotEmpty()
    }

    /** True when pinning one more in this scope would exceed the limit (the hub enforces it too; this avoids a round trip). */
    public fun pinWouldExceedLimit(list: List<HubAnnouncementUi>, target: HubAnnouncementUi): Boolean =
        !target.pinned && list.count { it.pinned && it.scope == target.scope && it.departmentId == target.departmentId } >= PIN_LIMIT_PER_SCOPE

    /** The audience choices the composer offers for this viewer. */
    public fun audiences(posting: HubPostingRights, departments: List<HubDepartmentUi>): List<FlashHubAudience> {
        val byId = departments.associateBy { it.id }
        return when (posting) {
            HubPostingRights.None -> emptyList()
            is HubPostingRights.OrgWide -> buildList {
                add(FlashHubAudience("org", null, FlashHubCopy.ANN_SCOPE_COMPANY))
                departments.forEach { add(FlashHubAudience("department", it.id, it.name)) }
            }
            is HubPostingRights.Department -> posting.departmentIds.map {
                FlashHubAudience("department", it, byId[it]?.name ?: it)
            }
        }
    }

    public fun codePoints(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    public enum class CounterTone { NORMAL, WARNING, ERROR }

    public fun counterTone(used: Int, max: Int): CounterTone = when {
        used > max -> CounterTone.ERROR
        used * 10 >= max * 9 -> CounterTone.WARNING
        else -> CounterTone.NORMAL
    }

    /** Send is enabled when both fields have text, neither is over its limit and nothing is being sent. */
    public fun canSend(title: String, body: String, sending: Boolean): Boolean =
        !sending && title.isNotBlank() && body.isNotBlank() && codePoints(title.trim()) <= TITLE_MAX && codePoints(body.trim()) <= BODY_MAX

    /** Maps the hub client's typed failure to the composer's sentence (Plan A3 section 9, design doc "Post failed"). */
    public fun composerFailure(error: FlashError): FlashHubComposerFailure = when (error) {
        is FlashError.NetworkUnavailable -> FlashHubComposerFailure(FlashHubCopy.ANN_OFFLINE, retryable = true)
        is FlashError.Domain -> when (error.code) {
            "TOO_LONG" -> FlashHubComposerFailure(FlashHubCopy.annTooLong((error.details as? Int) ?: BODY_MAX), retryable = false)
            "RATE_LIMITED" -> {
                val n = ((error.details as? Int) ?: 5).coerceAtLeast(1)
                FlashHubComposerFailure(FlashHubCopy.annRate(n), retryable = true, retryAfterSec = n)
            }
            "NOT_ALLOWED" -> FlashHubComposerFailure(FlashHubCopy.ANN_NOT_ALLOWED, retryable = false)
            "UNSUPPORTED" -> FlashHubComposerFailure(FlashHubCopy.ANN_UNSUPPORTED, retryable = false)
            "REMOVED" -> FlashHubComposerFailure(FlashHubCopy.ANN_REMOVED, retryable = false)
            "CONFLICT" -> FlashHubComposerFailure(FlashHubCopy.ANN_CONFLICT, retryable = false)
            "PIN_LIMIT" -> FlashHubComposerFailure(FlashHubCopy.ANN_PIN_LIMIT, retryable = false)
            else -> FlashHubComposerFailure(FlashHubCopy.ANN_POST_FAILED, retryable = true)
        }
        else -> FlashHubComposerFailure(FlashHubCopy.ANN_POST_FAILED, retryable = true)
    }

    // --- Links in announcements --------------------------------------------------------------------------

    /** A piece of announcement text: plain, or a link that may be offered for opening. */
    public sealed interface TextPart {
        public data class Plain(public val text: String) : TextPart
        public data class Link(public val text: String, public val url: String) : TextPart
    }

    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '”', '’')

    /** Splits [text] into plain parts and http/https links. Nothing else is ever made tappable (design situation 13). */
    public fun parseLinks(text: String): List<TextPart> {
        val parts = ArrayList<TextPart>()
        var i = 0
        var plainStart = 0
        while (i < text.length) {
            val rest = text.regionMatches(i, "http://", 0, 7, ignoreCase = true) || text.regionMatches(i, "https://", 0, 8, ignoreCase = true)
            val boundary = i == 0 || !text[i - 1].isLetterOrDigit()
            if (rest && boundary) {
                var end = i
                while (end < text.length && !text[end].isWhitespace()) end++
                var cut = end
                while (cut > i && text[cut - 1] in TRAILING) cut--
                val candidate = text.substring(i, cut)
                if (isOpenableLink(candidate)) {
                    if (i > plainStart) parts.add(TextPart.Plain(text.substring(plainStart, i)))
                    parts.add(TextPart.Link(candidate, candidate))
                    plainStart = cut
                    i = cut
                    continue
                }
            }
            i++
        }
        if (plainStart < text.length) parts.add(TextPart.Plain(text.substring(plainStart)))
        return parts
    }

    /** Only http and https with a host; `intent:`, `javascript:`, `file:` and everything else is never launched. */
    public fun isOpenableLink(url: String): Boolean {
        val lower = url.lowercase()
        val afterScheme = when {
            lower.startsWith("https://") -> url.substring(8)
            lower.startsWith("http://") -> url.substring(7)
            else -> return false
        }
        if (url.any { it.isWhitespace() || it.code < 0x20 }) return false
        val host = linkHost(url) ?: return false
        return host.isNotEmpty() && afterScheme.isNotEmpty()
    }

    public fun linkHost(url: String): String? {
        val lower = url.lowercase()
        val start = when {
            lower.startsWith("https://") -> 8
            lower.startsWith("http://") -> 7
            else -> return null
        }
        var end = start
        while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
        val authority = url.substring(start, end)
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    /** Links to the organisation's own host open without the "leaving Flash" question; every other host asks first. */
    public fun linkNeedsConfirmation(url: String, orgHost: String?): Boolean {
        val host = linkHost(url) ?: return true
        val org = orgHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        return host != org
    }


    // --- Search across the screen ------------------------------------------------------------------------

    public const val QUERY_MAX: Int = 200

    /** The query as used for matching: trimmed and capped (a pasted wall of text never reaches the matchers). */
    public fun normaliseQuery(raw: String): String = raw.trim().take(QUERY_MAX)

    private fun foldedContains(haystack: String, folded: String): Boolean = foldForSearch(haystack).contains(folded)

    public fun matchDepartments(list: List<HubDepartmentUi>, query: String): List<HubDepartmentUi> {
        val q = foldForSearch(normaliseQuery(query))
        if (q.isEmpty()) return emptyList()
        return list.filter { foldedContains(it.name, q) || it.headNames.any { h -> foldedContains(h, q) } }
    }

    public fun matchNews(list: List<HubAnnouncementUi>, query: String): List<HubAnnouncementUi> {
        val q = foldForSearch(normaliseQuery(query))
        if (q.isEmpty()) return emptyList()
        return list.filter { foldedContains(it.title, q) || foldedContains(it.body, q) || foldedContains(it.authorName, q) }
    }

    public fun matchGroups(list: List<HubGroupUi>, query: String): List<HubGroupUi> {
        val q = foldForSearch(normaliseQuery(query))
        if (q.isEmpty()) return emptyList()
        return list.filter { foldedContains(it.name, q) || foldedContains(it.departmentName, q) }
    }

    /** What "Enter" opens in the combined search results: the first hit in the order People, Departments, News, Groups. */
    public sealed interface SearchHit {
        public data class Person(public val deviceId: String) : SearchHit
        public data class Department(public val id: String) : SearchHit
        public data class News(public val id: String) : SearchHit
        public data class Group(public val id: String) : SearchHit
    }

    public fun firstHit(
        people: List<HubPersonUi>,
        departments: List<HubDepartmentUi>,
        news: List<HubAnnouncementUi>,
        groups: List<HubGroupUi>,
    ): SearchHit? = people.firstOrNull()?.let { SearchHit.Person(it.deviceId) }
        ?: departments.firstOrNull()?.let { SearchHit.Department(it.id) }
        ?: news.firstOrNull()?.let { SearchHit.News(it.id) }
        ?: groups.firstOrNull()?.let { SearchHit.Group(it.groupId) }

    /** People shown in the combined results before the "see all" action. */
    public const val SEARCH_PEOPLE_PREVIEW: Int = 20

    // --- Deep links --------------------------------------------------------------------------------------

    /** Routes of design section 3 / plan H9. */
    public sealed interface Route {
        public data class Announcement(public val id: String) : Route
        public data class Person(public val deviceId: String) : Route
    }

    public const val ROUTE_ANNOUNCEMENT_PREFIX: String = "hub/announcement/"
    public const val ROUTE_PERSON_PREFIX: String = "hub/person/"
    private const val ROUTE_ID_MAX: Int = 128

    /** What the shell does with a route that arrived from a notification or a cold start. */
    public sealed interface RouteDecision {
        /** The shell does not know yet whether this device is enrolled (engine still starting): keep the route and decide later. */
        public data object Wait : RouteDecision

        /** Not a Hub route at all, or damaged: forget it. */
        public data object Drop : RouteDecision

        /** Select the Hub tab; the Hub screen applies [route] and then reports it consumed. */
        public data class OpenHub(public val route: String) : RouteDecision

        /** The device is not (or no longer) enrolled: land on Chats and say so. The route is forgotten. */
        public data object NotEnrolled : RouteDecision
    }

    public fun routeDecision(raw: String?, enrolmentKnown: Boolean, enrolled: Boolean): RouteDecision = when {
        raw == null -> RouteDecision.Drop
        parseRoute(raw) == null -> RouteDecision.Drop
        !enrolmentKnown -> RouteDecision.Wait
        enrolled -> RouteDecision.OpenHub(raw.trim())
        else -> RouteDecision.NotEnrolled
    }

    public fun routeAnnouncement(id: String): String = ROUTE_ANNOUNCEMENT_PREFIX + id
    public fun routePerson(deviceId: String): String = ROUTE_PERSON_PREFIX + deviceId

    /** Parses `hub/announcement/<id>` and `hub/person/<deviceId>`. Anything else, an empty or oversize id, or odd characters gives null. */
    public fun parseRoute(raw: String?): Route? {
        val s = raw?.trim() ?: return null
        val (prefix, make) = when {
            s.startsWith(ROUTE_ANNOUNCEMENT_PREFIX) -> ROUTE_ANNOUNCEMENT_PREFIX to { id: String -> Route.Announcement(id) }
            s.startsWith(ROUTE_PERSON_PREFIX) -> ROUTE_PERSON_PREFIX to { id: String -> Route.Person(id) }
            else -> return null
        }
        val id = s.removePrefix(prefix)
        if (id.isEmpty() || id.length > ROUTE_ID_MAX) return null
        if (!id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }) return null
        return make(id)
    }
}
