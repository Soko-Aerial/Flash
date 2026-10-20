package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.model.FlashChatSearchResultsUi
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashAnimateItem
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun FlashChatListScreen(
    state: FlashChatListUiState,
    onConversationClick: (String) -> Unit,
    /**
     * Opens chat search. Nullable so a host that cannot deliver results HIDES the icon rather than
     * rendering a dead button — see [onNewGroupClick]. Android passes a real handler; the desktop
     * shell did not, and showed a search icon that did nothing.
     */
    onSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hanging shell bar occupies; rows scroll under it (UI-046). */
    bottomInset: Dp = 0.dp,
    onLanClick: (() -> Unit)? = null,
    onConversationLongClick: (String) -> Unit = {},
    onArchiveConversation: (String) -> Unit = {},
    onUnarchiveConversation: (String) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    // UI-013 selection-mode bulk actions (contextual action bar replaces the top bar)
    onCloseSelection: () -> Unit = {},
    onPinSelected: () -> Unit = {},
    onMuteSelected: () -> Unit = {},
    onMarkSelectedRead: () -> Unit = {},
    onArchiveSelected: () -> Unit = {},
    onUnarchiveSelected: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    // UI-025 / UI-026 / UI-027 system states
    isLoading: Boolean = false,
    errorMessage: String? = null,
    isErrorEnvironmental: Boolean = false,
    onRetryLoad: () -> Unit = {},
    onFindDevicesClick: () -> Unit = {},
    // UI-024 global / chat-list search
    isSearching: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChanged: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onRecentSearchClick: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    /** Conversation ids with a full-history message-body match for [searchQuery] (#12), resolved by
     *  the repository; folded into the client-side title/preview filter so buried matches surface. */
    messageBodyMatches: Set<String> = emptySet(),
    /** Plan B Phase B3: Colleague device IDs matching the search query against the organisation snapshot. */
    colleagueMatchIds: Set<String> = emptySet(),
    /**
     * UI-024 upgrade: structured cross-conversation hits (message snippets + attachment names) from
     * the repository. Replaces the old behaviour where search only filtered conversation rows with no
     * indication of what matched. Empty for lightweight hosts.
     */
    searchResults: FlashChatSearchResultsUi = FlashChatSearchResultsUi(),
    /** Active result scope chip (All / Chats / Messages / Files / Media / Links). */
    searchScope: FlashSearchScope = FlashSearchScope.All,
    onSearchScopeChanged: (FlashSearchScope) -> Unit = {},
    /** Deep-link: opens [conversationId] scrolled to [messageId] with a pulse highlight. */
    onSearchMessageClick: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
    /** Deep-link for a file hit (same destination, different glyph in the row). */
    onSearchFileClick: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
    /** IME "Search": the host records the query in the recent-search history. */
    onSearchSubmit: () -> Unit = {},
    /**
     * Group Phase 1A: opens the create-group sheet; null HIDES the action.
     *
     * This was `() -> Unit = {}` — non-null, so it could never be null, and it was forwarded to
     * `FlashChatListTopBar`'s nullable parameter where the `!= null` check therefore always passed.
     * A host that did not supply one got a rendered, tappable "New group" button that did nothing.
     */
    onNewGroupClick: (() -> Unit)? = null,
    /** GM-10: opens join-group-with-link dialog; null hides the action. */
    onJoinWithLinkClick: (() -> Unit)? = null,
    /** Browse department groups on the enterprise hub; null hides the action. */
    onBrowseOrgGroupsClick: (() -> Unit)? = null,
    /** UI-058: Transfers action of an enrolled device (its Transfers tab became Hub); null hides it. */
    onTransfersClick: (() -> Unit)? = null,
    /** Transfers in progress, shown on the [onTransfersClick] action. */
    transfersActiveCount: Int = 0,
    /** Active conversation id in two-pane mode to highlight the currently open chat. */
    activeConversationId: String? = null,
    /**
     * Names of organisation groups this device asked to join and is waiting on (CLI-04). Each shows as a
     * "Waiting for approval" row at the top; the row becomes a normal chat once an admin approves.
     */
    pendingApprovalGroupNames: List<String> = emptyList(),
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val statusSwap = motion.statusCrossfade()

    var viewingArchived by rememberSaveable { mutableStateOf(false) }

    FlashBackHandler(enabled = viewingArchived) {
        viewingArchived = false
    }

    val activeItems = if (viewingArchived) state.archivedItems else state.items
    val displayItems = remember(activeItems, searchQuery, messageBodyMatches, colleagueMatchIds) {
        FlashChatListSearchMath.filterChats(activeItems, searchQuery, messageBodyMatches, colleagueMatchIds)
    }
    val searchActive = FlashChatListSearchMath.isSearchActive(searchQuery)
    // Chats whose NAME or PREVIEW matched. Body-only matches are represented by their message/file
    // hit rows instead, so a thread never appears twice with no explanation of why it is here.
    val titleMatchedChats = remember(activeItems, searchQuery, colleagueMatchIds) {
        FlashChatListSearchMath.filterChats(activeItems, searchQuery, colleagueMatchIds = colleagueMatchIds)
    }
    val searchEntries = remember(searchScope, titleMatchedChats, searchResults) {
        FlashSearchResultMath.entries(searchScope, titleMatchedChats, searchResults)
    }
    val scopedResultCount = remember(searchScope, titleMatchedChats, searchResults) {
        FlashSearchResultMath.scopeCount(searchScope, titleMatchedChats, searchResults)
    }
    val showRecents = isSearching && !searchActive && recentSearches.isNotEmpty()
    val showPending = pendingApprovalGroupNames.isNotEmpty() && !viewingArchived && !isSearching
    val showEmptyState = !isLoading && errorMessage == null && activeItems.isEmpty() && !isSearching && !showPending

    Scaffold(
        modifier = modifier,
        topBar = {
            if (state.selectionMode) {
                FlashChatListSelectionBar(
                    selectedCount = state.selectedIds.size,
                    onClose = onCloseSelection,
                    onPin = onPinSelected,
                    onMute = onMuteSelected,
                    onMarkRead = onMarkSelectedRead,
                    onArchive = onArchiveSelected,
                    onDelete = onDeleteSelected,
                    isArchivedView = viewingArchived,
                    onUnarchive = onUnarchiveSelected,
                )
            } else if (isSearching) {
                FlashChatListSearchBar(
                    query = searchQuery,
                    onQueryChanged = onSearchQueryChanged,
                    onClose = onCloseSearch,
                    resultCount = if (searchActive) scopedResultCount else null,
                    onSearchSubmit = onSearchSubmit,
                )
            } else if (viewingArchived) {
                FlashArchivedChatsTopBar(
                    onBackClick = { viewingArchived = false },
                    onSearchClick = onSearchClick,
                )
            } else {
                FlashChatListTopBar(
                    onSearchClick = onSearchClick,
                    onLanClick = onLanClick,
                    onNewGroupClick = onNewGroupClick,
                    onJoinWithLinkClick = onJoinWithLinkClick,
                    onBrowseOrgGroupsClick = onBrowseOrgGroupsClick,
                    onTransfersClick = onTransfersClick,
                    transfersActiveCount = transfersActiveCount,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colors.backgroundApp),
        ) {
            // UI-025/026/027 branches crossfade instead of hard-swapping. The target is the
            // branch identity only, so list updates never restart the transition.
            AnimatedContent(
                targetState = chatListPageState(errorMessage, isLoading, showEmptyState),
                transitionSpec = { statusSwap },
                contentAlignment = Alignment.Center,
                label = "chatListPageState",
            ) { page ->
                when (page) {
                    // UI-027 container-level failure — replaces the data region entirely.
                    ChatListPageState.Error -> FlashErrorState(
                        title = if (isErrorEnvironmental) {
                            "Can't reach any devices"
                        } else {
                            "Couldn't load conversations"
                        },
                        message = errorMessage.orEmpty(),
                        severity = if (isErrorEnvironmental) {
                            FlashErrorSeverity.Environmental
                        } else {
                            FlashErrorSeverity.Failure
                        },
                        onRetry = onRetryLoad,
                    )
                    // UI-026 layout-matched skeletons while data resolves.
                    ChatListPageState.Loading -> FlashSkeletonChatList()
                    // UI-025 first-run empty state with the P2P next action.
                    ChatListPageState.Empty -> FlashEmptyState(
                        kind = if (viewingArchived) {
                            FlashStateCopy.EmptyKind.ArchivedChatsEmpty
                        } else {
                            FlashStateCopy.EmptyKind.ChatListFirstRun
                        },
                        onAction = if (viewingArchived) {
                            { viewingArchived = false }
                        } else {
                            onFindDevicesClick
                        },
                    )
                    ChatListPageState.Content -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        // Rows scroll under the hanging shell bar (UI-046) instead of being
                        // clipped above it.
                        contentPadding = PaddingValues(bottom = bottomInset),
                    ) {
                        if (showPending) {
                            itemsIndexed(
                                items = pendingApprovalGroupNames,
                                key = { _, name -> "flash-pending-join-$name" },
                                contentType = { _, _ -> "pending_join" },
                            ) { _, name -> FlashPendingJoinRow(name) }
                        }
                        if (showRecents) {
                            item(key = "flash-recent-searches") {
                                FlashRecentSearchChips(
                                    recents = recentSearches,
                                    onRecentClick = onRecentSearchClick,
                                    onClearAll = onClearRecentSearches,
                                )
                            }
                        }
                        if (isSearching && searchActive) {
                            // UI-024 upgrade: scoped, sectioned results (chats + message snippets +
                            // files) in the SAME LazyColumn, so scrolling and recycling are unchanged.
                            item(
                                key = FlashSearchResultMath.key(FlashSearchListEntry.ScopeRow),
                                contentType = "search_scope",
                            ) {
                                FlashSearchScopeRow(
                                    scopes = GlobalSearchScopes,
                                    selected = searchScope,
                                    onSelect = onSearchScopeChanged,
                                )
                            }
                            items(
                                count = searchEntries.size,
                                key = { index -> FlashSearchResultMath.key(searchEntries[index]) },
                                contentType = { index ->
                                    when (searchEntries[index]) {
                                        is FlashSearchListEntry.SectionHeader -> "search_header"
                                        is FlashSearchListEntry.Chat -> "search_chat"
                                        is FlashSearchListEntry.Message -> "search_message"
                                        is FlashSearchListEntry.File -> "search_file"
                                        else -> "search_empty"
                                    }
                                },
                            ) { index ->
                                when (val entry = searchEntries[index]) {
                                    is FlashSearchListEntry.SectionHeader -> FlashSearchSectionHeader(
                                        title = entry.title,
                                        count = entry.count,
                                        showSeeAll = entry.showSeeAll,
                                        onSeeAll = { onSearchScopeChanged(entry.scope) },
                                    )
                                    is FlashSearchListEntry.Chat -> FlashChatListRow(
                                        item = entry.item,
                                        onClick = { onConversationClick(entry.item.id) },
                                        showDivider = true,
                                        modifier = flashAnimateItem(motion),
                                    )
                                    is FlashSearchListEntry.Message -> FlashMessageSearchResultRow(
                                        hit = entry.hit,
                                        query = searchQuery,
                                        onClick = {
                                            onSearchMessageClick(entry.hit.conversationId, entry.hit.messageId)
                                        },
                                    )
                                    is FlashSearchListEntry.File -> FlashFileSearchResultRow(
                                        hit = entry.hit,
                                        query = searchQuery,
                                        onClick = {
                                            onSearchFileClick(entry.hit.conversationId, entry.hit.messageId)
                                        },
                                    )
                                    is FlashSearchListEntry.NoResults -> FlashSearchNoResults(
                                        query = searchQuery,
                                        scopeLabel = entry.scopeLabel,
                                    )
                                    FlashSearchListEntry.ScopeRow -> Unit
                                }
                            }
                        } else {
                            if (!viewingArchived && state.archivedItems.isNotEmpty() && !isSearching) {
                                item(key = "flash-archived-chats-row") {
                                    val unreadArchived = remember(state.archivedItems) {
                                        state.archivedItems.sumOf { it.unreadCount }
                                    }
                                    FlashArchivedChatsRow(
                                        archivedCount = state.archivedItems.size,
                                        unreadCount = unreadArchived,
                                        onClick = { viewingArchived = true },
                                    )
                                }
                            }
                            // BOLT: contentType differentiation for direct vs group rows to optimize LazyColumn composition slot recycling
                            itemsIndexed(
                                items = displayItems,
                                key = { _, item -> item.id },
                                contentType = { _, item -> if (item.isGroup) "group" else "direct" },
                            ) { index, item ->
                                val isLastRow =
                                    !showRecents && index == displayItems.lastIndex
                                FlashChatListRow(
                                    item = item,
                                    onClick = {
                                        if (state.selectionMode) {
                                            onToggleSelection(item.id)
                                        } else {
                                            onConversationClick(item.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (state.selectionMode) {
                                            onToggleSelection(item.id)
                                        } else {
                                            onConversationLongClick(item.id)
                                        }
                                    },
                                    onArchive = if (viewingArchived) onUnarchiveConversation else onArchiveConversation,
                                    swipeActionLabel = if (viewingArchived) "Unarchive" else "Archive",
                                    isSelected = item.id in state.selectedIds || (!state.selectionMode && activeConversationId != null && item.id == activeConversationId),
                                    selectionMode = state.selectionMode,
                                    showDivider = showRecents || !isLastRow,
                                    modifier = flashAnimateItem(motion),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A group the device asked to join that still needs an admin's approval. */
@Composable
private fun FlashPendingJoinRow(groupName: String) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = "$groupName, waiting for approval" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashAvatar(
            initials = groupName.take(2).uppercase(),
            seed = groupName,
            size = FlashDimensions.avatarLg,
        )
        Column(Modifier.weight(1f)) {
            FlashText(text = groupName, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1)
            FlashText(
                text = "Waiting for approval",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
    }
}

/** Which of the four chat-list branches is on screen (drives the crossfade). */
private enum class ChatListPageState { Error, Loading, Empty, Content }

private fun chatListPageState(
    errorMessage: String?,
    isLoading: Boolean,
    showEmptyState: Boolean,
): ChatListPageState = when {
    errorMessage != null -> ChatListPageState.Error
    isLoading -> ChatListPageState.Loading
    showEmptyState -> ChatListPageState.Empty
    else -> ChatListPageState.Content
}

@Preview(name = "Chat list — light", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenLightPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = sampleFlashChatListState(),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — dark", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashChatListScreen(
            state = sampleFlashChatListState(),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — selection", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenSelectionPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = sampleFlashChatListState().copy(
                selectionMode = true,
                selectedIds = setOf("conv-false-school", "conv-design"),
            ),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — skeleton loading", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenLoadingPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = FlashChatListUiState(),
            onConversationClick = {},
            onSearchClick = {},
            isLoading = true,
        )
    }
}

@Preview(name = "Chat list — empty first run", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenEmptyPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = FlashChatListUiState(),
            onConversationClick = {},
            onSearchClick = {},
        )
    }
}

@Preview(name = "Chat list — error", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashChatListScreenErrorPreview() {
    FlashTheme {
        FlashChatListScreen(
            state = FlashChatListUiState(),
            onConversationClick = {},
            onSearchClick = {},
            errorMessage = "We couldn't load your conversations. Check the connection and try again.",
        )
    }
}
