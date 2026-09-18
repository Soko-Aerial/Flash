package com.transfer.flash.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.adaptive.FlashNavigationRail
import com.transfer.flash.ui.adaptive.FlashNavigationRailTab
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination

/**
 * The vertical navigation rail shown at expanded window widths (>= 840dp).
 * Modern WhatsApp & Telegram Desktop layout: compact 68dp icon rail with unread badges.
 */
@Composable
public fun DesktopSideBar(
    tabs: List<DesktopSideTab>,
    selectedTab: FlashDestination,
    onTabSelected: (FlashDestination) -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    localDisplayName: String? = null,
    onProfileClick: (() -> Unit)? = null,
) {
    val railTabs = tabs.map { tab ->
        FlashNavigationRailTab(
            destination = tab.destination,
            icon = tab.icon,
            label = tab.label,
            badgeCount = if (tab.destination == FlashDestination.ChatList) unreadCount else 0,
        )
    }

    FlashNavigationRail(
        tabs = railTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
        localDisplayName = localDisplayName,
        onProfileClick = onProfileClick,
    )
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
