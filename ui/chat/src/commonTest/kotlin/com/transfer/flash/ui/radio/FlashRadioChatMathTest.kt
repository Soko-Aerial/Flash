package com.transfer.flash.ui.radio

import com.transfer.flash.core.messaging.radio.RadioDeliveryNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADCHAT-U01 (ADR-227): the radio delivery line under a bubble says only what the engine proved.
 */
class FlashRadioChatMathTest {

    @Test
    fun waitingNoteSaysWaitingForAcknowledgement() {
        val note = RadioDeliveryNote(RadioDeliveryNote.STATE_SENT_WAITING)
        assertEquals(FlashRadioCopy.CHAT_RADIO_WAITING, FlashRadioChatMath.deliveryLine(note))
        assertFalse(FlashRadioChatMath.isFailure(note))
    }

    @Test
    fun onlyUnacknowledgedNonFailureNotesOfferStopRetry() {
        assertTrue(FlashRadioChatMath.canStopRetry(RadioDeliveryNote(RadioDeliveryNote.STATE_SENT_WAITING)))
        assertTrue(FlashRadioChatMath.canStopRetry(RadioDeliveryNote(RadioDeliveryNote.STATE_NOT_CONNECTED)))
        assertFalse(FlashRadioChatMath.canStopRetry(RadioDeliveryNote(RadioDeliveryNote.STATE_DELIVERED, isDelivered = true)))
        assertFalse(FlashRadioChatMath.canStopRetry(RadioDeliveryNote(RadioDeliveryNote.STATE_STOPPED, isFailure = true)))
    }

    @Test
    fun acknowledgedNoteSaysDelivered() {
        val note = RadioDeliveryNote(RadioDeliveryNote.STATE_DELIVERED, isDelivered = true)
        assertEquals(FlashRadioCopy.CHAT_RADIO_DELIVERED, FlashRadioChatMath.deliveryLine(note))
    }

    @Test
    fun deliveredFlagWinsOverTheStoredStatusText() {
        // The engine sets the flag when the ACK arrives; the line must follow the flag, not the stale text.
        val note = RadioDeliveryNote(RadioDeliveryNote.STATE_SENT_WAITING, isDelivered = true)
        assertEquals(FlashRadioCopy.CHAT_RADIO_DELIVERED, FlashRadioChatMath.deliveryLine(note))
    }

    @Test
    fun unconnectedNoteSaysHeldInTheOutbox() {
        val note = RadioDeliveryNote(RadioDeliveryNote.STATE_NOT_CONNECTED)
        assertEquals(FlashRadioCopy.CHAT_RADIO_NOT_CONNECTED, FlashRadioChatMath.deliveryLine(note))
    }

    @Test
    fun refusalIsShownAsItsOwnSentence() {
        val note = RadioDeliveryNote(RadioDeliveryNote.REFUSAL_ATTACHMENT, isFailure = true)
        assertEquals(RadioDeliveryNote.REFUSAL_ATTACHMENT, FlashRadioChatMath.deliveryLine(note))
        assertTrue(FlashRadioChatMath.isFailure(note))
    }

    @Test
    fun permanentFailureIsShownAsTheEngineSentence() {
        val note = RadioDeliveryNote(RadioDeliveryNote.STATE_FAILED, isFailure = true)
        assertEquals(RadioDeliveryNote.STATE_FAILED, FlashRadioChatMath.deliveryLine(note))
    }
}
