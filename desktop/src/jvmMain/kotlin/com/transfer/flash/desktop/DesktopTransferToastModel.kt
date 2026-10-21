package com.transfer.flash.desktop

import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState

/** What a button on a transfer toast does. */
internal enum class ToastAction(val label: String) {
    Accept("Accept"),
    Decline("Decline"),
    Retry("Retry"),
    Dismiss("Dismiss"),
}

/** One card in the transfer toast: a file, one line of context, and the buttons that make sense for its state. */
internal data class DesktopToastCard(
    val transferId: FlashTransferId,
    val title: String,
    val detail: String,
    val actions: List<ToastAction>,
)

/**
 * Which transfers deserve an actionable toast (open-items tracker C-06). The operating-system notification
 * (Compose tray / AWT) cannot carry buttons, so an offer that arrives while Flash is in the tray used to be a banner
 * the person could read but not answer. This is the model behind the small always-on-top card that can.
 *
 * Pure so it can be tested without a window: no cards while the app is in front (the in-app UI already shows the
 * same buttons), offers first, at most [MAX_CARDS], and nothing that was already answered or dismissed.
 */
internal object DesktopTransferToastModel {
    const val MAX_CARDS: Int = 3
    const val CARD_HEIGHT_DP: Int = 96
    const val GAP_DP: Int = 8
    const val MARGIN_DP: Int = 8
    const val WIDTH_DP: Int = 360

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
                DesktopToastCard(
                    transferId = it.id,
                    title = "Incoming file from ${it.peerName.ifBlank { "a nearby device" }}",
                    detail = it.fileName,
                    actions = listOf(ToastAction.Accept, ToastAction.Decline),
                )
            }
        // A failed send the person can retry. A failed receive, or a failure that cannot be retried, is the plain
        // notification's job: there is nothing a button could do.
        val failed = transfers
            .filter { it.state == FlashTransferState.Failed && it.direction == FlashTransferDirection.Sending && it.canRetry }
            .filter { it.id !in dismissed }
            .map {
                DesktopToastCard(
                    transferId = it.id,
                    title = "Could not send ${it.fileName}",
                    detail = TransferFailureText.displayReason(it.errorMessage)?.ifBlank { null } ?: "The transfer failed.",
                    actions = listOf(ToastAction.Retry, ToastAction.Dismiss),
                )
            }
        return (offers + failed).take(MAX_CARDS)
    }

    /** Height in dp of a window holding [count] cards (card + gap), plus the outer margin. */
    fun windowHeightDp(count: Int): Int =
        if (count <= 0) 0 else count * CARD_HEIGHT_DP + (count - 1) * GAP_DP + 2 * MARGIN_DP
}
