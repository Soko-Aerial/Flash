@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Filter chips + the list (with sticky letter headers) of the People segment. Items are built off the main thread by the screen. */
internal fun LazyListScope.hubPeopleItems(
    items: List<FlashHubPeopleItem>,
    filter: FlashHubPeopleFilter,
    onFilter: (FlashHubPeopleFilter) -> Unit,
    departments: List<HubDepartmentUi>,
    myDepartmentId: String?,
    query: String,
    onOpenPerson: (String) -> Unit,
) {
    item(key = "people-filters", contentType = "filters") {
        FlashHubPeopleFilterRow(filter, onFilter, departments, myDepartmentId)
    }
    if (items.isEmpty()) {
        item(key = "people-empty", contentType = "empty") {
            FlashHubEmptyState(
                icon = FlashIcons.Group,
                headline = FlashHubMath.peopleEmptyText(query, filter),
            )
        }
        return
    }
    items.forEach { entry ->
        when (entry) {
            is FlashHubPeopleItem.Header -> stickyHeader(key = entry.key, contentType = "header") { FlashHubLetterHeader(entry.letter) }
            is FlashHubPeopleItem.Person -> item(key = entry.key, contentType = "person") {
                FlashHubPersonRow(entry.person, entry.showDepartment, onClick = { onOpenPerson(entry.person.deviceId) })
            }
        }
    }
}

@Composable
internal fun FlashHubLetterHeader(letter: String, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    FlashText(
        text = letter,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundApp)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
            .semantics { heading() },
        style = FlashTheme.typography.metadataEmphasis,
        color = colors.textSecondary,
    )
}

@Composable
internal fun FlashHubPeopleFilterRow(
    filter: FlashHubPeopleFilter,
    onFilter: (FlashHubPeopleFilter) -> Unit,
    departments: List<HubDepartmentUi>,
    myDepartmentId: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashHubFilterChip(FlashHubCopy.PEOPLE_FILTER_ALL, filter == FlashHubPeopleFilter.All) { onFilter(FlashHubPeopleFilter.All) }
        if (myDepartmentId != null) {
            FlashHubFilterChip(FlashHubCopy.PEOPLE_FILTER_MINE, filter == FlashHubPeopleFilter.Mine) { onFilter(FlashHubPeopleFilter.Mine) }
        }
        departments.filter { it.id != myDepartmentId }.forEach { d ->
            val selected = filter is FlashHubPeopleFilter.Department && filter.id == d.id
            FlashHubFilterChip(d.name, selected) { onFilter(FlashHubPeopleFilter.Department(d.id)) }
        }
    }
}

@Composable
internal fun FlashHubFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FlashTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .clip(FlashShapes.navBar)
            .background(if (selected) colors.accentPrimary.copy(alpha = 0.16f) else colors.backgroundSurfaceSubtle)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = FlashSpacing.space12)
            .semantics { semanticsSelected = selected },
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            label,
            style = if (selected) FlashTheme.typography.bodyEmphasis else FlashTheme.typography.bodyDefault,
            color = if (selected) colors.accentPrimary else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One colleague. Min height 64 dp, avatar 44 dp; every text part ellipsises, the spoken sentence has everything. */
@Composable
internal fun FlashHubPersonRow(person: HubPersonUi, showDepartment: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val spoken = FlashHubMath.personAccessibility(person, includeDepartment = showDepartment)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Box {
            FlashAvatar(initials = FlashHubMath.initials(person.displayName), size = 44.dp, seed = person.deviceId)
            FlashHubPresenceDot(person.online, Modifier.align(Alignment.BottomEnd))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(
                text = if (person.isSelf) "${person.displayName} · ${FlashHubCopy.PEOPLE_YOU}" else person.displayName,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showDepartment && person.departmentName.isNotBlank()) {
                FlashText(person.departmentName, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlashHubMath.roleBadge(person.role)?.let { FlashHubChip(it, emphasised = true) }
        if (person.paired && !person.isSelf) FlashHubChip(FlashHubCopy.PEOPLE_PAIRED)
    }
}

/** What the person sheet / detail pane needs from the screen. */
internal data class FlashHubPersonPaneState(
    val busy: Boolean = false,
    val note: String? = null,
    val callAllowed: Boolean = false,
    val pairAvailable: Boolean = false,
)

/** Person profile: used in the bottom sheet (compact) and in the detail pane (wide). Never shows keys. */
@Composable
internal fun FlashHubPersonContent(
    person: HubPersonUi,
    pane: FlashHubPersonPaneState,
    onMessage: () -> Unit,
    onCall: () -> Unit,
    onPair: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val sheet = FlashHubMath.personSheet(person, pane.callAllowed, pane.pairAvailable)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16).padding(bottom = FlashSpacing.space24),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space16)) {
            Box {
                FlashAvatar(initials = FlashHubMath.initials(person.displayName), size = FlashDimensions.avatarXl, seed = person.deviceId)
                FlashHubPresenceDot(person.online, Modifier.align(Alignment.BottomEnd))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(sheet.title, style = FlashTheme.typography.headingMedium, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                FlashText(sheet.departmentLine, style = FlashTheme.typography.bodyDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    sheet.roleBadge?.let { FlashHubChip(it, emphasised = true) }
                    if (sheet.pairedBadge) FlashHubChip(FlashHubCopy.PEOPLE_PAIRED)
                }
                FlashText(sheet.idLine, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary)
            }
            if (onClose != null) FlashHubIconButton(FlashIcons.Close, FlashHubCopy.PERSON_CLOSE, onClose)
        }
        FlashText(sheet.reachabilityLine, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary,
            modifier = Modifier.semantics { contentDescription = FlashHubCopy.reachabilityAccessibility(sheet.reachabilityLine) })
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8), modifier = Modifier.fillMaxWidth()) {
            FlashHubButton(
                label = if (pane.busy) FlashHubCopy.PERSON_MESSAGING else sheet.messageLabel,
                onClick = onMessage,
                enabled = sheet.messageEnabled && !pane.busy,
                icon = FlashIcons.Chat,
                modifier = Modifier.weight(1f),
            )
            if (sheet.showCall) FlashHubButton(FlashHubCopy.PERSON_CALL, onCall, icon = FlashIcons.Call, primary = false, modifier = Modifier.weight(1f))
        }
        if (sheet.showPair) FlashHubButton(FlashHubCopy.PERSON_PAIR, onPair, icon = FlashIcons.Connection, primary = false, modifier = Modifier.fillMaxWidth())
        pane.note?.let {
            FlashText(it, style = FlashTheme.typography.bodyDefault, color = colors.textError,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        sheet.footer?.let { FlashText(it, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary) }
        sheet.honesty?.let { FlashText(it, style = FlashTheme.typography.metadataDefault, color = colors.textTertiary) }
    }
}
