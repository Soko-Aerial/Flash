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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
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
    /**
     * Group Phase 1A: opens the create-group sheet; null HIDES the action.
     *
     * This was `() -> Unit = {}` — non-null, so it could never be null, and it was forwarded to
     * `FlashChatListTopBar`'s nullable parameter where the `!= null` check therefore always passed.
     * A host that did not supply one got a rendered, tappable "New group" button that did nothing.
     * That is the trap this signature removes: an unpassed action is now absent, not inert.
     */
    onNewGroupClick: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val statusSwap = motion.statusCrossfade()

    var viewingArchived by rememberSaveable { mutableStateOf(false) }

    FlashBackHandler(enabled = viewingArchived) {
        viewingArchived = false
    }

    val activeItems = if (viewingArchived) state.archivedItems else state.items
    val displayItems = remember(activeItems, searchQuery, messageBodyMatches) {
        FlashChatListSearchMath.filterChats(activeItems, searchQuery, messageBodyMatches)
    }
    val searchActive = FlashChatListSearchMath.isSearchActive(searchQuery)
    val showRecents = isSearching && !searchActive && recentSearches.isNotEmpty()
    val showEmptyState = !isLoading && errorMessage == null && activeItems.isEmpty() && !isSearching

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
                    resultCount = if (searchActive) displayItems.size else null,
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
                        if (showRecents) {
                            item(key = "flash-recent-searches") {
                                FlashRecentSearchChips(
                                    recents = recentSearches,
                                    onRecentClick = onRecentSearchClick,
                                    onClearAll = onClearRecentSearches,
                                )
                            }
                        }
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
                                isSelected = item.id in state.selectedIds,
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
