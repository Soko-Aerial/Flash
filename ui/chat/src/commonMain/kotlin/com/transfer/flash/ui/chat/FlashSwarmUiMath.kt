package com.transfer.flash.ui.chat

import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransferWaitReason

/**
 * Pure math and string formatters for group swarm file availability (UI-055, SW-11).
 *
 * Formats user-facing status lines without exposing technical protocol details
 * like piece numbers, hashes, or peer IDs (AGENTS 22).
 */
public object FlashSwarmUiMath {

    public const val PICK_AGAIN_TO_SHARE: String =
        "Your file is no longer available. Pick it again to keep sharing"

    public const val YOU_CAN_GO_OFFLINE: String =
        "You can go offline now"

    /**
     * One member's line in the sender's "who has the file" list: done, still receiving (share and speed), connected
     * but not started, or offline.
     */
    public fun recipientStatusLine(
        hasAll: Boolean,
        online: Boolean,
        progress: Float,
        speedMbps: Float,
    ): String {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        return when {
            hasAll -> "Has the file"
            !online -> if (pct > 0) "Offline · $pct%" else "Offline"
            pct == 0 -> "Waiting to start"
            speedMbps >= 0.05f -> "$pct% · ${"%.1f".format(speedMbps)} MB/s"
            else -> "$pct%"
        }
    }

    /**
     * Formats the detail status line for a file receiver (§5.5, UI-055).
     */
    public fun receiverStatusLine(
        waitReason: FlashTransferWaitReason?,
        holdersOnline: Int = 0,
        senderName: String = "the sender",
        bytesDone: Long = 0L,
        bytesTotal: Long = 0L,
        failureMessage: String? = null,
    ): String? {
        if (!failureMessage.isNullOrBlank()) {
            return failureMessage
        }

        if (waitReason != null) {
            return when (waitReason) {
                FlashTransferWaitReason.WaitingForSender -> {
                    if (bytesDone > 0L && bytesTotal > 0L) {
                        TransferFailureText.waitingForSenderProgress(senderName, bytesDone, bytesTotal)
                    } else {
                        TransferFailureText.waitingForSender(senderName)
                    }
                }
                FlashTransferWaitReason.WaitingForHolders ->
                    TransferFailureText.WAITING_FOR_MISSING_PARTS
                FlashTransferWaitReason.WaitingForNetwork ->
                    TransferFailureText.WAITING_FOR_WIFI
                FlashTransferWaitReason.WaitingForSpace ->
                    "Not enough free space"
                FlashTransferWaitReason.WaitingForStorage ->
                    TransferFailureText.STORAGE_UNAVAILABLE
                FlashTransferWaitReason.WaitingForSystem ->
                    TransferFailureText.SYSTEM_TIMEOUT
                FlashTransferWaitReason.WaitingForSession ->
                    TransferFailureText.CONNECTING_MEMBERS
            }
        }

        if (holdersOnline > 1) {
            return "Getting it from $holdersOnline devices"
        }

        return null
    }

    /**
     * Formats the status line for the origin / sender of a group file (§5.5, UI-055).
     */
    public fun senderStatusLine(
        deliveredTo: Int? = null,
        deliveredTotal: Int? = null,
        canGoOffline: Boolean = false,
        holdersNeedingParts: Int = 0,
        isSourceLost: Boolean = false,
        isComplete: Boolean = false,
    ): String? {
        if (isSourceLost) {
            return PICK_AGAIN_TO_SHARE
        }

        if (isComplete) {
            return null
        }

        val hasRecipientCount = deliveredTo != null && deliveredTotal != null && deliveredTotal > 0

        if (canGoOffline) {
            return if (hasRecipientCount) {
                "Delivered to $deliveredTo of $deliveredTotal · $YOU_CAN_GO_OFFLINE"
            } else {
                YOU_CAN_GO_OFFLINE
            }
        }

        if (hasRecipientCount && deliveredTo > 0) {
            return "Delivered to $deliveredTo of $deliveredTotal"
        }

        if (holdersNeedingParts > 0) {
            val deviceWord = if (holdersNeedingParts == 1) "device" else "devices"
            return "$holdersNeedingParts $deviceWord still need parts only you have"
        }

        return null
    }

    /**
     * Formats a combined subtitle for file card display.
     */
    public fun formatSubtitle(
        formattedSize: String,
        pct: Int,
        speedStr: String,
        etaStr: String,
        detailLine: String?,
    ): String {
        return if (!detailLine.isNullOrBlank()) {
            if (pct in 1..99) {
                "$detailLine · $pct%"
            } else {
                detailLine
            }
        } else {
            "$formattedSize · $pct%$speedStr$etaStr"
        }
    }
}
