package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlashDeliveryStatusLogicTest {

    @Test
    fun `delivery status enum contains all 5 lifecycle states`() {
        val statuses = FlashMessageStatus.values()
        assertEquals(5, statuses.size)
        assertEquals(FlashMessageStatus.Pending, FlashMessageStatus.valueOf("Pending"))
        assertEquals(FlashMessageStatus.Sent, FlashMessageStatus.valueOf("Sent"))
        assertEquals(FlashMessageStatus.Delivered, FlashMessageStatus.valueOf("Delivered"))
        assertEquals(FlashMessageStatus.Read, FlashMessageStatus.valueOf("Read"))
        assertEquals(FlashMessageStatus.Failed, FlashMessageStatus.valueOf("Failed"))
    }

    @Test
    fun `message model supports delivery status metadata`() {
        val outgoingPending = FlashMessageUi(
            id = "msg-1",
            senderName = "You",
            senderInitials = "Y",
            timeLabel = "10:00",
            text = "Sending file",
            isMine = true,
            deliveryStatus = FlashMessageStatus.Pending,
        )
        assertEquals(FlashMessageStatus.Pending, outgoingPending.deliveryStatus)

        val outgoingRead = outgoingPending.copy(deliveryStatus = FlashMessageStatus.Read)
        assertEquals(FlashMessageStatus.Read, outgoingRead.deliveryStatus)

        val incoming = FlashMessageUi(
            id = "msg-2",
            senderName = "Alex",
            senderInitials = "A",
            timeLabel = "10:01",
            text = "Received",
            isMine = false,
        )
        assertNull(incoming.deliveryStatus)
    }

    @Test
    fun `a11y descriptions match status states`() {
        fun a11yFor(status: FlashMessageStatus): String = when (status) {
            FlashMessageStatus.Pending -> "Sending message"
            FlashMessageStatus.Sent -> "Sent"
            FlashMessageStatus.Delivered -> "Delivered"
            FlashMessageStatus.Read -> "Read by recipient"
            FlashMessageStatus.Failed -> "Failed to send. Double-tap to retry."
        }

        assertEquals("Sending message", a11yFor(FlashMessageStatus.Pending))
        assertEquals("Sent", a11yFor(FlashMessageStatus.Sent))
        assertEquals("Delivered", a11yFor(FlashMessageStatus.Delivered))
        assertEquals("Read by recipient", a11yFor(FlashMessageStatus.Read))
        assertEquals("Failed to send. Double-tap to retry.", a11yFor(FlashMessageStatus.Failed))
    }
}
