package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashChatListUiState
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.ui.theme.FlashTheme

@Composable
fun FlashChatListScreen(
    state: FlashChatListUiState,
    onConversationClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hanging shell bar occupies; rows scroll under it (UI-046). */
    bottomInset: Dp = 0.dp,
    onLanClick: (() -> Unit)? = null,
    onConversationLongClick: (String) -> Unit = {},
    onArchiveConversation: (String) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    // UI-013 selection-mode bulk actions (contextual action bar replaces the top bar)
    onCloseSelection: () -> Unit = {},
    onPinSelected: () -> Unit = {},
    onMuteSelected: () -> Unit = {},
    onMarkSelectedRead: () -> Unit = {},
    onArchiveSelected: () -> Unit = {},
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
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val statusSwap = motion.statusCrossfade()
    val items = state.items
    val displayItems = remember(items, searchQuery, messageBodyMatches) {
        FlashChatListSearchMath.filterChats(items, searchQuery, messageBodyMatches)
    }
    val searchActive = FlashChatListSearchMath.isSearchActive(searchQuery)
    val showRecents = isSearching && !searchActive && recentSearches.isNotEmpty()
    val showEmptyState = !isLoading && errorMessage == null && items.isEmpty() && !isSearching

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
                )
            } else if (isSearching) {
                FlashChatListSearchBar(
                    query = searchQuery,
                    onQueryChanged = onSearchQueryChanged,
                    onClose = onCloseSearch,
                    resultCount = if (searchActive) displayItems.size else null,
                )
            } else {
                FlashChatListTopBar(
                    onSearchClick = onSearchClick,
                    onLanClick = onLanClick,
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
                        kind = FlashStateCopy.EmptyKind.ChatListFirstRun,
                        onAction = onFindDevicesClick,
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
                        itemsIndexed(
                            items = displayItems,
                            key = { _, item -> item.id },
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
                                onArchive = onArchiveConversation,
                                isSelected = item.id in state.selectedIds,
                                selectionMode = state.selectionMode,
                                showDivider = showRecents || !isLastRow,
                                modifier = Modifier.animateItem(
                                    placementSpec = motion.messagePlacementSpec(),
                                    fadeOutSpec = motion.messageFadeOutSpec(),
                                ),
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
