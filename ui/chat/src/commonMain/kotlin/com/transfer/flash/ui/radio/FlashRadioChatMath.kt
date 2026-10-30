package com.transfer.flash.ui.radio

import com.transfer.flash.core.messaging.radio.RadioDeliveryNote

/**
 * Copy for the radio delivery line under an outgoing bubble (ADR-227).
 *
 * Kept free of Compose types so it is unit-testable ([FlashRadioChatMathTest]). The status text comes from the
 * engine's own note; this object only chooses the sentence that fits the state, so the bubble never invents
 * a delivery claim.
 */
public object FlashRadioChatMath {

    /** True when the note deserves the error colour in the bubble. */
    public fun isFailure(note: RadioDeliveryNote): Boolean = note.isFailure

    /** The operator can stop only future retries; delivered and permanently refused notes are final. */
    public fun canStopRetry(note: RadioDeliveryNote): Boolean = !note.isDelivered && !note.isFailure

    /**
     * The one line shown under the message.
     *
     * - acknowledged: the cryptographic ACK came back -> "delivered"
     * - transmitted, no ACK yet -> "waiting for acknowledgement"
     * - never transmitted because the link is down -> "held in the outbox"
     * - anything else (refusal, permanent failure) -> the engine's own sentence, unprefixed, because it is already
     *   a complete sentence for a person ("Photos and files cannot be sent by radio").
     */
    public fun deliveryLine(note: RadioDeliveryNote): String = when {
        note.isDelivered -> FlashRadioCopy.CHAT_RADIO_DELIVERED
        note.isFailure -> note.statusText
        note.statusText == RadioDeliveryNote.STATE_SENT_WAITING -> FlashRadioCopy.CHAT_RADIO_WAITING
        note.statusText == RadioDeliveryNote.STATE_NOT_CONNECTED -> FlashRadioCopy.CHAT_RADIO_NOT_CONNECTED
        else -> FlashRadioCopy.CHAT_RADIO_WAITING
    }
}
