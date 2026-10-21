package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.avatar.flashAvatarColorsFor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.settings.FlashHubStatusMath
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.flashAnimateItem
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** The Departments segment: one card per department the caller may see. */
internal fun LazyListScope.hubDepartmentItems(
    departments: List<HubDepartmentUi>,
    onOpen: (String) -> Unit,
) {
    if (departments.isEmpty()) {
        item(key = "dept-empty", contentType = "empty") {
            FlashHubEmptyState(icon = FlashIcons.Group, headline = FlashHubCopy.NO_DEPARTMENTS)
        }
        return
    }
    val largest = departments.maxOf { it.memberCount }
    items(departments.size, key = { "d:${departments[it].id}" }, contentType = { "department" }) { i ->
        val d = departments[i]
        FlashHubDepartmentCard(
            d, onClick = { onOpen(d.id) },
            share = FlashHubVisualMath.share(d.memberCount, largest),
            modifier = flashAnimateItem(FlashTheme.motion).hubStagger(if (i < HUB_STAGGER_ITEMS) i else -1, "hub-dept"),
        )
    }
}

@Composable
internal fun FlashHubDepartmentCard(d: HubDepartmentUi, onClick: () -> Unit, modifier: Modifier = Modifier, share: Float? = null) {
    val colors = FlashTheme.colors
    val grown = rememberHubGrow(share ?: 0f, label = "hubDeptShare")
    Row(
        modifier = modifier
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
            .fillMaxWidth()
            .clip(HubShapes.card)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, if (d.isMine) colors.accentPrimary else colors.borderSubtle, HubShapes.card)
            .hubClickable(onClick = onClick)
            .padding(FlashSpacing.space16)
            .semantics(mergeDescendants = true) { contentDescription = FlashHubMath.departmentAccessibility(d) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        HubMark(initials = FlashHubMath.initials(d.name), seed = d.id, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                FlashText(d.name, modifier = Modifier.weight(1f, fill = false), style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d.isMine) FlashHubChip(FlashHubCopy.DEPT_YOURS, emphasised = true)
            }
            FlashText(FlashHubMath.departmentSummary(d), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
            FlashText(FlashHubMath.headsLine(d), style = FlashTheme.typography.metadataDefault, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (share != null) {
                Box(Modifier.fillMaxWidth().padding(top = FlashSpacing.space4).height(4.dp).clip(HubShapes.capsule).background(colors.backgroundSurfaceStrong)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(grown).clip(HubShapes.capsule).background(colors.accentPrimary.copy(alpha = if (d.isMine) 1f else 0.6f)))
                }
            }
        }
    }
}

/** Sections shown on a department's own screen. */
@Composable
internal fun FlashHubDepartmentContent(
    department: HubDepartmentUi,
    members: List<HubPersonUi>,
    groups: List<HubGroupUi>,
    news: List<HubAnnouncementUi>,
    nowMs: Long,
    onBack: (() -> Unit)?,
    onOpenPerson: (String) -> Unit,
    onMessage: (String) -> Unit,
    onOpenAnnouncement: (String) -> Unit,
    onSeeAllPeople: () -> Unit,
    onGroupAction: (HubGroupUi) -> Unit,
    joiningGroupId: String?,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val shownMembers = members.take(MEMBER_PREVIEW)
    Column(modifier = modifier.fillMaxWidth().padding(vertical = FlashSpacing.space8), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
        Row(Modifier.padding(horizontal = FlashSpacing.space8), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) FlashHubIconButton(FlashIcons.Back, FlashHubCopy.DEPT_BACK, onBack)
            FlashText(department.name, modifier = Modifier.weight(1f).padding(start = FlashSpacing.space8).semantics { heading() },
                style = FlashTheme.typography.headingMedium, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        FlashText(FlashHubMath.departmentSummary(department), Modifier.padding(horizontal = FlashSpacing.space16),
            style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)

        HubBlockTitle(FlashHubCopy.DEPT_HEADS)
        if (department.headIds.isEmpty()) {
            HubBlockNote(FlashHubCopy.DEPT_NO_HEADS)
        } else {
            department.headIds.forEachIndexed { i, id ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = FlashSpacing.space16),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    FlashText(department.headNames.getOrNull(i) ?: id.takeLast(4), modifier = Modifier.weight(1f), style = FlashTheme.typography.bodyEmphasis,
                        color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlashHubButton(FlashHubCopy.PERSON_MESSAGE, { onMessage(id) }, icon = FlashIcons.Chat, primary = false)
                }
            }
        }

        HubBlockTitle(FlashHubCopy.DEPT_MEMBERS)
        if (members.isEmpty()) HubBlockNote(FlashHubCopy.DEPT_NO_MEMBERS)
        shownMembers.forEach { p -> FlashHubPersonRow(p, showDepartment = false, onClick = { onOpenPerson(p.deviceId) }) }
        if (members.size > shownMembers.size) {
            FlashHubTextAction("${FlashHubCopy.SEGMENT_PEOPLE} (${members.size})", onSeeAllPeople, Modifier.padding(horizontal = FlashSpacing.space8))
        }

        HubBlockTitle(FlashHubCopy.DEPT_GROUPS)
        if (groups.isEmpty()) HubBlockNote(FlashHubCopy.DEPT_NO_GROUPS)
        groups.forEach { g -> FlashHubGroupRow(g, busy = joiningGroupId == g.groupId, onAction = { onGroupAction(g) }) }

        HubBlockTitle(FlashHubCopy.DEPT_NEWS)
        if (news.isEmpty()) HubBlockNote(FlashHubCopy.DEPT_NO_NEWS)
        news.take(5).forEach { a ->
            FlashAnnouncementCard(a, FlashHubMath.scopeLabel(a) { department.name }, hasMenu = false, nowMs = nowMs, onOpen = { onOpenAnnouncement(a.id) }, onMenu = {})
        }
    }
}

private const val MEMBER_PREVIEW = 25


@Composable
private fun HubBlockTitle(text: String) {
    FlashHubSectionTitle(text)
}

@Composable
private fun HubBlockNote(text: String) {
    FlashText(text, modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4),
        style = FlashTheme.typography.bodyDefault, color = FlashTheme.colors.textTertiary)
}

/** The Groups segment: yours first, then by department; join / request / waiting / open. */
internal fun LazyListScope.hubGroupItems(
    groups: List<HubGroupUi>,
    loadError: String?,
    feedback: String?,
    joiningGroupId: String?,
    onAction: (HubGroupUi) -> Unit,
    onRetry: () -> Unit,
    onManage: (() -> Unit)? = null,
) {
    if (feedback != null) {
        item(key = "groups-feedback", contentType = "banner") {
            FlashHubNoteCard(FlashIcons.Chat, feedback, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    if (groups.isEmpty()) {
        item(key = "groups-empty", contentType = "empty") {
            if (loadError != null) {
                FlashHubEmptyState(icon = FlashIcons.Relay, headline = FlashHubCopy.GROUPS_NOT_LOADED, body = loadError,
                    actionLabel = FlashHubCopy.RETRY, onAction = onRetry)
            } else {
                FlashHubEmptyState(icon = FlashIcons.Relay, headline = FlashHubCopy.NO_GROUPS, body = FlashHubCopy.NO_GROUPS_BODY)
            }
        }
        return
    }
    FlashHubMath.groupSections(groups).forEach { section ->
        item(key = "gs:${section.title}", contentType = "header") { HubBlockTitle(section.title) }
        items(section.groups.size, key = { "g:${section.groups[it].groupId}" }, contentType = { "group" }) { i ->
            val g = section.groups[i]
            FlashHubGroupRow(g, busy = joiningGroupId == g.groupId, onAction = { onAction(g) }, modifier = flashAnimateItem(FlashTheme.motion))
        }
    }
    if (onManage != null) {
        item(key = "groups-manage", contentType = "action") {
            FlashHubTextAction(FlashHubCopy.GROUPS_MANAGE, onManage, modifier = Modifier.padding(horizontal = FlashSpacing.space8))
        }
    }
    item(key = "groups-limitation", contentType = "footer") {
        FlashText(FlashHubStatusMath.GROUP_LIMITATION_TEXT, modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            style = FlashTheme.typography.metadataDefault, color = FlashTheme.colors.textTertiary)
    }
}

@Composable
internal fun FlashHubGroupRow(g: HubGroupUi, busy: Boolean, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val label = FlashHubMath.groupButtonLabel(g)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = false) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        HubMark(initials = FlashHubMath.initials(g.name), seed = g.groupId, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(g.name, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlashText(FlashHubMath.groupMeta(g), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlashHubButton(
            label = if (busy) FlashHubCopy.REFRESHING else label,
            onClick = onAction,
            enabled = FlashHubMath.groupButtonEnabled(g, busy),
            primary = g.membership == HubGroupMembership.NOT_JOINED,
        )
    }
}
