package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay

/**
 * **Page 3 — Hub Announcements and Broadcast Feed.**
 *
 * The project stores announcements *readable* on the hub (ADR-138), so this page never dresses them as private. The
 * reader carries the honesty notice as part of the content rather than as a footnote, and the composer states its
 * audience in words before it posts.
 *
 * The composer appears only when the device actually holds posting rights, so the same page shows a member nothing to
 * post with and a department head a scoped audience picker — no dead "you cannot post" button anywhere.
 */
@Composable
internal fun HcPageAnnouncements(deck: HcDeck, widthDp: Float) {
    val wide = hcIsWide(widthDp)
    var filterIndex by remember(deck.scenario) { mutableStateOf(0) }
    var selectedId by remember(deck.scenario) { mutableStateOf<String?>(null) }
    var readIds by remember(deck.scenario) { mutableStateOf(deck.announcements.filter { it.isRead }.map { it.id }.toSet()) }
    var pinnedIds by remember(deck.scenario) { mutableStateOf(deck.announcements.filter { it.pinned }.map { it.id }.toSet()) }
    var removedIds by remember(deck.scenario) { mutableStateOf(emptySet<String>()) }

    val live = deck.announcements.filter { it.id !in removedIds }
    val unread = live.count { it.id !in readIds }
    val filters = listOf("All", "Organisation", "Departments", "Unread", "Pinned")
    val visible = live
        .filter { item ->
            when (filterIndex) {
                1 -> item.scope == "org"
                2 -> item.scope == "department"
                3 -> item.id !in readIds
                4 -> item.id in pinnedIds
                else -> true
            }
        }
        .sortedWith(compareByDescending<HubAnnouncementUi> { it.id in pinnedIds }.thenByDescending { it.createdAtMs })

    val highlighted = selectedId ?: if (wide) visible.firstOrNull()?.id else null
    val selected = live.firstOrNull { it.id == highlighted }

    HcSplitPage(
        widthDp = widthDp,
        detailOpen = selected != null,
        onCloseDetail = { selectedId = null },
        detailTitle = selected?.title ?: "Announcement",
        detailPlaceholder = {
            HcEmptyState(
                glyph = HcGlyph.ANNOUNCEMENT,
                headline = "Choose an announcement",
                body = "Pinned notices and the newest ones sit at the top. Everything here is stored readable on the hub.",
            )
        },
        listPane = {
            HcScrollColumn {
                HcPageHeader(
                    title = "Announcements",
                    subtitle = "${deck.orgName} · ${deck.myDepartment}",
                    glyph = HcGlyph.ANNOUNCEMENT,
                    tone = if (unread > 0) HcTone.ACCENT else HcTone.MUTED,
                    statusLabel = when {
                        deck.announcements.isEmpty() -> "Nothing has been posted"
                        unread > 0 -> "$unread unread · newest ${hcAgo(deck.nowMs, live.maxOfOrNull { it.createdAtMs } ?: deck.nowMs)}"
                        else -> "All read · newest ${hcAgo(deck.nowMs, live.maxOfOrNull { it.createdAtMs } ?: deck.nowMs)}"
                    },
                    statusPulse = unread > 0,
                )
                if (deck.posting != HubPostingRights.None) {
                    HcAnnouncementComposer(deck, Modifier.padding(bottom = FlashSpacing.space4))
                }
                HcFilterRow(
                    options = filters,
                    selectedIndex = filterIndex,
                    onSelect = { filterIndex = it },
                    countOf = { index ->
                        when (index) {
                            1 -> live.count { it.scope == "org" }
                            2 -> live.count { it.scope == "department" }
                            3 -> unread
                            4 -> live.count { it.id in pinnedIds }
                            else -> live.size
                        }
                    },
                )
                when {
                    deck.announcements.isEmpty() -> HcEmptyState(
                        glyph = HcGlyph.ANNOUNCEMENT,
                        headline = if (deck.enrolled) "No announcements yet" else "No organisation yet",
                        body = if (deck.enrolled) {
                            "Notices from the organisation and this device's department arrive here. Nothing has been posted so far."
                        } else {
                            "Announcements appear after this device is enrolled with a hub."
                        },
                        actionLabel = if (deck.enrolled) "Check again" else "Enter an invitation code",
                        onAction = {},
                    )
                    visible.isEmpty() -> HcEmptyState(
                        glyph = HcGlyph.ANNOUNCEMENT,
                        headline = hcEmptyHeadline(filterIndex),
                        body = hcEmptyBody(filterIndex),
                        actionLabel = "Show everything",
                        onAction = { filterIndex = 0 },
                    )
                    else -> HcBand(
                        title = if (filterIndex == 0) "Newest first" else filters[filterIndex],
                        trailing = "${visible.size}",
                    ) {
                        visible.forEach { item ->
                            HcAnnouncementRow(
                                deck = deck,
                                item = item,
                                unread = item.id !in readIds,
                                pinned = item.id in pinnedIds,
                                highlighted = item.id == highlighted,
                                onSelect = { selectedId = item.id },
                            )
                        }
                    }
                }
            }
        },
        detailPane = {
            if (selected != null) {
                HcAnnouncementReader(
                    deck = deck,
                    item = selected,
                    isRead = selected.id in readIds,
                    isPinned = selected.id in pinnedIds,
                    canModerate = deck.posting != HubPostingRights.None,
                    onToggleRead = {
                        readIds = if (selected.id in readIds) readIds - selected.id else readIds + selected.id
                    },
                    onTogglePin = {
                        pinnedIds = if (selected.id in pinnedIds) pinnedIds - selected.id else pinnedIds + selected.id
                    },
                    onEdit = {},
                    onDelete = {
                        removedIds = removedIds + selected.id
                        selectedId = null
                    },
                )
            }
        },
    )
}

private fun hcEmptyHeadline(filterIndex: Int): String = when (filterIndex) {
    1 -> "Nothing company-wide"
    2 -> "Nothing from a department"
    3 -> "Nothing is unread"
    4 -> "Nothing is pinned"
    else -> "Nothing to show"
}

private fun hcEmptyBody(filterIndex: Int): String = when (filterIndex) {
    1 -> "The organisation administrator has not posted a company-wide notice."
    2 -> "No department has posted a notice this device can see."
    3 -> "Every announcement this device can see has been read."
    4 -> "Open an announcement and pin it to keep it at the top of this list."
    else -> "This filter has no announcements."
}

// ---------------------------------------------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------------------------------------------

/** The scoped composer. Only rendered when the deck says this device may post. */
@Composable
private fun HcAnnouncementComposer(deck: HcDeck, modifier: Modifier = Modifier) {
    val audiences = hcAudiences(deck)
    var expanded by remember(deck.scenario) { mutableStateOf(false) }
    var title by remember(deck.scenario) { mutableStateOf("") }
    var body by remember(deck.scenario) { mutableStateOf("") }
    var audienceIndex by remember(deck.scenario) { mutableStateOf(0) }
    var pinned by remember(deck.scenario) { mutableStateOf(false) }
    var validation by remember(deck.scenario) { mutableStateOf<String?>(null) }
    var note by remember(deck.scenario) { mutableStateOf<String?>(null) }
    var submitting by remember(deck.scenario) { mutableStateOf(false) }
    var submitTick by remember(deck.scenario) { mutableStateOf(0) }

    LaunchedEffect(submitTick) {
        if (submitTick == 0) return@LaunchedEffect
        submitting = true
        note = null
        delay(1300)
        submitting = false
        note = "Posted to ${audiences.getOrNull(audienceIndex)?.first ?: "the organisation"}. The hub stores it readable."
        title = ""
        body = ""
        pinned = false
    }

    val scopeSentence = when (val p = deck.posting) {
        is HubPostingRights.OrgWide -> "You may post company-wide or to any department."
        is HubPostingRights.Department ->
            "You may post to ${p.departmentIds.mapNotNull { id -> deck.departments.firstOrNull { it.id == id }?.name }.joinToString(", ")} only."
        HubPostingRights.None -> ""
    }

    HcCard(
        modifier = modifier,
        eyebrow = "Announcement",
        title = if (expanded) "New announcement" else "Post an announcement",
        glyph = HcGlyph.EDIT,
        action = {
            HcTextAction(if (expanded) "Cancel" else "Write", { expanded = !expanded; note = null })
        },
    ) {
        if (!expanded) {
            FlashText(scopeSentence, style = FlashTheme.typography.metadataDefault, color = FlashTheme.colors.textSecondary)
            return@HcCard
        }
        HcTextField(
            value = title,
            onValueChange = { title = it; validation = null },
            placeholder = "Title, e.g. Release freeze from Thursday",
            singleLine = true,
        )
        HcTextField(
            value = body,
            onValueChange = { body = it },
            placeholder = "What everyone needs to know. Plain text; it is stored readable.",
            minHeight = 96.dp,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText("Audience", style = FlashTheme.typography.metadataEmphasis, color = FlashTheme.colors.textTertiary)
            HcChipFlow {
                audiences.forEachIndexed { index, (label, _) ->
                    HcChoiceChip(
                        label = label,
                        selected = index == audienceIndex,
                        onClick = { audienceIndex = index },
                    )
                }
            }
        }
        HcSwitch(on = pinned, onToggle = { pinned = it }, label = "Pin to the top of the feed")
        if (validation != null) {
            FlashText(validation!!, style = FlashTheme.typography.metadataEmphasis, color = FlashTheme.colors.textError)
        }
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                HcGlyphIcon(HcGlyph.CHECK, tint = hcTone(HcTone.SUCCESS).on, size = 16.dp)
                FlashText(note!!, Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = hcTone(HcTone.SUCCESS).on)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcButton(
                label = if (submitting) "Posting…" else "Post",
                enabled = !submitting,
                glyph = HcGlyph.UPLOAD,
                onClick = {
                    if (title.isBlank()) {
                        validation = "Give the announcement a title."
                    } else if (body.isBlank()) {
                        validation = "Write the announcement's text."
                    } else {
                        validation = null
                        submitTick += 1
                    }
                },
                modifier = Modifier.weight(1f),
            )
            HcButton("Discard", { title = ""; body = ""; pinned = false; validation = null; note = null }, primary = false, tone = HcTone.NEUTRAL)
        }
    }
}

private fun hcAudiences(deck: HcDeck): List<Pair<String, String?>> = when (val posting = deck.posting) {
    is HubPostingRights.OrgWide ->
        listOf("Everyone" to null) + deck.departments
            .filter { posting.allDepartments.isEmpty() || it.id in posting.allDepartments }
            .map { it.name to it.id }
    is HubPostingRights.Department ->
        deck.departments.filter { it.id in posting.departmentIds }.map { it.name to it.id }
    HubPostingRights.None -> emptyList()
}

// ---------------------------------------------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcAnnouncementRow(
    deck: HcDeck,
    item: HubAnnouncementUi,
    unread: Boolean,
    pinned: Boolean,
    highlighted: Boolean,
    onSelect: () -> Unit,
) {
    val colors = FlashTheme.colors
    val tone = when {
        highlighted -> HcTone.ACCENT
        pinned -> HcTone.WARNING
        unread -> HcTone.ACCENT
        else -> HcTone.NEUTRAL
    }
    Row(
        Modifier
            .fillMaxWidth()
            .hcCardSurface(tone, RoundedCornerShape(HcDims.tileRadius))
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        if (unread) {
            Box(Modifier.padding(top = 5.dp)) { HcPulseDot(HcTone.ACCENT, pulsing = true, diameter = 8.dp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText(
                item.title,
                style = if (unread) FlashTheme.typography.bodyEmphasis else FlashTheme.typography.bodyDefault,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                hcPreview(item.body),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                HcChip(hcScopeLabel(deck, item), tone = if (item.scope == "org") HcTone.ACCENT else HcTone.NEUTRAL, compact = true)
                if (pinned) HcChip("Pinned", tone = HcTone.WARNING, glyph = HcGlyph.PIN, compact = true)
                if (item.edited) HcChip("Edited", tone = HcTone.MUTED, compact = true)
                item.expiresAtMs?.let { HcChip("${hcUntil(deck.nowMs, it)}", tone = HcTone.MUTED, compact = true) }
            }
            FlashText(
                "${item.authorName} · ${hcAgo(deck.nowMs, item.createdAtMs)}",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            FlashText(
                "#${item.seq}",
                style = hcMono(FlashTheme.typography.numericDefault),
                color = colors.textTertiary,
                maxLines = 1,
            )
            HcChevron(colors.textTertiary)
        }
    }
}

private fun hcPreview(body: String): String {
    val firstLine = body.substringBefore('\n').trim()
    return firstLine.ifBlank { body.trim() }
}

private fun hcScopeLabel(deck: HcDeck, item: HubAnnouncementUi): String =
    if (item.scope == "org") {
        "Everyone"
    } else {
        deck.departments.firstOrNull { it.id == item.departmentId }?.name ?: "Department"
    }

// ---------------------------------------------------------------------------------------------------------------
// Reader
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HcAnnouncementReader(
    deck: HcDeck,
    item: HubAnnouncementUi,
    isRead: Boolean,
    isPinned: Boolean,
    canModerate: Boolean,
    onToggleRead: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = FlashTheme.colors
    HcCard(
        eyebrow = hcScopeLabel(deck, item),
        title = item.title,
        glyph = HcGlyph.ANNOUNCEMENT,
        tone = if (isPinned) HcTone.WARNING else HcTone.NEUTRAL,
        action = { HcChip(if (isRead) "Read" else "Unread", tone = if (isRead) HcTone.MUTED else HcTone.ACCENT, compact = true) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcAvatar(item.authorName, item.authorName, diameter = FlashDimensions.avatarSm)
            Column(Modifier.weight(1f)) {
                FlashText(item.authorName, style = FlashTheme.typography.captionEmphasis, color = colors.textPrimary, maxLines = 1)
                FlashText(hcAuthorRole(item.authorRole), style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 1)
            }
            FlashText(
                hcAgo(deck.nowMs, item.createdAtMs),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        HcDivider()
        item.body.split("\n\n").forEach { paragraph ->
            if (paragraph.isNotBlank()) {
                FlashText(
                    paragraph.trim(),
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textPrimary,
                )
            }
        }
        HcDivider()
        HcKeyValue("Sequence", "#${item.seq}", mono = true)
        HcKeyValue("Published", hcAgo(deck.nowMs, item.createdAtMs))
        if (item.edited) HcKeyValue("Edited", hcAgo(deck.nowMs, item.updatedAtMs))
        item.expiresAtMs?.let { HcKeyValue("Expires", hcUntil(deck.nowMs, it), tone = HcTone.WARNING, glyph = HcGlyph.CLOCK) }
        HcBannerCard(
            HcBanner(
                id = "ann-plain",
                tone = HcTone.WARNING,
                glyph = HcGlyph.SHIELD,
                title = "Stored readable on the hub",
                body = "Announcements are not sealed end to end. Anyone who can reach the hub with a valid account can read this text. Do not put secrets here.",
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcButton(
                label = if (isRead) "Mark as unread" else "Mark as read",
                onClick = onToggleRead,
                glyph = if (isRead) HcGlyph.CLOSE else HcGlyph.CHECK,
                primary = !isRead,
                modifier = Modifier.weight(1f),
            )
            HcButton(
                label = if (isPinned) "Unpin" else "Pin",
                onClick = onTogglePin,
                glyph = HcGlyph.PIN,
                primary = false,
                tone = if (isPinned) HcTone.WARNING else HcTone.ACCENT,
                modifier = Modifier.weight(1f),
            )
        }
        if (canModerate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                HcTextAction("Edit", onEdit, enabled = false, tone = HcTone.NEUTRAL)
                Spacer(Modifier.weight(1f))
                HcTextAction("Delete for everyone", onDelete, tone = HcTone.DANGER)
            }
        }
    }
}

private fun hcAuthorRole(role: String): String = when (role.lowercase()) {
    "admin", "org_admin" -> "Organisation administrator"
    "dept_admin", "head" -> "Department head"
    else -> "Member"
}
