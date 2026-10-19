package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Page 3: Broadcast (organisation announcements).
 *
 * Filter chips with honest counts, then the announcement cards: author identity, scope, priority
 * and pin badges, unread dots, and an inline expand instead of a separate reader screen. Read
 * state is part of the demo (tapping opens and marks read) but nothing pretends to reach a server
 * — actions route to the judge demo notice.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Filter the feed is displayed under. "Mine" is the viewer's own department. */
private enum class NovaNewsFilter(val label: String) {
    All("All"),
    Unread("Unread"),
    Company("Company"),
    Mine("My department"),
}

@Composable
internal fun NovaNewsPage(
    world: NovaWorld,
    onDemoAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(NovaNewsFilter.All) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var readIds by rememberSaveable { mutableStateOf(setOf<String>()) }

    NovaPageScaffold(page = NovaPage.Broadcast, world = world, onDemoAction = onDemoAction, modifier = modifier) {
        NovaPostingNote()
        if (world.news.isEmpty()) {
            NovaCard {
                NovaEmptyPanel(
                    icon = FlashIcons.Announcement,
                    title = "No announcements",
                    body = "When your organisation posts, it lands here first — pinned bulletins at the top, everything else newest first.",
                    tone = NovaTone.NEUTRAL,
                )
            }
        } else {
            NovaNewsFilterChips(
                world = world,
                readIds = readIds,
                selected = filter,
                onSelect = { filter = it },
            )
            val visible = remember(world.news, filter, readIds) { novaVisibleNews(world, filter, readIds) }
            if (visible.isEmpty()) {
                NovaCard {
                    NovaEmptyPanel(
                        icon = FlashIcons.Check,
                        title = "All caught up",
                        body = "Nothing unread under this filter. Read items stay below until they are removed upstream.",
                        tone = NovaTone.OK,
                    )
                }
            } else {
                visible.forEach { item ->
                    NovaNewsCard(
                        item = item,
                        expanded = expandedId == item.id,
                        unread = item.unread && item.id !in readIds,
                        index = visible.indexOf(item),
                        key = world.scenario,
                        onToggle = {
                            expandedId = if (expandedId == item.id) null else item.id
                            readIds = readIds + item.id
                        },
                        onDemoAction = onDemoAction,
                    )
                }
            }
        }
    }
}

private fun novaVisibleNews(world: NovaWorld, filter: NovaNewsFilter, readIds: Set<String>): List<NovaAnnouncement> =
    world.news.filter { item ->
        when (filter) {
            NovaNewsFilter.All -> true
            NovaNewsFilter.Unread -> item.unread && item.id !in readIds
            NovaNewsFilter.Company -> item.departmentId == null
            NovaNewsFilter.Mine -> item.departmentId == world.people.firstOrNull { it.isSelf }?.departmentId
        }
    }

// --- Filters -------------------------------------------------------------------------------------------

@Composable
private fun NovaNewsFilterChips(
    world: NovaWorld,
    readIds: Set<String>,
    selected: NovaNewsFilter,
    onSelect: (NovaNewsFilter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovaFilterChip(
            label = NovaNewsFilter.All.label,
            selected = selected == NovaNewsFilter.All,
            count = world.news.size,
            onClick = { onSelect(NovaNewsFilter.All) },
        )
        NovaFilterChip(
            label = NovaNewsFilter.Unread.label,
            selected = selected == NovaNewsFilter.Unread,
            count = world.news.count { it.unread && it.id !in readIds },
            onClick = { onSelect(NovaNewsFilter.Unread) },
        )
        NovaFilterChip(
            label = NovaNewsFilter.Company.label,
            selected = selected == NovaNewsFilter.Company,
            count = world.news.count { it.departmentId == null },
            onClick = { onSelect(NovaNewsFilter.Company) },
        )
        NovaFilterChip(
            label = NovaNewsFilter.Mine.label,
            selected = selected == NovaNewsFilter.Mine,
            count = world.news.count { it.departmentId == world.people.firstOrNull { p -> p.isSelf }?.departmentId },
            onClick = { onSelect(NovaNewsFilter.Mine) },
        )
    }
}

// --- Cards ---------------------------------------------------------------------------------------------

@Composable
private fun NovaNewsCard(
    item: NovaAnnouncement,
    expanded: Boolean,
    unread: Boolean,
    index: Int,
    key: Any,
    onToggle: () -> Unit,
    onDemoAction: (String) -> Unit,
) {
    val colors = FlashTheme.colors
    NovaCard(
        modifier = Modifier
            .novaStagger(index, key)
            .novaClick(onToggle),
        contentPadding = FlashSpacing.space12,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            NovaMonogramTile(initials = item.initialsText, seed = item.seed, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                    FlashText(
                        text = item.author,
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FlashText(
                        text = item.authorRole,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
                NovaMetaLine(
                    text = item.timeLine + if (item.edited) " · edited" else "",
                    icon = FlashIcons.Clock,
                )
            }
            if (item.pinned) {
                NovaIconBadgeMini(icon = FlashIcons.Pin, label = "Pinned")
            }
            if (unread) NovaDot(tone = NovaTone.INFO, breathe = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            NovaPill(
                text = item.scopeLabel,
                tone = if (item.departmentId == null) NovaTone.INFO else NovaTone.NEUTRAL,
                withIcon = false,
            )
            if (item.highPriority) {
                NovaPill(text = "Priority", tone = NovaTone.WARN)
            }
        }
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = item.title,
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(FlashSpacing.space2))
        FlashText(
            text = item.body,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            Spacer(Modifier.height(FlashSpacing.space8))
            NovaDivider()
            Row(
                Modifier.padding(top = FlashSpacing.space8),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                NovaActionChip(
                    label = "Copy link",
                    onClick = { onDemoAction("Announcement link copied (demo — the clipboard gets a flash://hub/a/${item.id} style link)") },
                    tone = NovaTone.INFO,
                )
                NovaActionChip(
                    label = if (unread) "Mark read" else "Read",
                    onClick = { onDemoAction("Read state is local to the demo") },
                    tone = NovaTone.NEUTRAL,
                )
            }
        } else {
            FlashText(
                text = if (unread) "Tap to read and mark read" else "Tap to expand",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space4),
            )
        }
    }
}

/** A tiny icon-only badge (pin). The pill carries the label as its own accessibility description. */
@Composable
private fun NovaIconBadgeMini(icon: FlashIconSpec, label: String) {
    val colors = FlashTheme.colors
    Row(
        Modifier
            .clip(NovaPillShape)
            .background(colors.backgroundSurfaceSubtle)
            .border(FlashDimensions.borderHairline, colors.borderDefault, NovaPillShape)
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(icon = icon, size = 14.dp, tint = colors.textSecondary, contentDescription = label)
    }
}

// --- Posting note --------------------------------------------------------------------------------------

@Composable
private fun NovaPostingNote() {
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Announcement, tone = NovaTone.INFO)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = "Who posts here",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = "Only administrators and department heads. Announcements are stored unencrypted on the hub — that is what makes them readable for the whole organisation they are addressed to.",
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
        }
    }
}
