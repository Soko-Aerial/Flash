package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashFileSearchHitUi
import com.transfer.flash.core.messaging.model.FlashMessageSearchHitUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Result rows for the structured global search (UI-024 upgrade).
 *
 * Before this, a global search only filtered conversation rows: a chat matched by a message sent
 * months ago still showed its latest preview, and there was no record of *what* matched. These rows
 * carry the match itself — snippet, sender, time, file name/size — and are the tap targets that
 * deep-link into the conversation at that message.
 */

/** "Messages · 12" header with an optional "See all" that switches the active scope. */
@Composable
public fun FlashSearchSectionHeader(
    title: String,
    count: Int,
    showSeeAll: Boolean,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = FlashSpacing.space16, end = FlashSpacing.space8, top = FlashSpacing.space16, bottom = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = title,
            style = typography.metadataEmphasis,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        FlashText(
            text = count.toString(),
            style = typography.metadataDefault,
            color = colors.textTertiary,
        )
        if (showSeeAll) {
            Box(
                modifier = Modifier
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                    .semantics {
                        role = Role.Button
                        contentDescription = "See all $title"
                    },
            ) {
                FlashText(
                    text = "See all",
                    style = typography.metadataEmphasis,
                    color = colors.accentPrimary,
                )
            }
        }
    }
}

/** A message-body hit: chat · sender · time, then a two-line highlighted snippet. */
@Composable
public fun FlashMessageSearchResultRow(
    hit: FlashMessageSearchHitUi,
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Message in ${hit.conversationTitle} from ${hit.senderName}: ${hit.snippet}"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlashText(
                text = hit.conversationTitle,
                style = typography.metadataEmphasis,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            FlashText(
                text = " · ${hit.senderName} · ${hit.timeLabel}",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlashText(
            text = highlighted,
            style = typography.bodyDefault,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = FlashSpacing.space2),
        )
    }
}

/** A file-name hit: extension badge, highlighted file name and "1.2 MB · Design Team · Oct 8". */
@Composable
public fun FlashFileSearchResultRow(
    hit: FlashFileSearchHitUi,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val badge = remember(hit.fileName) { FlashSharedContentMath.badgeForExtension(hit.fileName) }
    val size = remember(hit.fileSizeBytes) { FlashSharedContentMath.formatFileSize(hit.fileSizeBytes) }
    val highlighted = remember(hit.fileName, query) {
        buildHighlightedMessageText(
            text = hit.fileName,
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
                contentDescription = "File ${hit.fileName}, $size, shared in ${hit.conversationTitle} by ${hit.senderName}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.backgroundSurfaceSubtle, RoundedCornerShape(FlashSpacing.space8))
                .semantics { contentDescription = "File type $badge" },
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = badge,
                style = typography.metadataEmphasis,
                color = colors.accentPrimary,
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = highlighted,
                style = typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = "$size · ${hit.conversationTitle} · ${hit.timeLabel}",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlashIcon(
            icon = FlashIcons.Transfer,
            contentDescription = null,
            tint = colors.textTertiary,
            size = FlashDimensions.iconSm,
        )
    }
}

/** Never a blank surface: says what was searched and, when scoped, how to widen it. */
@Composable
public fun FlashSearchNoResults(
    query: String,
    scopeLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space24)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlashText(
            text = if (query.isBlank()) "Type to search messages, files and links" else "No matches",
            style = typography.bodyEmphasis,
            color = colors.textSecondary,
        )
        if (query.isNotBlank()) {
            FlashText(
                text = if (scopeLabel == FlashSearchScope.All.label) {
                    "Nothing in chats, messages, files or links matches \"$query\"."
                } else {
                    "Nothing in $scopeLabel matches \"$query\". Try All."
                },
                style = typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space4),
            )
        }
    }
}
