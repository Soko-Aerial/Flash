package com.transfer.flash.desktop

import androidx.compose.ui.window.Notification
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopNotificationManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testScope = CoroutineScope(Job() + Dispatchers.Default)

    @Test
    fun messageNotificationSentWhenWindowIsHidden() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        var isWindowVisible = false
        var activeConvId: String? = null

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { isWindowVisible },
            activeConversationId = { activeConvId },
            isNotificationsEnabled = { true },
        )

        manager.handleInboundMessage(
            conversationId = "conv-1",
            senderName = "Alice",
            text = "Hello desktop!",
            groupTitle = null,
        )

        assertEquals(1, notifications.size)
        assertEquals("Alice", notifications[0].title)
        assertEquals("Hello desktop!", notifications[0].message)
    }

    @Test
    fun messageNotificationSuppressedWhenWindowIsVisibleAndSameConversationOpen() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        var isWindowVisible = true
        var activeConvId: String? = "conv-1"

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { isWindowVisible },
            activeConversationId = { activeConvId },
            isNotificationsEnabled = { true },
        )

        manager.handleInboundMessage(
            conversationId = "conv-1",
            senderName = "Alice",
            text = "Hello desktop!",
            groupTitle = null,
        )

        assertEquals(0, notifications.size)
    }

    @Test
    fun messageNotificationSentWhenWindowIsVisibleAndDifferentConversationOpen() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        var isWindowVisible = true
        var activeConvId: String? = "conv-2"

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { isWindowVisible },
            activeConversationId = { activeConvId },
            isNotificationsEnabled = { true },
        )

        manager.handleInboundMessage(
            conversationId = "conv-1",
            senderName = "Alice",
            text = "Hello from conv 1!",
            groupTitle = null,
        )

        assertEquals(1, notifications.size)
        assertEquals("Alice", notifications[0].title)
        assertEquals("Hello from conv 1!", notifications[0].message)
    }

    @Test
    fun messageNotificationSuppressedWhenDisabledInSettings() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { false },
        )

        manager.handleInboundMessage(
            conversationId = "conv-1",
            senderName = "Alice",
            text = "Should not notify",
            groupTitle = null,
        )

        assertEquals(0, notifications.size)
    }

    @Test
    fun groupMessageNotificationFormatsWithGroupTitle() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        manager.handleInboundMessage(
            conversationId = "group-1",
            senderName = "Bob",
            text = "Meeting at 3",
            groupTitle = "Dev Team",
        )

        assertEquals(1, notifications.size)
        assertEquals("Dev Team", notifications[0].title)
        assertEquals("Bob: Meeting at 3", notifications[0].message)
    }

    @Test
    fun transferCompletedNotificationSentOnStateTransition() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val transferId = FlashTransferId("tx-1")
        val initialTransfer = FlashTransfer(
            id = transferId,
            peerName = "Phone",
            direction = FlashTransferDirection.Receiving,
            fileName = "movie.mp4",
            bytesTotal = 1000L,
            bytesDone = 500L,
            state = FlashTransferState.Transferring,
        )

        // Initial state observation
        manager.handleTransfersUpdate(listOf(initialTransfer))
        assertEquals(0, notifications.size)

        // Complete state transition
        val completedTransfer = initialTransfer.copy(
            state = FlashTransferState.Completed,
            bytesDone = 1000L,
        )
        manager.handleTransfersUpdate(listOf(completedTransfer))

        assertEquals(1, notifications.size)
        assertEquals("Transfer Complete", notifications[0].title)
        assertTrue(notifications[0].message.contains("movie.mp4"))
    }

    @Test
    fun receivedAttachmentAndTransferCompletionAreSeparateEvents() {
        val engine = testDesktopEngine(stateDir = tempFolder.newFolder("desktop_state"))
        val notifications = mutableListOf<Notification>()
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )
        val running = FlashTransfer(
            id = FlashTransferId("tx-2"),
            peerName = "Phone",
            direction = FlashTransferDirection.Receiving,
            fileName = "Voice message.m4a",
            bytesTotal = 1000L,
            bytesDone = 500L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(running))
        manager.handleInboundAttachment("conv-1", "Raspberry", "Voice message.m4a", "audio/mp4", "test 1")
        manager.handleTransfersUpdate(listOf(running.copy(state = FlashTransferState.Completed, bytesDone = 1000L)))

        assertEquals(listOf("test 1", "Transfer Complete"), notifications.map { it.title })

        // A different file that nobody announced still reports its completion.
        val other = running.copy(id = FlashTransferId("tx-3"), fileName = "movie.mp4")
        manager.handleTransfersUpdate(listOf(other))
        manager.handleTransfersUpdate(listOf(other.copy(state = FlashTransferState.Completed, bytesDone = 1000L)))
        assertEquals(listOf("test 1", "Transfer Complete", "Transfer Complete"), notifications.map { it.title })
    }

    @Test
    fun incomingCallNotificationSent() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val callUi = FlashCallUiState(
            callId = "call-1",
            peerId = "peer-1",
            peerName = "Alice",
            direction = FlashCallDirection.INCOMING,
            video = false,
            state = FlashCallState.RINGING,
        )

        manager.handleCallUpdate(callUi)

        assertEquals(1, notifications.size)
        assertEquals("Incoming Voice Call", notifications[0].title)
        assertTrue(notifications[0].message.contains("Alice"))
    }

    @Test
    fun pairingRequestNotificationSent() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val pairingUi = FlashPairingCoordinator.PairingUi(
            peerName = "Pixel 8",
            phase = PairingPhase.RequestReceived,
            secondsLeft = 60,
            numericCode = "123456",
        )

        manager.handlePairingUpdate(pairingUi)

        assertEquals(1, notifications.size)
        assertEquals("Pairing request from Pixel 8", notifications[0].title)
        assertTrue(notifications[0].message.contains("compare the verification codes"))
    }

    @Test
    fun messageNotificationSentWhenWindowIsVisibleAndSameConversationOpenButMinimized() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        var backgroundMessageReceived = false

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { true },
            activeConversationId = { "conv-1" },
            isNotificationsEnabled = { true },
            isWindowMinimized = { true },
            isWindowFocused = { false },
        ).apply {
            onBackgroundMessageReceived = { backgroundMessageReceived = true }
        }

        manager.handleInboundMessage(
            conversationId = "conv-1",
            senderName = "Alice",
            text = "Wake up!",
            groupTitle = null,
        )

        assertEquals(1, notifications.size)
        assertEquals("Alice", notifications[0].title)
        assertEquals("Wake up!", notifications[0].message)
        assertTrue("Background message callback must be invoked when minimized", backgroundMessageReceived)
    }

    @Test
    fun messageNotificationSentWhenWindowIsVisibleAndSameConversationOpenButNotFocused() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        var backgroundConvId: String? = null

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { true },
            activeConversationId = { "conv-1" },
            isNotificationsEnabled = { true },
            isWindowMinimized = { false },
            isWindowFocused = { false },
        ).apply {
            onBackgroundMessageReceived = { backgroundConvId = it }
        }

        manager.handleInboundMessage(
            conversationId = "conv-1",
            senderName = "Alice",
            text = "Background ping",
            groupTitle = null,
        )

        assertEquals(1, notifications.size)
        assertEquals("Alice", notifications[0].title)
        assertEquals("Background ping", notifications[0].message)
        assertEquals("conv-1", backgroundConvId)
    }

    @Test
    fun attachmentNotificationSentWhenWindowMinimized() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        var backgroundAttachmentFired = false

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { true },
            activeConversationId = { "conv-1" },
            isNotificationsEnabled = { true },
            isWindowMinimized = { true },
            isWindowFocused = { false },
        ).apply {
            onBackgroundMessageReceived = { backgroundAttachmentFired = true }
        }

        manager.handleInboundAttachment(
            conversationId = "conv-1",
            senderName = "Bob",
            fileName = "notes.pdf",
            mimeType = "application/pdf",
            groupTitle = null,
        )

        assertEquals(1, notifications.size)
        assertEquals("Bob", notifications[0].title)
        assertTrue(notifications[0].message.contains("notes.pdf"))
        assertTrue(backgroundAttachmentFired)
    }

    @Test
    fun transferNotificationsSuppressedWhileWindowForegroundAndActive() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        var windowVisible = true
        var windowMinimized = false
        var windowFocused = true

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { windowVisible },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            isWindowMinimized = { windowMinimized },
            isWindowFocused = { windowFocused },
        )

        val offered = FlashTransfer(
            id = FlashTransferId("tx-offer"),
            peerName = "Phone",
            direction = FlashTransferDirection.Receiving,
            fileName = "photo.jpg",
            bytesTotal = 100L,
            bytesDone = 0L,
            state = FlashTransferState.Offered,
        )
        val running = offered.copy(
            id = FlashTransferId("tx-complete"),
            fileName = "archive.zip",
            state = FlashTransferState.Transferring,
        )
        val runningFail = offered.copy(
            id = FlashTransferId("tx-fail"),
            fileName = "broken.bin",
            state = FlashTransferState.Transferring,
        )

        manager.handleTransfersUpdate(listOf(offered, running, runningFail))
        manager.handleTransfersUpdate(
            listOf(
                offered,
                running.copy(state = FlashTransferState.Completed, bytesDone = 100L),
                runningFail.copy(state = FlashTransferState.Failed, errorMessage = "Reset"),
            ),
        )
        assertEquals(0, notifications.size)

        // State tracking must keep advancing while suppressed so losing focus later notifies cleanly on the next event.
        windowFocused = false
        val nextTransfer = offered.copy(
            id = FlashTransferId("tx-next"),
            fileName = "video.mp4",
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(nextTransfer))
        manager.handleTransfersUpdate(listOf(nextTransfer.copy(state = FlashTransferState.Completed, bytesDone = 100L)))

        assertEquals(1, notifications.size)
        assertEquals("Transfer Complete", notifications[0].title)
        assertTrue(notifications[0].message.contains("video.mp4"))
    }

    @Test
    fun callAndPairingNotificationsSuppressedWhileWindowForegroundAndActive() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        var windowMinimized = false

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { true },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            isWindowMinimized = { windowMinimized },
            isWindowFocused = { true },
        )

        val ringingCall = FlashCallUiState(
            callId = "call-1",
            peerId = "peer-1",
            peerName = "Alice",
            direction = FlashCallDirection.INCOMING,
            video = false,
            state = FlashCallState.RINGING,
        )
        val pairingUi = FlashPairingCoordinator.PairingUi(
            peerName = "Pixel 8",
            phase = PairingPhase.RequestReceived,
            secondsLeft = 60,
            numericCode = "123456",
        )

        manager.handleCallUpdate(ringingCall)
        manager.handlePairingUpdate(pairingUi)
        assertEquals(0, notifications.size)

        // Reset phases, minimize the window, and ensure subsequent alerts fire normally.
        manager.handleCallUpdate(null)
        manager.handlePairingUpdate(null)
        windowMinimized = true

        manager.handleCallUpdate(ringingCall.copy(callId = "call-2"))
        manager.handlePairingUpdate(pairingUi)

        assertEquals(2, notifications.size)
        assertEquals("Incoming Voice Call", notifications[0].title)
        assertEquals("Pairing request from Pixel 8", notifications[1].title)
    }

    @Test
    fun transferOfferToastHasDeclineAndAcceptActions() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val offer = FlashTransfer(
            id = FlashTransferId("tx-offer-1"),
            peerName = "Alice Phone",
            direction = FlashTransferDirection.Receiving,
            fileName = "document.pdf",
            bytesTotal = 2048L,
            bytesDone = 0L,
            state = FlashTransferState.Offered,
        )

        manager.handleTransfersUpdate(listOf(offer))

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToastKind.TransferOffer, card.kind)
        assertEquals("Incoming file from Alice Phone", card.title)
        assertEquals("document.pdf", card.detail)
        assertEquals(listOf(ToastAction.Decline, ToastAction.Accept), card.actions)
        assertNull("Offer cards stay until answered", card.autoDismissAtMs)
    }

    @Test
    fun transferCompletedToastHasShowInFolderAndOpenFileWith8sAutoDismiss() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val transfer = FlashTransfer(
            id = FlashTransferId("tx-comp-1"),
            peerName = "Alice",
            direction = FlashTransferDirection.Receiving,
            fileName = "photo.jpg",
            bytesTotal = 1024L,
            bytesDone = 512L,
            state = FlashTransferState.Transferring,
            localPath = tempFolder.newFile("photo.jpg").absolutePath,
        )

        manager.handleTransfersUpdate(listOf(transfer))
        assertEquals(0, manager.toastCards.value.size)

        val completed = transfer.copy(state = FlashTransferState.Completed, bytesDone = 1024L)
        manager.handleTransfersUpdate(listOf(completed))

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToastKind.TransferCompleted, card.kind)
        assertEquals("Transfer Complete", card.title)
        assertEquals("photo.jpg transferred successfully", card.detail)
        assertEquals(listOf(ToastAction.ShowInFolder, ToastAction.OpenFile), card.actions)
        assertNotNull(card.autoDismissAtMs)
        assertTrue(card.autoDismissAtMs!! > System.currentTimeMillis())
        assertEquals(transfer.localPath, card.localPath)
    }

    @Test
    fun transferFailedToastHasDismissAndRetryWith15sAutoDismiss() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val transfer = FlashTransfer(
            id = FlashTransferId("tx-fail-1"),
            peerName = "Bob",
            direction = FlashTransferDirection.Sending,
            fileName = "data.zip",
            bytesTotal = 1024L,
            bytesDone = 200L,
            state = FlashTransferState.Transferring,
        )

        manager.handleTransfersUpdate(listOf(transfer))
        assertEquals(0, manager.toastCards.value.size)

        val failed = transfer.copy(
            state = FlashTransferState.Failed,
            errorMessage = "Peer disconnected",
        )
        manager.handleTransfersUpdate(listOf(failed))

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToastKind.TransferFailed, card.kind)
        assertEquals("Could not send data.zip", card.title)
        assertEquals(listOf(ToastAction.Dismiss, ToastAction.Retry), card.actions)
        assertNotNull(card.autoDismissAtMs)
        assertTrue(card.autoDismissAtMs!! > System.currentTimeMillis() + 10_000L)
    }

    @Test
    fun chatMessageToastHasDismissAndOpenChatWith8sAutoDismiss() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        manager.handleInboundMessage(
            conversationId = "conv-alice",
            senderName = "Alice",
            text = "Hey there!",
            groupTitle = null,
        )

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToastKind.ChatMessage, card.kind)
        assertEquals("Alice", card.title)
        assertEquals("Hey there!", card.detail)
        assertEquals(listOf(ToastAction.Dismiss, ToastAction.OpenChat), card.actions)
        assertNotNull(card.autoDismissAtMs)
        assertEquals("conv-alice", card.conversationId)
    }

    @Test
    fun incomingCallToastHasDeclineAndAcceptWithCallBadgeAndNoAutoDismiss() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val callUi = FlashCallUiState(
            callId = "call-101",
            peerId = "peer-bob",
            peerName = "Bob Smith",
            direction = FlashCallDirection.INCOMING,
            video = true,
            state = FlashCallState.RINGING,
        )

        manager.handleCallUpdate(callUi)

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToastKind.IncomingCall, card.kind)
        assertEquals("Bob Smith", card.title)
        assertEquals("Incoming Video Call", card.detail)
        assertEquals(listOf(ToastAction.Decline, ToastAction.Accept), card.actions)
        assertTrue(card.isVideoCall)
        assertEquals("Video", card.transportLabel)
        assertNull("Ringing call card stays until answered or ended", card.autoDismissAtMs)

        // When call ends, toast is automatically dismissed
        manager.handleCallUpdate(callUi.copy(state = FlashCallState.ENDED))
        assertEquals(0, manager.toastCards.value.size)
    }

    @Test
    fun pairingRequestToastOpensCodeComparisonWithoutExposingCode() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val pairingUi = FlashPairingCoordinator.PairingUi(
            peerName = "Pixel 9",
            phase = PairingPhase.RequestReceived,
            secondsLeft = 60,
            numericCode = "789012",
        )

        manager.handlePairingUpdate(pairingUi)

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ToastKind.PairingRequest, card.kind)
        assertEquals("Pairing request from Pixel 9", card.title)
        assertTrue(card.detail.contains("compare the verification codes"))
        assertFalse(card.detail.contains("789012"))
        assertEquals(listOf(ToastAction.ReviewPairing), card.actions)
        assertNull("Pairing toast stays until confirmed or declined", card.autoDismissAtMs)

        // When pairing is confirmed/declined, toast is removed
        manager.handlePairingUpdate(pairingUi.copy(phase = PairingPhase.Confirmed))
        assertEquals(0, manager.toastCards.value.size)
    }

    @Test
    fun verifyingTransferShowsIndeterminateIntegrityState() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        val transfer = FlashTransfer(
            id = FlashTransferId("tx-verify-1"),
            peerName = "Phone",
            direction = FlashTransferDirection.Receiving,
            fileName = "large_iso.img",
            bytesTotal = 100_000L,
            bytesDone = 100_000L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(transfer))

        val verifying = transfer.copy(state = FlashTransferState.Verifying)
        manager.handleTransfersUpdate(listOf(verifying))

        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        val card = cards.single()
        assertTrue(card.isVerifying)
        assertEquals(transfer.fileName, card.title)
        assertEquals("Checking file integrity…", card.detail)
    }

    @Test
    fun windowFocusSuppressionLeakResolvedWhenWindowUnfocusedLater() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        var windowFocused = true

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { true },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            isWindowMinimized = { false },
            isWindowFocused = { windowFocused },
        )

        val running = FlashTransfer(
            id = FlashTransferId("tx-focus-test"),
            peerName = "Desktop Peer",
            direction = FlashTransferDirection.Receiving,
            fileName = "report.docx",
            bytesTotal = 5000L,
            bytesDone = 2500L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(running))
        assertEquals(0, manager.toastCards.value.size)

        // Completes while window is focused (in foreground)
        val completed = running.copy(state = FlashTransferState.Completed, bytesDone = 5000L)
        manager.handleTransfersUpdate(listOf(completed))

        // Suppressed while focused; terminal state must NOT be recorded in previousTransferStates
        assertEquals(0, manager.toastCards.value.size)

        // Window loses focus
        windowFocused = false
        manager.onWindowFocusChanged(false)

        // Now the completion notice should fire because suppression has lifted
        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        assertEquals("Transfer Complete", cards[0].title)
        assertTrue(cards[0].detail.contains("report.docx"))
    }

    @Test
    fun osBalloonsRetiredWhenSendNotificationOmitted() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        // No sendNotification lambda passed
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )

        manager.handleInboundMessage(
            conversationId = "conv-retire",
            senderName = "Charlie",
            text = "Testing retirement of OS balloons",
            groupTitle = null,
        )

        assertEquals(1, manager.toastCards.value.size)
        assertEquals("Charlie", manager.toastCards.value[0].title)
    }

    @Test
    fun staleDeferredTransferDoesNotReplayWhenWindowUnfocusedLater() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        var windowFocused = true
        var currentTime = 1_000_000L

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { true },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            isWindowMinimized = { false },
            isWindowFocused = { windowFocused },
            currentTimeMillis = { currentTime },
        )

        val running = FlashTransfer(
            id = FlashTransferId("tx-stale-test"),
            peerName = "Desktop Peer",
            direction = FlashTransferDirection.Receiving,
            fileName = "archive.tar.gz",
            bytesTotal = 10_000L,
            bytesDone = 5_000L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(running))
        assertEquals(0, manager.toastCards.value.size)

        // Completes while window is focused (suppressed in foreground)
        val completed = running.copy(state = FlashTransferState.Completed, bytesDone = 10_000L)
        manager.handleTransfersUpdate(listOf(completed))
        assertEquals(0, manager.toastCards.value.size)

        // Simulate 2 hours passing (7,200,000 ms) while user works in foreground
        currentTime += 7_200_000L

        // Window loses focus long after transfer completed
        windowFocused = false
        manager.onWindowFocusChanged(false)

        // Stale completion must NOT replay to the user
        assertEquals(0, manager.toastCards.value.size)
    }

    @Test
    fun recentDeferredTransferReplaysWhenWindowUnfocusedWithinFreshnessWindow() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        var windowFocused = true
        var currentTime = 1_000_000L

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            isWindowVisible = { true },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            isWindowMinimized = { false },
            isWindowFocused = { windowFocused },
            currentTimeMillis = { currentTime },
        )

        val running = FlashTransfer(
            id = FlashTransferId("tx-fresh-test"),
            peerName = "Desktop Peer",
            direction = FlashTransferDirection.Receiving,
            fileName = "fresh_file.zip",
            bytesTotal = 10_000L,
            bytesDone = 5_000L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(running))
        assertEquals(0, manager.toastCards.value.size)

        // Completes while window is focused (suppressed in foreground)
        val completed = running.copy(state = FlashTransferState.Completed, bytesDone = 10_000L)
        manager.handleTransfersUpdate(listOf(completed))
        assertEquals(0, manager.toastCards.value.size)

        // Advance 10 seconds (well within the 60s freshness window)
        currentTime += 10_000L

        // Window loses focus shortly after completion
        windowFocused = false
        manager.onWindowFocusChanged(false)

        // Fresh completion replays cleanly
        val cards = manager.toastCards.value
        assertEquals(1, cards.size)
        assertEquals("Transfer Complete", cards[0].title)
        assertTrue(cards[0].detail.contains("fresh_file.zip"))
    }

    @Test
    fun attachmentFileNamesDoNotSuppressTransferCompletion() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        var currentTime = 1_000_000L

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            currentTimeMillis = { currentTime },
        )

        // Attachment 1 arrives at T = 1,000,000
        manager.handleInboundAttachment(
            conversationId = "conv-1",
            senderName = "Alice",
            fileName = "old_attachment.pdf",
            mimeType = "application/pdf",
            groupTitle = null,
        )
        assertEquals(1, notifications.size)

        // A second attachment notice arrives before the transfers finish.
        currentTime += 65_000L

        // Attachment 2 arrives at T = 1,065,000.
        manager.handleInboundAttachment(
            conversationId = "conv-1",
            senderName = "Bob",
            fileName = "new_attachment.pdf",
            mimeType = "application/pdf",
            groupTitle = null,
        )
        assertEquals(2, notifications.size)

        // Now suppose old_attachment.pdf completes as a transfer at T = 1,065,500
        currentTime += 500L
        val oldTransfer = FlashTransfer(
            id = FlashTransferId("tx-old-att"),
            peerName = "Alice",
            direction = FlashTransferDirection.Receiving,
            fileName = "old_attachment.pdf",
            bytesTotal = 1000L,
            bytesDone = 500L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(oldTransfer))
        manager.handleTransfersUpdate(listOf(oldTransfer.copy(state = FlashTransferState.Completed, bytesDone = 1000L)))

        // A completion is a separate event from a chat attachment notice.
        assertEquals(3, notifications.size)
        assertEquals("Transfer Complete", notifications[2].title)

        // A recent attachment with the same filename must not suppress a completion.
        val newTransfer = FlashTransfer(
            id = FlashTransferId("tx-new-att"),
            peerName = "Bob",
            direction = FlashTransferDirection.Receiving,
            fileName = "new_attachment.pdf",
            bytesTotal = 1000L,
            bytesDone = 500L,
            state = FlashTransferState.Transferring,
        )
        manager.handleTransfersUpdate(listOf(newTransfer))
        manager.handleTransfersUpdate(listOf(newTransfer.copy(state = FlashTransferState.Completed, bytesDone = 1000L)))

        // The second completion also produces a notification.
        assertEquals(4, notifications.size)
    }

    @Test
    fun announcementNotificationDispatchesTitleOnly() {
        val stateDir = tempFolder.newFolder("desktop_state_ann")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        val openedAnnouncementId = CompletableDeferred<String>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
        )
        manager.onOpenAnnouncement = { openedAnnouncementId.complete(it) }

        manager.handleAnnouncement(
            announcementId = "ann-101",
            title = "Company Town Hall at 3 PM",
            departmentName = null,
        )

        assertEquals(1, notifications.size)
        assertEquals("New announcement", notifications[0].title)
        assertEquals("Company Town Hall at 3 PM", notifications[0].message)
        val toast = manager.toastCards.value.single()
        assertEquals("Company Town Hall at 3 PM", toast.detail)
        assertFalse(openedAnnouncementId.isCompleted)
        manager.handleToastAction(ToastAction.OpenAnnouncement, toast)
        assertEquals("ann-101", runBlocking { withTimeout(5_000) { openedAnnouncementId.await() } })

        // Department-scoped announcement
        manager.handleAnnouncement(
            announcementId = "ann-102",
            title = "Deployment Frozen for Holiday",
            departmentName = "Infrastructure",
        )

        assertEquals(2, notifications.size)
        assertEquals("New announcement · Infrastructure", notifications[1].title)
        assertEquals("Deployment Frozen for Holiday", notifications[1].message)
    }

    @Test
    fun announcementNotificationSuppressedWhenNotificationsDisabled() {
        val stateDir = tempFolder.newFolder("desktop_state_ann_suppressed")
        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()

        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { false },
        )

        manager.handleAnnouncement(
            announcementId = "ann-103",
            title = "Ignored Announcement",
            departmentName = null,
        )

        assertEquals(0, notifications.size)
    }

    @Test
    fun announcementNotificationSuppressedWhenAnnouncementSettingMutedAndPersisted() {
        val stateDir = tempFolder.newFolder("desktop_state_ann_muted")
        val store = DesktopSettingsStore(stateDir)
        assertTrue(store.loadSettings().announcementNotifications)
        store.saveSettings(store.loadSettings().copy(announcementNotifications = false))
        assertEquals(false, store.loadSettings().announcementNotifications)

        val engine = testDesktopEngine(stateDir = stateDir)
        val notifications = mutableListOf<Notification>()
        val manager = DesktopNotificationManager(
            engine = engine,
            scope = testScope,
            sendNotification = { notifications.add(it) },
            isWindowVisible = { false },
            activeConversationId = { null },
            isNotificationsEnabled = { true },
            isAnnouncementNotificationsEnabled = { false },
        )

        manager.handleAnnouncement(
            announcementId = "ann-104",
            title = "Muted Announcement",
            departmentName = "Sales",
        )
        assertEquals(0, notifications.size)
    }
}
