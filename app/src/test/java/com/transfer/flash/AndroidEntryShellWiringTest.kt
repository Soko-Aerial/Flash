package com.transfer.flash

import com.transfer.flash.debug.TranssionSetupGuide
import com.transfer.flash.notifications.FlashNotificationManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit and host-wiring tests for Phase 3 Android entry, navigation, splash-skip, permissions, and widget. */
class AndroidEntryShellWiringTest {

    private fun repoFile(relativePath: String): String {
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, relativePath).isFile }
        return File(base, relativePath).readText().replace("\r\n", "\n")
    }

    @Test
    fun `launch splash skips for shares, deep links, shortcuts, and notification actions`() {
        val skipCases = listOf(
            LaunchEntryPolicy.shouldSkipLaunchSplash("android.intent.action.SEND", null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("android.intent.action.SEND_MULTIPLE", null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("android.intent.action.VIEW", "flash", false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("com.transfer.flash.action.SHORTCUT_SEND", null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("com.transfer.flash.action.SHORTCUT_NEARBY", null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("com.transfer.flash.action.SHORTCUT_CHATS", null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("android.service.quicksettings.action.QS_TILE_PREFERENCES", null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash(FlashNotificationManager.ACTION_VIEW_NEARBY, null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash(FlashNotificationManager.ACTION_VIEW_TRANSFERS, null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash(FlashNotificationManager.ACTION_OPEN_FILE, null, false, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("android.intent.action.MAIN", null, true, false, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash("android.intent.action.MAIN", null, false, true, false, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash(null, null, false, false, true, false),
            LaunchEntryPolicy.shouldSkipLaunchSplash(null, null, false, false, false, true),
        )
        assertTrue(skipCases.all { it })

        assertFalse(
            LaunchEntryPolicy.shouldSkipLaunchSplash(
                action = "android.intent.action.MAIN",
                dataScheme = null,
                hasAnswerCallExtra = false,
                hasPttPressExtra = false,
                hasConversationExtra = false,
                hasHubRouteExtra = false,
            ),
        )
        assertFalse(
            LaunchEntryPolicy.shouldSkipLaunchSplash(
                action = "android.intent.action.VIEW",
                dataScheme = "https",
                hasAnswerCallExtra = false,
                hasPttPressExtra = false,
                hasConversationExtra = false,
                hasHubRouteExtra = false,
            ),
        )
    }

    @Test
    fun `notification permission banner stays hidden during splash and recovers via rationale or settings`() {
        assertEquals(
            NotificationPermissionUiState.Hidden,
            NotificationPermissionPolicy.resolveUiState(
                sdkInt = 34,
                hasPermission = false,
                splashSettled = false,
                dismissed = false,
                hasPromptedBefore = false,
                shouldShowRationale = false,
                hasActiveCall = false,
                hasActiveShare = false,
                inConversation = false,
            ),
        )
        assertEquals(
            NotificationPermissionUiState.Hidden,
            NotificationPermissionPolicy.resolveUiState(
                sdkInt = 32,
                hasPermission = false,
                splashSettled = true,
                dismissed = false,
                hasPromptedBefore = false,
                shouldShowRationale = false,
                hasActiveCall = false,
                hasActiveShare = false,
                inConversation = false,
            ),
        )
        assertEquals(
            NotificationPermissionUiState.Rationale,
            NotificationPermissionPolicy.resolveUiState(
                sdkInt = 34,
                hasPermission = false,
                splashSettled = true,
                dismissed = false,
                hasPromptedBefore = false,
                shouldShowRationale = false,
                hasActiveCall = false,
                hasActiveShare = false,
                inConversation = false,
            ),
        )
        assertEquals(
            NotificationPermissionUiState.Rationale,
            NotificationPermissionPolicy.resolveUiState(
                sdkInt = 34,
                hasPermission = false,
                splashSettled = true,
                dismissed = false,
                hasPromptedBefore = true,
                shouldShowRationale = true,
                hasActiveCall = false,
                hasActiveShare = false,
                inConversation = false,
            ),
        )
        assertEquals(
            NotificationPermissionUiState.PermanentlyDenied,
            NotificationPermissionPolicy.resolveUiState(
                sdkInt = 34,
                hasPermission = false,
                splashSettled = true,
                dismissed = false,
                hasPromptedBefore = true,
                shouldShowRationale = false,
                hasActiveCall = false,
                hasActiveShare = false,
                inConversation = false,
            ),
        )
        assertEquals(
            NotificationPermissionUiState.Hidden,
            NotificationPermissionPolicy.resolveUiState(
                sdkInt = 34,
                hasPermission = false,
                splashSettled = true,
                dismissed = false,
                hasPromptedBefore = false,
                shouldShowRationale = false,
                hasActiveCall = true,
                hasActiveShare = false,
                inConversation = false,
            ),
        )
    }

    @Test
    fun `call permission planner orders audio before camera and executes when both granted`() {
        assertEquals(CallPermissionAction.REQUEST_AUDIO, CallPermissionActionPlan.decide(hasAudio = false, hasCamera = false))
        assertEquals(CallPermissionAction.REQUEST_AUDIO, CallPermissionActionPlan.decide(hasAudio = false, hasCamera = true))
        assertEquals(CallPermissionAction.REQUEST_CAMERA, CallPermissionActionPlan.decide(hasAudio = true, hasCamera = false))
        assertEquals(CallPermissionAction.EXECUTE_NOW, CallPermissionActionPlan.decide(hasAudio = true, hasCamera = true))
    }

    @Test
    fun `share uri rejection and transsion widget failure produce user-visible messages`() {
        val fileOnly = ShareUriFeedback.rejectedMessage(hasFileUri = true, acceptedCount = 0, hasTextFallback = false)
        assertTrue(fileOnly.contains("Files or Gallery"))

        val mixed = ShareUriFeedback.rejectedMessage(hasFileUri = true, acceptedCount = 2, hasTextFallback = false)
        assertTrue(mixed.contains("skipped"))

        val widgetFail = TranssionTargetFeedback.failureMessage(TranssionSetupGuide.Target.WIDGET_PIN)
        assertTrue(widgetFail.contains("home screen"))
    }

    @Test
    fun `manifest sets singleTop and MainActivity wires Phase 3 entry and two-pane fixes`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))

        val main = repoFile("app/src/main/java/com/transfer/flash/MainActivity.kt")
        assertFalse(main.contains("maybeRequestNotificationPermission("))
        assertFalse(main.contains("nav.navigate(FlashDestination.ChatList)"))
        assertTrue(main.contains("setIntent(intent)"))
        assertTrue(main.contains("allowTwoPane = twoPane"))
        assertTrue(main.contains("destination = FlashDestination.Transfers"))
        assertTrue(main.contains("destination = FlashDestination.NearbyDevices"))
        assertTrue(main.contains("FlashDestination.Settings -> settingsScreenContent()"))
        assertTrue(main.contains("onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) }"))

        val transfersSection = main.substringAfter("val transfersScreenContent:").substringBefore("val nearbyScreenContent:")
        assertTrue(transfersSection.contains("onFindDevices = { nav.selectTab(FlashDestination.NearbyDevices) }"))

        val widgetProvider = repoFile("app/src/main/java/com/transfer/flash/widget/FlashAppWidgetProvider.kt")
        assertTrue(widgetProvider.contains("setOnClickPendingIntent(R.id.widget_container, openAppPendingIntent)"))
        assertTrue(widgetProvider.contains("setOnClickPendingIntent(R.id.widget_action_icon, openSendPendingIntent)"))
        assertTrue(widgetProvider.contains("ACTION_WIDGET_SEND"))
    }

    @Test
    fun `MainActivity wires Phase 4 nearby, transfers, pairing retry, and call dismiss`() {
        val main = repoFile("app/src/main/java/com/transfer/flash/MainActivity.kt")
        assertTrue(main.contains("localAddressLabel = localLanAddresses.firstOrNull()"))
        assertTrue(main.contains("onOpenWifiSettings ="))
        assertTrue(main.contains("onManualConnectSuspend ="))
        assertTrue(main.contains("onPeerClick = { peer -> selectedNearbyPeerId = peer.id }"))
        assertTrue(main.contains("onTrustedPeerClick = { trusted -> selectedNearbyPeerId = trusted.id }"))
        assertTrue(main.contains("onClearCancelled ="))
        assertTrue(main.contains("onItemClick = { item -> selectedTransferId = item.id }"))
        assertTrue(main.contains("onRetryLoad = { engine.start() }"))
        assertTrue(main.contains("dismissedEndedCallId = visibleCall.callId"))
        assertTrue(main.contains("showEndCallConfirm = true"))
    }

    @Test
    fun `MainActivity and widget_flash wire Phase 7 banner stability, back priority, intent guard, widget picker, and single-pane details`() {
        val main = repoFile("app/src/main/java/com/transfer/flash/MainActivity.kt")
        val bannerHost = main.substringAfter("private fun NotificationPermissionBannerHost(")
            .substringBefore("private fun NotificationPermissionBanner(")
        assertFalse(
            "NotificationPermissionBannerHost must not switch content between Box and Column",
            bannerHost.contains("Box(modifier = modifier, content = content)"),
        )
        assertTrue(bannerHost.contains("Column(modifier = modifier)"))

        val twoPaneBackIdx = main.indexOf("BackHandler(enabled = hasTwoPaneDetail)")
        val searchBackIdx = main.indexOf("BackHandler(enabled = isSearching)")
        val selectionBackIdx = main.indexOf("BackHandler(enabled = chatListSelectionMode)")
        assertTrue(twoPaneBackIdx in 0 until searchBackIdx)
        assertTrue(searchBackIdx < selectionBackIdx)
        val searchBackBlock = main.substring(searchBackIdx, selectionBackIdx)
        assertTrue(searchBackBlock.contains("searchScope = FlashSearchScope.All"))

        assertTrue(main.contains("if (savedInstanceState == null) {\n            dispatchIncomingIntent(intent)"))
        assertTrue(main.contains("pendingWidgetSendPicker.value = true"))
        assertTrue(main.contains("widgetSendPickerLauncher.launch(arrayOf(\"*/*\"))"))
        assertTrue(main.contains("isPeerVerified ="))
        assertTrue(main.contains("onVerifyPeerTrust ="))
        assertTrue(main.contains("connectionTransport ="))
        assertTrue(main.contains("onFindDevicesClick = { nav.selectTab(FlashDestination.NearbyDevices) }"))
        assertTrue(main.contains("localDeviceId = engine.localDeviceId.takeIf"))
        assertTrue(main.contains("openPeerChat(peer)"))
        assertTrue(main.contains("transferDetailPaneContent("))
        assertTrue(main.contains("nearbyDetailPaneContent("))
        assertTrue(main.contains("rememberSaveable { mutableStateOf<String?>(null) }"))
        assertTrue(main.contains("val peerIdHint = model.peerDeviceId ?: request.peerDeviceId"))

        val widgetXml = repoFile("app/src/main/res/layout/widget_flash.xml")
        assertTrue(widgetXml.contains("android:layout_width=\"48dp\""))
        assertTrue(widgetXml.contains("android:layout_height=\"48dp\""))
        assertTrue(widgetXml.contains("android:padding=\"12dp\""))
    }
}
