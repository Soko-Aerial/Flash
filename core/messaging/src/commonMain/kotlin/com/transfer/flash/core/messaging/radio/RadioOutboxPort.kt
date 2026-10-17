package com.transfer.flash.core.messaging.radio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of handing a message to the radio outbox port.
 */
public sealed interface RadioSendResult {
    /** Driver confirmed transmission to the TNC/air. State: 'sent by radio, waiting'. */
    public object SentWaitingAck : RadioSendResult

    /** Refused: payload text exceeds segment budget (max 3,008 bytes). */
    public data class RefusedTooLong(public val reason: String = RadioDeliveryNote.REFUSAL_TOO_LONG) : RadioSendResult

    /** Refused: photos, files, voice notes or calls cannot be sent over radio. */
    public data class RefusedAttachment(public val reason: String = RadioDeliveryNote.REFUSAL_ATTACHMENT) : RadioSendResult

    /** Refused: radio gateway is switched off or link is disconnected. */
    public data class NotConnected(public val reason: String = RadioDeliveryNote.STATE_NOT_CONNECTED) : RadioSendResult

    /** Refused: recipient is not a paired peer known to be radio-reachable. */
    public data class RefusedUnreachable(public val reason: String) : RadioSendResult

    /** Refused: security authorization gate declined transmission (e.g. Profile M unconfirmed). */
    public data class RefusedAuthorization(public val reason: String) : RadioSendResult

    /** Delivery or transmission failed permanently. */
    public data class Failed(public val reason: String) : RadioSendResult
}

/**
 * An inbound text message received over the radio link.
 */
public data class RadioInboundMessage(
    public val senderDeviceId: String,
    public val messageId: String,
    public val text: String,
    public val sentAtMs: Long,
    public val senderCallsign: String? = null,
)

/**
 * A cryptographic delivery acknowledgement received over the radio link.
 */
public data class RadioDeliveryReceipt(
    public val messageId: String,
    public val recipientDeviceId: String,
    public val deliveredAtMs: Long,
)

/**
 * Connection and operational status of the radio gateway.
 */
public enum class RadioGatewayConnectionStatus {
    OFF,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/**
 * The port in `:core:messaging` through which the chat outbox talks to the hardware radio transport.
 * Clean boundary: `:core:messaging` defines this contract; the radio engine implements it.
 */
public interface RadioOutboxPort {
    /** True when the radio gateway is switched on in settings. */
    public val isGatewayEnabled: Boolean

    /** True when the radio link (serial/Bluetooth) is currently open and healthy. */
    public val isConnected: Boolean

    /** Live connection status flow. */
    public val connectionStatus: StateFlow<RadioGatewayConnectionStatus>

    /** True if [recipientDeviceId] is a paired peer known to be radio-reachable. */
    public fun isRadioReachable(recipientDeviceId: String): Boolean

    /**
     * Hands one text message to the radio transmit queue.
     *
     * @param recipientDeviceId the target station device ID
     * @param messageId unique message local ID
     * @param text UTF-8 text payload
     * @param sentAt client generation timestamp
     * @param emergency true if flagged as emergency traffic (highest transmission priority)
     */
    public suspend fun sendTextMessage(
        recipientDeviceId: String,
        messageId: String,
        text: String,
        sentAt: Long,
        emergency: Boolean = false,
    ): RadioSendResult

    /** Inbound text messages received and decrypted from the radio channel. */
    public val inboundMessages: Flow<RadioInboundMessage>

    /** Inbound cryptographic delivery receipts confirming peer delivery. */
    public val deliveryReceipts: Flow<RadioDeliveryReceipt>
}
