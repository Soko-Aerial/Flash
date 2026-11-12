package com.transfer.flash.desktop

import androidx.compose.ui.input.key.Key
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.security.pairing.FlashTrustedPeer
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.chat.FlashShareItemUi
import com.transfer.flash.ui.chat.FlashSharePayloadUi
import com.transfer.flash.ui.chat.FlashShareRecipientUi
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationState
import com.transfer.flash.ui.transfers.FlashTransferDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.TransfersUiState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopShellBehaviorTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    @Test
    fun `drag and drop only auto-sends when chat pane is visibly open`() {
        assertEquals(
            "peer-1",
            visibleChatTargetForDrop(
                destination = FlashDestination.Conversation,
                navConversationId = "peer-1",
                selectedChatConversationId = "other",
                twoPane = false,
            ),
        )
        assertEquals(
            "peer-2",
            visibleChatTargetForDrop(
                destination = FlashDestination.ChatList,
                navConversationId = null,
                selectedChatConversationId = "peer-2",
                twoPane = true,
            ),
        )
        assertNull(
            visibleChatTargetForDrop(
                destination = FlashDestination.ChatList,
                navConversationId = null,
                selectedChatConversationId = "peer-2",
                twoPane = false,
            ),
        )
        listOf(
            FlashDestination.Settings,
            FlashDestination.Transfers,
            FlashDestination.NearbyDevices,
            FlashDestination.Hub,
        ).forEach { hiddenChatDestination ->
            assertNull(
                visibleChatTargetForDrop(
                    destination = hiddenChatDestination,
                    navConversationId = null,
                    selectedChatConversationId = "peer-2",
                    twoPane = true,
                ),
                "Drop on $hiddenChatDestination must open target sheet instead of silently sending to off-screen chat",
            )
        }
    }

    @Test
    fun `share target delivers directly for groups and trusted peers only`() {
        val trustedIds = setOf("peer-trusted")
        val groupRecipient = FlashShareRecipientUi(
            id = "group-ops",
            name = "Ops",
            initials = "OP",
            isGroup = true,
            isPaired = true,
        )
        val trustedPeer = FlashShareRecipientUi(
            id = "peer-trusted",
            name = "Ada",
            initials = "AD",
            isGroup = false,
            isPaired = true,
        )
        val nearbyUntrusted = FlashShareRecipientUi(
            id = "peer-nearby",
            name = "New Phone",
            initials = "NP",
            isGroup = false,
            isPaired = false,
        )

        assertTrue(canDeliverDesktopShareDirectly(groupRecipient, trustedIds))
        assertTrue(canDeliverDesktopShareDirectly(trustedPeer, trustedIds))
        assertFalse(canDeliverDesktopShareDirectly(nearbyUntrusted, trustedIds))
    }

    @Test
    fun `selected nearby peer resolves smoothly across pairing transition`() {
        val endpoint = FlashDiscoveredEndpoint(
            device = FlashDevice(
                id = FlashDeviceId("peer-1"),
                friendlyName = "Pixel 9",
                transportType = FlashTransportType.LAN,
            ),
            hostAddress = "192.168.1.50",
            port = 45822,
            serviceName = "Flash Pixel 9",
            deviceKind = FlashDeviceKind.PHONE,
        )
        val beforePair = nearbyUiStateOf(
            trusted = emptyList(),
            discovered = listOf(endpoint),
            discoveryState = FlashDiscoveryState(isDiscovering = true, advertisedPort = 45822),
            ui = null,
            ready = true,
            localFriendlyName = "Desktop",
            localDeviceId = "local-desktop-1",
            liveSessions = 0,
        )
        val resolvedBefore = resolveSelectedNearbyPeer(
            peerId = "peer-1",
            nearby = beforePair,
            discovered = listOf(endpoint),
            orgName = "Acme",
            isOrgPeer = { false },
        )
        assertNotNull(resolvedBefore)
        assertFalse(resolvedBefore.isTrusted)
        assertEquals(FlashDeviceKind.PHONE, resolvedBefore.deviceKind)

        val afterPair = nearbyUiStateOf(
            trusted = listOf(FlashTrustedPeer(id = "peer-1", name = "Pixel 9")),
            discovered = listOf(endpoint),
            discoveryState = FlashDiscoveryState(isDiscovering = true, advertisedPort = 45822),
            ui = null,
            ready = true,
            localFriendlyName = "Desktop",
            localDeviceId = "local-desktop-1",
            liveSessions = 0,
            isOrgPeer = { it == "peer-1" },
            orgName = "Acme",
        )
        val resolvedAfter = resolveSelectedNearbyPeer(
            peerId = "peer-1",
            nearby = afterPair,
            discovered = listOf(endpoint),
            orgName = "Acme",
            isOrgPeer = { it == "peer-1" },
        )
        assertNotNull(resolvedAfter)
        assertTrue(resolvedAfter.isTrusted)
        assertTrue(resolvedAfter.isColleague)
        assertEquals("Acme", resolvedAfter.orgName)
        assertEquals(FlashNetworkTransport.Lan, resolvedAfter.transport)
        assertEquals(FlashDeviceKind.PHONE, resolvedAfter.deviceKind)
    }

    @Test
    fun `escape key prioritizes visible pane details over hidden chat selection`() {
        assertEquals(
            DesktopEscapeAction.ClearTransferDetail,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.Transfers,
                hubEnrolled = true,
                stackSize = 2,
                twoPane = true,
                selectedChatConversationId = "hidden-chat",
                hasSelectedTransferItem = true,
                hasSelectedNearbyPeer = false,
            ),
        )
        assertEquals(
            DesktopEscapeAction.PopTransfersBack,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.Transfers,
                hubEnrolled = true,
                stackSize = 2,
                twoPane = false,
                selectedChatConversationId = "hidden-chat",
                hasSelectedTransferItem = false,
                hasSelectedNearbyPeer = false,
            ),
        )
        assertEquals(
            DesktopEscapeAction.ClearNearbyDetail,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.NearbyDevices,
                hubEnrolled = false,
                stackSize = 1,
                twoPane = true,
                selectedChatConversationId = "hidden-chat",
                hasSelectedTransferItem = false,
                hasSelectedNearbyPeer = true,
            ),
        )
        assertEquals(
            DesktopEscapeAction.None,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.Settings,
                hubEnrolled = false,
                stackSize = 1,
                twoPane = true,
                selectedChatConversationId = "hidden-chat",
                hasSelectedTransferItem = false,
                hasSelectedNearbyPeer = false,
            ),
        )
        assertEquals(
            DesktopEscapeAction.CloseConversation,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.ChatList,
                hubEnrolled = false,
                stackSize = 1,
                twoPane = true,
                selectedChatConversationId = "visible-chat",
                hasSelectedTransferItem = false,
                hasSelectedNearbyPeer = false,
            ),
        )
    }

    @Test
    fun `keyboard shortcuts track hub enrollment and expose Transfers`() {
        assertEquals(
            DesktopShortcutAction.SelectTab(FlashDestination.Transfers),
            resolveDesktopShortcut(Key.Two, shiftPressed = false, hubEnrolled = false),
        )
        assertEquals(
            DesktopShortcutAction.SelectTab(FlashDestination.Hub),
            resolveDesktopShortcut(Key.Two, shiftPressed = false, hubEnrolled = true),
        )
        assertEquals(
            DesktopShortcutAction.SelectTab(FlashDestination.NearbyDevices),
            resolveDesktopShortcut(Key.Three, shiftPressed = false, hubEnrolled = true),
        )
        assertEquals(
            DesktopShortcutAction.SelectTab(FlashDestination.Settings),
            resolveDesktopShortcut(Key.Four, shiftPressed = false, hubEnrolled = true),
        )
        assertEquals(
            DesktopShortcutAction.OpenTransfers,
            resolveDesktopShortcut(Key.Five, shiftPressed = false, hubEnrolled = true),
        )
        assertEquals(
            DesktopShortcutAction.OpenTransfers,
            resolveDesktopShortcut(Key.T, shiftPressed = false, hubEnrolled = true),
        )
        assertEquals(
            DesktopShortcutAction.TogglePtt,
            resolveDesktopShortcut(Key.T, shiftPressed = true, hubEnrolled = true),
        )
        assertEquals(
            DesktopShortcutAction.OpenSearch,
            resolveDesktopShortcut(Key.F, shiftPressed = false, hubEnrolled = false),
        )
    }

    @Test
    fun `full width destinations exclude Settings and Hub from list detail split`() {
        assertTrue(isFullWidthDesktopDestination(FlashDestination.Settings))
        assertTrue(isFullWidthDesktopDestination(FlashDestination.Hub))
        assertFalse(isFullWidthDesktopDestination(FlashDestination.ChatList))
        assertFalse(isFullWidthDesktopDestination(FlashDestination.Conversation))
        assertFalse(isFullWidthDesktopDestination(FlashDestination.Transfers))
        assertFalse(isFullWidthDesktopDestination(FlashDestination.NearbyDevices))
    }

    @Test
    fun `launch splash is skipped on explorer file share launch and tray items mirror enrollment`() {
        assertTrue(shouldPlayDesktopLaunchSplash(launchAnimationEnabled = true, hasInitialShareFiles = false))
        assertFalse(shouldPlayDesktopLaunchSplash(launchAnimationEnabled = true, hasInitialShareFiles = true))
        assertFalse(shouldPlayDesktopLaunchSplash(launchAnimationEnabled = false, hasInitialShareFiles = false))

        assertEquals(
            listOf(
                DesktopTrayNavItem(FlashDestination.ChatList, "Chats"),
                DesktopTrayNavItem(FlashDestination.Transfers, "Transfers"),
                DesktopTrayNavItem(FlashDestination.NearbyDevices, "Nearby Devices"),
                DesktopTrayNavItem(FlashDestination.Settings, "Settings"),
            ),
            desktopTrayNavItems(hubEnrolled = false),
        )
        assertEquals(
            listOf(
                DesktopTrayNavItem(FlashDestination.ChatList, "Chats"),
                DesktopTrayNavItem(FlashDestination.Hub, "Hub"),
                DesktopTrayNavItem(FlashDestination.Transfers, "Transfers"),
                DesktopTrayNavItem(FlashDestination.NearbyDevices, "Nearby Devices"),
                DesktopTrayNavItem(FlashDestination.Settings, "Settings"),
            ),
            desktopTrayNavItems(hubEnrolled = true),
        )
        assertEquals("Ada — Settings / Device profile", desktopSidebarProfileDescription("Ada"))
        assertEquals("Settings / Device profile", desktopSidebarProfileDescription(null))
    }

    @Test
    fun `desktop shell sources wire entry focus, rail accessibility, and adaptive shell rules`() {
        val desktopMain = desktopSource("DesktopMain.kt")
        assertTrue(desktopMain.contains("win.toFront()"))
        assertTrue(desktopMain.contains("win.requestFocus()"))
        assertTrue(desktopMain.contains("launchSplash.finish()"))
        assertTrue(desktopMain.contains("desktopTrayNavItems(nav.hubEnrolled)"))
        assertTrue(desktopMain.contains("ultraLowUi = effectivePerformanceMode.ultraLowUi"))
        assertTrue(desktopMain.contains("ultraLowUi = toastPerformance.ultraLowUi"))
        assertTrue(desktopMain.contains("trayNavTick++"))

        val desktopSideBar = desktopSource("DesktopSideBar.kt")
        assertTrue(desktopSideBar.contains("TooltipArea("))
        assertTrue(desktopSideBar.contains("selectableGroup()"))
        assertTrue(desktopSideBar.contains("role = Role.Tab"))
        assertTrue(desktopSideBar.contains("role = Role.Button"))
        assertTrue(desktopSideBar.contains("FlashDimensions.minTouchTarget"))

        val desktopToastWindow = desktopSource("DesktopTransferToastWindow.kt")
        assertTrue(desktopToastWindow.contains("state.position = toastWindowPosition("))
        assertTrue(desktopToastWindow.contains("PointerIcon.Hand"))

        val desktopShell = desktopSource("DesktopShell.kt")
        assertTrue(desktopShell.contains("val useNavRail = sizeClass != FlashWindowSizeClass.Compact"))
        assertTrue(desktopShell.contains("FlashNavigationMath.showsBottomBar(nav.current.destination, nav.hubEnrolled, railMode = useNavRail)"))
        assertTrue(desktopShell.contains("onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) }"))
        assertTrue(desktopShell.contains("EMPTY_FOLDER_SHARE_MESSAGE"))

        val desktopDetailPanes = desktopSource("DesktopDetailPanes.kt")
        assertTrue(desktopDetailPanes.contains("destination = destination"))

        assertTrue(desktopShell.contains("dismissedEndedCallId = it.callId"))
        assertTrue(desktopShell.contains("onClearCancelled ="))
        assertTrue(desktopShell.contains("onItemClick = { item -> selectedTransferId = item.id }"))
        assertTrue(desktopShell.contains("onPeerClick = { peer -> selectedNearbyPeerId = peer.id }"))
        assertTrue(desktopShell.contains("onTrustedPeerClick = { trusted -> selectedNearbyPeerId = trusted.id }"))
        assertTrue(desktopShell.contains("onRetryLoad = { engine.start() }"))
        assertTrue(desktopShell.contains("onRetryPairing = activeNearbyRetryTarget?.let"))
        assertTrue(desktopShell.contains("onRetry = retryTarget?.let"))
        assertTrue(desktopShell.contains("mergeDesktopSharePayload("))
        assertTrue(desktopShell.contains("isPeerVerified = conversationIdIsVerified"))
        assertTrue(desktopShell.contains("onVerifyPeerTrust ="))
        assertTrue(desktopShell.contains("connectionTransport = conversationState.header.transport"))
        assertTrue(desktopShell.contains("onFindDevicesClick = { selectDesktopShellTab(FlashDestination.NearbyDevices) }"))
        assertTrue(desktopShell.contains("localDeviceId = engine.localDeviceId"))
        assertTrue(desktopShell.contains("isVerifying = state == DomainState.Verifying"))
    }

    @Test
    fun `selectDesktopShellTab closes active chat unless restoring a Hub-opened chat in two-pane`() {
        // Case 1: Chat opened from ChatList in two-pane -> clicking Chats closes it instead of trapping it.
        val navFromChats = FlashNavigationState(FlashDestination.ChatList)
        navFromChats.navigate(FlashDestination.Conversation, conversationId = "peer-1")
        var opened: String? = null
        var closedCount = 0
        var clearedCount = 0

        selectDesktopShellTab(
            destination = FlashDestination.ChatList,
            nav = navFromChats,
            twoPane = true,
            selectedChatConversationId = "peer-1",
            onOpenConversation = { opened = it },
            onCloseConversation = { closedCount++ },
            onClearSelections = { clearedCount++ },
        )

        assertEquals(FlashDestination.ChatList, navFromChats.current.destination)
        assertNull(navFromChats.current.conversationId)
        assertNull(opened)
        assertEquals(1, closedCount)
        assertEquals(1, clearedCount)

        // Case 2: Chat opened from Hub in two-pane -> clicking Chats promotes it to Chats tab.
        val navFromHub = FlashNavigationState(FlashDestination.ChatList)
        navFromHub.updateHubEnrolled(true)
        navFromHub.selectTab(FlashDestination.Hub)
        navFromHub.navigate(FlashDestination.Conversation, conversationId = "peer-hub")
        assertTrue(navFromHub.conversationOpenedFromHub)
        var reopenedFromHub: String? = null
        var closedFromHub = 0

        selectDesktopShellTab(
            destination = FlashDestination.ChatList,
            nav = navFromHub,
            twoPane = true,
            selectedChatConversationId = "peer-hub",
            onOpenConversation = { reopenedFromHub = it },
            onCloseConversation = { closedFromHub++ },
            onClearSelections = {},
        )

        assertEquals("peer-hub", reopenedFromHub)
        assertEquals(0, closedFromHub)
        assertEquals(FlashDestination.Conversation, navFromHub.current.destination)
        assertEquals("peer-hub", navFromHub.current.conversationId)
        assertFalse(navFromHub.conversationOpenedFromHub)

        // Case 3: Switching to NearbyDevices clears selections, and switching back to Chats does not reopen old chat.
        var clearedOnTabSwitch = 0
        selectDesktopShellTab(
            destination = FlashDestination.NearbyDevices,
            nav = navFromHub,
            twoPane = true,
            selectedChatConversationId = "peer-hub",
            onOpenConversation = {},
            onCloseConversation = {},
            onClearSelections = { clearedOnTabSwitch++ },
        )
        assertEquals(1, clearedOnTabSwitch)
        assertEquals(FlashDestination.NearbyDevices, navFromHub.current.destination)

        var reopenedAfterSwitch: String? = null
        selectDesktopShellTab(
            destination = FlashDestination.ChatList,
            nav = navFromHub,
            twoPane = true,
            selectedChatConversationId = null,
            onOpenConversation = { reopenedAfterSwitch = it },
            onCloseConversation = {},
            onClearSelections = {},
        )
        assertNull(reopenedAfterSwitch)
        assertEquals(FlashDestination.ChatList, navFromHub.current.destination)

        // Transfers remains reachable via openTransfers() even when hub-enrolled.
        selectDesktopShellTab(
            destination = FlashDestination.Transfers,
            nav = navFromHub,
            twoPane = true,
            selectedChatConversationId = null,
            onOpenConversation = {},
            onCloseConversation = {},
            onClearSelections = {},
        )
        assertEquals(FlashDestination.Transfers, navFromHub.current.destination)
    }

    @Test
    fun `selectedTransferId resolves live from transfersUi and returns null when removed`() {
        val activeItem = FlashTransferItemUi(
            id = "xfer-1",
            fileName = "clip.mp4",
            direction = FlashTransferDirection.Send,
            peerName = "Pixel 9",
            bytesTotal = 10_000L,
            bytesDone = 4_000L,
            state = FlashTransferState.Active,
        )
        val liveState = TransfersUiState(active = listOf(activeItem))
        assertEquals(activeItem, resolveSelectedTransferItem("xfer-1", liveState))

        val updatedItem = activeItem.copy(bytesDone = 10_000L, state = FlashTransferState.Completed, verified = true)
        val completedState = TransfersUiState(history = listOf(updatedItem))
        assertEquals(updatedItem, resolveSelectedTransferItem("xfer-1", completedState))

        // Once dismissed or cleared, resolveSelectedTransferItem returns null instead of a stale snapshot.
        val emptyState = TransfersUiState()
        assertNull(resolveSelectedTransferItem("xfer-1", emptyState))
        assertNull(resolveSelectedTransferItem(null, liveState))
        assertNull(resolveSelectedTransferItem("  ", liveState))
    }

    @Test
    fun `call overlay and pending share gate Ctrl shortcuts and Esc dismisses ended calls and radio gateway`() {
        listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.F, Key.T, Key.Comma).forEach { k ->
            assertNull(
                resolveDesktopShortcut(k, shiftPressed = false, hubEnrolled = true, hasActiveCall = true),
                "Shortcut $k must be gated while call overlay is visible",
            )
            assertNull(
                resolveDesktopShortcut(k, shiftPressed = false, hubEnrolled = true, hasPendingShare = true),
                "Shortcut $k must be gated while pendingDesktopShare is open",
            )
        }
        assertNull(
            resolveDesktopShortcut(Key.T, shiftPressed = true, hubEnrolled = true, hasActiveCall = true),
            "PTT shortcut must be gated while call overlay is visible",
        )

        assertEquals(
            DesktopEscapeAction.DismissEndedCall,
            resolveDesktopEscapeAction(
                pttActive = true,
                isSearching = true,
                destination = FlashDestination.Conversation,
                hubEnrolled = false,
                stackSize = 2,
                twoPane = true,
                selectedChatConversationId = "peer-1",
                hasSelectedTransferItem = true,
                hasSelectedNearbyPeer = true,
                hasEndedCallOverlay = true,
            ),
        )
        assertEquals(
            DesktopEscapeAction.ConsumeInCall,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.Conversation,
                hubEnrolled = false,
                stackSize = 2,
                twoPane = true,
                selectedChatConversationId = "peer-1",
                hasSelectedTransferItem = false,
                hasSelectedNearbyPeer = false,
                hasActiveCallOverlay = true,
            ),
        )
        assertEquals(
            DesktopEscapeAction.CloseRadioGateway,
            resolveDesktopEscapeAction(
                pttActive = false,
                isSearching = false,
                destination = FlashDestination.Settings,
                hubEnrolled = false,
                stackSize = 1,
                twoPane = true,
                selectedChatConversationId = null,
                hasSelectedTransferItem = false,
                hasSelectedNearbyPeer = false,
                showRadioGateway = true,
            ),
        )
    }

    @Test
    fun `saveImageToGallery preserves real media extensions including mp4 instead of jpg`() {
        val tempDir = Files.createTempDirectory("flash-gallery-test").toFile().also { tempDirs += it }
        val targetDir = File(tempDir, "out")
        val sourceVideo = File(tempDir, "sample_clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        DesktopHelpers.saveImageToGallery(
            ref = sourceVideo.toURI().toString(),
            mimeType = "video/mp4",
            targetDir = targetDir,
        )

        val saved = targetDir.listFiles()?.toList().orEmpty()
        assertEquals(1, saved.size)
        assertTrue(saved.single().name.endsWith(".mp4"), "Expected .mp4 but got ${saved.single().name}")

        assertEquals("mp4", DesktopHelpers.resolveGalleryExtension("video/mp4"))
        assertEquals("webm", DesktopHelpers.resolveGalleryExtension("video/webm"))
        assertEquals("mov", DesktopHelpers.resolveGalleryExtension("video/quicktime"))
        assertEquals("m4a", DesktopHelpers.resolveGalleryExtension("audio/mp4"))
        assertEquals("mp3", DesktopHelpers.resolveGalleryExtension("audio/mpeg"))
        assertEquals("png", DesktopHelpers.resolveGalleryExtension("image/png"))
        assertEquals("webp", DesktopHelpers.resolveGalleryExtension("image/webp"))
        assertEquals("gif", DesktopHelpers.resolveGalleryExtension("image/gif"))
        assertEquals("jpg", DesktopHelpers.resolveGalleryExtension("image/jpeg"))
        assertEquals("mp4", DesktopHelpers.resolveGalleryExtension("*/*", sourceFileName = "recording.mp4"))
    }

    @Test
    fun `resolveDesktopPairingRetryTarget resolves peerDeviceId first, falls back to peerName, and returns null when unknown`() {
        val endpointA = FlashDiscoveredEndpoint(
            device = FlashDevice(id = FlashDeviceId("id-a"), friendlyName = "Pixel 9", transportType = FlashTransportType.LAN),
            hostAddress = "192.168.1.10",
            port = 45822,
            serviceName = "Flash A",
        )
        val endpointB = FlashDiscoveredEndpoint(
            device = FlashDevice(id = FlashDeviceId("id-b"), friendlyName = "Pixel 9", transportType = FlashTransportType.WIFI_DIRECT),
            hostAddress = "192.168.1.11",
            port = 45822,
            serviceName = "Flash B",
        )

        // Duplicate friendlyName disambiguated by peerDeviceId
        assertEquals(
            "id-b" to "Pixel 9",
            resolveDesktopPairingRetryTarget(
                peerDeviceId = "id-b",
                peerName = "Pixel 9",
                lastPairingPeer = "id-a" to "Pixel 9",
                discoveredEndpoints = listOf(endpointA, endpointB),
                trustedPeers = emptyList(),
            ),
        )
        // Fallback to peerName when peerDeviceId is null
        assertEquals(
            "id-a" to "Pixel 9",
            resolveDesktopPairingRetryTarget(
                peerDeviceId = null,
                peerName = "Pixel 9",
                lastPairingPeer = null,
                discoveredEndpoints = listOf(endpointA),
                trustedPeers = emptyList(),
            ),
        )
        // Null when neither peerDeviceId nor peerName matches any known peer
        assertNull(
            resolveDesktopPairingRetryTarget(
                peerDeviceId = null,
                peerName = "Unknown Phone",
                lastPairingPeer = "id-a" to "Pixel 9",
                discoveredEndpoints = listOf(endpointA),
                trustedPeers = listOf(FlashTrustedPeer(id = "trusted-1", name = "Work Tablet")),
            ),
        )
    }

    @Test
    fun `mergeDesktopSharePayload appends DnD items without overwriting existing share`() {
        val first = FlashShareItemUi("file:///a.png", "a.png", 10L, "image/png")
        val second = FlashShareItemUi("file:///b.mp4", "b.mp4", 20L, "video/mp4")
        val existing = FlashSharePayloadUi(items = listOf(first), text = "Note")

        val merged = mergeDesktopSharePayload(existing, listOf(first, second))
        assertEquals(listOf(first, second), merged.items)
        assertEquals("Note", merged.text)
    }

    @Test
    fun `desktopTwoPaneAvailableWidthDp subtracts rail and splitter hit width so detail keeps 360dp`() {
        val availableAt840 = desktopTwoPaneAvailableWidthDp(840f)
        assertEquals(840f - 68f - 12f, availableAt840)
        val maxListWidth = FlashAdaptiveMath.clampListPaneWidthDp(1_000f, availableAt840)
        val remainingDetailWidth = availableAt840 - maxListWidth
        assertTrue(
            remainingDetailWidth >= FlashAdaptiveMath.DetailPaneUserMinWidthDp,
            "Detail pane width ($remainingDetailWidth) must stay >= ${FlashAdaptiveMath.DetailPaneUserMinWidthDp}",
        )
    }

    private fun desktopSource(name: String): String {
        val dir = sequenceOf(
            File("src/jvmMain/kotlin/com/transfer/flash/desktop"),
            File("desktop/src/jvmMain/kotlin/com/transfer/flash/desktop"),
        ).first { it.isDirectory }
        return File(dir, name).readText()
    }
}
