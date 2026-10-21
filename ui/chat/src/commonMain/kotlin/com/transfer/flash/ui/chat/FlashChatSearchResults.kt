package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashSearchText
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** What a match in the conversation is: body text, a file name/caption, or a link. */
public enum class FlashChatSearchKind { Message, File, Link }

/** One in-chat search match, ready to render in the results drawer. */
public data class FlashChatSearchHit(
    public val messageId: String,
    public val kind: FlashChatSearchKind,
    public val senderName: String,
    public val snippet: String,
    public val timeLabel: String,
)

/**
 * UI-023 upgrade: in-chat search results drawer.
 *
 * The header keeps the `3 / 7` stepper for fast cycling, but a long thread is not navigable by
 * pressing "next" seventeen times. This sheet-style panel under the bar lists **every** match with
 * its sender, time and a highlighted snippet; tapping one jumps straight to it. Scope chips narrow
 * the same search to messages, files or links, mirroring the global search's vocabulary.
 */
@Composable
public fun FlashChatSearchResultsPanel(
    query: String,
    hits: List<FlashChatSearchHit>,
    scope: FlashSearchScope,
    onScopeChanged: (FlashSearchScope) -> Unit,
    onHitClick: (FlashChatSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface),
    ) {
        FlashSearchScopeRow(
            scopes = ConversationSearchScopes,
            selected = scope,
            onSelect = onScopeChanged,
        )
        when {
            query.isBlank() -> FlashText(
                text = "Type to search text, files and links in this conversation",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            )
            hits.isEmpty() -> FlashText(
                text = if (scope == FlashSearchScope.All) {
                    "No matches for \"$query\""
                } else {
                    "No ${scope.label.lowercase()} match \"$query\""
                },
                style = typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            )
            else -> {
                FlashText(
                    text = FlashSearchResultMath.countLabel(hits.size),
                    style = typography.metadataEmphasis,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = FlashSpacing.space16, top = FlashSpacing.space4),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(hits, key = { it.messageId }) { hit ->
                        FlashChatSearchResultRow(
                            hit = hit,
                            query = query,
                            onClick = { onHitClick(hit) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashChatSearchResultRow(
    hit: FlashChatSearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val highlighted = remember(hit.snippet, query) {
        buildHighlightedMessageText(
            text = hit.snippet,
            query = query,
            highlightColor = colors.accentPrimary.copy(alpha = 0.35f),
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "${hit.senderName} at ${hit.timeLabel}: ${hit.snippet}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            modifier = Modifier
                .size(FlashDimensions.minTouchTarget)
                .clip(CircleShape)
                .background(colors.backgroundSurfaceSubtle),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = when (hit.kind) {
                    FlashChatSearchKind.File -> FlashIcons.Transfer
                    FlashChatSearchKind.Link -> FlashIcons.Relay
                    FlashChatSearchKind.Message -> FlashIcons.Chat
                },
                contentDescription = null,
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = "${hit.senderName} · ${hit.timeLabel}",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
            )
            FlashText(
                text = highlighted,
                style = typography.bodyDefault,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pure matching for in-chat search. Replaces the "body text only" rule: file names and image
 * captions are searchable, and the Links scope matches links specifically.
 */
public object FlashChatSearchHits {

    /** True when [message] has any searchable representation of [query]. */
    public fun matches(message: FlashMessageUi, rawQuery: String): Boolean =
        hits(listOf(message), rawQuery).isNotEmpty()

    /**
     * Ordered (oldest-first, like the thread) matches for [rawQuery] narrowed to [scope].
     *
     * A message appears once per scope; its [FlashChatSearchKind] prefers File (the most specific
     * thing a person can be looking for), then Link, then plain text.
     */
    public fun hits(
        messages: List<FlashMessageUi>,
        rawQuery: String,
        scope: FlashSearchScope = FlashSearchScope.All,
    ): List<FlashChatSearchHit> {
        val query = FlashChatSearchMath.normalizeQuery(rawQuery)
        if (query.isEmpty()) return emptyList()
        return messages.mapNotNull { message ->
            val textMatch = message.text.contains(query, ignoreCase = true)
            val matchingFile = message.fileAttachments.firstOrNull {
                it.name.contains(query, ignoreCase = true)
            }?.name
            val matchingCaption = message.images
                .mapNotNull { it.caption }
                .firstOrNull { it.contains(query, ignoreCase = true) }
            val fileMatch = matchingFile != null || matchingCaption != null
            val linkMatch = FlashSearchText.matchesLink(message.text, query)
            val include = when (scope) {
                FlashSearchScope.Files -> fileMatch
                FlashSearchScope.Links -> linkMatch
                FlashSearchScope.Messages -> textMatch
                else -> textMatch || fileMatch || linkMatch
            }
            if (!include) return@mapNotNull null
            val kind = when {
                // In a dedicated scope the row must describe what that scope matched: a message with
                // both a "meeting.pdf" and a link is a File hit in Files and a Link hit in Links.
                scope == FlashSearchScope.Files -> FlashChatSearchKind.File
                scope == FlashSearchScope.Links -> FlashChatSearchKind.Link
                scope == FlashSearchScope.Messages -> FlashChatSearchKind.Message
                fileMatch -> FlashChatSearchKind.File
                linkMatch -> FlashChatSearchKind.Link
                else -> FlashChatSearchKind.Message
            }
            val snippet = when {
                scope == FlashSearchScope.Links -> FlashSearchText.snippet(message.text, query)
                textMatch -> FlashSearchText.snippet(message.text, query)
                matchingFile != null -> matchingFile
                else -> matchingCaption.orEmpty()
            }
            FlashChatSearchHit(
                messageId = message.id,
                kind = kind,
                senderName = message.senderName,
                snippet = snippet,
                timeLabel = message.timeLabel,
            )
        }
    }
}
