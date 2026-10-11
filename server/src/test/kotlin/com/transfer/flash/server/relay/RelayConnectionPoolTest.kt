package com.transfer.flash.server.relay

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayConnectionPoolTest {

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

    private fun createSession(deviceId: String, name: String = "Device-$deviceId"): Pair<RelaySession, FakeWebSocketSession> {
        val fakeWs = FakeWebSocketSession()
        val session = RelaySession(
            deviceId = deviceId,
            friendlyName = name,
            remoteAddress = "127.0.0.1:50000",
            websocketSession = fakeWs,
        )
        return session to fakeWs
    }

    @Test
    fun testRegisterAndGet() = runTest {
        val pool = RelayConnectionPool()
        assertEquals(0, pool.activeCount)

        val (session1, _) = createSession("dev-1", "Alice")
        val displaced = pool.register(session1)

        assertNull(displaced)
        assertEquals(1, pool.activeCount)
        assertTrue(pool.isConnected("dev-1"))
        assertEquals("Alice", pool.get("dev-1")?.friendlyName)
    }

    @Test
    fun testUnregister() = runTest {
        val pool = RelayConnectionPool()
        val (session1, fakeWs) = createSession("dev-1")
        pool.register(session1)

        val unregistered = pool.unregister("dev-1")
        assertNotNull(unregistered)
        assertEquals("dev-1", unregistered.deviceId)
        assertEquals(0, pool.activeCount)
        assertFalse(pool.isConnected("dev-1"))
        assertNull(pool.get("dev-1"))
    }

    @Test
    fun testDuplicateSessionDisplacement() = runTest {
        val pool = RelayConnectionPool()
        val (sessionA, fakeWsA) = createSession("dev-duplicate", "Device-Old")
        val (sessionB, fakeWsB) = createSession("dev-duplicate", "Device-New")

        val displacedFirst = pool.register(sessionA)
        assertNull(displacedFirst)
        assertEquals(1, pool.activeCount)
        assertEquals("Device-Old", pool.get("dev-duplicate")?.friendlyName)

        // Register second session with same deviceId
        val displacedSecond = pool.register(sessionB)
        assertNotNull(displacedSecond)
        assertEquals("Device-Old", displacedSecond.friendlyName)
        assertEquals(1, pool.activeCount)
        assertEquals("Device-New", pool.get("dev-duplicate")?.friendlyName)
    }

    @Test
    fun testBroadcast() = runTest {
        val pool = RelayConnectionPool()
        val (session1, fakeWs1) = createSession("dev-1", "Alice")
        val (session2, fakeWs2) = createSession("dev-2", "Bob")
        val (session3, fakeWs3) = createSession("dev-3", "Charlie")

        pool.register(session1)
        pool.register(session2)
        pool.register(session3)

        assertEquals(3, pool.activeCount)

        // Broadcast to all
        val delivered = pool.broadcastText("FLASH_ANNOUNCEMENT text=Hello")
        assertEquals(3, delivered)

        val frame1 = fakeWs1.outgoingChannel.receive() as Frame.Text
        assertEquals("FLASH_ANNOUNCEMENT text=Hello", frame1.readText())

        val frame2 = fakeWs2.outgoingChannel.receive() as Frame.Text
        assertEquals("FLASH_ANNOUNCEMENT text=Hello", frame2.readText())

        val frame3 = fakeWs3.outgoingChannel.receive() as Frame.Text
        assertEquals("FLASH_ANNOUNCEMENT text=Hello", frame3.readText())

        // Broadcast with filter (exclude dev-2)
        val filteredCount = pool.broadcastText("FLASH_PING", filter = { it.deviceId != "dev-2" })
        assertEquals(2, filteredCount)

        val frame1Second = fakeWs1.outgoingChannel.receive() as Frame.Text
        assertEquals("FLASH_PING", frame1Second.readText())

        val frameCharlie = fakeWs3.outgoingChannel.receive() as Frame.Text
        assertEquals("FLASH_PING", frameCharlie.readText())
    }
}
