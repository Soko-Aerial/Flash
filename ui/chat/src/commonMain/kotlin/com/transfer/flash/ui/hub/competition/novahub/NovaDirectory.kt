package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Page 2: Directory & Peer Explorer.
 *
 * Search, the sync watermark (snapshot age, monotonic version, backoff), department chips, and the
 * colleague rows with their honest reach + key-status signals. Tapping a person opens the peer
 * drawer — the sheet that explains what the hub changes about chatting with them (and what it does
 * not: push-to-talk stays paired-only). Hidden departments never render: privacy option C means
 * they are absent from a member's view, and the page says so in words instead of pretending.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

@Composable
internal fun NovaDirectoryPage(
    world: NovaWorld,
    onDemoAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedDeptId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedPersonId by rememberSaveable { mutableStateOf<String?>(null) }

    NovaPageScaffold(page = NovaPage.Directory, world = world, onDemoAction = onDemoAction, modifier = modifier) {
        NovaSearchField(query = query, onQueryChange = { query = it })
        NovaSyncCard(world)
        if (world.departments.isNotEmpty()) {
            NovaDepartmentChips(
                departments = world.departments,
                selectedId = selectedDeptId,
                onSelect = { id -> selectedDeptId = if (selectedDeptId == id) null else id },
            )
        }
        NovaPeopleSection(
            world = world,
            query = query,
            selectedDeptId = selectedDeptId,
            onOpenPerson = { openedPersonId = it },
        )
        NovaPrivacyNote()
    }

    val opened = world.people.firstOrNull { it.id == openedPersonId }
    if (opened != null) {
        NovaPeerDrawer(person = opened, onDismiss = { openedPersonId = null }, onDemoAction = onDemoAction)
    }
}

// --- Sync watermark ------------------------------------------------------------------------------------

private fun novaSyncLabel(state: NovaSyncState): String = when (state) {
    NovaSyncState.Never -> "Never synced"
    NovaSyncState.Ok -> "Synced"
    NovaSyncState.Syncing -> "Syncing"
    NovaSyncState.FailedOffline -> "Offline"
    NovaSyncState.FailedOther -> "Retrying"
    NovaSyncState.Unsupported -> "Not on this hub"
}

@Composable
private fun NovaSyncCard(world: NovaWorld) {
    val sync = world.sync
    val tone = sync.state.tone()
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(
                icon = when (sync.state) {
                    NovaSyncState.Ok, NovaSyncState.Syncing -> FlashIcons.Verified
                    NovaSyncState.FailedOffline, NovaSyncState.FailedOther -> FlashIcons.Clock
                    else -> FlashIcons.Group
                },
                tone = tone,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = "Directory sync",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                NovaMetaLine(
                    text = "Snapshot ${sync.snapshotAge} · version ${sync.version} · ${sync.visibleCount} people visible",
                    icon = FlashIcons.Relay,
                )
                sync.backoffSentence?.let {
                    NovaMetaLine(text = it, icon = FlashIcons.Clock)
                }
                if (sync.state == NovaSyncState.Syncing) {
                    NovaSkeletonLine(Modifier.padding(top = FlashSpacing.space4), widthFraction = 0.7f)
                    NovaSkeletonLine(widthFraction = 0.45f, height = 10.dp)
                    NovaMeter(progress = 0.55f, tone = tone, flowing = true)
                }
            }
            NovaPill(text = novaSyncLabel(sync.state), tone = tone)
        }
    }
}

// --- Departments ---------------------------------------------------------------------------------------

@Composable
private fun NovaDepartmentChips(
    departments: List<NovaDepartment>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    // Privacy option C: hidden departments are not rendered at all for non-members — the page's
    // privacy note below explains the behaviour in words instead.
    val visible = departments.filter { !it.hidden }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovaFilterChip(label = "All departments", selected = selectedId == null, onClick = { onSelect(null) })
        visible.forEach { dept ->
            NovaFilterChip(
                label = if (dept.mine) "${dept.name} · mine" else dept.name,
                selected = selectedId == dept.id,
                count = dept.memberCount,
                onClick = { onSelect(dept.id) },
            )
        }
    }
}

// --- People --------------------------------------------------------------------------------------------

@Composable
private fun NovaPeopleSection(
    world: NovaWorld,
    query: String,
    selectedDeptId: String?,
    onOpenPerson: (String) -> Unit,
) {
    val q = query.trim()
    val people = world.people.filter { person ->
        val deptOk = selectedDeptId == null || person.departmentId == selectedDeptId
        val queryOk = q.isEmpty() ||
            person.name.contains(q, ignoreCase = true) ||
            person.department.contains(q, ignoreCase = true) ||
            (person.roleBadge?.contains(q, ignoreCase = true) == true)
        deptOk && queryOk
    }
    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        NovaSectionHeader(
            title = "Colleagues",
            trailing = if (world.people.isEmpty()) "" else "${people.size} of ${world.people.size}",
        )
        if (world.people.isEmpty()) {
            NovaCard {
                NovaEmptyPanel(
                    icon = FlashIcons.Group,
                    title = "No organisation directory yet",
                    body = "Colleagues load with your first successful sync and stay cached for when the hub is away.",
                    tone = NovaTone.NEUTRAL,
                )
            }
        } else if (people.isEmpty()) {
            NovaCard {
                NovaEmptyPanel(
                    icon = FlashIcons.Search,
                    title = "Nobody matches",
                    body = "No colleague, department or role matches \"${query.trim()}\" in the snapshot this device may see.",
                    tone = NovaTone.NEUTRAL,
                )
            }
        } else {
            NovaCard(contentPadding = FlashSpacing.space4) {
                people.forEachIndexed { index, person ->
                    NovaPersonRow(
                        person = person,
                        index = index,
                        key = world.scenario,
                        onClick = { onOpenPerson(person.id) },
                    )
                    if (index != people.lastIndex) NovaDivider()
                }
            }
        }
    }
}

@Composable
private fun NovaPersonRow(
    person: NovaMember,
    index: Int,
    key: Any,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .novaClick(onClick)
            .novaStagger(index, key)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        NovaMonogramTile(initials = person.initialsText, seed = person.seed, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    text = person.name,
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                person.roleBadge?.let {
                    NovaPill(text = it, tone = NovaTone.NEUTRAL, withIcon = false)
                }
                if (person.isSelf) NovaPill(text = "You", tone = NovaTone.INFO, withIcon = false)
            }
            NovaMetaLine(
                text = "${person.department} · ${person.reach.label}",
                icon = when (person.reach) {
                    NovaReach.LAN -> FlashIcons.Wifi
                    NovaReach.Hub -> FlashIcons.Relay
                    else -> FlashIcons.Clock
                },
            )
            if (!person.orgVerified) {
                FlashText(
                    text = "Device key changed since the snapshot — verify before you trust it",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Presence: a dot only when presence is actually known. Unknown shows NO dot, by design.
        when (person.reach.dot()) {
            true -> NovaDot(tone = NovaTone.OK, breathe = person.reach == NovaReach.LAN)
            false -> NovaDot(tone = NovaTone.NEUTRAL)
            null -> Unit
        }
    }
}

// --- Privacy note --------------------------------------------------------------------------------------

@Composable
private fun NovaPrivacyNote() {
    NovaCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            NovaIconTile(icon = FlashIcons.Encryption, tone = NovaTone.NEUTRAL)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                FlashText(
                    text = "Directory privacy",
                    style = FlashTheme.typography.captionEmphasis,
                    color = FlashTheme.colors.textPrimary,
                )
                FlashText(
                    text = "Hidden departments appear only to their own members and administrators — for everyone else they are simply absent, not greyed out. What you browse here stays on this device.",
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textSecondary,
                )
            }
        }
    }
}

// --- Peer drawer ---------------------------------------------------------------------------------------

@Composable
private fun NovaPeerDrawer(
    person: NovaMember,
    onDismiss: () -> Unit,
    onDemoAction: (String) -> Unit,
) {
    val colors = FlashTheme.colors
    Box(Modifier.fillMaxSize()) {
        // Scrim: tapping anywhere outside closes the drawer.
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .novaClick(onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(NovaSheetShape)
                .background(FlashTheme.colors.sheetSurface)
                .border(FlashDimensions.borderHairline, FlashTheme.colors.borderSubtle, NovaSheetShape)
                .padding(FlashSpacing.space20),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(NovaHairShape)
                        .background(colors.borderStrong),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
                NovaMonogramTile(initials = person.initialsText, seed = person.seed, size = 52.dp, shape = NovaHeroShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
                    FlashText(
                        text = person.name,
                        style = FlashTheme.typography.headingSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NovaMetaLine(
                        text = "${person.department}${person.roleBadge?.let { r -> " · $r" } ?: ""}",
                        icon = FlashIcons.Group,
                    )
                    NovaMetaLine(text = person.reach.label, icon = FlashIcons.Relay)
                }
                NovaIconButton(icon = FlashIcons.Close, label = "Close", onClick = onDismiss)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                NovaPill(
                    text = if (person.paired) "Paired with you" else "Not paired yet",
                    tone = if (person.paired) NovaTone.OK else NovaTone.NEUTRAL,
                    icon = if (person.paired) FlashIcons.Check else FlashIcons.Clock,
                )
                NovaPill(
                    text = if (person.orgVerified) "Key matches the organisation statement" else "Key changed since the snapshot",
                    tone = if (person.orgVerified) NovaTone.OK else NovaTone.WARN,
                )
            }
            FlashText(
                text = "You can chat with colleagues over the organisation. Push-to-talk stays a paired-device feature, and this drawer never shows key material.",
                style = FlashTheme.typography.captionDefault,
                color = colors.textSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                NovaPrimaryButton(
                    label = if (person.paired) "Open chat" else "Chat over the hub",
                    onClick = { onDemoAction("Chat with ${person.name} (demo — nothing was sent)") },
                )
                if (!person.paired) {
                    NovaActionChip(
                        label = "Pair device",
                        onClick = { onDemoAction("Pairing ${person.name} (demo — pairing is owned by the pairing track)") },
                    )
                }
            }
        }
    }
}
