package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import com.transfer.flash.core.messaging.model.FlashChatSearchResultsUi
import com.transfer.flash.core.messaging.model.FlashFileSearchHitUi
import com.transfer.flash.core.messaging.model.FlashMessageSearchHitUi
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.rememberFlashHaptics

/**
 * What kind of thing a search is currently narrowing to (UI-024 upgrade).
 *
 * The same vocabulary is used by the global search (All / Chats / Messages / Files / Media / Links)
 * and by in-chat search (All / Messages / Files / Links), so a person does not have to learn two
 * different filter languages. [Media] is the image/video slice of the attachment hits; [Files] is
 * everything else.
 */
public enum class FlashSearchScope(public val label: String) {
    All("All"),
    Chats("Chats"),
    Messages("Messages"),
    Files("Files"),
    Media("Media"),
    Links("Links"),
}

/** Scopes offered by the global / chat-list search, in display order. */
public val GlobalSearchScopes: List<FlashSearchScope> = listOf(
    FlashSearchScope.All,
    FlashSearchScope.Chats,
    FlashSearchScope.Messages,
    FlashSearchScope.Files,
    FlashSearchScope.Media,
    FlashSearchScope.Links,
)

/** Scopes offered inside a conversation. There are no separate chat rows there. */
public val ConversationSearchScopes: List<FlashSearchScope> = listOf(
    FlashSearchScope.All,
    FlashSearchScope.Messages,
    FlashSearchScope.Files,
    FlashSearchScope.Links,
)

/**
 * One row of the global search result list. Search renders as a *single* LazyColumn with
 * heterogeneous entries so section headers, chats, message snippets and file rows recycle through
 * the same list machinery — and so the whole arrangement (including how many rows a section shows
 * before "See all") is pure, unit-testable logic rather than layout code.
 */
public sealed interface FlashSearchListEntry {
    /** The scope chip row itself: always the first item while a query is active. */
    public data object ScopeRow : FlashSearchListEntry

    /** "Messages · 12" with an optional "See all" action that switches scope. */
    public data class SectionHeader(
        public val title: String,
        public val count: Int,
        public val scope: FlashSearchScope,
        public val showSeeAll: Boolean,
    ) : FlashSearchListEntry

    public data class Chat(public val item: FlashChatListItemUi) : FlashSearchListEntry

    public data class Message(public val hit: FlashMessageSearchHitUi) : FlashSearchListEntry

    public data class File(public val hit: FlashFileSearchHitUi) : FlashSearchListEntry

    /** Nothing matched at all (or nothing matched in the selected scope). */
    public data class NoResults(public val scopeLabel: String) : FlashSearchListEntry
}

/**
 * Pure arrangement/counting logic for the structured global search (UI-024 upgrade).
 *
 * The repository hands over message hits and file hits; the screen hands over the chats whose name
 * or preview matched. Everything about which of those appear, in what order, under which header and
 * whether a "See all" link is shown lives here so it can be tested without Compose.
 */
public object FlashSearchResultMath {

    /** Rows a section shows in the "All" view before it collapses behind "See all". */
    public const val SECTION_PREVIEW_LIMIT: Int = 3

    /** Which of [results]' file hits belong to the selected scope. */
    public fun filesForScope(
        results: FlashChatSearchResultsUi,
        scope: FlashSearchScope,
    ): List<FlashFileSearchHitUi> = when (scope) {
        FlashSearchScope.Files -> results.fileHits.filter { !it.isMedia }
        FlashSearchScope.Media -> results.fileHits.filter { it.isMedia }
        FlashSearchScope.All -> results.fileHits
        else -> emptyList()
    }

    /** Which of [results]' message hits belong to the selected scope. */
    public fun messagesForScope(
        results: FlashChatSearchResultsUi,
        scope: FlashSearchScope,
    ): List<FlashMessageSearchHitUi> = when (scope) {
        FlashSearchScope.Messages, FlashSearchScope.All -> results.messageHits
        FlashSearchScope.Links -> results.messageHits.filter { it.containsLink }
        else -> emptyList()
    }

    /** Number shown on the search bar's counter for the active scope. */
    public fun scopeCount(
        scope: FlashSearchScope,
        chats: List<FlashChatListItemUi>,
        results: FlashChatSearchResultsUi,
    ): Int = when (scope) {
        FlashSearchScope.All -> chats.size + results.messageHits.size + results.fileHits.size
        FlashSearchScope.Chats -> chats.size
        FlashSearchScope.Messages -> results.messageHits.size
        FlashSearchScope.Files -> results.fileHits.count { !it.isMedia }
        FlashSearchScope.Media -> results.fileHits.count { it.isMedia }
        FlashSearchScope.Links -> results.messageHits.count { it.containsLink }
    }

    /** "12 results" / "1 result". */
    public fun countLabel(count: Int): String = if (count == 1) "1 result" else "$count results"

    /**
     * The ordered rows to render.
     *
     * - [FlashSearchScope.All]: sectioned — Chats, Messages, Files, Media, Links — each capped at
     *   [SECTION_PREVIEW_LIMIT] with "See all" when the section is larger.
     * - A dedicated scope: one flat list of everything in that scope (no header, the chip says it).
     * - Nothing at all: a single [FlashSearchListEntry.NoResults] so the surface is never blank.
     *
     * [chats] are the conversations whose **name or preview** matched; conversations that matched
     * only through a message/file appear as their hit rows instead, so a thread is never listed
     * twice with no explanation of why.
     */
    public fun entries(
        scope: FlashSearchScope,
        chats: List<FlashChatListItemUi>,
        results: FlashChatSearchResultsUi,
        sectionLimit: Int = SECTION_PREVIEW_LIMIT,
    ): List<FlashSearchListEntry> {
        if (scope != FlashSearchScope.All) {
            val rows = when (scope) {
                FlashSearchScope.Chats -> chats.map { FlashSearchListEntry.Chat(it) }
                FlashSearchScope.Messages,
                FlashSearchScope.Links,
                -> messagesForScope(results, scope).map { FlashSearchListEntry.Message(it) }
                FlashSearchScope.Files,
                FlashSearchScope.Media,
                -> filesForScope(results, scope).map { FlashSearchListEntry.File(it) }
                FlashSearchScope.All -> emptyList()
            }
            return rows.ifEmpty { listOf(FlashSearchListEntry.NoResults(scope.label)) }
        }

        val media = results.fileHits.filter { it.isMedia }
        val plainFiles = results.fileHits.filter { !it.isMedia }
        val links = results.messageHits.filter { it.containsLink }
        // A link message would otherwise appear under BOTH Messages and Links with the same
        // LazyColumn key (a crash) and as the same row twice (noise). It lives under Links in the
        // all-scopes view; the Messages chip still lists every body hit.
        val plainMessages = results.messageHits.filterNot { it.containsLink }
        val out = ArrayList<FlashSearchListEntry>()
        fun <T> section(
            title: String,
            scope: FlashSearchScope,
            items: List<T>,
            row: (T) -> FlashSearchListEntry,
        ) {
            if (items.isEmpty()) return
            out += FlashSearchListEntry.SectionHeader(
                title = title,
                count = items.size,
                scope = scope,
                showSeeAll = items.size > sectionLimit,
            )
            items.take(sectionLimit).forEach { out += row(it) }
        }
        section("Chats", FlashSearchScope.Chats, chats) { FlashSearchListEntry.Chat(it) }
        section("Messages", FlashSearchScope.Messages, plainMessages) { FlashSearchListEntry.Message(it) }
        section("Files", FlashSearchScope.Files, plainFiles) { FlashSearchListEntry.File(it) }
        section("Media", FlashSearchScope.Media, media) { FlashSearchListEntry.File(it) }
        section("Links", FlashSearchScope.Links, links) { FlashSearchListEntry.Message(it) }
        return out.ifEmpty { listOf(FlashSearchListEntry.NoResults(FlashSearchScope.All.label)) }
    }

    /** Stable LazyColumn key per entry (SectionHeader carries its scope, rows their message id). */
    public fun key(entry: FlashSearchListEntry): String = when (entry) {
        FlashSearchListEntry.ScopeRow -> "search-scope-row"
        is FlashSearchListEntry.SectionHeader -> "search-header-${entry.scope.name}"
        is FlashSearchListEntry.Chat -> "search-chat-${entry.item.id}"
        is FlashSearchListEntry.Message -> "search-message-${entry.hit.messageId}"
        is FlashSearchListEntry.File -> "search-file-${entry.hit.messageId}-${entry.hit.fileName}"
        is FlashSearchListEntry.NoResults -> "search-no-results"
    }
}

/**
 * The scope chip row shown directly under the search bar. A plain horizontally-scrolling row of
 * Flash chips: selected chip gets the accent tint, the rest stay on `backgroundSurfaceSubtle`.
 */
@Composable
public fun FlashSearchScopeRow(
    scopes: List<FlashSearchScope>,
    selected: FlashSearchScope,
    onSelect: (FlashSearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val haptics = rememberFlashHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        scopes.forEach { scope ->
            val isSelected = scope == selected
            Box(
                modifier = Modifier
                    .background(
                        color = if (isSelected) colors.accentPrimary.copy(alpha = 0.18f) else colors.backgroundSurfaceSubtle,
                        shape = FlashShapes.chip,
                    )
                    .border(
                        width = FlashDimensions.borderHairline,
                        color = if (isSelected) colors.accentPrimary else colors.borderSubtle,
                        shape = FlashShapes.chip,
                    )
                    .clickable {
                        if (!isSelected) haptics(FlashHaptic.Tick)
                        onSelect(scope)
                    }
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (isSelected) "${scope.label} filter, selected" else "Filter by ${scope.label}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = scope.label,
                    style = typography.captionDefault,
                    color = if (isSelected) colors.accentPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
