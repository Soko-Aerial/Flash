@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.protocol.FlashTextFraming
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayFrameRouterTest {

    private class FakeWebSocketSession(
        override val coroutineContext: CoroutineContext = Job(),
    ) : WebSocketSession {
        val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
        val outgoingChannel = Channel<Frame>(Channel.UNLIMITED)
        val isClosed = AtomicBoolean(false)

        override val incoming: ReceiveChannel<Frame> = incomingChannel
        override val outgoing: SendChannel<Frame> = outgoingChannel
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override var masking: Boolean = false

        override suspend fun flush() {}
        @Deprecated("Use cancel instead")
        override fun terminate() {
            isClosed.set(true)
            incomingChannel.close()
            outgoingChannel.close()
        }
    }

    private class RecordingMailboxSeam : MailboxQueueSeam {
        val enqueued = mutableListOf<Pair<String, FlashEnvelope>>()
        override suspend fun enqueue(recipientId: String, envelope: FlashEnvelope): Boolean {
            enqueued.add(recipientId to envelope)
            return true
        }
    }

    private fun createSession(deviceId: String): Pair<RelaySession, FakeWebSocketSession> {
        val fakeWs = FakeWebSocketSession()
        val session = RelaySession(
            deviceId = deviceId,
            friendlyName = "Device-$deviceId",
            remoteAddress = "127.0.0.1:40000",
            websocketSession = fakeWs,
        )
        return session to fakeWs
    }

    @Test
    fun testParseRelayMessageLineBased() {
        val pool = RelayConnectionPool()
        val router = RelayFrameRouter(pool)

        val wireText = FlashTextFraming.encodeFields(
            "FLASH_RELAY",
            "recipient" to "device-bob",
            "id" to "msg-12345",
            "type" to "MSG_TEXT",
            "sender" to "device-alice",
            "sentAt" to "1728670000000",
            "payload" to "{\"text\":\"Hello Bob\"}",
        )

        val parsed = router.parseRelayMessage(wireText)
        assertNotNull(parsed)
        val (recipientId, envelope) = parsed
        assertEquals("device-bob", recipientId)
        assertEquals("msg-12345", envelope.id)
        assertEquals("MSG_TEXT", envelope.type)
        assertEquals("device-alice", envelope.senderId)
        assertEquals(1728670000000L, envelope.sentAt)
        assertEquals("{\"text\":\"Hello Bob\"}", envelope.payloadJson)
    }

    @Test
    fun testDirectDispatchWhenRecipientOnline() = runTest {
        val pool = RelayConnectionPool()
        val router = RelayFrameRouter(pool)

        val (aliceSession, aliceWs) = createSession("alice")
        val (bobSession, bobWs) = createSession("bob")

        pool.register(aliceSession)
        pool.register(bobSession)

        val wireText = FlashTextFraming.encodeFields(
            "FLASH_RELAY",
            "recipient" to "bob",
            "id" to "msg-101",
            "type" to "MSG_TEXT",
            "sender" to "alice",
            "sentAt" to "1728670000000",
            "payload" to "{\"text\":\"Hi Bob\"}",
        )

        router.processFrame(aliceSession, wireText)

        // Bob should receive the relay frame
        val bobReceived = bobWs.outgoingChannel.receive() as Frame.Text
        assertTrue(bobReceived.readText().contains("FLASH_RELAY"))
        assertTrue(bobReceived.readText().contains("id=msg-101"))

        // Alice should receive the delivery ACK
        val aliceReceived = aliceWs.outgoingChannel.receive() as Frame.Text
        val ackFields = FlashTextFraming.parseFields(aliceReceived.readText(), "FLASH_ACK")
        assertNotNull(ackFields)
        assertEquals("msg-101", ackFields["id"])
        assertEquals("bob", ackFields["recipient"])
        assertEquals(RelayFrameRouter.STATUS_DELIVERED, ackFields["status"])
    }

    @Test
    fun testOfflineFallbackToMailbox() = runTest {
        val pool = RelayConnectionPool()
        val mailbox = RecordingMailboxSeam()
        val router = RelayFrameRouter(pool, mailboxQueue = mailbox)

        val (aliceSession, aliceWs) = createSession("alice")
        pool.register(aliceSession)

        // Bob is offline (not in pool)
        val wireText = FlashTextFraming.encodeFields(
            "FLASH_RELAY",
            "recipient" to "bob-offline",
            "id" to "msg-202",
            "type" to "MSG_TEXT",
            "sender" to "alice",
            "sentAt" to "1728670000000",
            "payload" to "{\"text\":\"Offline message\"}",
        )

        router.processFrame(aliceSession, wireText)

        // Mailbox should have received the message
        assertEquals(1, mailbox.enqueued.size)
        val (recipientId, envelope) = mailbox.enqueued.first()
        assertEquals("bob-offline", recipientId)
        assertEquals("msg-202", envelope.id)

        // Alice should receive QUEUED ACK
        val aliceReceived = aliceWs.outgoingChannel.receive() as Frame.Text
        val ackFields = FlashTextFraming.parseFields(aliceReceived.readText(), "FLASH_ACK")
        assertNotNull(ackFields)
        assertEquals("msg-202", ackFields["id"])
        assertEquals("bob-offline", ackFields["recipient"])
        assertEquals(RelayFrameRouter.STATUS_QUEUED, ackFields["status"])
    }

    @Test
    fun testPingPong() = runTest {
        val pool = RelayConnectionPool()
        val router = RelayFrameRouter(pool)

        val (aliceSession, aliceWs) = createSession("alice")
        pool.register(aliceSession)

        router.processFrame(aliceSession, "FLASH_PING")

        val response = aliceWs.outgoingChannel.receive() as Frame.Text
        val pongFields = FlashTextFraming.parseFields(response.readText(), "FLASH_PONG")
        assertNotNull(pongFields)
        assertNotNull(pongFields["timestamp"])
    }
}
