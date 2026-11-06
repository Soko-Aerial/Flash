@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.transfer.flash.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.HubLinkState
import com.transfer.flash.core.messaging.model.*
import com.transfer.flash.ui.hub.*
import com.transfer.flash.ui.adaptive.FlashNavigationRail
import com.transfer.flash.ui.adaptive.FlashNavigationRailTab
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shell.FlashBottomNav
import com.transfer.flash.ui.shell.FlashBottomNavItem
import com.transfer.flash.ui.theme.rememberFlashMotion
import com.transfer.flash.ui.theme.FlashTheme
import java.io.File
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/** Opt-in native render evidence, with sample data only. Set FLASH_HUB_RENDER_DIR to an output directory. */
class FlashHubWorkspaceRenderTest {
    @Test fun renderWorkspaceStates() {
        val output = System.getenv("FLASH_HUB_RENDER_DIR")
        assumeTrue("Set FLASH_HUB_RENDER_DIR to capture the Hub review matrix", output != null)
        val directory = File(output!!).apply { mkdirs() }
        val now = 1_800_000_100_000L
        val announcement = HubAnnouncementUi(
            "review-news", "org", null, "A space to keep everyone connected",
            "Find colleagues, catch up on company updates and join your department’s conversations here in Flash.",
            "Dana Kruger", "admin", now - 3_600_000L, now - 3_600_000L, false, true, null, 1L, false,
        )
        val base = FlashHubScreenData(
            state = HubSampleData.state, nowMs = now, people = HubSampleData.people.map { if (it.isSelf) it.copy(displayName = "Jordan Ellis") else it },
            departments = HubSampleData.departments, groups = HubSampleData.groups, news = listOf(announcement),
        )
        val tabs = listOf(
            FlashNavigationRailTab(FlashDestination.ChatList, FlashIcons.Chat, "Chats"),
            FlashNavigationRailTab(FlashDestination.Hub, FlashIcons.Hub, "Hub"),
            FlashNavigationRailTab(FlashDestination.NearbyDevices, FlashIcons.Nearby, "Nearby"),
            FlashNavigationRailTab(FlashDestination.Settings, FlashIcons.Settings, "Settings"),
        )
        val actions = FlashHubActions(
            onRefresh = {}, onSignOut = {}, onMarkRead = {}, onOpenConversation = {},
            startConversation = { FlashResult.Success(it) },
            postAnnouncement = { FlashResult.Success(announcement) },
            editAnnouncement = { _, _, _ -> FlashResult.Success(announcement) },
            deleteAnnouncement = { FlashResult.Success(Unit) }, pinAnnouncement = { _, _ -> FlashResult.Success(Unit) },
            onOpenLink = {}, groupAction = { FlashResult.Failure(FlashError.Unknown("Preview only")) },
            onOpenGroup = {}, onRetryGroups = {}, onDismissTip = {}, onRouteConsumed = {},
        )
        data class Case(val name: String, val width: Int, val height: Int, val dark: Boolean = false,
            val data: FlashHubScreenData = base, val selection: FlashHubSelection = FlashHubSelection(), val fontScale: Float = 1f)
        val cases = listOf(
            Case("phone-people", 390, 844),
            Case("phone-dark", 390, 844, dark = true),
            Case("phone-admin-news", 390, 844, data = base.copy(state = base.state.copy(myRole = HubPersonRole.ADMIN), posting = HubPostingRights.OrgWide()), selection = FlashHubSelection(segment = FlashHubSegment.NEWS)),
            Case("phone-offline", 390, 844, data = base.copy(link = HubLinkState.OFFLINE, state = base.state.copy(syncStatus = HubSyncStatus.FAILED_OFFLINE))),
            Case("phone-loading", 390, 844, data = base.copy(state = base.state.copy(loaded = false))),
            Case("phone-large-text", 390, 844, fontScale = 2f),
            Case("desktop-departments", 1200, 800, selection = FlashHubSelection(segment = FlashHubSegment.DEPARTMENTS)),
            Case("desktop-person", 1200, 800, data = base.copy(openedPerson = base.people.first()), selection = FlashHubSelection(personId = base.people.first().deviceId)),
            Case("phone-empty", 390, 844, data = base.copy(people = emptyList(), state = base.state.copy(peopleCount = 0))),
            Case("phone-groups-error", 390, 844, data = base.copy(groups = emptyList(), groupsLoadError = "Groups could not be loaded. Try again."), selection = FlashHubSelection(segment = FlashHubSegment.GROUPS)),
        )
        for (case in cases) {
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(case.width, case.height, density = Density(1f, case.fontScale)) {
                    FlashTheme(darkTheme = case.dark, motion = rememberFlashMotion(reduceMotion = true)) {
                        if (case.width >= 840) {
                            Row(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp)) {
                                FlashNavigationRail(tabs, FlashDestination.Hub, {}, localDisplayName = "You")
                                Box(Modifier.weight(1f)) { FlashHubScreen(case.data, case.selection, {}, actions) }
                            }
                        } else {
                            Box(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp)) {
                                FlashHubScreen(case.data, case.selection, {}, actions, Modifier.padding(bottom = 84.dp))
                                FlashBottomNav(
                                    tabs.map { FlashBottomNavItem(it.destination, it.icon, it.label) },
                                    FlashDestination.Hub, {}, Modifier.align(Alignment.BottomCenter),
                                )
                            }
                        }
                    }
                }
            }
            var lastFrame: org.jetbrains.skia.Image? = null
            try {
                repeat(30) { frame ->
                    SwingUtilities.invokeAndWait {
                        if (lastFrame == null || scene.hasInvalidations()) {
                            val next = scene.render(frame * 100_000_000L)
                            lastFrame?.close()
                            lastFrame = next
                        }
                    }
                    Thread.sleep(100)
                }
                lastFrame!!.encodeToData()!!.use { File(directory, "${case.name}.png").writeBytes(it.bytes) }
            } finally {
                lastFrame?.close()
                SwingUtilities.invokeAndWait { scene.close() }
            }
        }
    }
}
