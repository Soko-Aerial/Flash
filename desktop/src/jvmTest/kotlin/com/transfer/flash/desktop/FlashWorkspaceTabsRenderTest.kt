@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.transfer.flash.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
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
import com.transfer.flash.ui.nearby.*
import com.transfer.flash.ui.transfers.*
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.chat.FlashPairingRequestUi
import com.transfer.flash.core.transfer.model.FlashTransferWaitReason
import androidx.compose.runtime.Composable
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

/** Opt-in sample-only render matrix. Does not open a database or use the network. */
class FlashWorkspaceTabsRenderTest {
    @Test fun renderTabs() {
        val output = System.getenv("FLASH_TABS_RENDER_DIR")
        assumeTrue("Set FLASH_TABS_RENDER_DIR for the native review matrix", output != null)
        val directory = File(output!!).apply { mkdirs() }
        val nearby = NearbyUiState(
            identity = NearbyIdentityUi("Jordan’s laptop", "a1b2c3d4", 4747),
            peers = listOf(NearbyPeerUi("new", "Design studio", FlashNetworkTransport.Lan, deviceKind = FlashDeviceKind.DESKTOP)),
            trustedPeers = listOf(
                NearbyTrustedPeerUi("a", "Amara’s phone", FlashDeviceKind.PHONE),
                NearbyTrustedPeerUi("b", "Finance team laptop", FlashDeviceKind.DESKTOP),
                NearbyTrustedPeerUi("c", "Studio archive", verified = false),
                NearbyTrustedPeerUi("d", "Research and development shared workstation"),
            ),
        )
        val file = FlashTransferItemUi("active", "Brand guidelines — November.pdf", FlashTransferDirection.Send,
            "Amara Okafor", 50_000_000L, 21_000_000L, FlashTransferState.Active,
            speedBytesPerSec = 2_500_000, etaSeconds = 12, transportLabel = "LAN")
        val transfers = TransfersUiState.fromItems(listOf(
            file,
            file.copy(id = "paused", fileName = "Product photography.zip", state = FlashTransferState.Paused, speedBytesPerSec = 0),
            file.copy(id = "fail", fileName = "Quarterly planning.xlsx", state = FlashTransferState.Failed,
                errorMessage = "Connection interrupted. Reconnect to the same network and try again."),
            file.copy(id = "done", fileName = "Meeting notes.pdf", state = FlashTransferState.Completed,
                bytesDone = file.bytesTotal, verified = true),
        ))
        val tabs = listOf(
            FlashNavigationRailTab(FlashDestination.ChatList, FlashIcons.Chat, "Chats"),
            FlashNavigationRailTab(FlashDestination.Transfers, FlashIcons.Transfer, "Transfers"),
            FlashNavigationRailTab(FlashDestination.NearbyDevices, FlashIcons.Nearby, "Nearby"),
            FlashNavigationRailTab(FlashDestination.Settings, FlashIcons.Settings, "Settings"),
        )
        data class Case(val name: String, val nearbyState: NearbyUiState? = null,
            val transferState: TransfersUiState = transfers, val width: Int = 390, val height: Int = 844,
            val dark: Boolean = false, val fontScale: Float = 1f, val optionalActions: Boolean = true, val frames: Int = 30)
        val request = FlashPairingRequestUi("Amara’s phone", "AO", "123456", FlashNetworkTransport.Lan)
        val cases = listOf(
            Case("nearby-phone", nearby), Case("nearby-dark", nearby, dark = true),
            Case("nearby-desktop", nearby, width = 1200, height = 800),
            Case("nearby-large-text", nearby, fontScale = 2f),
            Case("nearby-empty", NearbyUiState()), Case("nearby-loading", NearbyUiState(isLoading = true)),
            Case("nearby-network-off", NearbyUiState(radiosAvailable = false)),
            Case("transfers-phone"), Case("transfers-dark", dark = true),
            Case("transfers-desktop", width = 1200, height = 800),
            Case("transfers-large-text", fontScale = 2f),
            Case("transfers-incoming", transferState = TransfersUiState(offers = listOf(file.copy(state = FlashTransferState.Offered, direction = FlashTransferDirection.Receive)))),
            Case("transfers-empty", transferState = TransfersUiState()),
            Case("transfers-loading", transferState = TransfersUiState(isLoading = true)),
            Case("transfers-error", transferState = TransfersUiState(isError = true)),
            Case("transfers-history", transferState = transfers.copy(active = emptyList())),
        ) + listOf(
            Case("nearby-menu", nearby),
            Case("nearby-optional-actions-absent", nearby, optionalActions = false),
            Case("nearby-paused", nearby.copy(isScanning = false)),
            Case("nearby-empty-paused", NearbyUiState(isScanning = false)),
            Case("nearby-scan-running", nearby.copy(scan = NearbyNetworkScan.Running(64))),
            Case("nearby-scan-done", nearby.copy(scan = NearbyNetworkScan.Done(3, true))),
            Case("nearby-peers", nearby.copy(trustedPeers = emptyList(), peers = listOf(
                NearbyPeerUi("p", "Amara’s phone", FlashNetworkTransport.Lan, isTrusted = true),
                NearbyPeerUi("c", "Research and development department workstation", FlashNetworkTransport.WifiDirect,
                    isColleague = true, orgName = "Acme organization"),
                NearbyPeerUi("u", "New device", FlashNetworkTransport.Lan),
            ))),
            Case("nearby-narrow", nearby, width = 320),
            Case("nearby-short-large-text", NearbyUiState(), width = 600, height = 400, fontScale = 2f),
            Case("nearby-quiet-hint", nearby.copy(discoveryQuiet = true), frames = 340),
            Case("transfers-narrow", width = 320),
            Case("transfers-cancelled", transferState = TransfersUiState(failed = listOf(file.copy(state = FlashTransferState.Failed,
                retryable = false, errorMessage = "Cancelled by you")))),
            Case("transfers-zero-byte", transferState = TransfersUiState(history = listOf(file.copy(state = FlashTransferState.Completed,
                bytesTotal = 0, bytesDone = 0, verified = false)))),
            Case("transfers-loading-with-data", transferState = transfers.copy(isLoading = true)),
            Case("transfers-group-safe", transferState = TransfersUiState(active = listOf(file.copy(canGoOffline = true, isGroupFile = true)))),
            Case("transfers-multiple-holders", transferState = TransfersUiState(active = listOf(file.copy(direction = FlashTransferDirection.Receive, holdersOnline = 3)))),
        ) + NearbyScanBlock.entries.map { reason ->
            Case("nearby-scan-${reason.name.lowercase()}", nearby.copy(scan = NearbyNetworkScan.Unavailable(reason)))
        } + FlashTransferWaitReason.entries.map { reason ->
            Case("transfers-wait-${reason.name.lowercase()}", transferState = TransfersUiState(active = listOf(
                file.copy(state = FlashTransferState.Queued, waitReason = reason, direction = FlashTransferDirection.Receive))))
        } + FlashPairingPhase.entries.filter { it != FlashPairingPhase.Idle }.map { phase ->
            Case("nearby-pair-${phase.name.lowercase()}", nearby.copy(pairingRequest = request, pairingPhase = phase, pairingSecondsLeft = 18))
        }
        val prefix = System.getenv("FLASH_TABS_RENDER_PREFIX")
        for (case in cases.filter { prefix == null || it.name.startsWith(prefix) }) {
            val destination = if (case.nearbyState != null) FlashDestination.NearbyDevices else FlashDestination.Transfers
            lateinit var scene: ImageComposeScene
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(case.width, case.height, density = Density(1f, case.fontScale)) {
                    FlashTheme(darkTheme = case.dark, motion = rememberFlashMotion(reduceMotion = true)) {
                        val content: @Composable () -> Unit = {
                            if (case.nearbyState != null) {
                                FlashNearbyScreen(case.nearbyState, {}, {}, {}, {},
                                    onVerifyTrustedClick = if (case.optionalActions) ({}) else null,
                                    onManualConnect = if (case.optionalActions) ({ _, _ -> }) else null,
                                    onScanNetwork = if (case.optionalActions) ({}) else null)
                            } else {
                                FlashTransfersScreen(case.transferState, {}, {}, {}, {},
                                    onFindDevices = {}, onAcceptOffer = {}, onDeclineOffer = {}, onHistoryShare = {},
                                    onClearHistory = if (case.optionalActions) ({}) else null,
                                    onDismissItem = if (case.optionalActions) ({}) else null,
                                    onClearFailed = if (case.optionalActions) ({}) else null)
                            }
                        }
                        if (case.width >= 840) {
                            Row(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp)) {
                                FlashNavigationRail(tabs, destination, {}, localDisplayName = "Jordan")
                                Box(Modifier.weight(1f)) { content() }
                            }
                        } else {
                            Box(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp)) {
                                Box(Modifier.fillMaxSize().padding(bottom = 84.dp)) { content() }
                                FlashBottomNav(tabs.map { FlashBottomNavItem(it.destination, it.icon, it.label) },
                                    destination, {}, Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
            }
            var lastFrame: org.jetbrains.skia.Image? = null
            try {
                repeat(case.frames) { frame ->
                    SwingUtilities.invokeAndWait {
                        if (case.name == "nearby-menu" && frame == 12) {
                            // More actions on the unverified tile in the 390 dp fixture.
                            val anchor = Offset(346f, 481f)
                            scene.sendPointerEvent(PointerEventType.Press, anchor, type = PointerType.Touch)
                            scene.sendPointerEvent(PointerEventType.Release, anchor, type = PointerType.Touch)
                        }
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
