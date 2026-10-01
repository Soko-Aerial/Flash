package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class WsConnectionTest {

    @Test
    fun `close terminates connection promptly and notifies listener`() {
        val server = ServerSocket(0)
        val clientSocket = Socket("127.0.0.1", server.localPort)
        val serverSideSocket = server.accept()

        val closedLatch = CountDownLatch(1)
        val closedReason = AtomicReference<String>()
        val listener = object : WsConnection.Listener {
            override fun onTextMessage(connection: WsConnection, text: String) {}
            override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {}
            override fun onConnectionClosed(connection: WsConnection, reason: String) {
                closedReason.set(reason)
                closedLatch.countDown()
            }
        }

        val conn = WsConnection(
            socket = clientSocket,
            maskOutboundFrames = true,
            remoteLabel = "test",
            listener = listener,
        )

        assertTrue(conn.isOpen)
        conn.close("Normal closure")
        assertFalse(conn.isOpen)

        assertTrue(closedLatch.await(2, TimeUnit.SECONDS), "onConnectionClosed must be called promptly")
        assertEquals("Normal closure", closedReason.get())

        serverSideSocket.close()
        server.close()
    }

    @Test
    fun `close does not hang indefinitely and closes socket within timeout`() {
        val server = ServerSocket(0)
        val clientSocket = Socket("127.0.0.1", server.localPort)
        val serverSideSocket = server.accept()

        val listener = object : WsConnection.Listener {
            override fun onTextMessage(connection: WsConnection, text: String) {}
            override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {}
            override fun onConnectionClosed(connection: WsConnection, reason: String) {}
        }

        val conn = WsConnection(
            socket = clientSocket,
            maskOutboundFrames = true,
            remoteLabel = "test-contention",
            listener = listener,
        )

        // Close from caller
        val closeStarted = System.currentTimeMillis()
        conn.close("Forced close")
        val closeDuration = System.currentTimeMillis() - closeStarted

        // close() must return immediately without blocking caller thread
        assertTrue(closeDuration < 500, "close() must be non-blocking, took ${closeDuration}ms")

        // Underlying socket must be closed by the background scope
        val socketClosedLatch = CountDownLatch(1)
        Thread {
            while (!clientSocket.isClosed) {
                Thread.sleep(20)
            }
            socketClosedLatch.countDown()
        }.start()

        assertTrue(socketClosedLatch.await(2, TimeUnit.SECONDS), "Underlying socket must be closed")

        serverSideSocket.close()
        server.close()
    }
}
