@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Connection wording is independent of directory refresh: cached data does not mean a live connection. */
internal fun hubWorkspaceConnection(link: HubLinkState): String = when (link) {
    HubLinkState.ONLINE, HubLinkState.CERT_EXPIRING -> FlashHubCopy.OVERVIEW_CONNECTED
    HubLinkState.CONNECTING -> FlashHubCopy.WORKSPACE_CONNECTING
    HubLinkState.AUTHENTICATING -> FlashHubCopy.WORKSPACE_SIGNING_IN
    HubLinkState.CERT_EXPIRED -> FlashHubCopy.WORKSPACE_EXPIRED
    HubLinkState.SERVER_MISMATCH -> FlashHubCopy.WORKSPACE_ATTENTION
    HubLinkState.REVOKED -> FlashHubCopy.WORKSPACE_REMOVED
    HubLinkState.NOT_ENROLLED -> FlashHubCopy.WORKSPACE_UNENROLLED
    HubLinkState.DISCONNECTED, HubLinkState.OFFLINE -> FlashHubCopy.OVERVIEW_OFFLINE
}

/** Organization identity stays above navigation, including while loading or reading cached content. */
@Composable
internal fun FlashHubWorkspaceHeader(
    data: FlashHubScreenData,
    departmentName: String?,
    onSearch: () -> Unit,
    onStatus: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Column(modifier.fillMaxWidth().background(colors.backgroundSurface).padding(horizontal = FlashSpacing.space16)) {
        Row(
            Modifier.fillMaxWidth().padding(top = FlashSpacing.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashIcon(FlashIcons.Hub, contentDescription = null, tint = colors.textSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                FlashText(
                    data.state.orgName.ifBlank { FlashHubCopy.TAB_LABEL },
                    modifier = Modifier.semantics { heading() },
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
                if (data.state.loaded) FlashText(
                    FlashHubMath.overviewRoleLine(data.state.myRole, departmentName),
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                )
            }
            FlashHubIconButton(FlashIcons.Search, FlashHubCopy.SEARCH, onSearch)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = FlashDimensions.minTouchTarget)
                    .hubClickable(onClick = onStatus)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${hubWorkspaceConnection(data.link)}. ${FlashHubCopy.STATUS_OPEN}"
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                val online = data.link == HubLinkState.ONLINE || data.link == HubLinkState.CERT_EXPIRING
                FlashIcon(
                    if (online) FlashIcons.Verified else FlashIcons.Connection,
                    contentDescription = null,
                    tint = if (online) colors.textSuccess else colors.textSecondary,
                    size = FlashDimensions.iconSm,
                )
                FlashText(hubWorkspaceConnection(data.link), modifier = Modifier.weight(1f, fill = false), style = FlashTheme.typography.metadataDefault, color = colors.textSecondary)
                FlashIcon(FlashIcons.ChevronDown, contentDescription = null, tint = colors.textSecondary, size = FlashDimensions.iconSm)
            }
            if (LocalDensity.current.fontScale >= 1.3f) FlashHubIconButton(
                FlashIcons.Retry,
                if (data.state.isRefreshing) FlashHubCopy.REFRESHING else FlashHubCopy.STATUS_REFRESH,
                onRefresh,
                enabled = !data.state.isRefreshing,
            ) else FlashHubTextAction(
                if (data.state.isRefreshing) FlashHubCopy.REFRESHING else FlashHubCopy.STATUS_REFRESH,
                onRefresh,
                enabled = !data.state.isRefreshing,
            )
        }
    }
}

/** Scrollable list padding that clears the floating bottom navigation capsule when [bottomInset] > 0. */
internal fun hubContentPadding(bottomInset: Dp = 0.dp): PaddingValues =
    PaddingValues(bottom = FlashSpacing.space16 + bottomInset.coerceAtLeast(0.dp))

/** Prevents flashing unfiltered [allPeople] while async people search results ([searchHits]) are still loading. */
internal fun resolveHubPeopleForScreen(
    searching: Boolean,
    searchHits: List<HubPersonUi>?,
    allPeople: List<HubPersonUi>,
): List<HubPersonUi> = if (searching) searchHits.orEmpty() else allPeople

@Composable
internal fun FlashHubWorkspaceSection(segment: FlashHubSegment, count: Int?) {
    val title = if (segment == FlashHubSegment.NEWS) FlashHubCopy.WORKSPACE_NEWS else FlashHubMath.segmentLabel(segment)
    val description = when (segment) {
        FlashHubSegment.NEWS -> FlashHubCopy.WORKSPACE_NEWS_HELP
        FlashHubSegment.PEOPLE -> FlashHubCopy.WORKSPACE_PEOPLE_HELP
        FlashHubSegment.DEPARTMENTS -> FlashHubCopy.WORKSPACE_DEPARTMENTS_HELP
        FlashHubSegment.GROUPS -> FlashHubCopy.WORKSPACE_GROUPS_HELP
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space24),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(if (count == null) title else "$title · $count", Modifier.semantics { heading() }, style = FlashTheme.typography.headingMedium, color = FlashTheme.colors.textPrimary)
        FlashText(description, style = FlashTheme.typography.bodyDefault, color = FlashTheme.colors.textSecondary)
    }
}
