@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.time.FlashTimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashRelayClientTest {

    private class FakeTimeSource(var timeMs: Long = 1000L) : FlashTimeSource {
        override fun nowMs(): Long = timeMs
    }

    private class FakeRelayTransport : RelayTransport {
        private val _state = MutableStateFlow(RelaySessionState.DISCONNECTED)
        override val state: StateFlow<RelaySessionState> = _state.asStateFlow()

        private val _incomingFrames = MutableSharedFlow<RelayFrame>(extraBufferCapacity = 64)
        override val incomingFrames: Flow<RelayFrame> = _incomingFrames.asSharedFlow()

        val sentFrames = mutableListOf<RelayFrame>()
        val sentRaw = mutableListOf<String>()

        override suspend fun connect(hubUrl: String, deviceId: String, friendlyName: String, role: String): Boolean {
            _state.value = RelaySessionState.CONNECTED
            _incomingFrames.emit(RelayFrame.Welcome(deviceId = deviceId, status = "CONNECTED", timestamp = 1000L))
            return true
        }

        override suspend fun sendFrame(frame: RelayFrame): Boolean {
            sentFrames.add(frame)
            return true
        }

        override suspend fun sendRaw(rawText: String): Boolean {
            sentRaw.add(rawText)
            return true
        }

        override suspend fun disconnect(reason: String) {
            _state.value = RelaySessionState.DISCONNECTED
        }

        suspend fun emitInbound(frame: RelayFrame) {
            _incomingFrames.emit(frame)
        }

        fun setState(s: RelaySessionState) {
            _state.value = s
        }
    }

    @Test
    fun testSendEnvelopeDispatchesDataFrame() = runTest {
        val transport = FakeRelayTransport()
        transport.setState(RelaySessionState.CONNECTED)
        val timeSource = FakeTimeSource(10000L)

        val client = FlashRelayClient(
            deviceId = "dev-alice-001",
            friendlyName = "Alice",
            hubUrl = "ws://127.0.0.1:8443/flash-relay",
            transport = transport,
            timeSource = timeSource,
        )

        val envelope = FlashEnvelope(
            id = "env-999",
            type = "MSG_TEXT",
            payloadJson = """{"text":"Secret operational update"}""",
            senderId = "dev-alice-001",
            sentAt = 10000L,
        )

        val sent = client.sendEnvelope("dev-bob-002", envelope)
        assertTrue(sent)

        assertEquals(1, transport.sentFrames.size)
        val dataFrame = transport.sentFrames[0] as RelayFrame.Data
        assertEquals("dev-bob-002", dataFrame.recipientId)
        assertEquals("env-999", dataFrame.id)
        assertEquals(envelope.payloadJson, dataFrame.payload)
    }

    @Test
    fun testInboundEnvelopesFlowEmits() = runTest {
        val transport = FakeRelayTransport()
        transport.setState(RelaySessionState.CONNECTED)
        val timeSource = FakeTimeSource(15000L)

        val client = FlashRelayClient(
            deviceId = "dev-alice-001",
            friendlyName = "Alice",
            hubUrl = "ws://127.0.0.1:8443/flash-relay",
            transport = transport,
            timeSource = timeSource,
        )

        val inboundData = RelayFrame.Data(
            recipientId = "dev-alice-001",
            id = "msg-inbound-777",
            type = "MSG_TEXT",
            senderId = "dev-charlie-003",
            sentAt = 15000L,
            payload = """{"text":"Welcome to the team!"}""",
        )

        val receivedList = mutableListOf<FlashEnvelope>()
        val collectJob = launch {
            client.incomingEnvelopes.collect {
                receivedList.add(it)
            }
        }
        testScheduler.runCurrent()

        transport.emitInbound(inboundData)
        testScheduler.runCurrent()

        assertEquals(1, receivedList.size)
        assertEquals("msg-inbound-777", receivedList[0].id)
        assertEquals("dev-charlie-003", receivedList[0].senderId)
        assertEquals("""{"text":"Welcome to the team!"}""", receivedList[0].payloadJson)

        collectJob.cancel()
    }

    @Test
    fun testDeliveryAckFlowEmits() = runTest {
        val transport = FakeRelayTransport()
        transport.setState(RelaySessionState.CONNECTED)

        val client = FlashRelayClient(
            deviceId = "dev-alice-001",
            friendlyName = "Alice",
            hubUrl = "ws://127.0.0.1:8443/flash-relay",
            transport = transport,
        )

        val ack = RelayFrame.Ack(
            id = "msg-888",
            recipientId = "dev-bob-002",
            status = RelayFrame.Ack.STATUS_QUEUED,
            timestamp = 20000L,
        )

        val receivedAcks = mutableListOf<RelayFrame.Ack>()
        val ackJob = launch {
            client.deliveryAcks.collect {
                receivedAcks.add(it)
            }
        }
        testScheduler.runCurrent()

        transport.emitInbound(ack)
        testScheduler.runCurrent()

        assertEquals(1, receivedAcks.size)
        assertEquals("msg-888", receivedAcks[0].id)
        assertTrue(receivedAcks[0].isQueued)

        ackJob.cancel()
    }

    @Test
    fun testSendTypingGeneratesPaddedMetaFrame() = runTest {
        val transport = FakeRelayTransport()
        transport.setState(RelaySessionState.CONNECTED)

        val client = FlashRelayClient(
            deviceId = "dev-alice-001",
            friendlyName = "Alice",
            hubUrl = "ws://127.0.0.1:8443/flash-relay",
            transport = transport,
        )

        val sent = client.sendTyping("conv-eng-daily")
        assertTrue(sent)

        assertEquals(1, transport.sentFrames.size)
        val meta = transport.sentFrames[0] as RelayFrame.Meta
        assertEquals(RelayFrame.Meta.ACTION_TYPING, meta.action)
        assertEquals("conv-eng-daily", meta.conversationId)
        assertEquals("dev-alice-001", meta.senderId)
    }

    @Test
    fun testSendAckDispatchesAckFrame() = runTest {
        val transport = FakeRelayTransport()
        transport.setState(RelaySessionState.CONNECTED)
        val timeSource = FakeTimeSource(25000L)

        val client = FlashRelayClient(
            deviceId = "dev-alice-001",
            friendlyName = "Alice",
            hubUrl = "ws://127.0.0.1:8443/flash-relay",
            transport = transport,
            timeSource = timeSource,
        )

        val sent = client.sendAck("msg-1234", "dev-charlie-003", RelayFrame.Ack.STATUS_DELIVERED)
        assertTrue(sent)

        assertEquals(1, transport.sentFrames.size)
        val ack = transport.sentFrames[0] as RelayFrame.Ack
        assertEquals("msg-1234", ack.id)
        assertEquals("dev-charlie-003", ack.recipientId)
        assertEquals(RelayFrame.Ack.STATUS_DELIVERED, ack.status)
        assertEquals(25000L, ack.timestamp)
    }

    @Test
    fun testRevokedCertificatePreventsSend() = runTest {
        val transport = FakeRelayTransport()
        transport.setState(RelaySessionState.REVOKED)

        val client = FlashRelayClient(
            deviceId = "dev-alice-001",
            friendlyName = "Alice",
            hubUrl = "ws://127.0.0.1:8443/flash-relay",
            transport = transport,
        )

        val envelope = FlashEnvelope(
            id = "env-fail",
            type = "MSG_TEXT",
            payloadJson = "{}",
            senderId = "dev-alice-001",
            sentAt = 1000L,
        )

        val sent = client.sendEnvelope("dev-bob-002", envelope)
        assertFalse(sent, "Send must be refused when session is REVOKED")
    }
}
