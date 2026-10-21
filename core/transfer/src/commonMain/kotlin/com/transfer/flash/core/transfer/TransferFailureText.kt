@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog

/**
 * What the user reads when a transfer fails (ADR-069, FA-3).
 *
 * Both UIs print `FlashTransfer.errorMessage` verbatim for a Failed row, and until now that field carried
 * whatever an exception or a protocol reason said (`source length mismatch: read=4 expected=9 extraByte=-1`,
 * `all channels failed`). [friendly] maps the reasons the engine can produce to one sentence a person can act on;
 * the raw reason is logged under `TRANSFER` so a bug report still has it. Anything unrecognised becomes a generic
 * sentence, never the raw text.
 *
 * Messages for failures the repository raises itself ([NOT_ENOUGH_SPACE], [EMPTY_FILE], integrity) are already
 * written for people and do not pass through [friendly].
 */
public object TransferFailureText {

    public const val EMPTY_FILE: String = "This file is empty (0 bytes), so there is nothing to send."

    public const val UNREADABLE_FILE: String =
        "The file could not be read. It may have been moved or deleted, or access to it has expired."

    public const val FILE_CHANGED: String = "The file changed while it was being sent. Try sending it again."

    public const val CONNECTION_LOST: String =
        "The connection to the other device was lost. Check that both devices are still connected and try again."

    public const val PEER_NOT_RESPONDING: String = "The other device stopped responding. Try again."

    public const val CANCELLED_BY_PEER: String = "The other device cancelled the transfer."

    public const val DECLINED_BY_PEER: String = "The other device declined the file."

    public const val PAUSED_BY_PEER: String = "Paused by the other device."

    /** A 1:1 transfer paused by the Android service timeout: its progress is kept, but it does not restart itself. */
    public const val PAUSED_BY_SYSTEM: String = "Paused by Android. Tap Resume to continue."

    /** A retried receive that the sender never continued (open-items tracker C-08): it must not stay at "Downloading". */
    public const val SENDER_DID_NOT_RESUME: String =
        "The sender did not continue sending this file. Ask them to send it again."

    public const val WAITING_FOR_ACCEPT: String = "Waiting for the other device to accept."

    public const val GENERIC: String = "The transfer stopped unexpectedly. Try again."

    /** A transfer that was running when the process died (C-09): listed again, not resumed by itself. */
    public const val INTERRUPTED_BY_RESTART: String =
        "Flash was closed while this was transferring. Tap Retry to continue."

    /** The receiver could not write a chunk to storage (R-06): full disk, removed card, revoked folder access. */
    public const val WRITE_FAILED: String =
        "The file could not be saved on this device. Check that there is free space and that the save location is still available, then ask for it to be sent again."

    /**
     * The receiver refused the offer (C-11, ADR-221): it already holds as many incoming transfers as it can, the offer
     * failed its checks, or it could not save the file. Carried to the sender as a `reason` on the `cancel` frame.
     */
    public const val RECEIVER_BUSY: String =
        "The other device is receiving too many files right now. Try again in a moment."

    public const val RECEIVER_REFUSED_FILE: String =
        "The other device could not accept this file. Send it again, or send it as a smaller file."

    public const val RECEIVER_STORAGE: String =
        "The other device could not save this file. Ask them to check that they have free space, then send it again."

    /** Wire tokens of the `reason` field of a refusing `cancel` frame (docs/protocol.md "Transfer control"). */
    public const val REFUSE_SESSION_FULL: String = "session_full"
    public const val REFUSE_INVALID_FILE_START: String = "invalid_file_start"
    public const val REFUSE_WRITE_FAILED: String = "write_failed"

    /** The sender-side sentence for a refusal [token], or null when the token is absent or unknown (a plain cancel). */
    public fun refusalSentence(token: String?): String? = when (token?.trim()?.lowercase()) {
        REFUSE_SESSION_FULL -> RECEIVER_BUSY
        REFUSE_INVALID_FILE_START -> RECEIVER_REFUSED_FILE
        REFUSE_WRITE_FAILED -> RECEIVER_STORAGE
        else -> null
    }

    // Swarm-specific failure sentences (ADR-072, Table 8.2)
    public const val DAMAGED: String =
        "The received file did not match what was sent. Ask for it to be sent again."

    public const val SOURCE_LOST: String =
        "The file is no longer available from the sender."

    public const val SOURCE_CHANGED: String =
        "The file changed while it was being sent. Try sending it again."

    public const val SOURCE_PERMISSION_LOST: String =
        "Pick the file again to keep sharing."

    public const val STORAGE_UNAVAILABLE: String =
        "Choose where to save files."

    public const val SYSTEM_TIMEOUT: String =
        "Paused by Android; continues automatically."

    public const val NOT_MEMBER: String =
        "You are no longer in this group."

    public const val EXPIRED: String =
        "Expired: the missing parts were not available for 7 days."

    public const val ALREADY_ON_DEVICE: String =
        "Already on this device."

    public const val CONNECTING_MEMBERS: String =
        "Connecting to group members…"

    public const val WAITING_FOR_WIFI: String =
        "Waiting for Wi-Fi."

    public const val WAITING_FOR_MISSING_PARTS: String =
        "Waiting for a device that has the missing parts."

    public const val CHECKING_REDOWNLOADING: String =
        "Checking the file… re-downloading damaged parts."

    /** Waiting for sender to come online (E-01). */
    public fun waitingForSender(senderName: String): String =
        "Waiting for $senderName to come online"

    /** Waiting for sender with progress (E-02). */
    public fun waitingForSenderProgress(senderName: String, doneBytes: Long, totalBytes: Long): String =
        "Waiting for $senderName · ${formatSize(doneBytes)} of ${formatSize(totalBytes)} here"

    /** Origin source deleted or missing (E-19). */
    public fun sourceLost(senderName: String): String =
        "$senderName's file is no longer available"

    /** Origin source changed on disk (E-20). */
    public fun sourceChanged(senderName: String): String =
        "$senderName changed the file after sending it"

    /** Origin departed from group before completion (E-36). */
    public fun senderDeparted(senderName: String): String =
        "$senderName left the group before everyone had the file"

    /** Cancelled by origin user (E-39). */
    public fun cancelledBy(senderName: String): String =
        "Cancelled by $senderName"

    /** Deleted by origin for everyone (E-43). */
    public fun deletedBy(senderName: String): String =
        "Deleted by $senderName"

    /** Free space remaining summary (E-23, E-24). */
    public fun needsSpace(needed: Long, free: Long): String =
        "Needs ${formatSize(needed)}, ${formatSize(free)} free"

    /** Receiver refused an offer it has no room for: [needed] bytes wanted, [free] bytes available. */
    public fun notEnoughSpace(needed: Long, free: Long): String =
        "Not enough free space to receive this file. It needs ${formatSize(needed)} and only ${formatSize(free)} " +
            "is available. Free up some space, then ask for it to be sent again."

    /**
     * The sentence for the control-flow reasons the repository stores on a row that is stopped, paused or waiting on
     * purpose (`cancelled by receiver`, `declined`, `paused by sender`, ...). These are not failures, so unlike
     * [friendly] this neither logs nor falls back to [GENERIC]: an unrecognised reason is returned unchanged.
     */
    public fun displayReason(rawReason: String?): String? {
        val raw = rawReason?.trim() ?: return null
        return when (raw.lowercase()) {
            "cancelled by receiver", "cancelled by sender", "cancelled by peer" -> CANCELLED_BY_PEER
            "declined" -> DECLINED_BY_PEER
            "paused by receiver", "paused by sender" -> PAUSED_BY_PEER
            "paused by system" -> PAUSED_BY_SYSTEM
            "waiting for receiver to accept" -> WAITING_FOR_ACCEPT
            else -> raw
        }
    }

    /** Reads a sentence for [rawReason]; logs the raw reason (never shown) against [transferId]. */
    public fun friendly(rawReason: String?, transferId: String? = null): String {
        val raw = rawReason?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            runCatching { FlashLog.w("TRANSFER", "transfer failed transferId=$transferId reason=$raw") }
        }
        val lower = raw.lowercase()
        return when {
            lower.isEmpty() -> GENERIC
            "damaged" in lower -> DAMAGED
            "source_lost" in lower -> SOURCE_LOST
            "not_member" in lower -> NOT_MEMBER
            "expired" in lower -> EXPIRED
            "write_failed" in lower -> WRITE_FAILED
            "storage_failed" in lower || "storage_unavailable" in lower -> STORAGE_UNAVAILABLE
            "system_timeout" in lower || "system_suspend" in lower -> SYSTEM_TIMEOUT
            "length mismatch" in lower -> FILE_CHANGED
            lower.startsWith("source read failed") || "no such file" in lower || "enoent" in lower ||
                "permission denied" in lower || "filenotfound" in lower || "eacces" in lower -> UNREADABLE_FILE
            "cancelled by peer" in lower -> CANCELLED_BY_PEER
            "all channels failed" in lower || "data channel closed" in lower ||
                "peer disconnected" in lower || "connection reset" in lower || "broken pipe" in lower -> CONNECTION_LOST
            "timeout" in lower || "timed out" in lower -> PEER_NOT_RESPONDING
            else -> GENERIC
        }
    }

    /** `1.4 GB`, `812 KB`: decimal units, one decimal place from MB up. Pure, so it is unit-tested. */
    public fun formatSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1_000L -> "$b B"
            b < 1_000_000L -> "${b / 1_000L} KB"
            b < 1_000_000_000L -> oneDecimal(b, 1_000_000L) + " MB"
            else -> oneDecimal(b, 1_000_000_000L) + " GB"
        }
    }

    private fun oneDecimal(value: Long, unit: Long): String {
        val tenths = value * 10L / unit
        return "${tenths / 10L}.${tenths % 10L}"
    }
}
