@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.EnterpriseJoinResult
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.messaging.model.HubAnnouncementDraft
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.messaging.model.HubUiState
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashAdaptiveTwoPane
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.chat.FlashSheetHost
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.settings.FlashHubStatusMath
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** Where the user is inside the Hub tab. Owned by the route (it drives the repository queries); saved across rotation. */
public data class FlashHubSelection(
    public val segment: FlashHubSegment = FlashHubSegment.PEOPLE,
    public val searchOpen: Boolean = false,
    public val query: String = "",
    public val peopleFilter: FlashHubPeopleFilter = FlashHubPeopleFilter.All,
    public val personId: String? = null,
    public val announcementId: String? = null,
    public val departmentId: String? = null,
)

/** Everything the screen draws, already sorted and filtered by the data layer (the UI never touches a repository). */
public data class FlashHubScreenData(
    public val state: HubUiState = HubUiState(),
    public val link: HubLinkState = HubLinkState.ONLINE,
    public val nowMs: Long = 0L,
    public val news: List<HubAnnouncementUi> = emptyList(),
    public val newsUnsupported: Boolean = false,
    public val posting: HubPostingRights = HubPostingRights.None,
    /** People for the current query and filter. */
    public val people: List<HubPersonUi> = emptyList(),
    public val departments: List<HubDepartmentUi> = emptyList(),
    public val groups: List<HubGroupUi> = emptyList(),
    public val groupsLoadError: String? = null,
    /** The person of [FlashHubSelection.personId], looked up independently of the filter. */
    public val openedPerson: HubPersonUi? = null,
    /** Members of the department of [FlashHubSelection.departmentId]. */
    public val departmentMembers: List<HubPersonUi> = emptyList(),
    /** The organisation's own hub host: links to it open without a question. */
    public val orgHost: String? = null,
    /** Show the one-time "Transfers moved to the top bar" tip. */
    public val showMovedTip: Boolean = false,
    /** A deep link waiting to be applied (cold start / notification). */
    public val pendingRoute: FlashHubMath.Route? = null,
)

/** Where announcement drafts are kept per audience (Plan A3: the draft survives an offline failure and a restart). */
public interface FlashHubDraftStore {
    public fun load(key: String): Pair<String, String>?
    public fun save(key: String, title: String, body: String)
    public fun clear(key: String)

    public companion object {
        /** Process-lifetime store, used when the host has no persistent one. */
        public fun inMemory(): FlashHubDraftStore = object : FlashHubDraftStore {
            private val map = HashMap<String, Pair<String, String>>()
            override fun load(key: String): Pair<String, String>? = map[key]
            override fun save(key: String, title: String, body: String) { map[key] = title to body }
            override fun clear(key: String) { map.remove(key) }
        }
    }
}

/** Every action the Hub tab can take. Hosts supply all of them; the optional ones hide their button when null. */
public class FlashHubActions(
    public val onRefresh: () -> Unit,
    public val onSignOut: () -> Unit,
    public val onMarkRead: (String) -> Unit,
    public val onOpenConversation: (String) -> Unit,
    public val startConversation: suspend (String) -> FlashResult<String>,
    public val postAnnouncement: suspend (HubAnnouncementDraft) -> FlashResult<HubAnnouncementUi>,
    public val editAnnouncement: suspend (String, HubAnnouncementDraft, Long) -> FlashResult<HubAnnouncementUi>,
    public val deleteAnnouncement: suspend (String) -> FlashResult<Unit>,
    public val pinAnnouncement: suspend (String, Boolean) -> FlashResult<Unit>,
    public val onOpenLink: (String) -> Unit,
    public val groupAction: suspend (HubGroupUi) -> FlashResult<EnterpriseJoinResult>,
    public val onOpenGroup: (String) -> Unit,
    public val onRetryGroups: () -> Unit,
    public val onDismissTip: () -> Unit,
    public val onRouteConsumed: () -> Unit,
    /** Voice call over the LAN; null hides Call. */
    public val onCall: ((String) -> Unit)? = null,
    /** The host's existing call gate for this person; a call button needs both this and [onCall]. */
    public val callAllowed: (HubPersonUi) -> Boolean = { false },
    /** Pairing hook owned by the pairing track (H8 leaves it null); non-null shows "Pair" for unpaired colleagues. */
    public val onPairColleague: ((String) -> Unit)? = null,
    /** Opens the host's group sheet where an administrator decides join requests; null hides the action. */
    public val onManageGroupRequests: (() -> Unit)? = null,
    public val drafts: FlashHubDraftStore = FlashHubDraftStore.inMemory(),
)

/**
 * UI-058 Hub tab. Stateless about data (everything arrives in [data] and [selection]); holds only transient UI state
 * (open dialogs, composer text). Compact widths use sheets and full-screen readers, wide widths use the adaptive
 * two-pane layout with the detail pane to the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun FlashHubScreen(
    data: FlashHubScreenData,
    selection: FlashHubSelection,
    onSelection: (FlashHubSelection) -> Unit,
    actions: FlashHubActions,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val mode = FlashHubMath.mode(data.state, data.link)
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.backgroundApp)) {
        val twoPane = FlashAdaptiveMath.isTwoPaneAllowed(FlashAdaptiveMath.windowSizeForWidth(maxWidth.value))
        when (mode) {
            FlashHubMode.HIDDEN -> Unit
            FlashHubMode.REVOKED -> FlashHubRevokedView(actions.onSignOut)
            else -> FlashHubBody(mode, data, selection, onSelection, actions, twoPane, maxWidth.value)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlashHubBody(
    mode: FlashHubMode,
    data: FlashHubScreenData,
    selection: FlashHubSelection,
    onSelection: (FlashHubSelection) -> Unit,
    actions: FlashHubActions,
    twoPane: Boolean,
    widthDp: Float,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }

    var whyOpen by rememberSaveable { mutableStateOf(false) }
    var menuAnnouncementId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteConfirmId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkConfirm by remember { mutableStateOf<String?>(null) }
    var composer by remember { mutableStateOf<FlashHubComposerState?>(null) }
    var personBusy by remember { mutableStateOf(false) }
    var personNote by remember { mutableStateOf<String?>(null) }
    var actionNote by remember { mutableStateOf<String?>(null) }
    var joiningGroupId by remember { mutableStateOf<String?>(null) }
    var groupFeedback by remember { mutableStateOf<String?>(null) }

    val state = data.state
    val news = remember(data.news, data.nowMs / 60_000L) { FlashHubMath.orderNews(data.news, data.nowMs) }
    val unread = remember(news) { FlashHubMath.unreadCount(news, data.nowMs) }
    val deptName: (String) -> String? = remember(data.departments) { { id -> data.departments.firstOrNull { it.id == id }?.name } }
    val query = FlashHubMath.normaliseQuery(selection.query)
    val searching = selection.searchOpen && query.isNotEmpty()

    // --- A deep link waiting to be applied --------------------------------------------------------------
    LaunchedEffect(data.pendingRoute) {
        when (val route = data.pendingRoute) {
            null -> Unit
            is FlashHubMath.Route.Announcement ->
                onSelection(selection.copy(segment = FlashHubSegment.NEWS, announcementId = route.id, searchOpen = false, query = ""))
            is FlashHubMath.Route.Person ->
                onSelection(selection.copy(segment = FlashHubSegment.PEOPLE, personId = route.deviceId, searchOpen = false, query = ""))
        }
        if (data.pendingRoute != null) actions.onRouteConsumed()
    }

    // --- An announcement removed while it is open -------------------------------------------------------
    val openAnnouncement = selection.announcementId?.let { id -> news.firstOrNull { it.id == id } }
    var sawOpenAnnouncement by remember(selection.announcementId) { mutableStateOf(false) }
    LaunchedEffect(openAnnouncement, selection.announcementId, state.loaded) {
        if (openAnnouncement != null) sawOpenAnnouncement = true
        if (selection.announcementId != null && openAnnouncement == null && state.loaded && (sawOpenAnnouncement || data.news.isNotEmpty() || data.newsUnsupported)) {
            actionNote = FlashHubCopy.ANN_REMOVED
            onSelection(selection.copy(announcementId = null))
        }
    }
    LaunchedEffect(actionNote) {
        if (actionNote != null) {
            delay(4000)
            actionNote = null
        }
    }

    // --- List content built off the main thread ---------------------------------------------------------
    val peopleItems by produceState(initialValue = emptyList<FlashHubPeopleItem>(), data.people, selection.peopleFilter, searching) {
        value = withContext(Dispatchers.Default) {
            if (searching) emptyList() else FlashHubMath.peopleItems(data.people, selection.peopleFilter)
        }
    }
    val orderedDepartments = remember(data.departments) { FlashHubMath.orderDepartments(data.departments) }
    val myDeptId = state.myDepartmentId

    fun select(segment: FlashHubSegment) = onSelection(selection.copy(segment = segment))
    fun closeSearch() { focusManager.clearFocus(); onSelection(selection.copy(searchOpen = false, query = "")) }

    fun openPerson(id: String) { personNote = null; onSelection(selection.copy(personId = id)) }
    fun closePerson() { personNote = null; personBusy = false; onSelection(selection.copy(personId = null)) }

    fun message(person: HubPersonUi) {
        if (personBusy) return
        if (person.paired || person.isSelf) {
            when (val o = FlashHubMath.messageOutcome(person, null)) {
                is FlashHubMessageOutcome.Open -> { closePerson(); actions.onOpenConversation(o.conversationId) }
                is FlashHubMessageOutcome.Show -> personNote = o.text
            }
            return
        }
        personBusy = true
        personNote = null
        scope.launch {
            val result = actions.startConversation(person.deviceId)
            personBusy = false
            when (val o = FlashHubMath.messageOutcome(person, result)) {
                is FlashHubMessageOutcome.Open -> { closePerson(); actions.onOpenConversation(o.conversationId) }
                is FlashHubMessageOutcome.Show -> personNote = o.text
            }
        }
    }

    fun openLink(url: String) {
        if (!FlashHubMath.isOpenableLink(url)) return
        if (FlashHubMath.linkNeedsConfirmation(url, data.orgHost)) linkConfirm = url else actions.onOpenLink(url)
    }

    fun startComposer(editing: HubAnnouncementUi?) {
        val audiences = FlashHubMath.audiences(data.posting, data.departments)
        if (editing != null) {
            composer = FlashHubComposerState(title = editing.title, body = editing.body, pinned = editing.pinned, editingId = editing.id)
        } else {
            val draft = audiences.firstOrNull()?.let { actions.drafts.load(draftKey(it)) }
            composer = FlashHubComposerState(title = draft?.first.orEmpty(), body = draft?.second.orEmpty())
        }
    }

    fun submitComposer() {
        val c = composer ?: return
        if (!FlashHubMath.canSend(c.title, c.body, c.sending)) return
        val audiences = FlashHubMath.audiences(data.posting, data.departments)
        val audience = audiences.getOrNull(c.audienceIndex) ?: audiences.firstOrNull()
        val editingId = c.editingId
        val target = if (editingId != null) news.firstOrNull { it.id == editingId } else null
        composer = c.copy(sending = true, failure = null)
        scope.launch {
            val draft = HubAnnouncementDraft(
                scope = target?.scope ?: audience?.scope ?: "org",
                departmentId = target?.departmentId ?: audience?.departmentId,
                title = c.title.trim(),
                body = c.body.trim(),
                pinned = c.pinned,
            )
            val key = audience?.let { draftKey(it) }
            if (key != null && editingId == null) actions.drafts.save(key, c.title, c.body)
            val result = if (editingId != null && target != null) actions.editAnnouncement(editingId, draft, target.seq) else actions.postAnnouncement(draft)
            when (result) {
                is FlashResult.Success -> {
                    if (key != null && editingId == null) actions.drafts.clear(key)
                    composer = null
                }
                is FlashResult.Failure -> composer = composer?.copy(sending = false, failure = FlashHubMath.composerFailure(result.error))
            }
        }
    }

    fun togglePin(a: HubAnnouncementUi) {
        if (FlashHubMath.pinWouldExceedLimit(news, a)) { actionNote = FlashHubCopy.ANN_PIN_LIMIT; return }
        scope.launch {
            val r = actions.pinAnnouncement(a.id, !a.pinned)
            if (r is FlashResult.Failure) actionNote = FlashHubMath.composerFailure(r.error).text
        }
    }

    fun groupAction(g: HubGroupUi) {
        if (joiningGroupId != null) return
        if (g.membership == com.transfer.flash.core.messaging.model.HubGroupMembership.JOINED) { actions.onOpenGroup(g.groupId); return }
        joiningGroupId = g.groupId
        scope.launch {
            val result = actions.groupAction(g)
            joiningGroupId = null
            val feedback = FlashHubStatusMath.joinFeedback(g.name, result)
            groupFeedback = feedback.text
            if (feedback.kind == FlashHubStatusMath.JoinFeedbackKind.JOINED) actions.onOpenGroup(g.groupId)
        }
    }

    // --- Lists ------------------------------------------------------------------------------------------
    val listStates = FlashHubSegment.entries.associateWith { rememberLazyListState() }
    val searchState = rememberLazyListState()
    val activeState: LazyListState = if (searching) searchState else listStates.getValue(selection.segment)
    val unreadIds = remember(news) { news.filter { !it.isRead }.mapTo(HashSet()) { it.id } }

    val searchPeople = if (searching) data.people else emptyList()
    val searchDepartments = remember(orderedDepartments, query, searching) { if (searching) FlashHubMath.matchDepartments(orderedDepartments, query) else emptyList() }
    val searchNews = remember(news, query, searching) { if (searching) FlashHubMath.matchNews(news, query) else emptyList() }
    val searchGroups = remember(data.groups, query, searching) { if (searching) FlashHubMath.matchGroups(data.groups, query) else emptyList() }

    fun submitSearch() {
        when (val hit = FlashHubMath.firstHit(searchPeople, searchDepartments, searchNews, searchGroups)) {
            is FlashHubMath.SearchHit.Person -> onSelection(selection.copy(personId = hit.deviceId, searchOpen = false, query = ""))
            is FlashHubMath.SearchHit.Department -> onSelection(selection.copy(segment = FlashHubSegment.DEPARTMENTS, departmentId = hit.id, searchOpen = false, query = ""))
            is FlashHubMath.SearchHit.News -> onSelection(selection.copy(segment = FlashHubSegment.NEWS, announcementId = hit.id, searchOpen = false, query = ""))
            is FlashHubMath.SearchHit.Group -> onSelection(selection.copy(segment = FlashHubSegment.GROUPS, searchOpen = false, query = ""))
            null -> Unit
        }
    }

    val listPane: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            FlashHubTopBar(
                title = state.orgName.ifBlank { FlashHubCopy.TAB_LABEL },
                searchOpen = selection.searchOpen,
                query = selection.query,
                onQueryChange = { onSelection(selection.copy(query = it.take(FlashHubMath.QUERY_MAX * 2))) },
                onSearchOpen = { onSelection(selection.copy(searchOpen = true)) },
                onSearchClose = ::closeSearch,
                onSubmit = ::submitSearch,
                searchFocus = searchFocus,
                modifier = Modifier.statusBarsPadding(),
            )
            LaunchedEffect(selection.searchOpen) { if (selection.searchOpen) runCatching { searchFocus.requestFocus() } }
            FlashHubStrip(data, mode, actions, onWhy = { whyOpen = true })
            if (!selection.searchOpen) FlashHubSegments(selection.segment, ::select, unreadNews = unread)
            actionNote?.let { NoticeLine(it) }
            if (data.showMovedTip) FlashHubTip(actions.onDismissTip)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when (mode) {
                    FlashHubMode.LOADING -> FlashHubSkeleton()
                    FlashHubMode.NEVER_SYNCED_OFFLINE -> FlashHubEmptyState(
                        icon = FlashIcons.Hub,
                        headline = FlashHubCopy.NEVER_SYNCED_TITLE,
                        body = FlashHubCopy.NEVER_SYNCED_BODY,
                        actionLabel = FlashHubCopy.RETRY,
                        onAction = actions.onRefresh,
                        actionBusy = state.isRefreshing,
                    )
                    else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = activeState,
                            modifier = Modifier.fillMaxSize().widthIn(max = FlashHubContentMaxWidth),
                        ) {
                            when {
                                searching -> hubSearchItems(
                                    query = query, people = searchPeople, departments = searchDepartments, news = searchNews, groups = searchGroups,
                                    nowMs = data.nowMs, deptName = deptName,
                                    onOpenPerson = ::openPerson,
                                    onOpenDepartment = { onSelection(selection.copy(segment = FlashHubSegment.DEPARTMENTS, departmentId = it, searchOpen = false, query = "")) },
                                    onOpenNews = { onSelection(selection.copy(announcementId = it)) },
                                    onSeeAllPeople = { onSelection(selection.copy(segment = FlashHubSegment.PEOPLE, searchOpen = false)) },
                                    onGroup = ::groupAction, joiningGroupId = joiningGroupId,
                                )
                                selection.segment == FlashHubSegment.NEWS -> hubNewsItems(
                                    news = news, posting = data.posting, unsupported = data.newsUnsupported, nowMs = data.nowMs,
                                    departmentName = deptName,
                                    onOpen = { onSelection(selection.copy(announcementId = it)) },
                                    onMenu = { menuAnnouncementId = it },
                                    onNew = { startComposer(null) },
                                )
                                selection.segment == FlashHubSegment.PEOPLE -> hubPeopleItems(
                                    items = peopleItems, filter = selection.peopleFilter,
                                    onFilter = { onSelection(selection.copy(peopleFilter = it)) },
                                    departments = orderedDepartments, myDepartmentId = myDeptId, query = query, onOpenPerson = ::openPerson,
                                )
                                selection.segment == FlashHubSegment.DEPARTMENTS -> hubDepartmentItems(
                                    departments = orderedDepartments,
                                    onOpen = { onSelection(selection.copy(departmentId = it)) },
                                )
                                else -> hubGroupItems(
                                    groups = data.groups, loadError = data.groupsLoadError, feedback = groupFeedback,
                                    joiningGroupId = joiningGroupId, onAction = ::groupAction, onRetry = actions.onRetryGroups,
                                    onManage = actions.onManageGroupRequests?.takeIf { FlashHubMath.showManageRequests(data.groups, true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (selection.segment == FlashHubSegment.NEWS && !searching) {
        FlashHubReadMarking(listStates.getValue(FlashHubSegment.NEWS), unreadIds, actions.onMarkRead)
    }

    // --- Detail content (wide: right pane; compact: overlays) -------------------------------------------
    val person = data.openedPerson
    val department = selection.departmentId?.let { id -> FlashHubMath.visibleDepartment(orderedDepartments, id) }
    val hiddenDepartment = selection.departmentId != null && department == null
    val rightsOfOpen = openAnnouncement?.let { FlashHubMath.rights(it, data.posting) } ?: FlashHubAnnouncementRights(false, false, false)

    val personPane = FlashHubPersonPaneState(
        busy = personBusy,
        note = personNote,
        callAllowed = person != null && actions.onCall != null && actions.callAllowed(person),
        pairAvailable = actions.onPairColleague != null,
    )

    val detailPane: @Composable () -> Unit = {
        when {
            person != null && selection.personId != null -> FlashHubPersonContent(
                person, personPane, onMessage = { message(person) }, onCall = { actions.onCall?.invoke(person.deviceId) },
                onPair = { actions.onPairColleague?.invoke(person.deviceId) }, onClose = ::closePerson,
            )
            openAnnouncement != null -> FlashAnnouncementReader(
                openAnnouncement, FlashHubMath.scopeLabel(openAnnouncement, deptName), rightsOfOpen, openAnnouncement.pinned, data.nowMs,
                onBack = { onSelection(selection.copy(announcementId = null)) },
                onEdit = { startComposer(openAnnouncement) }, onDelete = { deleteConfirmId = openAnnouncement.id },
                onTogglePin = { togglePin(openAnnouncement) },
                onCopy = { clipboard.setText(AnnotatedString("${openAnnouncement.title}\n\n${openAnnouncement.body}")) }, onLink = ::openLink,
            )
            department != null -> HubDepartmentPane(department, data, news, onSelection, selection, ::openPerson, ::groupAction, joiningGroupId, scope, actions, ::message)
            hiddenDepartment -> FlashHubEmptyState(icon = FlashIcons.Group, headline = FlashHubCopy.DEPARTMENT_HIDDEN)
            else -> FlashHubEmptyState(icon = FlashIcons.Hub, headline = FlashHubCopy.TAB_LABEL, body = FlashHubCopy.SEARCH_PROMPT)
        }
    }

    if (twoPane) {
        FlashAdaptiveTwoPane(listPane = listPane, detailPane = detailPane, windowWidthDp = widthDp)
    } else {
        Box(Modifier.fillMaxSize()) { listPane() }
        when {
            openAnnouncement != null -> Box(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp).statusBarsPadding()) {
                Column(Modifier.widthIn(max = FlashHubContentMaxWidth).align(Alignment.TopCenter)) { detailPane() }
            }
            department != null || hiddenDepartment -> Box(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp).statusBarsPadding()) {
                Column(Modifier.widthIn(max = FlashHubContentMaxWidth).align(Alignment.TopCenter)) {
                    if (hiddenDepartment) {
                        FlashHubIconButton(FlashIcons.Back, FlashHubCopy.DEPT_BACK, { onSelection(selection.copy(departmentId = null)) })
                    }
                    detailPane()
                }
            }
        }
    }

    // --- Back handling ----------------------------------------------------------------------------------
    val overlayOpen = whyOpen || composer != null || menuAnnouncementId != null || deleteConfirmId != null || linkConfirm != null ||
        selection.personId != null || selection.announcementId != null || selection.departmentId != null || selection.searchOpen
    FlashBackHandler(enabled = overlayOpen) {
        when {
            linkConfirm != null -> linkConfirm = null
            deleteConfirmId != null -> deleteConfirmId = null
            composer != null -> composer = null
            menuAnnouncementId != null -> menuAnnouncementId = null
            whyOpen -> whyOpen = false
            selection.personId != null -> closePerson()
            selection.announcementId != null -> onSelection(selection.copy(announcementId = null))
            selection.departmentId != null -> onSelection(selection.copy(departmentId = null))
            selection.searchOpen -> closeSearch()
        }
    }

    // --- Sheets and dialogs -----------------------------------------------------------------------------
    if (!twoPane && person != null && selection.personId != null) {
        FlashSheetHost(onDismiss = ::closePerson, containerColor = FlashTheme.colors.sheetSurface) {
            FlashHubPersonContent(
                person, personPane, onMessage = { message(person) }, onCall = { actions.onCall?.invoke(person.deviceId) },
                onPair = { actions.onPairColleague?.invoke(person.deviceId) }, onClose = null,
            )
        }
    }
    if (!twoPane && selection.personId != null && person == null && state.loaded) {
        FlashSheetHost(onDismiss = ::closePerson, containerColor = FlashTheme.colors.sheetSurface) {
            FlashHubEmptyState(icon = FlashIcons.Group, headline = FlashHubCopy.PERSON_UNKNOWN)
        }
    }
    composer?.let { c ->
        val audiences = FlashHubMath.audiences(data.posting, data.departments)
        FlashSheetHost(onDismiss = { if (!c.sending) composer = null }, containerColor = FlashTheme.colors.sheetSurface) {
            FlashAnnouncementComposer(
                state = c, audiences = audiences, canPin = data.posting !is HubPostingRights.None,
                onChange = { composer = it }, onSubmit = ::submitComposer, onCancel = { composer = null },
            )
        }
    }
    if (whyOpen) {
        FlashSheetHost(onDismiss = { whyOpen = false }, containerColor = FlashTheme.colors.sheetSurface) {
            Column(Modifier.fillMaxWidth().padding(FlashSpacing.space16).padding(bottom = FlashSpacing.space24)) {
                FlashText(FlashHubCopy.WHY_TITLE, style = FlashTheme.typography.headingMedium, color = FlashTheme.colors.textPrimary)
                FlashText(FlashHubCopy.WHY_BODY, modifier = Modifier.padding(vertical = FlashSpacing.space12), style = FlashTheme.typography.bodyDefault, color = FlashTheme.colors.textSecondary)
                FlashHubButton(FlashHubCopy.WHY_CLOSE, { whyOpen = false }, primary = false)
            }
        }
    }
    menuAnnouncementId?.let { id ->
        val a = news.firstOrNull { it.id == id }
        if (a == null) {
            menuAnnouncementId = null
        } else {
            val rights = FlashHubMath.rights(a, data.posting)
            FlashSheetHost(onDismiss = { menuAnnouncementId = null }, containerColor = FlashTheme.colors.sheetSurface) {
                Column(Modifier.fillMaxWidth().padding(FlashSpacing.space16).padding(bottom = FlashSpacing.space24), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(FlashSpacing.space8)) {
                    FlashHubButton(FlashHubCopy.ANN_COPY, { clipboard.setText(AnnotatedString("${a.title}\n\n${a.body}")); menuAnnouncementId = null }, icon = FlashIcons.Share, primary = false, modifier = Modifier.fillMaxWidth())
                    if (rights.canEdit) FlashHubButton(FlashHubCopy.ANN_EDIT, { menuAnnouncementId = null; startComposer(a) }, icon = FlashIcons.Edit, primary = false, modifier = Modifier.fillMaxWidth())
                    if (rights.canPin) FlashHubButton(if (a.pinned) FlashHubCopy.ANN_UNPIN else FlashHubCopy.ANN_PIN, { menuAnnouncementId = null; togglePin(a) }, icon = FlashIcons.Pin, primary = false, modifier = Modifier.fillMaxWidth())
                    if (rights.canDelete) FlashHubButton(FlashHubCopy.ANN_DELETE, { menuAnnouncementId = null; deleteConfirmId = a.id }, icon = FlashIcons.Delete, primary = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    deleteConfirmId?.let { id ->
        FlashConfirmHost(
            onDismiss = { deleteConfirmId = null },
            containerColor = FlashTheme.colors.backgroundSurface,
            title = { FlashText(FlashHubCopy.ANN_DELETE_CONFIRM_TITLE, style = FlashTheme.typography.headingSmall, color = FlashTheme.colors.textPrimary) },
            text = { FlashText(FlashHubCopy.ANN_DELETE_CONFIRM_BODY, style = FlashTheme.typography.bodyDefault, color = FlashTheme.colors.textSecondary) },
            confirmButton = {
                FlashHubTextAction(FlashHubCopy.ANN_DELETE, {
                    deleteConfirmId = null
                    scope.launch {
                        val r = actions.deleteAnnouncement(id)
                        if (r is FlashResult.Failure) actionNote = FlashHubMath.composerFailure(r.error).text
                        else if (selection.announcementId == id) onSelection(selection.copy(announcementId = null))
                    }
                })
            },
            dismissButton = { FlashHubTextAction(FlashHubCopy.ANN_CANCEL, { deleteConfirmId = null }) },
        )
    }
    linkConfirm?.let { url ->
        FlashConfirmHost(
            onDismiss = { linkConfirm = null },
            containerColor = FlashTheme.colors.backgroundSurface,
            title = { FlashText(FlashHubCopy.annLinkConfirm(url), style = FlashTheme.typography.bodyDefault, color = FlashTheme.colors.textPrimary) },
            text = { },
            confirmButton = { FlashHubTextAction(FlashHubCopy.ANN_LINK_OPEN, { linkConfirm = null; actions.onOpenLink(url) }) },
            dismissButton = { FlashHubTextAction(FlashHubCopy.ANN_LINK_CANCEL, { linkConfirm = null }) },
        )
    }
}

private fun draftKey(a: FlashHubAudience): String = "${a.scope}:${a.departmentId.orEmpty()}"

@Composable
internal fun NoticeLine(text: String) {
    FlashText(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = FlashTheme.typography.metadataDefault,
        color = FlashTheme.colors.textSecondary,
    )
}

@Composable
private fun FlashHubStrip(data: FlashHubScreenData, mode: FlashHubMode, actions: FlashHubActions, onWhy: () -> Unit) {
    val strip = FlashHubMath.strip(data.state, data.link, data.nowMs) ?: return
    if (mode == FlashHubMode.LOADING && strip.tone == FlashHubStripTone.CALM) return
    FlashHubStatusStripView(strip, onWhy = onWhy, onRetry = actions.onRefresh)
}

@Composable
private fun FlashHubTip(onDismiss: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(FlashHubCopy.TRANSFERS_MOVED_TIP, Modifier.weight(1f), style = FlashTheme.typography.bodyDefault, color = FlashTheme.colors.textPrimary)
        FlashHubTextAction(FlashHubCopy.TIP_DISMISS, onDismiss)
    }
}

@Composable
private fun HubDepartmentPane(
    department: HubDepartmentUi,
    data: FlashHubScreenData,
    news: List<HubAnnouncementUi>,
    onSelection: (FlashHubSelection) -> Unit,
    selection: FlashHubSelection,
    openPerson: (String) -> Unit,
    groupAction: (HubGroupUi) -> Unit,
    joiningGroupId: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    actions: FlashHubActions,
    message: (HubPersonUi) -> Unit,
) {
    val groups = remember(data.groups, department) {
        data.groups.filter { it.departmentName.equals(department.name, ignoreCase = true) }
    }
    val deptNews = remember(news, department) { news.filter { it.departmentId == department.id } }
    val sc = rememberScrollState()
    FlashHubDepartmentContent(
        department = department, members = data.departmentMembers, groups = groups, news = deptNews, nowMs = data.nowMs,
        onBack = { onSelection(selection.copy(departmentId = null)) },
        onOpenPerson = openPerson,
        onMessage = { id ->
            val head = data.departmentMembers.firstOrNull { it.deviceId == id } ?: data.people.firstOrNull { it.deviceId == id }
            if (head != null) message(head) else openPerson(id)
        },
        onOpenAnnouncement = { onSelection(selection.copy(announcementId = it)) },
        onSeeAllPeople = { onSelection(selection.copy(segment = FlashHubSegment.PEOPLE, peopleFilter = FlashHubPeopleFilter.Department(department.id), departmentId = null)) },
        onGroupAction = groupAction,
        joiningGroupId = joiningGroupId,
        modifier = Modifier.verticalScroll(sc),
    )
}
