package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.util.sampleFlashChatListState
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Pure filtering/counting logic for global / chat-list search (UI-024).
 * Unit-testable without instrumentation (see [FlashChatListSearchLogicTest]).
 */
object FlashChatListSearchMath {
    fun normalizeQuery(rawQuery: String): String = rawQuery.trim()

    /** True when the user has typed something searchable (non-blank after trim). */
    fun isSearchActive(rawQuery: String): Boolean = normalizeQuery(rawQuery).isNotEmpty()

    /**
     * Chats whose title OR preview text contain [rawQuery] (case-insensitive), OR whose id is in
     * [bodyMatchIds] — the set of conversations that have a full-history message-body match (#12),
     * resolved by the repository via `MessageDao.searchMessages`. Results keep their original list
     * order (no relevance re-sorting). Blank query returns all items unfiltered.
     */
    fun filterChats(
        items: List<FlashChatListItemUi>,
        rawQuery: String,
        bodyMatchIds: Set<String> = emptySet(),
    ): List<FlashChatListItemUi> {
        val query = normalizeQuery(rawQuery)
        if (query.isEmpty()) return items
        return items.filter { item ->
            item.id in bodyMatchIds ||
                item.title.contains(query, ignoreCase = true) ||
                item.previewText.contains(query, ignoreCase = true)
        }
    }

    /** Live counter label for the search bar ("1 chat", "4 chats"). */
    fun resultCountLabel(count: Int): String = if (count == 1) "1 chat" else "$count chats"

    /**
     * Normalizes a newest-first history into deduplicated recent-search chips:
     * trimmed, blank entries dropped, case-insensitive dedup keeping the first
     * spelling, capped at [limit] most recent.
     */
    fun recentSearches(
        newestFirst: List<String>,
        limit: Int = 6,
    ): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (entry in newestFirst) {
            val trimmed = normalizeQuery(entry)
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (key !in seen) seen[key] = trimmed
            if (seen.size >= limit) break
        }
        return seen.values.toList()
    }
}

/**
 * UI-024 Chat-list search bar — replaces [FlashChatListTopBar] while search is active.
 * Custom close chrome over a foundation text field pill; live result count via
 * polite live region. Styling mirrors [FlashChatSearchBar] (UI-023).
 */
@Composable
fun FlashChatListSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
    resultCount: Int?,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .statusBarsPadding()
            .padding(horizontal = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Close search
        Box(
            modifier = Modifier
                .size(FlashDimensions.minTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .semantics { role = Role.Button; contentDescription = "Close search" },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(icon = FlashIcons.Back)
        }

        // Query field pill
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(FlashShapes.composerInput)
                .background(colors.composerInputBackground)
                .border(
                    width = FlashDimensions.borderHairline,
                    color = colors.borderSubtle,
                    shape = FlashShapes.composerInput,
                )
                .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
                .semantics {
                    contentDescription = if (resultCount != null && resultCount > 0) {
                        "Searching ${query}, ${FlashChatListSearchMath.resultCountLabel(resultCount)}"
                    } else {
                        "Searching $query"
                    }
                },
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        FlashText(
                            text = "Search chats",
                            style = typography.bodyDefault,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                },
            )
        }

        Spacer(modifier = Modifier.size(FlashSpacing.space4))

        // Live result count (polite live region for TalkBack)
        if (resultCount == null) {
            Spacer(modifier = Modifier.width(FlashDimensions.minTouchTarget))
        } else {
            FlashText(
                text = if (resultCount == 0) "No matches" else FlashChatListSearchMath.resultCountLabel(resultCount),
                style = typography.metadataDefault,
                color = if (resultCount == 0) colors.textTertiary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * UI-024 Recent-search chips — shown under the search bar while the query is
 * empty so the empty state is already useful (recents as one-tap queries).
 * Queries are passed in newest-first, already normalized by the caller.
 */
@Composable
fun FlashRecentSearchChips(
    recents: List<String>,
    onRecentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClearAll: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FlashSpacing.space16, end = FlashSpacing.space8, top = FlashSpacing.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(
                text = "Recent",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            if (onClearAll != null && recents.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(FlashShapes.chip)
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                        .semantics { role = Role.Button; contentDescription = "Clear recent searches" },
                ) {
                    FlashText(
                        text = "Clear",
                        style = typography.metadataEmphasis,
                        color = colors.accentPrimary,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            recents.forEach { recent ->
                Box(
                    modifier = Modifier
                        .clip(FlashShapes.chip)
                        .background(colors.backgroundSurfaceSubtle)
                        .border(
                            width = FlashDimensions.borderHairline,
                            color = colors.borderSubtle,
                            shape = FlashShapes.chip,
                        )
                        .clickable(onClick = { onRecentClick(recent) })
                        .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Search for $recent"
                        },
                ) {
                    FlashText(
                        text = recent,
                        style = typography.captionDefault,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Preview(name = "Chat-list search — light", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListSearchBarLightPreview() {
    FlashTheme {
        FlashChatListSearchBar(
            query = "flash",
            onQueryChanged = {},
            onClose = {},
            resultCount = 2,
        )
    }
}

@Preview(name = "Chat-list search — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListSearchBarDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashChatListSearchBar(
            query = "wi-fi",
            onQueryChanged = {},
            onClose = {},
            resultCount = 0,
        )
    }
}

@Preview(name = "Chat-list search — empty query with recents", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListSearchEmptyWithRecentsPreview() {
    FlashTheme {
        Column {
            FlashChatListSearchBar(
                query = "",
                onQueryChanged = {},
                onClose = {},
                resultCount = null,
            )
            FlashRecentSearchChips(
                recents = listOf("build", "Wi-Fi Direct", "motion doc"),
                onRecentClick = {},
                onClearAll = {},
            )
        }
    }
}

@Preview(name = "Chat-list search — filtered results", showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListSearchFilteredResultsPreview() {
    FlashTheme {
        Column {
            FlashChatListSearchBar(
                query = "design",
                onQueryChanged = {},
                onClose = {},
                resultCount = 1,
            )
            FlashChatListRow(
                item = sampleFlashChatListState().items.first { it.id == "conv-design" },
                onClick = {},
            )
            FlashChatListRow(
                item = sampleFlashChatListState().items.first { it.id == "conv-transfer" },
                onClick = {},
                showDivider = false,
            )
        }
    }
}
