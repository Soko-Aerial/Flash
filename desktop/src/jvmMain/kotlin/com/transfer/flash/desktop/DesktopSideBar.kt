package com.transfer.flash.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Phase 22, sub-step 22-3 — the vertical tab bar shown at expanded window widths (≥840dp).
 *
 * Desktop-only component, deliberately local to `:desktop` (Step 4's rule: its 200.dp width
 * and this whole bar are NOT lifted to `ui:theme` — if it ever becomes useful for an Android
 * tablet layout, lift it then). Consumes the same tab set the bottom nav shows, via
 * [DesktopSideTab] so both bars share one source of truth.
 */
@Composable
public fun DesktopSideBar(
    tabs: List<DesktopSideTab>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(FlashTheme.colors.backgroundSurface)
            .padding(FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.destination == selectedTab
            val bg by animateColorAsState(
                targetValue = when (isSelected) {
                    true -> FlashTheme.colors.backgroundSurfaceStrong
                    false -> FlashTheme.colors.backgroundSurfaceSubtle
                },
                label = "sidebarTabBg",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FlashShapes.radius12))
                    .background(bg)
                    .clickable { onTabSelected(tab.destination) }
                    .padding(FlashSpacing.space12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(icon = tab.icon, size = FlashDimensions.iconMd)
                FlashText(
                    text = tab.label,
                    style = FlashTheme.typography.bodyDefault,
                    color = when (isSelected) {
                        true -> FlashTheme.colors.textPrimary
                        false -> FlashTheme.colors.textSecondary
                    },
                )
            }
        }
    }
}

/** One sidebar row: the same tuple the bottom nav's `FlashBottomNavItem` carries. */
public data class DesktopSideTab(
    val destination: FlashDestination,
    val icon: FlashIconSpec,
    val label: String,
)

/** The desktop tab set — one source of truth shared by the sidebar and the bottom nav. */
public val DESKTOP_SIDE_TABS: List<DesktopSideTab> = listOf(
    DesktopSideTab(FlashDestination.ChatList, FlashIcons.Chat, "Chats"),
    DesktopSideTab(FlashDestination.Transfers, FlashIcons.Transfer, "Transfers"),
    DesktopSideTab(FlashDestination.NearbyDevices, FlashIcons.Nearby, "Nearby"),
    DesktopSideTab(FlashDestination.Settings, FlashIcons.Settings, "Settings"),
)

private val SIDEBAR_WIDTH = 200.dp
