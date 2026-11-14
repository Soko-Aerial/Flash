@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import androidx.compose.ui.window.Notification
import com.transfer.flash.core.calling.CallCoordinator
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.PairingPhase
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.ui.settings.FlashStorageMath
import com.transfer.flash.core.common.result.FlashResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Desktop unified notification manager (Phase 3: Unified Custom Notification Center).
 *
 * Retires native OS balloon/tray dispatch and routes all notification events into the custom
 * Compose toast window queue (`toastCards`):
 * - Transfer Offer: [Decline] (left) / [Accept] (right)
 * - Transfer Completed: [Show in Folder] (left) / [Open File] (right), 8s auto-dismiss
 * - Transfer Failed: [Dismiss] (left) / [Retry] (right), 15s auto-dismiss
 * - Chat Message / Attachment: [Dismiss] (left) / [Open Chat] (right), 8s auto-dismiss
 * - Incoming Call: [Decline] (red, left) / [Accept] (green, right)
 * - Pairing Request: opens the code comparison dialog
 * - Indeterminate file integrity verification state (Verifying)
 */
public class DesktopNotificationManager(
    private val engine: DesktopEngine,
    private val scope: CoroutineScope,
    private val sendNotification: ((Notification) -> Unit)? = null,
    private val isWindowVisible: () -> Boolean,
    private val activeConversationId: () -> String?,
    private val isNotificationsEnabled: () -> Boolean = { engine.settings.value.showNotifications },
    private val isWindowMinimized: () -> Boolean = { false },
    private val isWindowFocused: () -> Boolean = { true },
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
) {
    public var onBackgroundMessageReceived: ((conversationId: String) -> Unit)? = null

    private var started = false
    private var transferJob: Job? = null
    private var callsJob: Job? = null
    private var pairingJob: Job? = null

    // Track transfer states by ID to fire transitions only once
    private val previousTransferStates = ConcurrentHashMap<FlashTransferId, FlashTransferState>()

    // Timestamps when transfers transitioned to terminal state while suppressed by window focus
    private val deferredCompletionTimes = ConcurrentHashMap<FlashTransferId, Long>()

    // Track pairing phase to fire notification only on transition into RequestReceived
    private var previousPairingPhase: PairingPhase? = null

    // Track call phase to fire notification only on transition into RINGING
    private var previousRingingCallId: String? = null

    // Unified custom toast window queue
    private val _toastCards = MutableStateFlow<List<DesktopToastCard>>(emptyList())
    public val toastCards: StateFlow<List<DesktopToastCard>> = _toastCards.asStateFlow()

    // Last known transfers for re-evaluating focus-suppressed transfers
    private var lastKnownTransfers: List<FlashTransfer> = emptyList()

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
        engine.onJoinRequestNotification = { groupId, groupTitle, requesterName ->
            handleJoinRequest(groupId, groupTitle, requesterName)
        }
        engine.onGroupOfferRefused = { _, groupName, message -> handleGroupOfferRefused(groupName, message) }

        // Observe transfers when ready
        transferJob = scope.launch {
            engine.ready.collectLatest { ready ->
                if (ready) {
                    engine.transfers?.activeTransfers
                        ?.distinctUntilChanged { old, new ->
                            if (old.size != new.size) return@distinctUntilChanged false
                            val oldMap = old.associateBy { it.id }
                            new.all { n ->
                                val o = oldMap[n.id] ?: return@distinctUntilChanged false
                                o.state == n.state &&
                                    o.fileName == n.fileName &&
                                    o.peerName == n.peerName &&
                                    o.direction == n.direction &&
                                    o.errorMessage == n.errorMessage &&
                                    o.canRetry == n.canRetry
                            }
                        }
                        ?.collect { transfers ->
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
        engine.onJoinRequestNotification = null
        engine.onGroupOfferRefused = null
        transferJob?.cancel()
        transferJob = null
        callsJob?.cancel()
        callsJob = null
        pairingJob?.cancel()
        pairingJob = null
        previousTransferStates.clear()
        deferredCompletionTimes.clear()
        previousPairingPhase = null
        previousRingingCallId = null
        lastKnownTransfers = emptyList()
        _toastCards.value = emptyList()
    }

    public fun isWindowForegroundAndActive(): Boolean {
        return isWindowVisible() && !isWindowMinimized() && isWindowFocused()
    }

    /**
     * Responds to window focus changes. When focus is lost, checks whether any transfers
     * completed while suppressed by window focus and dispatches deferred notifications.
     */
    public fun onWindowFocusChanged(isFocused: Boolean) {
        if (!isFocused) {
            checkPendingTransfersUpdate()
        }
    }

    /**
     * Responds to window visibility changes (minimized/restored/tray).
     */
    public fun onWindowVisibilityChanged(isActive: Boolean) {
        if (!isActive) {
            checkPendingTransfersUpdate()
        }
    }

    private fun checkPendingTransfersUpdate() {
        val transfers = engine.transfers?.activeTransfers?.value?.takeIf { it.isNotEmpty() }
            ?: lastKnownTransfers
        if (transfers.isNotEmpty()) {
            val now = currentTimeMillis()
            deferredCompletionTimes.entries.removeIf { (id, suppressedAt) ->
                if (now - suppressedAt > DEFERRED_REPLAY_WINDOW_MS) {
                    val matching = transfers.firstOrNull { it.id == id }
                    if (matching != null) {
                        previousTransferStates[id] = matching.state
                    }
                    true
                } else {
                    false
                }
            }
            handleTransfersUpdate(transfers)
        }
    }

    public fun handleInboundMessage(
        conversationId: String,
        senderName: String?,
        text: String,
        groupTitle: String?,
    ) {
        val inForeground = isWindowForegroundAndActive()
        val shouldSuppress = inForeground && activeConversationId() == conversationId

        if (!shouldSuppress && isNotificationsEnabled()) {
            val title = groupTitle ?: (senderName?.ifBlank { null } ?: "Flash Message")
            val body = if (groupTitle != null) {
                "${senderName?.ifBlank { null } ?: "Member"}: $text"
            } else {
                text
            }
            val now = currentTimeMillis()
            val card = DesktopToastCard(
                id = "msg-$conversationId",
                title = title,
                detail = body,
                actions = listOf(ToastAction.Dismiss, ToastAction.OpenChat),
                peerInitial = (senderName ?: title).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M",
                transportLabel = if (groupTitle != null) "Group" else "",
                autoDismissAtMs = now + DesktopTransferToastModel.AUTO_DISMISS_SHORT_MS,
                kind = ToastKind.ChatMessage,
                conversationId = conversationId,
            )
            dispatchToast(card, Notification.Type.Info)
        }

        if (!inForeground) {
            onBackgroundMessageReceived?.invoke(conversationId)
        }
    }

    public fun handleInboundAttachment(
        conversationId: String,
        senderName: String?,
        fileName: String,
        mimeType: String,
        groupTitle: String?,
    ) {
        val now = currentTimeMillis()

        val inForeground = isWindowForegroundAndActive()
        val shouldSuppress = inForeground && activeConversationId() == conversationId

        if (!shouldSuppress && isNotificationsEnabled()) {
            val title = groupTitle ?: (senderName?.ifBlank { null } ?: "Flash Message")
            val body = if (groupTitle != null) {
                "${senderName?.ifBlank { null } ?: "Member"} sent $fileName"
            } else {
                "Sent $fileName"
            }
            val ext = if (fileName.contains('.')) fileName.substringAfterLast('.').uppercase().take(4) else ""
            val card = DesktopToastCard(
                id = "att-$conversationId-$fileName-$now",
                title = title,
                detail = body,
                actions = listOf(ToastAction.Dismiss, ToastAction.OpenChat),
                peerInitial = (senderName ?: title).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "F",
                fileType = ext,
                transportLabel = if (groupTitle != null) "Group" else "",
                autoDismissAtMs = now + DesktopTransferToastModel.AUTO_DISMISS_SHORT_MS,
                kind = ToastKind.ChatMessage,
                conversationId = conversationId,
            )
            dispatchToast(card, Notification.Type.Info)
        }

        if (!inForeground) {
            onBackgroundMessageReceived?.invoke(conversationId)
        }
    }

    public fun handleJoinRequest(
        groupId: String,
        groupTitle: String,
        requesterName: String,
    ) {
        val inForeground = isWindowForegroundAndActive()
        val shouldSuppress = inForeground && activeConversationId() == groupId

        if (!shouldSuppress && isNotificationsEnabled()) {
            val title = groupTitle.ifBlank { "Group" }
            val body = "$requesterName wants to join"
            val now = currentTimeMillis()
            val card = DesktopToastCard(
                id = "join-$groupId-$now",
                title = title,
                detail = body,
                actions = listOf(ToastAction.Dismiss, ToastAction.OpenChat),
                peerInitial = requesterName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                transportLabel = "Group",
                autoDismissAtMs = now + DesktopTransferToastModel.AUTO_DISMISS_SHORT_MS,
                kind = ToastKind.ChatMessage,
                conversationId = groupId,
            )
            dispatchToast(card, Notification.Type.Info)
        }

        if (!inForeground) {
            onBackgroundMessageReceived?.invoke(groupId)
        }
    }

    /** M-23: always shown, even with the window in front. */
    public fun handleGroupOfferRefused(groupName: String, message: String) {
        if (isNotificationsEnabled()) {
            val now = currentTimeMillis()
            val card = DesktopToastCard(
                id = "group-refused-$now",
                title = groupName.ifBlank { "Group" },
                detail = message,
                actions = listOf(ToastAction.Dismiss),
                peerInitial = groupName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                autoDismissAtMs = now + DesktopTransferToastModel.AUTO_DISMISS_SHORT_MS,
                kind = ToastKind.Generic,
            )
            dispatchToast(card, Notification.Type.Warning)
        }
    }

    public fun handleTransfersUpdate(transfers: List<FlashTransfer>) {
        lastKnownTransfers = transfers
        val liveIds = transfers.mapTo(HashSet()) { it.id }
        previousTransferStates.keys.removeIf { it !in liveIds }
        deferredCompletionTimes.keys.removeIf { it !in liveIds }

        val rows = transfers.associateBy { it.id }
        _toastCards.update { list ->
            list.filter { card ->
                val row = rows[card.transferId]
                when (card.kind) {
                    ToastKind.TransferOffer -> row?.state == FlashTransferState.Offered
                    ToastKind.TransferFailed -> row?.state == FlashTransferState.Failed
                    ToastKind.Transfer -> !card.isVerifying || row?.state == FlashTransferState.Verifying
                    ToastKind.TransferCompleted -> row?.state == FlashTransferState.Completed
                    else -> true
                }
            }
        }

        val inForeground = isWindowForegroundAndActive()
        val now = currentTimeMillis()
        transfers.forEach { transfer ->
            val prev = previousTransferStates[transfer.id]
            if (prev != transfer.state) {
                // Inbound offer
                if (transfer.state == FlashTransferState.Offered && prev == null) {
                    if (!inForeground) {
                        previousTransferStates[transfer.id] = transfer.state
                        if (isNotificationsEnabled()) {
                            dispatchTransferOffer(transfer)
                        }
                    }
                    // If in foreground, do NOT record in previousTransferStates so subsequent minimize shows toast
                    return@forEach
                }

                // Indeterminate integrity verification state (Phase 3: 100% linger fix)
                if (transfer.state == FlashTransferState.Verifying) {
                    previousTransferStates[transfer.id] = transfer.state
                    if (!inForeground && isNotificationsEnabled()) {
                        dispatchTransferVerifying(transfer)
                    }
                    return@forEach
                }

                // Cancelled transfer: clean up any lingering toasts for this transfer
                if (transfer.state == FlashTransferState.Cancelled) {
                    previousTransferStates[transfer.id] = transfer.state
                    deferredCompletionTimes.remove(transfer.id)
                    dismissToast(transfer.id.value)
                    return@forEach
                }

                // Fire notification only if this is a state transition (not initial discovery)
                if (prev != null) {
                    if (transfer.state == FlashTransferState.Completed) {
                        if (!inForeground) {
                            val suppressedAt = deferredCompletionTimes.remove(transfer.id)
                            val isFresh = suppressedAt == null || (now - suppressedAt <= DEFERRED_REPLAY_WINDOW_MS)
                            previousTransferStates[transfer.id] = transfer.state
                            if (isFresh && isNotificationsEnabled()) {
                                dispatchTransferCompleted(transfer)
                            }
                        } else {
                            // Window-focus suppression: record completion timestamp for freshness guard on focus replay
                            deferredCompletionTimes.putIfAbsent(transfer.id, now)
                        }
                    } else if (transfer.state == FlashTransferState.Failed) {
                        if (!inForeground) {
                            val suppressedAt = deferredCompletionTimes.remove(transfer.id)
                            val isFresh = suppressedAt == null || (now - suppressedAt <= DEFERRED_REPLAY_WINDOW_MS)
                            previousTransferStates[transfer.id] = transfer.state
                            if (isFresh && isNotificationsEnabled()) {
                                dispatchTransferFailed(transfer)
                            }
                        } else {
                            deferredCompletionTimes.putIfAbsent(transfer.id, now)
                        }
                    } else {
                        // Intermediate states (Transferring, Paused, Queued)
                        previousTransferStates[transfer.id] = transfer.state
                    }
                } else {
                    // Initial discovery of already completed/failed transfer (e.g. boot restoration)
                    previousTransferStates[transfer.id] = transfer.state
                    deferredCompletionTimes.remove(transfer.id)
                }
            }
        }
    }

    private fun dispatchTransferOffer(transfer: FlashTransfer) {
        val peer = transfer.peerName.ifBlank { "a nearby device" }
        val transport = if (transfer.viaHub) "Hub" else "LAN"
        val formattedSize = if (transfer.bytesTotal > 0L) FlashStorageMath.formatBytes(transfer.bytesTotal) else ""
        val initial = transfer.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        val ext = if (transfer.fileName.contains('.')) transfer.fileName.substringAfterLast('.').uppercase().take(4) else ""
        val card = DesktopToastCard(
            transferId = transfer.id,
            title = "Incoming file from $peer",
            detail = transfer.fileName,
            actions = listOf(ToastAction.Decline, ToastAction.Accept),
            bytesTotal = transfer.bytesTotal,
            formattedSize = formattedSize,
            transportLabel = transport,
            peerInitial = initial,
            fileType = ext,
            autoDismissAtMs = null, // Stays until answered/dismissed
            kind = ToastKind.TransferOffer,
        )
        dispatchToast(card, Notification.Type.Info)
    }

    private fun dispatchTransferVerifying(transfer: FlashTransfer) {
        val transport = if (transfer.viaHub) "Hub" else "LAN"
        val initial = transfer.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        val ext = if (transfer.fileName.contains('.')) transfer.fileName.substringAfterLast('.').uppercase().take(4) else ""
        val card = DesktopToastCard(
            transferId = transfer.id,
            title = transfer.fileName,
            detail = "Checking file integrity…",
            actions = listOf(ToastAction.Dismiss),
            bytesTotal = transfer.bytesTotal,
            transportLabel = transport,
            peerInitial = initial,
            fileType = ext,
            autoDismissAtMs = null,
            kind = ToastKind.Transfer,
            isVerifying = true,
        )
        postToast(card)
    }

    private fun dispatchTransferCompleted(transfer: FlashTransfer) {
        val transport = if (transfer.viaHub) "Hub" else "LAN"
        val formattedSize = if (transfer.bytesTotal > 0L) FlashStorageMath.formatBytes(transfer.bytesTotal) else ""
        val initial = transfer.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        val ext = if (transfer.fileName.contains('.')) transfer.fileName.substringAfterLast('.').uppercase().take(4) else ""
        val now = currentTimeMillis()
        val localPath = transfer.localPath ?: transfer.sourceUri
        val fileAvailable = DesktopHelpers.resolveFile(localPath)?.isFile == true
        val card = DesktopToastCard(
            transferId = transfer.id,
            title = "Transfer Complete",
            detail = "${transfer.fileName} transferred successfully",
            actions = if (fileAvailable) listOf(ToastAction.ShowInFolder, ToastAction.OpenFile) else listOf(ToastAction.Dismiss),
            bytesTotal = transfer.bytesTotal,
            formattedSize = formattedSize,
            transportLabel = transport,
            peerInitial = initial,
            fileType = ext,
            autoDismissAtMs = now + DesktopTransferToastModel.AUTO_DISMISS_SHORT_MS,
            kind = ToastKind.TransferCompleted,
            localPath = localPath,
        )
        dispatchToast(card, Notification.Type.Info)
    }

    private fun dispatchTransferFailed(transfer: FlashTransfer) {
        val transport = if (transfer.viaHub) "Hub" else "LAN"
        val formattedSize = if (transfer.bytesTotal > 0L) FlashStorageMath.formatBytes(transfer.bytesTotal) else ""
        val initial = transfer.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
        val ext = if (transfer.fileName.contains('.')) transfer.fileName.substringAfterLast('.').uppercase().take(4) else ""
        val reason = TransferFailureText.displayReason(transfer.errorMessage)?.ifBlank { null }
            ?: if (transfer.direction == FlashTransferDirection.Sending) "Could not send ${transfer.fileName}" else "Failed to transfer ${transfer.fileName}"
        val actions = if (transfer.canRetry) {
            listOf(ToastAction.Dismiss, ToastAction.Retry)
        } else {
            listOf(ToastAction.Dismiss)
        }
        val now = currentTimeMillis()
        val card = DesktopToastCard(
            transferId = transfer.id,
            title = if (transfer.direction == FlashTransferDirection.Sending) "Could not send ${transfer.fileName}" else "Transfer Failed",
            detail = reason,
            actions = actions,
            bytesTotal = transfer.bytesTotal,
            formattedSize = formattedSize,
            transportLabel = transport,
            peerInitial = initial,
            fileType = ext,
            autoDismissAtMs = now + DesktopTransferToastModel.AUTO_DISMISS_FAILED_MS,
            kind = ToastKind.TransferFailed,
        )
        dispatchToast(card, Notification.Type.Warning)
    }

    public fun handleCallUpdate(callUi: com.transfer.flash.core.calling.model.FlashCallUiState?) {
        val ringing = callUi?.takeIf { it.direction == FlashCallDirection.INCOMING && it.state == FlashCallState.RINGING }
        val ringingId = ringing?.callId
        if (ringingId != previousRingingCallId) {
            previousRingingCallId = ringingId
            _toastCards.update { list -> list.filter { it.kind != ToastKind.IncomingCall } }
            if (ringing != null) {
                if (!isWindowForegroundAndActive() && isNotificationsEnabled()) {
                    val callType = if (ringing.video) "Video" else "Voice"
                    val card = DesktopToastCard(
                        id = "call-${ringing.callId}",
                        title = ringing.peerName.ifBlank { "Incoming $callType Call" },
                        detail = "Incoming $callType Call",
                        actions = listOf(ToastAction.Decline, ToastAction.Accept),
                        peerInitial = ringing.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "C",
                        transportLabel = callType,
                        autoDismissAtMs = null, // Stays while ringing
                        kind = ToastKind.IncomingCall,
                        callId = ringing.callId,
                        isVideoCall = ringing.video,
                    )
                    dispatchToast(
                        card = card,
                        type = Notification.Type.Info,
                        legacyTitle = "Incoming $callType Call",
                        legacyMessage = "Incoming call from ${ringing.peerName}",
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
                if (!isWindowForegroundAndActive() && isNotificationsEnabled()) {
                    val peer = pairingUi.peerName ?: "device"
                    val card = DesktopToastCard(
                        id = "pairing-request",
                        title = "Pairing request from $peer",
                        detail = "Open Flash to compare the verification codes.",
                        actions = listOf(ToastAction.ReviewPairing),
                        peerInitial = peer.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                        transportLabel = "",
                        autoDismissAtMs = null, // Stays until confirmed or declined
                        kind = ToastKind.PairingRequest,
                    )
                    dispatchToast(card, Notification.Type.Info)
                }
            } else if (pairingUi == null || currentPhase != PairingPhase.RequestReceived) {
                _toastCards.update { list -> list.filter { it.kind != ToastKind.PairingRequest } }
            }
        }
    }

    /** Dispatches a toast into the unified custom toast queue and legacy notification callback if present. */
    private fun dispatchToast(
        card: DesktopToastCard,
        type: Notification.Type,
        legacyTitle: String = card.title,
        legacyMessage: String = card.detail,
    ) {
        FlashLog.i(TAG, "Posting desktop toast kind=${card.kind}")
        postToast(card)
        runCatching {
            sendNotification?.invoke(Notification(title = legacyTitle, message = legacyMessage, type = type))
        }.onFailure { e ->
            FlashLog.w(TAG, "Failed to invoke sendNotification callback", e)
        }
    }

    /** Adds or updates a card in the toast queue, capping at twice MAX_CARDS. */
    public fun postToast(card: DesktopToastCard) {
        _toastCards.update { current ->
            val existingIndex = current.indexOfFirst { it.id == card.id }
            if (existingIndex >= 0) {
                current.toMutableList().apply { set(existingIndex, card) }
            } else {
                val (requests, notices) = (listOf(card) + current).partition { it.isPendingRequest }
                requests + notices.take(DesktopTransferToastModel.MAX_CARDS * 2)
            }
        }
    }

    /** Dismisses a card by ID from the toast queue. */
    public fun dismissToast(id: String) {
        _toastCards.update { current -> current.filter { it.id != id } }
    }

    /** Resolve the action against live state and keep the card visible when it can be retried. */
    public fun handleToastAction(
        action: ToastAction,
        card: DesktopToastCard,
        onOpenConversation: ((String) -> Unit)? = null,
        onReviewPairing: (() -> Unit)? = null,
        onActionFailed: ((String) -> Unit)? = null,
    ) {
        if (action !in card.actions || card !in _toastCards.value) return
        scope.launch {
            if (card !in _toastCards.value) return@launch
            val succeeded = when (action) {
                ToastAction.Accept -> if (card.kind == ToastKind.IncomingCall) {
                    respondToCall(card, accept = true)
                } else {
                    engine.transfers?.acceptIncoming(card.transferId) is FlashResult.Success
                }
                ToastAction.Decline -> if (card.kind == ToastKind.IncomingCall) {
                    respondToCall(card, accept = false)
                } else {
                    engine.transfers?.declineIncoming(card.transferId) is FlashResult.Success
                }
                ToastAction.ReviewPairing -> {
                    onReviewPairing?.invoke()
                    true
                }
                ToastAction.Retry -> engine.transfers?.resumeTransfer(card.transferId) is FlashResult.Success
                ToastAction.Dismiss -> true
                ToastAction.ShowInFolder -> {
                    val file = DesktopHelpers.resolveFile(card.localPath)
                    file?.isFile == true && runCatching { DesktopHelpers.revealInFileManager(file) }.isSuccess
                }
                ToastAction.OpenFile -> {
                    val file = DesktopHelpers.resolveFile(card.localPath)
                    file?.isFile == true && java.awt.Desktop.isDesktopSupported() &&
                        runCatching { java.awt.Desktop.getDesktop().open(file) }.isSuccess
                }
                ToastAction.OpenChat -> {
                    card.conversationId?.let { onOpenConversation?.invoke(it) }
                    true
                }
            }
            if (succeeded) {
                _toastCards.update { cards -> cards.filter { it != card } }
            } else {
                onActionFailed?.invoke(
                    when (action) {
                        ToastAction.OpenFile, ToastAction.ShowInFolder -> "File is no longer available."
                        else -> "This action could not be completed. Check the current item in Flash."
                    },
                )
            }
        }
    }

    private suspend fun respondToCall(card: DesktopToastCard, accept: Boolean): Boolean {
        val id = card.callId ?: return false
        val calls = engine.calls as? CallCoordinator ?: return false
        return calls.respondToRingingCall(id, accept)
    }

    public companion object {
        private const val TAG: String = "DesktopNotify"
        public const val DEFERRED_REPLAY_WINDOW_MS: Long = 60_000L
    }
}
