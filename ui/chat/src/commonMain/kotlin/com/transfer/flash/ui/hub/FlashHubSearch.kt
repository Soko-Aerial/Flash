package com.transfer.flash.ui.hub

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Results of the top-bar search: people, departments, news and groups in four labelled blocks (design doc "Search"). */
internal fun LazyListScope.hubSearchItems(
    query: String,
    people: List<HubPersonUi>,
    departments: List<HubDepartmentUi>,
    news: List<HubAnnouncementUi>,
    groups: List<HubGroupUi>,
    nowMs: Long,
    deptName: (String) -> String?,
    onOpenPerson: (String) -> Unit,
    onOpenDepartment: (String) -> Unit,
    onOpenNews: (String) -> Unit,
    onSeeAllPeople: () -> Unit,
    onGroup: (HubGroupUi) -> Unit,
    joiningGroupId: String?,
) {
    if (people.isEmpty() && departments.isEmpty() && news.isEmpty() && groups.isEmpty()) {
        item(key = "search-empty", contentType = "empty") {
            FlashHubEmptyState(icon = FlashIcons.Search, headline = FlashHubCopy.searchNothing(query))
        }
        return
    }
    if (people.isNotEmpty()) {
        item(key = "search-h-people", contentType = "header") { SearchTitle(FlashHubCopy.SEARCH_PEOPLE_TITLE) }
        val shown = people.take(FlashHubMath.SEARCH_PEOPLE_PREVIEW)
        items(shown.size, key = { "sp:${shown[it].deviceId}" }, contentType = { "person" }) { i ->
            val p = shown[i]
            FlashHubPersonRow(p, showDepartment = true, onClick = { onOpenPerson(p.deviceId) })
        }
        if (people.size > shown.size) {
            item(key = "search-see-all", contentType = "action") {
                FlashHubTextAction(FlashHubCopy.SEARCH_SEE_ALL_PEOPLE, onSeeAllPeople, modifier = Modifier.padding(horizontal = FlashSpacing.space8))
            }
        }
    }
    if (departments.isNotEmpty()) {
        item(key = "search-h-departments", contentType = "header") { SearchTitle(FlashHubCopy.SEARCH_DEPARTMENTS_TITLE) }
        items(departments.size, key = { "sd:${departments[it].id}" }, contentType = { "department" }) { i ->
            val d = departments[i]
            FlashHubDepartmentCard(d, onClick = { onOpenDepartment(d.id) })
        }
    }
    if (news.isNotEmpty()) {
        item(key = "search-h-news", contentType = "header") { SearchTitle(FlashHubCopy.SEARCH_NEWS_TITLE) }
        items(news.size, key = { "sn:${news[it].id}" }, contentType = { "card" }) { i ->
            val a = news[i]
            FlashAnnouncementCard(
                announcement = a,
                scopeLabel = FlashHubMath.scopeLabel(a, deptName),
                hasMenu = false,
                nowMs = nowMs,
                onOpen = { onOpenNews(a.id) },
                onMenu = {},
            )
        }
    }
    if (groups.isNotEmpty()) {
        item(key = "search-h-groups", contentType = "header") { SearchTitle(FlashHubCopy.SEARCH_GROUPS_TITLE) }
        items(groups.size, key = { "sg:${groups[it].groupId}" }, contentType = { "group" }) { i ->
            val g = groups[i]
            FlashHubGroupRow(g, busy = joiningGroupId == g.groupId, onAction = { onGroup(g) })
        }
    }
}

@androidx.compose.runtime.Composable
private fun SearchTitle(text: String) {
    FlashText(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8).semantics { heading() },
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textTertiary,
    )
}
