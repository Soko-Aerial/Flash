@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.utils.io.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedBodySocketTest {

    @Test
    fun testBoundedTextSocketBehaviorOnEarlyExit() = runBlocking {
        val serverSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort

        val serverAcceptedCount = java.util.concurrent.atomic.AtomicInteger(0)

        val serverJob = launch(Dispatchers.IO) {
            try {
                while (true) {
                    val clientSocket = serverSocket.accept()
                    serverAcceptedCount.incrementAndGet()
                    launch(Dispatchers.IO) {
                        try {
                            val input = clientSocket.getInputStream()
                            val output = clientSocket.getOutputStream()
                            val buffer = ByteArray(1024)
                            val read = input.read(buffer)
                            if (read > 0) {
                                val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 50000\r\nConnection: keep-alive\r\n\r\n"
                                output.write(header.toByteArray())
                                output.flush()
                                val chunk = ByteArray(1000) { 'A'.code.toByte() }
                                for (i in 0 until 50) {
                                    output.write(chunk)
                                    output.flush()
                                }
                                // Expect client to close/cancel rather than reading remaining bytes
                                input.read()
                            }
                        } catch (_: Exception) {
                            // client closed / reset connection
                        } finally {
                            try { clientSocket.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val client = HttpClient(CIO)

        try {
            val response1 = client.get("http://127.0.0.1:$port/stream")
            val text = response1.boundedText(1000)
            assertEquals(1001, text.length)

            // Second request verifies the client can continue making requests without socket deadlock
            val response2 = client.get("http://127.0.0.1:$port/stream")
            val text2 = response2.boundedText(1000)
            assertEquals(1001, text2.length)
            assertEquals(2, serverAcceptedCount.get())
        } finally {
            client.close()
            serverSocket.close()
            serverJob.cancel()
        }
    }
}
