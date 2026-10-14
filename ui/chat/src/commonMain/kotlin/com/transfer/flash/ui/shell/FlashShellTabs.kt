package com.transfer.flash.ui.shell

import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath

/**
 * UI-058: the single source of the shell's tab set, shared by the Android bottom bar, the
 * navigation rail and the desktop sidebar so they cannot drift apart or from
 * [FlashNavigationMath.tabOrder]. Not enrolled: Chats, Transfers, Nearby, Settings (unchanged).
 * Enrolled: Chats, Hub, Nearby, Settings.
 */
data class FlashShellTab(
    val destination: FlashDestination,
    val icon: FlashIconSpec,
    val label: String,
    val badgeCount: Int? = null,
)

object FlashShellTabs {

    /**
     * Tabs for the current enrolment, in [FlashNavigationMath.tabOrder] order. Badges are shown
     * only when positive; Hub never carries one until announcements exist.
     */
    fun of(hubEnrolled: Boolean, chatsUnread: Int = 0, hubBadge: Int = 0): List<FlashShellTab> =
        FlashNavigationMath.tabOrder(hubEnrolled).map { destination ->
            when (destination) {
                FlashDestination.ChatList ->
                    FlashShellTab(destination, FlashIcons.Chat, "Chats", chatsUnread.takeIf { it > 0 })
                FlashDestination.Transfers ->
                    FlashShellTab(destination, FlashIcons.Transfer, "Transfers")
                FlashDestination.Hub ->
                    FlashShellTab(destination, FlashIcons.Hub, "Hub", hubBadge.takeIf { it > 0 })
                FlashDestination.NearbyDevices ->
                    FlashShellTab(destination, FlashIcons.Nearby, "Nearby")
                FlashDestination.Settings ->
                    FlashShellTab(destination, FlashIcons.Settings, "Settings")
                FlashDestination.Conversation ->
                    error("Conversation is never a shell tab")
            }
        }
}
