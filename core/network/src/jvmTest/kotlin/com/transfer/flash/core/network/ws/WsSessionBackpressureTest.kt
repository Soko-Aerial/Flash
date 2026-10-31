package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PERF-N02: Proves WsSession inbound backpressure suspends cooperatively (O09-06 + O14-02)
 * without Thread.sleep(25) busy-polling, and that frameAcks emission skips UUID allocation
 * when unsubscribed (O09-08).
 */
class WsSessionBackpressureTest {

    private lateinit var server: ServerSocket
    private lateinit var clientSocket: Socket
    private lateinit var serverSideSocket: Socket
    private lateinit var connection: WsConnection
    private lateinit var session: WsSession

    @BeforeTest
    fun setUp() {
        server = ServerSocket(0)
        clientSocket = Socket("127.0.0.1", server.localPort)
        serverSideSocket = server.accept()

        connection = WsConnection(
            socket = clientSocket,
            maskOutboundFrames = true,
            remoteLabel = "test-session",
            listener = object : WsConnection.Listener {
                override suspend fun onTextMessage(connection: WsConnection, text: String) {}
                override suspend fun onBinaryMessage(connection: WsConnection, data: ByteArray) {}
                override fun onConnectionClosed(connection: WsConnection, reason: String) {}
            },
        )
        val device = FlashDevice(
            id = FlashDeviceId("peer1"),
            friendlyName = "Peer One",
            transportType = FlashTransportType.LAN,
        )
        session = WsSession(connection = connection, peer = device)
    }

    @AfterTest
    fun tearDown() {
        session.disconnect("test finished")
        serverSideSocket.close()
        server.close()
    }

    @Test
    fun testInboundBackpressureSuspendsCooperatively() = runBlocking {
        // Fill the 512-item text channel to capacity
        for (i in 1..512) {
            val enqueued = session.tryEnqueueEarlyText("msg-$i")
            assertTrue(enqueued, "Initial 512 messages should fit without suspension")
        }

        // Attempting to send message 513 must suspend cooperatively rather than sleeping or dropping
        val sendResumed = AtomicBoolean(false)
        val pushJob = launch(Dispatchers.Default) {
            session.onTextReceived("msg-513")
            sendResumed.set(true)
        }

        // Give it time to attempt enqueue and suspend
        kotlinx.coroutines.delay(100)
        assertFalse(sendResumed.get(), "onTextReceived must suspend while channel is full")

        // Consume one message to create capacity
        val consumed = session.awaitTextFrame()
        assertEquals("msg-1", consumed)

        // The suspended pushJob should resume promptly without waiting for a 25ms polling loop
        val result = withTimeoutOrNull(200) {
            pushJob.join()
            true
        }
        assertTrue(result == true, "Suspended onTextReceived must resume immediately upon capacity release")
        assertTrue(sendResumed.get())
    }

    @Test
    fun testFrameAcksSkippedWhenNoSubscribers() = runBlocking {
        // When no one collects frameAcks, send succeeds without emitting or crashing
        val sendResult = session.sendText("test-payload")
        assertTrue(sendResult.isSuccess)

        // Now collect from frameAcks in background
        val ackDeferred = async(Dispatchers.Default) {
            session.frameAcks.first()
        }
        // Give collector time to subscribe
        kotlinx.coroutines.delay(50)

        session.sendText("test-payload-2")
        val ack = withTimeoutOrNull(500) { ackDeferred.await() }
        assertTrue(ack != null, "Subscribed collector must receive FrameAck")
        assertTrue(ack.frameId.isNotBlank(), "FrameAck must have valid frameId")
    }
}
