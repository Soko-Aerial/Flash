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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
    fun incomingCallNotificationSent() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        assertEquals("Pairing Request", notifications[0].title)
        assertTrue(notifications[0].message.contains("Pixel 8"))
    }

    @Test
    fun messageNotificationSentWhenWindowIsVisibleAndSameConversationOpenButMinimized() {
        val stateDir = tempFolder.newFolder("desktop_state")
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
        val engine = DesktopEngine(stateDir = stateDir)
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
}
