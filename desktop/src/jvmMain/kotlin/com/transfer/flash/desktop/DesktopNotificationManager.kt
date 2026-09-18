@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.ui.window.Notification
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Desktop notification manager handling native OS notifications.
 *
 * Dispatches notifications for:
 * - Inbound messages and attachments (with foreground-window and open-conversation suppression).
 * - Completed and failed file transfers.
 * - Inbound voice/video call invitations.
 * - Inbound device pairing requests.
 */
public class DesktopNotificationManager(
    private val engine: DesktopEngine,
    private val scope: CoroutineScope,
    private val sendNotification: (Notification) -> Unit,
    private val isWindowVisible: () -> Boolean,
    private val activeConversationId: () -> String?,
    private val isNotificationsEnabled: () -> Boolean = { engine.settings.value.showNotifications },
) {
    private var started = false
    private var transferJob: Job? = null
    private var callsJob: Job? = null
    private var pairingJob: Job? = null

    // Track transfer states by ID to fire transitions only once
    private val previousTransferStates = ConcurrentHashMap<FlashTransferId, FlashTransferState>()

    // Track pairing phase to fire notification only on transition into RequestReceived
    private var previousPairingPhase: PairingPhase? = null

    // Track call phase to fire notification only on transition into RINGING
    private var previousCallPhase: FlashCallState? = null

    @Synchronized
    public fun start() {
        if (started) return
        started = true

        // Wire chat and attachment notification callbacks from engine
        engine.onInboundMessageNotification = { conversationId, senderName, text, groupTitle ->
            handleInboundMessage(conversationId, senderName, text, groupTitle)
        }
        engine.onInboundAttachmentNotification = { conversationId, senderName, fileName, mimeType, groupTitle ->
            handleInboundAttachment(conversationId, senderName, fileName, mimeType, groupTitle)
        }

        // Observe transfers when ready
        transferJob = scope.launch {
            engine.ready.collectLatest { ready ->
                if (ready) {
                    engine.transfers?.activeTransfers?.collect { transfers ->
                        handleTransfersUpdate(transfers)
                    }
                }
            }
        }

        // Observe calls when ready
        callsJob = scope.launch {
            engine.ready.collectLatest { ready ->
                if (ready) {
                    engine.calls?.activeCall?.collect { callUi ->
                        handleCallUpdate(callUi)
                    }
                }
            }
        }

        // Observe pairing coordinator
        pairingJob = scope.launch {
            engine.pairing.pairing.collect { pairingUi ->
                handlePairingUpdate(pairingUi)
            }
        }
    }

    @Synchronized
    public fun stop() {
        if (!started) return
        started = false
        engine.onInboundMessageNotification = null
        engine.onInboundAttachmentNotification = null
        transferJob?.cancel()
        transferJob = null
        callsJob?.cancel()
        callsJob = null
        pairingJob?.cancel()
        pairingJob = null
        previousTransferStates.clear()
        previousPairingPhase = null
        previousCallPhase = null
    }

    public fun handleInboundMessage(
        conversationId: String,
        senderName: String?,
        text: String,
        groupTitle: String?,
    ) {
        if (!isNotificationsEnabled()) return
        if (isWindowVisible() && activeConversationId() == conversationId) return

        val title = groupTitle ?: (senderName?.ifBlank { null } ?: "Flash Message")
        val body = if (groupTitle != null) {
            "${senderName?.ifBlank { null } ?: "Member"}: $text"
        } else {
            text
        }

        dispatchNotification(title, body, Notification.Type.Info)
    }

    public fun handleInboundAttachment(
        conversationId: String,
        senderName: String?,
        fileName: String,
        mimeType: String,
        groupTitle: String?,
    ) {
        if (!isNotificationsEnabled()) return
        if (isWindowVisible() && activeConversationId() == conversationId) return

        val title = groupTitle ?: (senderName?.ifBlank { null } ?: "Flash Message")
        val body = if (groupTitle != null) {
            "${senderName?.ifBlank { null } ?: "Member"} sent $fileName"
        } else {
            "Sent $fileName"
        }

        dispatchNotification(title, body, Notification.Type.Info)
    }

    public fun handleTransfersUpdate(transfers: List<com.transfer.flash.core.transfer.model.FlashTransfer>) {
        transfers.forEach { transfer ->
            val prev = previousTransferStates[transfer.id]
            if (prev != transfer.state) {
                previousTransferStates[transfer.id] = transfer.state

                // Fire notification only if this is a state transition (not initial discovery)
                if (prev != null) {
                    if (transfer.state == FlashTransferState.Completed) {
                        if (isNotificationsEnabled()) {
                            dispatchNotification(
                                title = "Transfer Complete",
                                message = "${transfer.fileName} transferred successfully",
                                type = Notification.Type.Info,
                            )
                        }
                    } else if (transfer.state == FlashTransferState.Failed) {
                        if (isNotificationsEnabled()) {
                            dispatchNotification(
                                title = "Transfer Failed",
                                message = "Failed to transfer ${transfer.fileName}",
                                type = Notification.Type.Warning,
                            )
                        }
                    }
                }
            }
        }
    }

    public fun handleCallUpdate(callUi: com.transfer.flash.core.calling.model.FlashCallUiState?) {
        val currentPhase = callUi?.state
        if (currentPhase != previousCallPhase) {
            previousCallPhase = currentPhase
            if (callUi != null &&
                callUi.direction == FlashCallDirection.INCOMING &&
                callUi.state == FlashCallState.RINGING
            ) {
                if (isNotificationsEnabled()) {
                    val callType = if (callUi.video) "Video" else "Voice"
                    dispatchNotification(
                        title = "Incoming $callType Call",
                        message = "Incoming call from ${callUi.peerName}",
                        type = Notification.Type.Info,
                    )
                }
            }
        }
    }

    public fun handlePairingUpdate(pairingUi: FlashPairingCoordinator.PairingUi?) {
        val currentPhase = pairingUi?.phase
        if (currentPhase != previousPairingPhase) {
            previousPairingPhase = currentPhase
            if (pairingUi != null && currentPhase == PairingPhase.RequestReceived) {
                if (isNotificationsEnabled()) {
                    dispatchNotification(
                        title = "Pairing Request",
                        message = "Pairing request from ${pairingUi.peerName ?: "device"}",
                        type = Notification.Type.Info,
                    )
                }
            }
        }
    }

    private fun dispatchNotification(title: String, message: String, type: Notification.Type) {
        FlashLog.i(TAG, "Posting desktop notification: [$title] $message")
        runCatching {
            sendNotification(Notification(title = title, message = message, type = type))
        }.onFailure { e ->
            FlashLog.w(TAG, "Failed to send desktop notification", e)
        }
    }

    public companion object {
        private const val TAG: String = "DesktopNotify"
    }
}
