package com.transfer.flash.desktop

import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.ui.settings.FlashStorageMath

/** What a button on a transfer/notification toast does. */
public enum class ToastAction(val label: String, val isPrimary: Boolean = false) {
    Accept("Accept", isPrimary = true),
    Decline("Decline", isPrimary = false),
    Retry("Retry", isPrimary = true),
    Dismiss("Dismiss", isPrimary = false),
    ShowInFolder("Show in Folder", isPrimary = false),
    OpenFile("Open File", isPrimary = true),
    OpenChat("Open Chat", isPrimary = true),
    OpenAnnouncement("Open Announcement", isPrimary = true),
    ReviewPairing("Review Pairing", isPrimary = true),
}

/** Category of toast notification. */
public enum class ToastKind {
    TransferOffer,
    TransferCompleted,
    TransferFailed,
    ChatMessage,
    Announcement,
    IncomingCall,
    PairingRequest,
    Transfer,
    Generic,
}

/** One card in the custom toast window: context, metadata, and actionable buttons. */
public data class DesktopToastCard(
    val id: String,
    val title: String,
    val detail: String,
    val actions: List<ToastAction>,
    val bytesTotal: Long = 0L,
    val formattedSize: String = "",
    val transportLabel: String = "LAN",
    val peerInitial: String = "",
    val fileType: String = "",
    val transferId: FlashTransferId = FlashTransferId(id),
    val autoDismissAtMs: Long? = null,
    val kind: ToastKind = ToastKind.Transfer,
    val localPath: String? = null,
    val conversationId: String? = null,
    val callId: String? = null,
    val isVideoCall: Boolean = false,
    val isVerifying: Boolean = false,
    val announcementId: String? = null,
) {
    constructor(
        transferId: FlashTransferId,
        title: String,
        detail: String,
        actions: List<ToastAction>,
        bytesTotal: Long = 0L,
        formattedSize: String = "",
        transportLabel: String = "LAN",
        peerInitial: String = "",
        fileType: String = "",
        autoDismissAtMs: Long? = null,
        kind: ToastKind = ToastKind.Transfer,
        localPath: String? = null,
        conversationId: String? = null,
        callId: String? = null,
        isVideoCall: Boolean = false,
        isVerifying: Boolean = false,
    ) : this(
        id = transferId.value,
        title = title,
        detail = detail,
        actions = actions,
        bytesTotal = bytesTotal,
        formattedSize = formattedSize,
        transportLabel = transportLabel,
        peerInitial = peerInitial,
        fileType = fileType,
        transferId = transferId,
        autoDismissAtMs = autoDismissAtMs,
        kind = kind,
        localPath = localPath,
        conversationId = conversationId,
        callId = callId,
        isVideoCall = isVideoCall,
        isVerifying = isVerifying,
    )
}

internal val DesktopToastCard.isPendingRequest: Boolean
    get() = kind == ToastKind.IncomingCall || kind == ToastKind.PairingRequest || kind == ToastKind.TransferOffer

/**
 * Unified model for custom desktop toast cards (Phase 3: Unified Custom Notification Center).
 * Supports transfers, chat messages, calls, pairing requests, and indeterminate verification states.
 */
public object DesktopTransferToastModel {
    const val MAX_CARDS: Int = 3
    const val CARD_HEIGHT_DP: Int = 116
    const val CARD_EXPANDED_HEIGHT_DP: Int = 124
    const val CARD_MAX_HEIGHT_DP: Int = 144
    const val GAP_DP: Int = 8
    const val MARGIN_DP: Int = 8
    const val WIDTH_DP: Int = 400

    /** Standard auto-dismiss timeouts. */
    const val AUTO_DISMISS_SHORT_MS: Long = 8_000L
    const val AUTO_DISMISS_FAILED_MS: Long = 15_000L

    fun cards(
        transfers: List<FlashTransfer>,
        dismissed: Set<FlashTransferId>,
        appInForeground: Boolean,
    ): List<DesktopToastCard> {
        if (appInForeground) return emptyList()
        val offers = transfers
            .filter { it.state == FlashTransferState.Offered && it.direction == FlashTransferDirection.Receiving }
            .filter { it.id !in dismissed }
            .map {
                val peer = it.peerName.ifBlank { "a nearby device" }
                val transport = if (it.viaHub) "Hub" else "LAN"
                val formattedSize = if (it.bytesTotal > 0L) FlashStorageMath.formatBytes(it.bytesTotal) else ""
                val initial = it.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
                val ext = if (it.fileName.contains('.')) it.fileName.substringAfterLast('.').uppercase().take(4) else ""
                DesktopToastCard(
                    transferId = it.id,
                    title = "Incoming file from $peer",
                    detail = it.fileName,
                    actions = listOf(ToastAction.Decline, ToastAction.Accept),
                    bytesTotal = it.bytesTotal,
                    formattedSize = formattedSize,
                    transportLabel = transport,
                    peerInitial = initial,
                    fileType = ext,
                    kind = ToastKind.TransferOffer,
                )
            }
        val failed = transfers
            .filter { it.state == FlashTransferState.Failed && it.direction == FlashTransferDirection.Sending && it.canRetry }
            .filter { it.id !in dismissed }
            .map {
                val transport = if (it.viaHub) "Hub" else "LAN"
                val formattedSize = if (it.bytesTotal > 0L) FlashStorageMath.formatBytes(it.bytesTotal) else ""
                val initial = it.peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
                val ext = if (it.fileName.contains('.')) it.fileName.substringAfterLast('.').uppercase().take(4) else ""
                DesktopToastCard(
                    transferId = it.id,
                    title = "Could not send ${it.fileName}",
                    detail = TransferFailureText.displayReason(it.errorMessage)?.ifBlank { null } ?: "The transfer failed.",
                    actions = listOf(ToastAction.Dismiss, ToastAction.Retry),
                    bytesTotal = it.bytesTotal,
                    formattedSize = formattedSize,
                    transportLabel = transport,
                    peerInitial = initial,
                    fileType = ext,
                    autoDismissAtMs = System.currentTimeMillis() + AUTO_DISMISS_FAILED_MS,
                    kind = ToastKind.TransferFailed,
                )
            }
        return (offers + failed).take(MAX_CARDS)
    }

    /** Foreground messages and refusals have already passed the manager's delivery policy. */
    private fun showsOverFlash(card: DesktopToastCard): Boolean =
        card.kind == ToastKind.ChatMessage || card.kind == ToastKind.Announcement || card.kind == ToastKind.Generic

    /** Filters active unexpired cards not in the dismissed set. */
    fun filterActiveCards(
        cards: List<DesktopToastCard>,
        dismissed: Set<String>,
        appInForeground: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): List<DesktopToastCard> {
        return cards
            .filter { !appInForeground || showsOverFlash(it) }
            .filter { it.id !in dismissed }
            .filter { it.autoDismissAtMs == null || it.autoDismissAtMs > nowMs }
            .sortedByDescending { if (it.kind == ToastKind.IncomingCall) 2 else if (it.isPendingRequest) 1 else 0 }
            .take(MAX_CARDS)
    }

    /**
     * Determines optimal card height based on card content (dynamic/intrinsic height).
     * Baseline is [CARD_HEIGHT_DP] (116.dp), expanding to [CARD_EXPANDED_HEIGHT_DP] (124.dp)
     * when actions or details require vertical headroom under font scaling, and up to
     * [CARD_MAX_HEIGHT_DP] (144.dp) when metadata, verification state, or multi-line details require additional vertical space.
     */
    fun cardHeightDp(card: DesktopToastCard): Int =
        if (card.formattedSize.isNotBlank() || card.transportLabel.isNotBlank() || card.isVerifying) {
            CARD_MAX_HEIGHT_DP
        } else if (card.actions.isNotEmpty() || card.detail.isNotBlank()) {
            CARD_EXPANDED_HEIGHT_DP
        } else {
            CARD_HEIGHT_DP
        }

    /** Height in dp of a window holding [count] cards (card + gap), plus the outer margin. */
    fun windowHeightDp(count: Int, cardHeightDp: Int = CARD_HEIGHT_DP): Int =
        if (count <= 0) 0 else count * cardHeightDp.coerceIn(CARD_HEIGHT_DP, CARD_MAX_HEIGHT_DP) + (count - 1) * GAP_DP + 2 * MARGIN_DP

    /** Height in dp of a window holding [cards], using dynamic card height. */
    fun windowHeightDp(cards: List<DesktopToastCard>): Int {
        if (cards.isEmpty()) return 0
        val effectiveCardHeight = cards.maxOfOrNull { cardHeightDp(it) } ?: CARD_HEIGHT_DP
        return windowHeightDp(cards.size, effectiveCardHeight)
    }
}

public typealias DesktopNotificationModel = DesktopTransferToastModel
