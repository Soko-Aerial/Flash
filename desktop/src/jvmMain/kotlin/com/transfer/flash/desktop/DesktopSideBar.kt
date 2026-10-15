package com.transfer.flash.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.adaptive.FlashNavigationRail
import com.transfer.flash.ui.adaptive.FlashNavigationRailTab
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.shell.FlashShellTabs

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
    /** Unread announcements, shown on the Hub tab (UI-058). */
    hubBadge: Int = 0,
    localDisplayName: String? = null,
    onProfileClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val railTabs = tabs.map { tab ->
        FlashNavigationRailTab(
            destination = tab.destination,
            icon = tab.icon,
            label = tab.label,
            badgeCount = when (tab.destination) {
                FlashDestination.ChatList -> unreadCount
                FlashDestination.Hub -> hubBadge
                else -> 0
            },
        )
    }

    FlashNavigationRail(
        tabs = railTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
        localDisplayName = localDisplayName,
        onProfileClick = onProfileClick,
        footer = footer,
    )
}

/** One sidebar row: the same tuple the bottom nav's `FlashBottomNavItem` carries. */
public data class DesktopSideTab(
    val destination: FlashDestination,
    val icon: FlashIconSpec,
    val label: String,
)

/**
 * The desktop tab set for the current enrolment (UI-058): built from [FlashShellTabs], the one source
 * shared with the bottom bar and the Android host, so it cannot drift from `FlashNavigationMath.tabOrder`.
 */
public fun desktopSideTabs(hubEnrolled: Boolean): List<DesktopSideTab> =
    FlashShellTabs.of(hubEnrolled).map { DesktopSideTab(it.destination, it.icon, it.label) }

/** The tab set of a device that is not enrolled (what every device had before the Hub tab). */
public val DESKTOP_SIDE_TABS: List<DesktopSideTab> = desktopSideTabs(hubEnrolled = false)
