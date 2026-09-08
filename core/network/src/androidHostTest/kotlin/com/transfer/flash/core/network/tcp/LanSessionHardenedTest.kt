@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.tcp

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FrameAck
import com.transfer.flash.core.network.FrameAckStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM loopback tests for P4 part 2 stream B (C4.8 delivery ACKs +
 * HeartbeatTracker-driven session hardening).
 *
 * Uses a silent [LanSessionLogger] because android.util.Log is unmocked on
 * the JVM and this module does not enable returnDefaultValues.
 */
class LanSessionHardenedTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var clientSocket: Socket
    private lateinit var peerSocket: Socket
    private var session: LanSession? = null

    private val silentLogger = LanSessionLogger { _, _, _, _ -> }

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = ServerSocket(0)
        val client = Socket()
        client.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
        peerSocket = server.accept()
        server.close()
        clientSocket = client
    }

    @After
    fun tearDown() {
        runCatching { session?.close("test teardown") }
        runCatching { clientSocket.close() }
        runCatching { peerSocket.close() }
        testScope.cancel()
    }

    private fun newSession(
        intervalMs: Long = 10_000,
        threshold: Int = 3,
        onDisconnected: (String) -> Unit = {},
    ): LanSession = newSessionOn(
        socket = clientSocket,
        localId = "local-device",
        localName = "Local",
        peerId = "peer-device",
        peerName = "Peer",
        intervalMs = intervalMs,
        threshold = threshold,
        onDisconnected = onDisconnected,
    )

    /** Builds a session over any socket with mirrored identities (for A/B pairs). */
    private fun newSessionOn(
        socket: Socket,
        localId: String,
        localName: String,
        peerId: String,
        peerName: String,
        intervalMs: Long = 10_000,
        threshold: Int = 3,
        onDisconnected: (String) -> Unit = {},
    ): LanSession {
        val s = LanSession(
            socket = socket,
            reader = BufferedReader(InputStreamReader(socket.getInputStream())),
            writer = PrintWriter(socket.getOutputStream(), true),
            localDeviceId = localId,
            localFriendlyName = localName,
            peerInfo = LanProbeHello(protocolVersion = 1, deviceId = peerId, friendlyName = peerName),
            onDisconnected = { _, reason -> onDisconnected(reason) },
            heartbeatIntervalMs = intervalMs,
            heartbeatMissedThreshold = threshold,
            logger = silentLogger,
        )
        session = s
        return s
    }

    /** Raw peer-side endpoint acting as the remote Flash device. */
    private class TestPeer(private val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val writer = PrintWriter(socket.getOutputStream(), true)
        val inbox = ConcurrentLinkedQueue<String>()
        @Volatile private var running = true

        fun start(handler: (line: String) -> Unit = {}) {
            Thread {
                while (running) {
                    val line = try {
                        reader.readLine()
                    } catch (_: Exception) {
                        break
                    } ?: break
                    inbox.add(line)
                    handler(line)
                }
            }.apply { isDaemon = true }.start()
        }

        fun send(line: String) {
            writer.println(line)
        }

        fun close() {
            running = false
            runCatching { socket.close() }
        }
    }

    private fun collectAcks(flow: Flow<FrameAck>): CopyOnWriteArrayList<FrameAck> {
        val sink = CopyOnWriteArrayList<FrameAck>()
        testScope.launch { flow.collect { sink.add(it) } }
        return sink
    }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    // ------------------------------------------------------------------
    // 1. Every successful send() emits a SocketWritten FrameAck.
    // ------------------------------------------------------------------
    @Test
    fun sendEmitsSocketWrittenAckPerCall() = runBlocking {
        val peer = TestPeer(peerSocket)
        peer.start()
        val s = newSession().also { it.start() }
        val acks = collectAcks(s.frameAcks)
        delay(150)

        val result = s.send("plain-frame".toByteArray(Charsets.UTF_8))

        assertTrue(result is FlashResult.Success)
        assertTrue(awaitTrue(2_000) { acks.isNotEmpty() })
        val ack = acks.single()
        assertEquals(FrameAckStage.SocketWritten, ack.stage)
        assertTrue(ack.frameId.isNotBlank())
        assertTrue(ack.atMs > 0)
    }

    // ------------------------------------------------------------------
    // 2. sendAwaitAck succeeds when the peer echoes FLASH_ACK; the sender
    //    emits PeerAcknowledged and the receiver delivers the payload.
    // ------------------------------------------------------------------
    @Test
    fun sendAwaitAckSucceedsWhenPeerAcks() = runBlocking {
        // Real interop: TWO LanSessions face each other over the loopback
        // pair. B's read loop auto-acks A's FLASH_DATA envelope and delivers
        // the payload on B.incomingFrames.
        val incomingOnB = CopyOnWriteArrayList<String>()
        val sessionB = newSessionOn(
            socket = peerSocket,
            localId = "peer-device",
            localName = "Peer",
            peerId = "local-device",
            peerName = "Local",
        ).also { it.start() }
        testScope.launch { sessionB.incomingFrames.collect { incomingOnB.add(it) } }

        val s = newSession().also { it.start() }
        val acks = collectAcks(s.frameAcks)
        delay(150)

        val result = s.sendAwaitAck("acked-hello".toByteArray(Charsets.UTF_8), timeoutMs = 3_000)

        assertTrue("expected Success, got $result", result is FlashResult.Success)
        assertTrue(awaitTrue(2_000) { acks.any { it.stage == FrameAckStage.PeerAcknowledged } })
        val peerAck = acks.first { it.stage == FrameAckStage.PeerAcknowledged }
        assertTrue(peerAck.atMs > 0)
        assertTrue("payload should reach receiver via incomingFrames", awaitTrue(2_000) { incomingOnB.contains("acked-hello") })
        sessionB.close("test done")
    }

    // ------------------------------------------------------------------
    // 3. ACK timeout => Failure(ConnectionTimeout) while the session stays
    //    Connected and remains usable.
    // ------------------------------------------------------------------
    @Test
    fun sendAwaitAckTimesOutWithoutClosingSession() = runBlocking {
        val peer = TestPeer(peerSocket)
        peer.start() // consumes lines, never acknowledges
        val s = newSession().also { it.start() }
        delay(150)

        val result = s.sendAwaitAck("no-answer".toByteArray(Charsets.UTF_8), timeoutMs = 200)

        assertTrue(result is FlashResult.Failure)
        assertTrue("expected ConnectionTimeout, got ${(result as FlashResult.Failure).error}", result.error is FlashError.ConnectionTimeout)
        assertEquals(FlashConnectionState.Connected, s.connectionState.value)

        val followUp = s.send("still-alive".toByteArray(Charsets.UTF_8))
        assertTrue(followUp is FlashResult.Success)
        assertTrue(awaitTrue(2_000) { peer.inbox.contains("still-alive") })
    }

    // ------------------------------------------------------------------
    // 4. Three missed heartbeats (silent peer) declare the peer dead and
    //    close the session with "heartbeat timeout" (~interval x threshold).
    // ------------------------------------------------------------------
    @Test
    fun threeMissedHeartbeatsCloseSessionWithHeartbeatTimeout() = runBlocking {
        val disconnected = CountDownLatch(1)
        val reasons = CopyOnWriteArrayList<String>()
        val peer = TestPeer(peerSocket)
        peer.start() // stays completely silent: no pong, no ack
        val s = newSession(intervalMs = 60, threshold = 3) { reason ->
            reasons.add(reason)
            disconnected.countDown()
        }
        s.start()

        val observed = disconnected.await(3, TimeUnit.SECONDS)

        assertTrue("session was not closed within 3s", observed)
        assertTrue("reason=${reasons}", reasons.any { it.contains("heartbeat") })
        assertEquals(FlashConnectionState.Disconnected, s.connectionState.value)
    }

    // ------------------------------------------------------------------
    // 5. Duplicate FLASH_ACK lines fire exactly one PeerAcknowledged per
    //    frameId and complete the waiter exactly once.
    // ------------------------------------------------------------------
    @Test
    fun duplicateAcksDoNotDoubleFirePeerAcknowledged() = runBlocking {
        val dataHeaders = ConcurrentLinkedQueue<LanProbeData>()
        val peer = TestPeer(peerSocket)
        peer.start { line ->
            val header = LanProbeMessages.parseData(line) ?: return@start
            dataHeaders.add(header)
            // Deliberately echo the SAME ack twice.
            peer.send(LanProbeMessages.ack(1, "peer-device", "Peer", header.frameId))
            peer.send(LanProbeMessages.ack(1, "peer-device", "Peer", header.frameId))
        }
        val s = newSession().also { it.start() }
        val acks = collectAcks(s.frameAcks)
        delay(150)

        val deferred = testScope.async { s.sendAwaitAck("dup-test".toByteArray(Charsets.UTF_8), timeoutMs = 4_000) }
        assertTrue(awaitTrue(2_000) { !dataHeaders.isEmpty() })
        val result = deferred.await()

        assertTrue("expected Success, got $result", result is FlashResult.Success)
        val frameId = dataHeaders.element().frameId
        assertTrue(awaitTrue(1_000) { acks.any { it.frameId == frameId && it.stage == FrameAckStage.PeerAcknowledged } })
        delay(300) // let the duplicate ACK line be processed before counting
        assertEquals(1, acks.count { it.frameId == frameId && it.stage == FrameAckStage.PeerAcknowledged })
        peer.close()
    }
}
