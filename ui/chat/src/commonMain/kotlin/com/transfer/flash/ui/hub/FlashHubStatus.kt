@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.messaging.model.HubSyncStatus
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** One line of the status page: an icon in a tone medallion, the topic, the plain-language value. Tone is never colour alone. */
internal data class FlashHubStatusRow(
    val icon: FlashIconSpec,
    val title: String,
    val value: String,
    val tone: HubTone,
)

/**
 * The rows of the Hub status page, built only from what the screen already knows (link, last sync, lease, clock). Pure, so
 * the wording and tones are unit-tested without composing.
 */
internal fun hubStatusRows(data: FlashHubScreenData): List<FlashHubStatusRow> {
    val state = data.state
    val online = data.link == HubLinkState.ONLINE || data.link == HubLinkState.CERT_EXPIRING
    val server = FlashHubStatusRow(
        icon = FlashIcons.Relay,
        title = FlashHubCopy.STATUS_SERVER,
        value = if (online) FlashHubCopy.OVERVIEW_CONNECTED else FlashHubCopy.OVERVIEW_OFFLINE,
        tone = if (online) HubTone.SUCCESS else HubTone.NEUTRAL,
    )
    val age = state.lastSyncedAtMs?.let { FlashHubMath.relativeTime(it, data.nowMs) }
    val directory = when {
        age == null -> FlashHubStatusRow(FlashIcons.Group, FlashHubCopy.STATUS_DIRECTORY, FlashHubCopy.STATUS_NEVER_SYNCED, HubTone.NEUTRAL)
        state.syncStatus == HubSyncStatus.FAILED_OFFLINE || state.syncStatus == HubSyncStatus.FAILED_OTHER ->
            FlashHubStatusRow(FlashIcons.Group, FlashHubCopy.STATUS_DIRECTORY, FlashHubCopy.statusShowing(age), HubTone.WARNING)
        else -> FlashHubStatusRow(FlashIcons.Group, FlashHubCopy.STATUS_DIRECTORY, FlashHubCopy.statusUpdated(age), HubTone.SUCCESS)
    }
    val days = FlashHubVisualMath.leaseDaysLeft(state.lease, data.nowMs)
    val trust = FlashHubStatusRow(
        icon = FlashIcons.Encryption,
        title = FlashHubCopy.STATUS_TRUST,
        value = when (days) {
            null -> FlashHubCopy.STATUS_NO_LEASE
            0 -> FlashHubCopy.STATUS_LEASE_ENDED
            else -> FlashHubCopy.statusLeaseDays(days)
        },
        tone = FlashHubVisualMath.leaseTone(state.lease, data.nowMs).hubTone(),
    )
    val clock = FlashHubStatusRow(
        icon = FlashIcons.Clock,
        title = FlashHubCopy.STATUS_CLOCK,
        value = if (state.clockWrong) FlashHubCopy.STRIP_CLOCK_WRONG else FlashHubCopy.STATUS_CLOCK_OK,
        tone = if (state.clockWrong) HubTone.WARNING else HubTone.SUCCESS,
    )
    return listOf(server, directory, trust, clock)
}

/** The Hub status page, shown in a sheet: four honest rows, one Refresh button, and the plain explanation of offline trust. */
@Composable
internal fun FlashHubStatusContent(
    data: FlashHubScreenData,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val rows = hubStatusRows(data)
    Column(
        modifier = modifier.fillMaxWidth().padding(FlashSpacing.space16).padding(bottom = FlashSpacing.space24),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            FlashHubCopy.STATUS_TITLE,
            modifier = Modifier.semantics { heading() },
            style = FlashTheme.typography.headingMedium,
            color = colors.textPrimary,
        )
        rows.forEachIndexed { index, row -> HubStatusRowView(row, Modifier.hubStagger(index + 1, "hub-status")) }
        FlashHubNoteCard(FlashIcons.Encryption, FlashHubCopy.WHY_BODY, horizontalPadding = FlashSpacing.space2)
        FlashHubButton(
            label = if (data.state.isRefreshing) FlashHubCopy.REFRESHING else FlashHubCopy.STATUS_REFRESH,
            onClick = onRefresh,
            enabled = !data.state.isRefreshing,
            icon = FlashIcons.Retry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HubStatusRowView(row: FlashHubStatusRow, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val tint = row.tone.color()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(HubShapes.control)
            .background(tint.copy(alpha = 0.08f))
            .border(FlashDimensions.borderHairline, tint.copy(alpha = 0.30f), HubShapes.control)
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) { contentDescription = "${row.title}: ${row.value}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(icon = row.icon, contentDescription = null, tint = tint, size = FlashDimensions.iconSm)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            FlashText(row.title, style = FlashTheme.typography.metadataDefault, color = colors.textSecondary, maxLines = 1)
            FlashText(row.value, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
