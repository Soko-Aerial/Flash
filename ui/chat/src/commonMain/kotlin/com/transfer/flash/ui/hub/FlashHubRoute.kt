@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.messaging.model.HubUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * What the host contributes to the Hub tab: platform behaviour (open a link, open a chat, call) and the little state the
 * hosts persist for it. Everything the Hub reads or writes about the organisation goes through the repository instead.
 */
public class FlashHubHost(
    public val onOpenConversation: (String) -> Unit,
    public val onOpenGroup: (String) -> Unit,
    public val onOpenLink: (String) -> Unit,
    public val onSignOut: () -> Unit,
    /** Voice call over the LAN; null hides Call. */
    public val onCall: ((deviceId: String, displayName: String) -> Unit)? = null,
    /**
     * The host's own extra call gate for this person (for example "already in a call"). The trust rule itself
     * ([FlashHubMath.callAllowed]: paired or verified organisation colleague, reachable on the LAN) is applied by the route.
     */
    public val callAllowed: (HubPersonUi) -> Boolean = { true },
    /** Pairing hook owned by the pairing track. Null while that track is not built; non-null shows Pair for unpaired people. */
    public val onPairColleague: ((String) -> Unit)? = null,
    /** Opens the existing group sheet for administrators to decide join requests (Hub shows the action only to them). */
    public val onManageGroupRequests: (() -> Unit)? = null,
    /** The organisation's own hub host name: links to it open without a question. */
    public val orgHost: String? = null,
    /** A raw route (`hub/announcement/<id>`, `hub/person/<deviceId>`) waiting to be applied, or null. */
    public val pendingRoute: String? = null,
    public val onRouteConsumed: () -> Unit = {},
    /** The little this tab remembers between runs (last segment, the one-time "Transfers moved" tip). */
    public val prefs: FlashHubUiPrefs = FlashHubUiPrefs.inMemory(),
    public val drafts: FlashHubDraftStore = FlashHubDraftStore.inMemory(),
)

/** What the Hub tab remembers between runs. Each host backs it with its own small store. */
public interface FlashHubUiPrefs {
    /** The `hubLastSegment` value (a [FlashHubSegment] name), or null. */
    public fun lastSegment(): String?
    public fun setLastSegment(name: String)

    /** True once the one-time "Transfers moved to the top bar" tip was dismissed. */
    public fun movedTipSeen(): Boolean
    public fun setMovedTipSeen()

    public companion object {
        public fun inMemory(): FlashHubUiPrefs = object : FlashHubUiPrefs {
            private var segment: String? = null
            private var tip = false
            override fun lastSegment(): String? = segment
            override fun setLastSegment(name: String) { segment = name }
            override fun movedTipSeen(): Boolean = tip
            override fun setMovedTipSeen() { tip = true }
        }
    }
}

/** Saves a [FlashHubSelection] across rotation and process death. Unknown or damaged values restore to the default. */
internal object FlashHubSelectionCodec {
    fun encodeFilter(filter: FlashHubPeopleFilter): String = when (filter) {
        FlashHubPeopleFilter.All -> "all"
        FlashHubPeopleFilter.Mine -> "mine"
        is FlashHubPeopleFilter.Department -> "dept:${filter.id}"
    }

    fun decodeFilter(raw: String?): FlashHubPeopleFilter = when {
        raw == "mine" -> FlashHubPeopleFilter.Mine
        raw != null && raw.startsWith("dept:") && raw.length > "dept:".length -> FlashHubPeopleFilter.Department(raw.removePrefix("dept:"))
        else -> FlashHubPeopleFilter.All
    }

    fun encode(s: FlashHubSelection): List<Any?> =
        listOf(s.segment.name, s.searchOpen, s.query, encodeFilter(s.peopleFilter), s.personId, s.announcementId, s.departmentId)

    fun decode(list: List<Any?>): FlashHubSelection? {
        if (list.size != 7) return null
        return FlashHubSelection(
            segment = FlashHubMath.segmentFromName(list[0] as? String) ?: FlashHubSegment.PEOPLE,
            searchOpen = list[1] as? Boolean ?: false,
            query = (list[2] as? String).orEmpty().take(FlashHubMath.QUERY_MAX * 2),
            peopleFilter = decodeFilter(list[3] as? String),
            personId = (list[4] as? String)?.takeIf { it.isNotEmpty() },
            announcementId = (list[5] as? String)?.takeIf { it.isNotEmpty() },
            departmentId = (list[6] as? String)?.takeIf { it.isNotEmpty() },
        )
    }

    val saver: Saver<FlashHubSelection, Any> = listSaver(
        save = { encode(it) },
        restore = { decode(it) ?: FlashHubSelection() },
    )
}

/**
 * UI-058 Hub tab bound to a [FlashChatRepository]: collects the repository's Hub read model, runs the actions through the
 * repository, and keeps the selection (segment, search, open person / announcement / department) across rotation.
 */
@Composable
public fun FlashHubRoute(
    repository: FlashChatRepository,
    link: HubLinkState,
    host: FlashHubHost,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var selection by rememberSaveable(stateSaver = FlashHubSelectionCodec.saver) { mutableStateOf(FlashHubSelection()) }
    var chosenSegment by rememberSaveable { mutableStateOf(false) }
    var tipSeen by remember(host) { mutableStateOf(host.prefs.movedTipSeen()) }

    val state by remember(repository) { repository.hubState }.collectAsState(initial = HubUiState(enrolled = true, loaded = false))
    val news by remember(repository) { repository.hubAnnouncements }.collectAsState(initial = emptyList<HubAnnouncementUi>())
    val posting by remember(repository) { repository.canPostHubAnnouncement }.collectAsState(initial = HubPostingRights.None)
    val departments by remember(repository) { repository.hubDepartments }.collectAsState(initial = emptyList<HubDepartmentUi>())
    val groups by remember(repository) { repository.hubGroups }.collectAsState(initial = emptyList<HubGroupUi>())
    val allPeople by remember(repository) { repository.hubPeople }.collectAsState(initial = emptyList<HubPersonUi>())

    val query = FlashHubMath.normaliseQuery(selection.query)
    val searching = selection.searchOpen && query.isNotEmpty()
    val people by remember(repository, searching, query) {
        if (searching) repository.searchHubPeople(query) else flowOf(allPeople)
    }.collectAsState(initial = allPeople)
    val openedPerson by remember(repository, selection.personId) {
        selection.personId?.let { repository.hubPerson(it) } ?: flowOf<HubPersonUi?>(null)
    }.collectAsState(initial = null)
    val departmentMembers by remember(repository, selection.departmentId) {
        selection.departmentId?.let { repository.searchHubPeople("", it) } ?: flowOf(emptyList<HubPersonUi>())
    }.collectAsState(initial = emptyList())

    val nowMs by produceState(initialValue = SystemTimeSource.nowMs()) {
        while (true) {
            delay(30_000)
            value = SystemTimeSource.nowMs()
        }
    }

    // The first time the tab has content: News when something is unread, otherwise the segment used last.
    val unreadCount by remember { derivedStateOf { news.count { !it.isRead } } }
    LaunchedEffect(state.loaded, chosenSegment) {
        if (state.loaded && !chosenSegment) {
            chosenSegment = true
            val first = FlashHubMath.defaultSegment(unreadCount, FlashHubMath.segmentFromName(host.prefs.lastSegment()))
            if (selection.personId == null && selection.announcementId == null && selection.departmentId == null) {
                selection = selection.copy(segment = first)
            }
        }
    }
    LaunchedEffect(selection.segment, chosenSegment) {
        if (chosenSegment) host.prefs.setLastSegment(selection.segment.name)
    }

    val actions = remember(repository, host) {
        FlashHubActions(
            onRefresh = { scope.launch { repository.refreshHub(userInitiated = true) } },
            onSignOut = host.onSignOut,
            onMarkRead = { id -> scope.launch { repository.markHubAnnouncementRead(id) } },
            onOpenConversation = host.onOpenConversation,
            startConversation = { id -> repository.startColleagueConversation(id) },
            postAnnouncement = { draft -> repository.postHubAnnouncement(draft) },
            editAnnouncement = { id, draft, seq -> repository.editHubAnnouncement(id, draft, seq) },
            deleteAnnouncement = { id -> repository.deleteHubAnnouncement(id) },
            pinAnnouncement = { id, pinned -> repository.setHubAnnouncementPinned(id, pinned) },
            onOpenLink = host.onOpenLink,
            groupAction = { g -> repository.joinEnterpriseGroup(g.groupId, g.name) },
            onOpenGroup = host.onOpenGroup,
            onRetryGroups = { scope.launch { repository.refreshHub(userInitiated = true) } },
            onDismissTip = { tipSeen = true; host.prefs.setMovedTipSeen() },
            onRouteConsumed = host.onRouteConsumed,
            onCall = host.onCall,
            callAllowed = { p -> FlashHubMath.callAllowed(p, host.callAllowed(p)) },
            onPairColleague = host.onPairColleague,
            onManageGroupRequests = host.onManageGroupRequests,
            drafts = host.drafts,
        )
    }

    val data = FlashHubScreenData(
        state = state,
        link = link,
        nowMs = nowMs,
        news = news,
        newsUnsupported = state.newsUnsupported,
        posting = posting,
        people = people,
        departments = departments,
        groups = groups,
        groupsLoadError = if (state.groupsLoadFailed) FlashHubCopy.GROUPS_NOT_LOADED_BODY else null,
        openedPerson = openedPerson,
        departmentMembers = departmentMembers,
        orgHost = host.orgHost,
        showMovedTip = !tipSeen,
        pendingRoute = FlashHubMath.parseRoute(host.pendingRoute),
    )
    FlashHubScreen(data = data, selection = selection, onSelection = { selection = it }, actions = actions, modifier = modifier)
}
