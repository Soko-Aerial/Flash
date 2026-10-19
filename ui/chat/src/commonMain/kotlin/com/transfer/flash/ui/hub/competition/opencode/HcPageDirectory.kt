package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * **Page 2 — Organisation Directory and Peer Explorer.**
 *
 * The directory answers three questions in one place: who is in this organisation, how Flash can currently reach
 * them, and *why* this device is allowed to see them at all. The last one matters: an entry may exist because the
 * person shares a group, or because the department is hidden from the shared directory, and the UI says which.
 *
 * The list is the phone's page; the person's card is the pushed screen on a phone and the second pane on a desktop.
 * Both are the same composable ([HcPersonDetail]), so the two form factors cannot drift.
 */
@Composable
internal fun HcPageDirectory(deck: HcDeck, widthDp: Float) {
    val wide = hcIsWide(widthDp)
    var segment by remember(deck.scenario) { mutableStateOf(0) }
    var query by remember(deck.scenario) { mutableStateOf("") }
    var filterIndex by remember(deck.scenario) { mutableStateOf(0) }
    var selectedId by remember(deck.scenario) { mutableStateOf<String?>(null) }

    val filters = listOf("Everyone") + deck.departments.map { it.name }
    val visible = deck.visiblePeople.filter { person -> hcMatches(person, deck, query, filterIndex, filters) }
    val highlighted = selectedId ?: if (wide) visible.firstOrNull()?.deviceId else null
    val selected = deck.people.firstOrNull { it.deviceId == highlighted }

    HcSplitPage(
        widthDp = widthDp,
        detailOpen = selected != null,
        onCloseDetail = { selectedId = null },
        detailTitle = selected?.displayName ?: "Colleague",
        detailPlaceholder = {
            HcEmptyState(
                glyph = HcGlyph.PERSON,
                headline = "Choose a colleague",
                body = "Pick someone on the left to see how Flash knows them: their signed statement, their reachability, and whether this device is paired with them.",
            )
        },
        listPane = {
            HcScrollColumn {
                HcPageHeader(
                    title = "Directory",
                    subtitle = "${deck.orgName} · ${hcCount(deck.directoryTotal)} colleagues in ${deck.departments.size} departments",
                    glyph = HcGlyph.PERSON,
                    tone = HcTone.ACCENT,
                    statusLabel = deck.syncSentence,
                    statusPulse = deck.syncStatus == HubSyncStatus.OK,
                )
                HcSegmented(
                    options = listOf("People", "Departments"),
                    selectedIndex = segment,
                    onSelect = { segment = it },
                    modifier = Modifier.padding(bottom = FlashSpacing.space8),
                )
                if (segment == 0) {
                    HcSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search a colleague or a department",
                        modifier = Modifier.padding(bottom = FlashSpacing.space4),
                    )
                    HcFilterRow(
                        options = filters,
                        selectedIndex = filterIndex,
                        onSelect = { filterIndex = it },
                        countOf = { index -> hcFilterCount(deck, index, filters, query) },
                    )
                    if (deck.visiblePeople.isEmpty()) {
                        HcEmptyState(
                            glyph = HcGlyph.PERSON,
                            headline = if (deck.enrolled) "The directory is empty" else "No organisation yet",
                            body = if (deck.enrolled) {
                                "Nothing has synced on this device. A single successful sync fills this page."
                            } else {
                                "Enrol this device to see the organisation's colleague directory."
                            },
                            actionLabel = if (deck.enrolled) "Sync now" else "Enter an invitation code",
                            onAction = {},
                        )
                    } else if (visible.isEmpty()) {
                        HcEmptyState(
                            glyph = HcGlyph.SEARCH,
                            headline = "No colleague matches",
                            body = "No one in ${if (filterIndex == 0) "this organisation" else filters[filterIndex]} matches \u201C$query\u201D.",
                            actionLabel = "Clear the search",
                            onAction = { query = ""; filterIndex = 0 },
                        )
                    } else {
                        hcPeopleSections(deck, visible, filterIndex, filters, highlighted) { selectedId = it }
                    }
                } else {
                    hcDepartmentList(deck, query) { departmentId ->
                        filterIndex = filters.indexOfFirst { it == deck.departments.firstOrNull { d -> d.id == departmentId }?.name }.coerceAtLeast(0)
                        segment = 0
                    }
                }
            }
        },
        detailPane = { if (selected != null) HcPersonDetail(deck, selected) },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Filtering
// ---------------------------------------------------------------------------------------------------------------

private fun hcMatches(person: HubPersonUi, deck: HcDeck, query: String, filterIndex: Int, filters: List<String>): Boolean {
    val q = query.trim()
    if (q.isNotEmpty()) {
        val haystack = "${person.displayName} ${person.departmentName} ${hcRoleLabel(person.role)}".lowercase()
        if (!haystack.contains(q.lowercase())) return false
    }
    if (filterIndex <= 0) return true
    val departmentName = filters.getOrNull(filterIndex) ?: return true
    return person.departmentName == departmentName
}

private fun hcFilterCount(deck: HcDeck, index: Int, filters: List<String>, query: String): Int =
    deck.visiblePeople.count { hcMatches(it, deck, query, index, filters) }

private fun hcRoleLabel(role: HubPersonRole): String = when (role) {
    HubPersonRole.PERSON -> "Member"
    HubPersonRole.HEAD -> "Department head"
    HubPersonRole.ADMIN -> "Organisation admin"
}

private fun hcReach(person: HubPersonUi): Pair<String, HcTone> = when (person.reachability) {
    HubReachability.LAN -> "On the LAN" to HcTone.SUCCESS
    HubReachability.HUB -> "Through the hub" to HcTone.ACCENT
    HubReachability.OFFLINE -> "Offline" to HcTone.MUTED
    HubReachability.UNKNOWN -> "Reachability unknown" to HcTone.MUTED
}

private fun hcReachStrength(person: HubPersonUi): Int = when (person.reachability) {
    HubReachability.LAN -> 4
    HubReachability.HUB -> 2
    else -> 1
}

// ---------------------------------------------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------------------------------------------

/** One section per department when "Everyone" is selected; a flat list when a department is filtered. */
@Composable
private fun hcPeopleSections(
    deck: HcDeck,
    visible: List<HubPersonUi>,
    filterIndex: Int,
    filters: List<String>,
    highlighted: String?,
    onSelect: (String) -> Unit,
) {
    if (filterIndex > 0) {
        HcBand(filters[filterIndex], trailing = "${visible.size}") {
            visible.forEach { person -> HcPersonRow(person, person.deviceId == highlighted, onSelect) }
        }
        return
    }
    val sections = visible.groupBy { it.departmentName.ifBlank { "No department" } }
    sections.forEach { (department, people) ->
        HcBand(department, trailing = "${people.size}") {
            people.forEach { person -> HcPersonRow(person, person.deviceId == highlighted, onSelect) }
        }
    }
}

@Composable
private fun HcPersonRow(person: HubPersonUi, highlighted: Boolean, onSelect: (String) -> Unit) {
    val colors = FlashTheme.colors
    val tone = if (highlighted) HcTone.ACCENT else HcTone.NEUTRAL
    Row(
        Modifier
            .fillMaxWidth()
            .hcCardSurface(tone, RoundedCornerShape(HcDims.tileRadius))
            .clickable(role = Role.Button) { onSelect(person.deviceId) }
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        HcAvatar(
            name = person.displayName,
            seed = person.deviceId,
            diameter = FlashDimensions.avatarMd,
            online = person.online,
            ringTone = if (person.role == HubPersonRole.PERSON) null else HcTone.ACCENT,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(person.displayName, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (person.isSelf) HcChip("You", tone = HcTone.NEUTRAL, compact = true)
                if (person.role == HubPersonRole.ADMIN || person.role == HubPersonRole.HEAD) {
                    HcChip(
                        if (person.role == HubPersonRole.ADMIN) "Admin" else "Head",
                        tone = HcTone.ACCENT,
                        compact = true,
                    )
                }
            }
            FlashText(
                "${hcRoleLabel(person.role)} · ${person.departmentName.ifBlank { "No department" }}",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            HcChip(hcReach(person).first, tone = hcReach(person).second, compact = true, glyph = hcReachGlyph(person))
            FlashText(
                if (person.paired) "Paired on this device" else "Not paired",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        HcSignalBars(hcReachStrength(person), hcTone(hcReach(person).second).strong)
    }
}

private fun hcReachGlyph(person: HubPersonUi): HcGlyph = when (person.reachability) {
    HubReachability.LAN -> HcGlyph.WIFI
    HubReachability.HUB -> HcGlyph.RELAY
    HubReachability.OFFLINE -> HcGlyph.LINK_OFF
    HubReachability.UNKNOWN -> HcGlyph.SEARCH
}

// ---------------------------------------------------------------------------------------------------------------
// Departments
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun hcDepartmentList(deck: HcDeck, query: String, onOpenDepartment: (String) -> Unit) {
    if (deck.departments.isEmpty()) {
        HcEmptyState(
            glyph = HcGlyph.DEPARTMENT,
            headline = "No departments are visible",
            body = if (deck.enrolled) {
                "Either no department has synced yet, or every department is hidden from the shared directory."
            } else {
                "The organisation's departments appear here after this device is enrolled."
            },
        )
        return
    }
    val q = query.trim().lowercase()
    val shown = deck.departments.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    HcBand("Departments", trailing = "${shown.size} visible") {
        shown.forEach { department -> HcDepartmentCard(deck, department) { onOpenDepartment(department.id) } }
    }
    if (deck.privacy.hiddenDepartments) {
        CubeHint("Hidden departments are not listed here. You still see the people you share a group with.")
    }
}

@Composable
private fun HcDepartmentCard(deck: HcDeck, department: HubDepartmentUi, onOpen: () -> Unit) {
    val colors = FlashTheme.colors
    val tone = if (department.isMine) HcTone.ACCENT else if (department.hidden) HcTone.MUTED else HcTone.NEUTRAL
    HcCard(tone = tone, eyebrow = if (department.isMine) "Your department" else null, title = department.name, glyph = HcGlyph.DEPARTMENT) {
        HcKeyValue("Members", "${department.memberCount} in the organisation")
        HcKeyValue("Listed groups", if (department.groupCount == 0) "None" else "${department.groupCount}")
        HcKeyValue(
            "Head",
            department.headNames.firstOrNull() ?: "No head in the snapshot",
        )
        if (department.hidden) {
            HcKeyValue("Directory", "Hidden from colleagues outside it", tone = HcTone.MUTED, glyph = HcGlyph.SHIELD)
        }
        HcChipFlow {
            HcChip("${deck.visiblePeople.count { it.departmentId == department.id }} visible to you", tone = HcTone.NEUTRAL, compact = true)
            if (department.isMine) HcChip("You belong here", tone = HcTone.ACCENT, glyph = HcGlyph.CHECK, compact = true)
        }
        HcTextAction("Show these colleagues", onOpen, tone = HcTone.ACCENT)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------------------------------------------

/** The colleague card. Used as the pushed screen on a phone and as the second pane on a desktop. */
@Composable
private fun HcPersonDetail(deck: HcDeck, person: HubPersonUi) {
    val colors = FlashTheme.colors
    val reach = hcReach(person)
    HcCard(eyebrow = person.departmentName.ifBlank { "No department" }, title = person.displayName, glyph = HcGlyph.PERSON, tone = reach.second) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            HcAvatar(
                name = person.displayName,
                seed = person.deviceId,
                diameter = FlashDimensions.avatarXl,
                online = person.online,
                ringTone = if (person.role == HubPersonRole.PERSON) null else HcTone.ACCENT,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(hcRoleLabel(person.role), style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1)
                FlashText(
                    person.deviceId,
                    style = hcMono(FlashTheme.typography.numericDefault),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HcChipFlow {
            HcChip(reach.first, tone = reach.second, glyph = hcReachGlyph(person), compact = true)
            HcChip(
                if (person.online == null) "Presence hidden" else if (person.online == true) "Online" else "Offline",
                tone = if (person.online == null) HcTone.MUTED else if (person.online == true) HcTone.SUCCESS else HcTone.MUTED,
                compact = true,
            )
            if (person.orgVerified) HcChip("Signed statement matches", tone = HcTone.SUCCESS, glyph = HcGlyph.VERIFIED, compact = true)
            if (person.paired) HcChip("Paired", tone = HcTone.SUCCESS, glyph = HcGlyph.SHIELD, compact = true)
        }
        HcDivider()
        HcKeyValue("Reachable", hcReachSentence(person, deck))
        HcKeyValue("Department", person.departmentName.ifBlank { "Not assigned" })
        HcKeyValue("Directory statement", hcStatementSentence(deck), mono = false)
        HcKeyValue("This device's link", if (person.paired) "Paired identity, sealed directly when nearby" else "Through the hub, sealed with their published key")
        if (!person.orgVerified) {
            HcBannerCard(
                HcBanner(
                    id = "person-bind",
                    tone = HcTone.DANGER,
                    glyph = HcGlyph.WARNING,
                    title = "This entry does not match a live key",
                    body = "The directory lists ${person.displayName}, but no session has proved the statement's key. Chat stays closed until it does.",
                ),
            )
        }
        HcDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            HcButton("Message", {}, modifier = Modifier.weight(1f), glyph = HcGlyph.CHAT, enabled = person.orgVerified)
            HcButton("Call", {}, modifier = Modifier.weight(1f), glyph = HcGlyph.CALL, primary = false, enabled = person.orgVerified)
        }
        if (!person.paired) {
            HcButton("Pair this device", {}, glyph = HcGlyph.SHIELD, primary = false, tone = HcTone.NEUTRAL)
        }
        HcSoftRule()
        FlashText(
            hcVisibilityNote(deck, person),
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

private fun hcReachSentence(person: HubPersonUi, deck: HcDeck): String = when (person.reachability) {
    HubReachability.LAN -> "On the same network as this device right now"
    HubReachability.HUB -> "Signed in to ${deck.hubName}; messages wait there if they leave"
    HubReachability.OFFLINE -> "Not signed in and not on the LAN; messages are held"
    HubReachability.UNKNOWN -> "No live session and no presence; this is not the same as offline"
}

private fun hcStatementSentence(deck: HcDeck): String =
    if (deck.directoryVersion == 0L) "No snapshot on this device" else "From directory v${hcCount(deck.directoryVersion.toInt())}"

/** Why this person is visible. Honest about the two routes the project actually has. */
private fun hcVisibilityNote(deck: HcDeck, person: HubPersonUi): String = when {
    person.isSelf -> "This is this device's own directory entry."
    person.departmentName.isBlank() -> "Listed without a department; they appear because they share an organisation group with you."
    deck.departments.firstOrNull { it.id == person.departmentId }?.hidden == true ->
        "Their department is hidden from the shared directory. You see them because you share a group, not because the department is listed."
    person.paired -> "Paired with this device: Flash reaches them directly when you are both on one network."
    else -> "Listed in the organisation directory. Messaging goes through the hub because this device is not paired with them."
}

@Composable
private fun CubeHint(text: String) {
    val colors = FlashTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = FlashSpacing.space4),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(colors.backgroundSurfaceStrong), contentAlignment = Alignment.Center) {
            HcGlyphIcon(HcGlyph.SHIELD, tint = colors.textTertiary, size = 12.dp)
        }
        FlashText(text, Modifier.weight(1f), style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
    }
}
