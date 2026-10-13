package com.transfer.flash.server.mailbox

import com.transfer.flash.core.hubprotocol.HubContract

/**
 * Size and retention policy of the mailbox (REL-08, REL-09). Every cap is enforced inside the same `BEGIN IMMEDIATE`
 * transaction as the insert, so concurrent senders can not jointly overshoot a cap.
 *
 * @property ttlMs how long an undelivered message is kept, by the SERVER clock (`ServerConfig.mailboxTtlDays`).
 * @property maxPayloadBytes largest stored payload (UTF-8 bytes). The relay wire already caps a payload at
 *   [HubContract.MAX_PAYLOAD_BYTES]; this is the storage-side backstop.
 * @property perRecipientRows / [perRecipientBytes] undelivered messages waiting for ONE recipient.
 * @property perSenderRows / [perSenderBytes] undelivered messages ONE sender has parked, across all recipients (so one
 *   compromised or buggy device can not fill the disk by spreading messages over many recipients).
 * @property globalRows / [globalBytes] everything in the mailbox.
 * @property maxClockSkewMs a sender-supplied `sentAt` further in the future than this is replaced by the server time.
 */
data class MailboxLimits(
    val ttlMs: Long = DEFAULT_TTL_MS,
    val maxPayloadBytes: Int = HubContract.MAX_PAYLOAD_BYTES,
    val perRecipientRows: Int = 1_000,
    val perRecipientBytes: Long = 50L * 1024 * 1024,
    val perSenderRows: Int = 5_000,
    val perSenderBytes: Long = 100L * 1024 * 1024,
    val globalRows: Long = 1_000_000L,
    val globalBytes: Long = 5L * 1024 * 1024 * 1024,
    val maxClockSkewMs: Long = 5L * 60 * 1000,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be positive, was $ttlMs" }
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(perRecipientRows > 0 && perSenderRows > 0 && globalRows > 0) { "row caps must be positive" }
        require(perRecipientBytes > 0 && perSenderBytes > 0 && globalBytes > 0) { "byte caps must be positive" }
        require(maxClockSkewMs >= 0) { "maxClockSkewMs must not be negative" }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** The limits for `ServerConfig.mailboxTtlDays` (everything else at its default). A day count overflowing a Long is clamped. */
        fun forTtlDays(days: Int): MailboxLimits {
            require(days > 0) { "mailboxTtlDays must be positive, was $days" }
            val ms = days.toLong() * 24 * 60 * 60 * 1000
            return MailboxLimits(ttlMs = ms)
        }
    }
}

/** What the relay asks the mailbox to keep: an opaque payload for one recipient. */
data class MailboxMessage(
    val origin: String,
    val target: String,
    val msgId: String,
    val type: String,
    /** Opaque to the hub: stored and returned byte for byte, never parsed. */
    val payload: String,
    /** The sender's own timestamp: informational only, never used for ordering or expiry. */
    val sentAt: Long,
    /** The group this copy was fanned out for (REL-14), or null for a direct message. */
    val group: String? = null,
)

/** Which cap a [EnqueueResult.QuotaExceeded] hit. */
enum class QuotaScope { PAYLOAD, RECIPIENT, SENDER, GLOBAL }

/** Outcome of [MailboxStore.enqueue]. Each case maps to one distinct ack towards the sender. */
sealed interface EnqueueResult {
    /** Stored; [seq] is its position in the hub-wide arrival order. */
    data class Queued(val seq: Long) : EnqueueResult

    /** The same (sender, message id, recipient) is already stored: nothing was added (an idempotent retry). */
    data class DuplicateQueued(val seq: Long) : EnqueueResult

    data class QuotaExceeded(val scope: QuotaScope, val reason: String) : EnqueueResult

    /** The recipient is not enrolled in this organisation. */
    data object UnknownRecipient : EnqueueResult

    /** A storage error. Never carries exception text to the wire; the cause is for the log. */
    data class Failed(val cause: Throwable) : EnqueueResult

    /** This hub keeps no mailbox. */
    data object Unavailable : EnqueueResult

    val isStored: Boolean get() = this is Queued || this is DuplicateQueued
}

/** A stored message as read back for delivery. */
data class QueuedMessage(
    val seq: Long,
    val origin: String,
    val target: String,
    val msgId: String,
    val type: String,
    val payload: String,
    val sentAt: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val group: String? = null,
)

/** A row removed because its recipient acknowledged it. */
data class AckedRow(val origin: String, val msgId: String, val createdAt: Long)

/** A row removed because it expired undelivered; the sender can be told. */
data class ExpiredRow(val origin: String, val target: String, val msgId: String)
