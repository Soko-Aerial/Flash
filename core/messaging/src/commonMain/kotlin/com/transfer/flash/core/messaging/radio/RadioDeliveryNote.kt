package com.transfer.flash.core.messaging.radio

/**
 * Human-readable radio delivery note and status descriptor attached to a message bubble.
 * Follows the pattern of HubDeliveryNote (Plan R §7).
 */
public data class RadioDeliveryNote(
    /** The status line to display, e.g. 'sent by radio, waiting', 'delivered', 'radio not connected', 'failed'. */
    public val statusText: String,
    /** True if the message failed permanently. */
    public val isFailure: Boolean = false,
    /** True if cryptographic delivery has been confirmed by an inbound ACK frame. */
    public val isDelivered: Boolean = false,
) {
    public companion object {
        public const val STATE_SENT_WAITING: String = "sent by radio, waiting"
        public const val STATE_DELIVERED: String = "delivered"
        public const val STATE_NOT_CONNECTED: String = "radio not connected"
        public const val STATE_FAILED: String = "failed to deliver by radio"
        public const val REFUSAL_TOO_LONG: String = "Message is too long to send by radio (maximum 3,008 bytes)"
        public const val REFUSAL_ATTACHMENT: String = "Photos and files cannot be sent by radio"
    }
}
