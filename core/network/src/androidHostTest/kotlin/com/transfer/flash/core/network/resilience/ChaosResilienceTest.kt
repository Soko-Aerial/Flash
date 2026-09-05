package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal deterministic delegate session for the chaos harness.
 */
private class FakeDelegateSession(
    override val peer: FlashDevice = FlashDevice(
        id = FlashDeviceId("chaos-peer"),
        friendlyName = "Chaos Peer",
        transportType = FlashTransportType.LAN,
        presence = FlashPeerPresence.Online,
    ),
    override val transportType: FlashTransportType = FlashTransportType.LAN,
) : FlashSession {

    private val _connectionState = MutableStateFlow(FlashConnectionState.Connected)
    override val connectionState: StateFlow<FlashConnectionState> = _connectionState.asStateFlow()

    val sentPayloads = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
    val sendCount = AtomicInteger(0)
    @Volatile var closed = false

    override suspend fun send(message: ByteArray): FlashResult<Unit> {
        if (closed) return FlashResult.Failure(
            com.transfer.flash.core.common.result.FlashError.PeerUnavailable(peerDeviceId.value, "closed"),
        )
        sentPayloads.add(message)
        sendCount.incrementAndGet()
        return FlashResult.Success(Unit)
    }

    override fun disconnect(reason: String) {
        closed = true
        _connectionState.value = FlashConnectionState.Disconnected
    }
}

class ChaosResilienceTest {

    // ------------------------------------------------------------------
    // Invariant (i): idempotent redelivery tolerated
    // ------------------------------------------------------------------

    @Test
    fun `duplicated and reordered frames dedup by frameId`() {
        val delegate = FakeDelegateSession()
        val chaos = ChaosSession(
            delegate,
            ChaosConfig(inboundDuplicateProbability = 1.0, reorderWindow = 4),
            kotlin.random.Random(99),
        )
        val harness = ChaosNetworkHarness(delegate, chaos)

        repeat(20) { i -> chaos.deliverInbound("frame-$i", byteArrayOf(i.toByte()), nowMs = i * 10L) }
        chaos.flushReorderBuffer(nowMs = 100_000)

        assertEquals(40, harness.receivedFrames.size) // every frame delivered twice
        assertEquals(20, harness.uniqueReceivedFrameIds().size)

        // Consumer-side gate tolerates the redelivery storm:
        var acceptedFirstTime = 0
        harness.receivedFrames.forEach { if (harness.dedupGate.observe(it.frameId)) acceptedFirstTime++ }
        assertEquals(20, acceptedFirstTime)
    }

    @Test
    fun `dropped frames are silently absent - sender-side outbox is the recovery path`() {
        val delegate = FakeDelegateSession()
        val chaos = ChaosSession(delegate, ChaosConfig(inboundDropProbability = 1.0), kotlin.random.Random(5))
        val harness = ChaosNetworkHarness(delegate, chaos)

        repeat(10) { i -> chaos.deliverInbound("f$i", ByteArray(0), nowMs = i.toLong()) }
        assertTrue(harness.receivedFrames.isEmpty())
    }

    // ------------------------------------------------------------------
    // Invariant (ii): no deadlock under queue-full storm
    // ------------------------------------------------------------------

    @Test
    fun `queue-full storm rejects without deadlock and drains fully`() = runBlocking {
        val delegate = FakeDelegateSession()
        val harness = ChaosNetworkHarness(delegate, ChaosSession(delegate, ChaosConfig(), kotlin.random.Random(3)))

        val capacity = BoundedSendQueue.DEFAULT_CAPACITY
        var rejections = 0
        repeat(capacity) { i ->
            val r = harness.enqueueOutbound("q$i", byteArrayOf(i.toByte()))
            assertTrue(r is EnqueueResult.Enqueued)
        }
        repeat(500) { i ->
            val r = harness.enqueueOutbound("overflow-$i", ByteArray(0))
            if (r is EnqueueResult.Rejected) rejections++
        }
        // Every overflow attempt is rejected fail-fast (reject-newest policy).
        assertEquals(500, rejections)

        val (sent, rejectedSends) = harness.drainOutbound()
        assertEquals(capacity, sent)
        assertEquals(0, rejectedSends)
        assertEquals(0, harness.sendQueue.size)
        assertEquals(capacity, delegate.sendCount.get())
    }

    @Test
    fun `threaded queue-full storm completes under watchdog timeout`() {
        val delegate = FakeDelegateSession()
        val harness = ChaosNetworkHarness(delegate, ChaosConfig(), seed = 11L)
        val producers = 4
        val perProducer = 300
        val pool = Executors.newFixedThreadPool(producers + 1)
        val done = CountDownLatch(producers)
        val enqueuedTotal = AtomicInteger(0)
        val rejectedTotal = AtomicInteger(0)

        // Watchdog: fail the test process-wide assertion below via latch time.
        repeat(producers) { p ->
            pool.execute {
                var n = 0
                while (n < perProducer) {
                    when (val r = harness.enqueueOutbound("p$p-$n", byteArrayOf(n.toByte()))) {
                        is EnqueueResult.Enqueued -> { n++; enqueuedTotal.incrementAndGet() }
                        is EnqueueResult.Rejected -> rejectedTotal.incrementAndGet() // backpressure observed
                    }
                }
                done.countDown()
            }
        }

        // Concurrent consumer draining through the session.
        val consumerFuture = pool.submit<Int> {
            runBlocking {
                while (done.count > 0 || harness.sendQueue.size > 0) {
                    harness.drainOutbound(maxItems = 8)
                    Thread.yield()
                }
            }
            1
        }

        assertTrue("storm deadlocked", done.await(15, TimeUnit.SECONDS))
        consumerFuture.get(10, TimeUnit.SECONDS)

        // Every accepted write was eventually handed to the session exactly once.
        assertEquals(enqueuedTotal.get(), delegate.sendCount.get())
        assertTrue(rejectedTotal.get() > 0) // the storm actually hit the boundary

        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------
    // Invariant (iii): Dead declared after missedThreshold without pongs
    // ------------------------------------------------------------------

    @Test
    fun `dead declared after threshold of missed pongs on explicit ticks`() {
        val delegate = FakeDelegateSession()
        val harness = ChaosNetworkHarness(delegate, ChaosConfig(), seed = 21L)

        harness.pongReceived(0)
        assertEquals(HeartbeatAction.PingNow, harness.tickHeartbeat(10_000))
        harness.pingSent(10_000)

        var action: HeartbeatAction = HeartbeatAction.AwaitPong
        val expectedDeadAt = 10_000L + 3 * 10_000L
        var now = 20_000L
        while (now <= expectedDeadAt) {
            action = harness.tickHeartbeat(now)
            if (action == HeartbeatAction.PingNow) harness.pingSent(now)
            if (action == HeartbeatAction.DeclareDead) break
            now += 10_000L
        }
        assertEquals(HeartbeatAction.DeclareDead, action)
        assertEquals(HeartbeatState.Dead, harness.heartbeat.state)

        // Dead peer ⇒ owner tears the session down cleanly (upgrade 3).
        harness.forceDisconnect("heartbeat-dead")
        assertTrue(harness.chaos.disconnectEvents.contains("heartbeat-dead"))
        assertTrue(harness.chaos.isLinkBroken)
    }

    // ------------------------------------------------------------------
    // Invariant (iv): reconnect policy resets after stable connect
    // ------------------------------------------------------------------

    @Test
    fun `reconnect delays reset to base range after stable connect`() {
        val delegate = FakeDelegateSession()
        val policy = ReconnectPolicy(random01 = { 0.5 })
        val harness = ChaosNetworkHarness(
            delegate,
            ChaosSession(delegate, ChaosConfig(), kotlin.random.Random(31)),
            reconnectPolicy = policy,
        )

        val firstEpisode = mutableListOf<Long>()
        repeat(4) { firstEpisode.add(harness.nextReconnectDelay()) }
        // Monotone non-decreasing growth toward cap under constant random draw.
        assertEquals(firstEpisode.sorted(), firstEpisode)
        assertTrue(firstEpisode.last() > firstEpisode.first())

        harness.simulateStableConnect(nowMs = 50_000)

        val secondEpisode = mutableListOf<Long>()
        repeat(4) { secondEpisode.add(harness.nextReconnectDelay()) }
        assertEquals(firstEpisode, secondEpisode) // attempt counter restarted at 0
        assertEquals(1000L, secondEpisode.first()) // base again
    }

    // ------------------------------------------------------------------
    // Hard-disconnect mid-drain: outbox retention inside the harness
    // ------------------------------------------------------------------

    @Test
    fun `hard disconnect mid-drain requeues frames and typed failure surfaces`() = runBlocking {
        val delegate = FakeDelegateSession()
        val chaos = ChaosSession(
            delegate,
            ChaosConfig(outboundDisconnectProbability = 1.0),
            kotlin.random.Random(77),
        )
        val harness = ChaosNetworkHarness(delegate, chaos)

        harness.enqueueOutbound("a", byteArrayOf(1))
        harness.enqueueOutbound("b", byteArrayOf(2))

        val (sent, rejected) = harness.drainOutbound()
        assertEquals(0, sent)
        // First send triggers the hard disconnect; the drain loop then stops
        // (link broken) with BOTH frames still retained in the queue.
        assertEquals(1, rejected)
        assertTrue(harness.chaos.disconnectEvents.contains("outbound-fault"))

        harness.simulateStableConnect() // peer recovered / reconnected
        val drained = ArrayList<String>()
        harness.sendQueue.drainInto { drained.add(it.frameId) }
        assertEquals(listOf("a", "b"), drained) // FIFO preserved through the outage
    }
}
