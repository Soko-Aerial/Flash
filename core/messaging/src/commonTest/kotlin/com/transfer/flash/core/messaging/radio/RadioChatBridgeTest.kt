package com.transfer.flash.core.messaging.radio

import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioChatBridgeTest {

    private class FakeRadioOutboxPort : RadioOutboxPort {
        var isGatewayEnabledValue = true
        var isConnectedValue = true
        val reachablePeers = mutableSetOf<String>()

        val sentMessages = mutableListOf<SentMessage>()
        val inboundFlow = MutableSharedFlow<RadioInboundMessage>(replay = 16)
        val receiptFlow = MutableSharedFlow<RadioDeliveryReceipt>(replay = 16)

        data class SentMessage(
            val recipientId: String,
            val messageId: String,
            val text: String,
            val sentAtMs: Long,
            val emergency: Boolean,
        )

        override val isGatewayEnabled: Boolean get() = isGatewayEnabledValue
        override val isConnected: Boolean get() = isConnectedValue

        override val connectionStatus: StateFlow<RadioGatewayConnectionStatus>
            get() = MutableStateFlow(
                if (isConnectedValue) RadioGatewayConnectionStatus.CONNECTED else RadioGatewayConnectionStatus.OFF
            ).asStateFlow()

        override fun isRadioReachable(recipientDeviceId: String): Boolean =
            recipientDeviceId in reachablePeers

        override val inboundMessages: SharedFlow<RadioInboundMessage> = inboundFlow.asSharedFlow()
        override val deliveryReceipts: SharedFlow<RadioDeliveryReceipt> = receiptFlow.asSharedFlow()

        override suspend fun sendTextMessage(
            recipientDeviceId: String,
            messageId: String,
            text: String,
            sentAt: Long,
            emergency: Boolean,
        ): RadioSendResult {
            if (!isConnectedValue) return RadioSendResult.NotConnected("radio not connected")
            if (recipientDeviceId !in reachablePeers) return RadioSendResult.RefusedUnreachable("peer unreachable")
            sentMessages += SentMessage(recipientDeviceId, messageId, text, sentAt, emergency)
            return RadioSendResult.SentWaitingAck
        }
    }

    @Test
    fun testCanCarry_onlyReachablePairedPeers() = runTest {
        val port = FakeRadioOutboxPort()
        port.reachablePeers += "peer-radio"

        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { _, _, _, _, _ -> },
            onDeliveryReceipt = { _, _, _ -> },
            onMessageFailed = {},
        )

        assertTrue(bridge.canCarry("peer-radio"))
        assertFalse(bridge.canCarry("peer-lan-only"))
    }

    @Test
    fun testSendDirect_successfulTransmission_setsWaitingNote() = runTest {
        val port = FakeRadioOutboxPort()
        port.reachablePeers += "peer-1"

        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { _, _, _, _, _ -> },
            onDeliveryReceipt = { _, _, _ -> },
            onMessageFailed = {},
        )

        val frame = MessageWireFrame.TextMessage(
            localId = "msg-001",
            conversationId = "conv-1",
            senderId = "me",
            senderName = "Me",
            text = "Hello over radio",
            sentAt = 1000L,
        )

        val sent = bridge.sendDirect("peer-1", frame)
        assertTrue(sent)
        assertEquals(1, port.sentMessages.size)

        val note = bridge.notes.value["msg-001"]
        assertNotNull(note)
        assertEquals(RadioDeliveryNote.STATE_SENT_WAITING, note.statusText)
        assertFalse(note.isDelivered)
        assertFalse(note.isFailure)
    }

    @Test
    fun testSendDirect_tooLong_refusedWithSentence() = runTest {
        val port = FakeRadioOutboxPort()
        port.reachablePeers += "peer-1"

        var failedMessageId: String? = null
        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { _, _, _, _, _ -> },
            onDeliveryReceipt = { _, _, _ -> },
            onMessageFailed = { failedMessageId = it },
        )

        // Exceed 3,008 bytes
        val oversizedText = "A".repeat(3009)
        val frame = MessageWireFrame.TextMessage(
            localId = "msg-oversized",
            conversationId = "conv-1",
            senderId = "me",
            senderName = "Me",
            text = oversizedText,
            sentAt = 2000L,
        )

        val sent = bridge.sendDirect("peer-1", frame)
        assertFalse(sent)
        assertEquals(0, port.sentMessages.size)
        assertEquals("msg-oversized", failedMessageId)

        val note = bridge.notes.value["msg-oversized"]
        assertNotNull(note)
        assertEquals(RadioDeliveryNote.REFUSAL_TOO_LONG, note.statusText)
        assertTrue(note.isFailure)
    }

    @Test
    fun testSendDirect_gatewayOff_queuedWithRadioNotConnected() = runTest {
        val port = FakeRadioOutboxPort()
        port.reachablePeers += "peer-1"
        port.isConnectedValue = false

        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { _, _, _, _, _ -> },
            onDeliveryReceipt = { _, _, _ -> },
            onMessageFailed = {},
        )

        val frame = MessageWireFrame.TextMessage(
            localId = "msg-offline",
            conversationId = "conv-1",
            senderId = "me",
            senderName = "Me",
            text = "Queued message",
            sentAt = 3000L,
        )

        val sent = bridge.sendDirect("peer-1", frame)
        assertFalse(sent)
        assertEquals(0, port.sentMessages.size)

        val note = bridge.notes.value["msg-offline"]
        assertNotNull(note)
        assertEquals(RadioDeliveryNote.STATE_NOT_CONNECTED, note.statusText)
        assertFalse(note.isFailure)
    }

    @Test
    fun testAttachmentRefusal_plainSentence() = runTest {
        val port = FakeRadioOutboxPort()
        port.reachablePeers += "peer-radio"

        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { _, _, _, _, _ -> },
            onDeliveryReceipt = { _, _, _ -> },
            onMessageFailed = {},
        )

        // For radio-reachable peer, attachment is refused with plain sentence
        val refusal = bridge.checkAttachmentRefusal("peer-radio")
        assertEquals("Photos and files cannot be sent by radio", refusal)

        // For non-radio peer, no radio refusal (LAN/Hub transport handles it)
        val noRefusal = bridge.checkAttachmentRefusal("peer-lan")
        assertNull(noRefusal)
    }

    @Test
    fun testDeliveryReceipt_updatesNoteToDelivered() = runTest {
        val port = FakeRadioOutboxPort()
        port.reachablePeers += "peer-1"

        var ackDeliveredId: String? = null
        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { _, _, _, _, _ -> },
            onDeliveryReceipt = { id, _, _ -> ackDeliveredId = id },
            onMessageFailed = {},
        )

        val frame = MessageWireFrame.TextMessage(
            localId = "msg-ack",
            conversationId = "conv-1",
            senderId = "me",
            senderName = "Me",
            text = "Ping",
            sentAt = 4000L,
        )
        bridge.sendDirect("peer-1", frame)

        // Send cryptographic ACK receipt from port
        port.receiptFlow.emit(RadioDeliveryReceipt(messageId = "msg-ack", recipientDeviceId = "peer-1", deliveredAtMs = 4500L))
        testScheduler.runCurrent()

        assertEquals("msg-ack", ackDeliveredId)
        val note = bridge.notes.value["msg-ack"]
        assertNotNull(note)
        assertEquals(RadioDeliveryNote.STATE_DELIVERED, note.statusText)
        assertTrue(note.isDelivered)
        assertFalse(note.isFailure)
    }

    @Test
    fun testInboundMessage_dispatchesToCallback() = runTest {
        val port = FakeRadioOutboxPort()

        var inboundSender: String? = null
        var inboundText: String? = null
        val bridge = RadioChatBridge(
            port = port,
            scope = backgroundScope,
            onInboundMessage = { sender, _, _, text, _ ->
                inboundSender = sender
                inboundText = text
            },
            onDeliveryReceipt = { _, _, _ -> },
            onMessageFailed = {},
        )

        port.inboundFlow.emit(
            RadioInboundMessage(
                senderDeviceId = "sender-peer",
                senderCallsign = "W1AW",
                messageId = "in-100",
                text = "Inbound radio packet",
                sentAtMs = 5000L,
            )
        )
        testScheduler.runCurrent()

        assertEquals("sender-peer", inboundSender)
        assertEquals("Inbound radio packet", inboundText)
    }
}
