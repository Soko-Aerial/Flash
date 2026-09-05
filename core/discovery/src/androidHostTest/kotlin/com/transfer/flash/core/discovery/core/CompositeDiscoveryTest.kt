package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Synchronous dispatcher: every dispatch runs inline on the caller thread, so
 * emissions into [FakeTransport] flows deterministically drive the composite's
 * handlers without kotlinx-coroutines-test virtual time (which is absent from
 * this module's dependencies).
 */
private object DirectDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

/** Test double: emits into a controllable SharedFlow and records calls. */
private class FakeTransport(override val transportName: String) : FlashRadioTransport {
    val outgoing = MutableSharedFlow<FlashTransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<FlashTransportEvent> get() = outgoing

    val calls = mutableListOf<String>()
    var advertiseResult: FlashResult<Unit> = FlashResult.Success(Unit)
    var browseResult: FlashResult<Unit> = FlashResult.Success(Unit)
    var stopResult: FlashResult<Unit> = FlashResult.Success(Unit)
    var lastIdentity: FlashAdvertisedIdentity? = null
    val policies = mutableListOf<DiscoveryModePolicy>()

    override suspend fun setMode(policy: DiscoveryModePolicy) {
        policies += policy
    }

    override suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit> {
        calls += "advertise:$port"
        lastIdentity = identity
        return advertiseResult
    }

    override suspend fun startBrowsing(): FlashResult<Unit> {
        calls += "browse"
        return browseResult
    }

    override suspend fun restartBrowsing(): FlashResult<Unit> {
        calls += "restart"
        return browseResult
    }

    override suspend fun stop(): FlashResult<Unit> {
        calls += "stop"
        return stopResult
    }

    fun found(endpoint: FlashDiscoveredEndpoint) {
        check(outgoing.tryEmit(FlashTransportEvent.Found(endpoint)))
    }

    /** Liveness heartbeat: same peer, nothing changed (see FlashTransportEvent.Presence). */
    fun presence(endpoint: FlashDiscoveredEndpoint) {
        check(outgoing.tryEmit(FlashTransportEvent.Presence(endpoint)))
    }

    fun stateChanged(browsing: Boolean, message: String = "") {
        check(outgoing.tryEmit(FlashTransportEvent.StateChanged(browsing, message)))
    }

    fun lost(deviceId: String, serviceName: String? = null) {
        check(outgoing.tryEmit(FlashTransportEvent.Lost(FlashDeviceId(deviceId), serviceName)))
    }
}

/**
 * Hand-cranked sweeper pacing: the composite's `delayFn` parks on a rendezvous
 * channel, so [tick] runs EXACTLY one sweeper iteration (sweep + browse
 * watchdog) synchronously on the caller's thread.
 */
private class TickGate {
    private val gate = Channel<Unit>(Channel.RENDEZVOUS)
    val delayFn: suspend (Long) -> Unit = { _ -> gate.receive() }

    fun tick() = runBlocking { gate.send(Unit) }
}

private fun endpointOf(
    id: String,
    host: String = "10.0.0.${Math.abs(id.hashCode()) % 250 + 1}",
    port: Int = 40_000,
    serviceName: String = "svc-$id",
    friendlyName: String = "Phone $id",
    transportType: FlashTransportType = FlashTransportType.LAN,
    protocolVersion: Int = 1,
) = FlashDiscoveredEndpoint(
    device = FlashDevice(
        id = FlashDeviceId(id),
        friendlyName = friendlyName,
        transportType = transportType,
        protocolVersion = protocolVersion,
    ),
    hostAddress = host,
    port = port,
    serviceName = serviceName,
)

private val identity = FlashAdvertisedIdentity(
    deviceId = FlashDeviceId("self"),
    friendlyName = "Self",
    deviceModel = "Test Model",
    protocolVersion = 1,
)

class CompositeDiscoveryTest {

    private lateinit var harness: Harness

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.close()
    }

    private class Harness(vararg names: String) {
        val transports = names.map { FakeTransport(it) }
        var now = 0L
            private set
        val scope = CoroutineScope(SupervisorJob() + DirectDispatcher)
        val composite = CompositeDiscovery(
            transports = transports,
            directoryFactory = { StandardEndpointDirectory() },
            scopeFactory = { scope },
            clock = { now },
        )

        val events = mutableListOf<FlashTransportEvent>()
        private val eventCollector = scope.launch {
            composite.mergedEvents.collect { events += it }
        }

        // Name-based lookups: positional first/last silently cross wires when a
        // test lists transport names in non-standard order.
        val lan: FakeTransport get() = transports.first { it.transportName == "LAN" }
        val wifiDirect: FakeTransport get() = transports.first { it.transportName == "WIFI_DIRECT" }

        fun advance(ms: Long) {
            now += ms
        }

        fun close() {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Cross-transport dedup + loss hysteresis
    // ------------------------------------------------------------------

    @Test
    fun dedup_sameDeviceOnTwoRadios_singleEndpointWithHigherPriorityData() = runBlocking {
        harness = Harness("WIFI_DIRECT", "LAN")
        harness.composite.startAll(40_000, identity)
        harness.advance(100)

        harness.wifiDirect.found(endpointOf("d1", host = "192.168.49.1", transportType = FlashTransportType.WIFI_DIRECT))
        harness.lan.found(endpointOf("d1", host = "10.0.0.2", transportType = FlashTransportType.LAN))

        val endpoints = harness.composite.discoveredEndpoints.value
        assertEquals(1, endpoints.size)
        assertEquals("10.0.0.2", endpoints[0].hostAddress)
        assertEquals(FlashTransportType.LAN, endpoints[0].transportType)
        // Exactly ONE Found reached consumers despite two radio sightings.
        assertEquals(1, harness.events.filterIsInstance<FlashTransportEvent.Found>().size)
    }

    @Test
    fun dedup_losingHighPrioritySighting_fallsBackToLower_withoutLostEvent() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")
        harness.composite.startAll(40_000, identity)
        harness.advance(100)

        harness.lan.found(endpointOf("d1", host = "10.0.0.2"))
        harness.wifiDirect.found(
            endpointOf("d1", host = "192.168.49.1", transportType = FlashTransportType.WIFI_DIRECT),
        )

        harness.lan.lost("d1", serviceName = "svc-d1")

        val endpoints = harness.composite.discoveredEndpoints.value
        assertEquals(1, endpoints.size)
        assertEquals("192.168.49.1", endpoints[0].hostAddress)
        // Hysteresis: peer still alive on Wi-Fi Direct → NO Lost was emitted;
        // an Updated carrying the fallback endpoint replaces it instead.
        assertFalse(harness.events.any { it is FlashTransportEvent.Lost })
        assertTrue(harness.events.any { it is FlashTransportEvent.Updated })
    }

    @Test
    fun dedup_lowerPriorityUpgrade_emitsUpdatedWithRicherPath() = runBlocking {
        harness = Harness("BLE", "LAN")
        harness.composite.startAll(40_000, identity)
        harness.advance(100)

        harness.transports[0].found(endpointOf("d1", host = "AA:BB:CC:DD:EE:FF", transportType = FlashTransportType.UNKNOWN))
        harness.lan2().found(endpointOf("d1", host = "10.0.0.7"))

        val endpoints = harness.composite.discoveredEndpoints.value
        assertEquals(1, endpoints.size)
        assertEquals("10.0.0.7", endpoints[0].hostAddress)
        assertEquals(1, harness.events.filterIsInstance<FlashTransportEvent.Updated>().size)
    }

    private fun Harness.lan2(): FakeTransport = transports.last()

    @Test
    fun lost_lastSightingGone_emitsLostOnce_withServiceName() = runBlocking {
        harness = Harness("LAN")
        harness.composite.startAll(40_000, identity)
        harness.advance(100)
        harness.lan.found(endpointOf("d1"))

        harness.lan.lost("d1", serviceName = "svc-d1")

        assertEquals(0, harness.composite.discoveredEndpoints.value.size)
        val lostEvents = harness.events.filterIsInstance<FlashTransportEvent.Lost>()
        assertEquals(1, lostEvents.size)
        assertEquals(FlashDeviceId("d1"), lostEvents[0].deviceId)
        assertEquals("svc-d1", lostEvents[0].serviceName)
    }

    // ------------------------------------------------------------------
    // Presence sweeper (plan C3.5)
    // ------------------------------------------------------------------

    @Test
    fun sweep_emitsLostExactlyAtGraceWindow_onceOnly() = runBlocking {
        harness = Harness("LAN")
        harness.composite.startAll(40_000, identity)

        harness.advance(0)
        harness.lan.found(endpointOf("d1"))
        harness.advance(10_000)
        harness.lan.found(endpointOf("d1")) // refresh presence; lastSeen = 10_000

        // 29_999 ms since lastSeen: just inside the default 30 s window.
        harness.advance(29_999)
        harness.composite.sweep(nowMs = harness.now)
        assertTrue(harness.events.none { it is FlashTransportEvent.Lost })
        assertEquals(1, harness.composite.discoveredEndpoints.value.size)

        // Exactly at the boundary (now - lastSeen == 30_000): expired.
        harness.advance(1)
        harness.composite.sweep(nowMs = harness.now)

        val lostEvents = harness.events.filterIsInstance<FlashTransportEvent.Lost>()
        assertEquals(1, lostEvents.size)
        assertEquals(FlashDeviceId("d1"), lostEvents[0].deviceId)
        assertEquals(0, harness.composite.discoveredEndpoints.value.size)

        // Sweeping again later must NOT duplicate the Lost.
        harness.advance(60_000)
        harness.composite.sweep(nowMs = harness.now)
        assertEquals(1, harness.events.filterIsInstance<FlashTransportEvent.Lost>().size)
    }

    @Test
    fun sweep_peerAliveOnOtherRadio_reportsFallbackInsteadOfLost() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")
        harness.composite.startAll(40_000, identity)
        harness.advance(100)
        harness.lan.found(endpointOf("d1", host = "10.0.0.2"))
        harness.wifiDirect.found(
            endpointOf("d1", host = "192.168.49.9", transportType = FlashTransportType.WIFI_DIRECT),
        )

        // Only the LAN directory ages out: the Wi-Fi Direct sighting is refreshed
        // late in the window so it stays inside grace at sweep time.
        harness.advance(29_000) // now = 29_100
        harness.wifiDirect.found(
            endpointOf("d1", host = "192.168.49.9", transportType = FlashTransportType.WIFI_DIRECT),
        )
        // Sweep 1 ms BEFORE the refreshed sighting hits the (inclusive) boundary.
        harness.composite.sweep(nowMs = harness.now + CompositeDiscovery.DEFAULT_GRACE_MS - 1)

        assertFalse(harness.events.any { it is FlashTransportEvent.Lost })
        val endpoints = harness.composite.discoveredEndpoints.value
        assertEquals(1, endpoints.size)
        assertEquals("192.168.49.9", endpoints[0].hostAddress)
    }

    @Test
    fun sweeper_automatic_agesOutDepartedPeer_withoutManualSweepCalls() = runBlocking {
        // Regression (P3.5 field report): a peer that stops advertising or drops
        // off Wi-Fi stayed visible forever because nothing drove the clock.
        // The engine must age endpoints out on its own once running.
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val releaseSweeper = java.util.concurrent.CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val lan = FakeTransport("LAN")
        val composite = CompositeDiscovery(
            transports = listOf(lan),
            scopeFactory = { scope },
            clock = { now.get() },
            // Sweeper ticks block on the latch so the test controls pacing; each
            // released tick advances virtual time by one interval.
            delayFn = { ms ->
                releaseSweeper.await()
                now.addAndGet(ms)
            },
            sweepIntervalMs = 15_000,
            maxSweepLoops = 2, // terminate deterministically after grace elapses
        )
        val endpoints = java.util.concurrent.atomic.AtomicReference(
            composite.discoveredEndpoints.value,
        )
        scope.launch {
            composite.discoveredEndpoints.collect { endpoints.set(it) }
        }

        composite.startAll(40_000, identity)
        fun awaitSize(expected: Int): Boolean {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (endpoints.get().size == expected) return true
                if (expected > 0) {
                    // Collector may not be subscribed yet (IO dispatch race);
                    // re-announce safely — duplicates dedup to Unchanged.
                    lan.found(endpointOf("d1"))
                }
                Thread.sleep(10)
            }
            return false
        }
        org.junit.Assert.assertTrue(awaitSize(1))

        releaseSweeper.countDown()
        // Loop 1: now=15_000 — inside grace, stays. Loop 2: now=30_000 — exactly
        // at the inclusive boundary → aged out WITHOUT any manual sweep call.
        org.junit.Assert.assertTrue(awaitSize(0))
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // startAll aggregation
    // ------------------------------------------------------------------

    @Test
    fun startAll_success_startsAdvertisingAndBrowsingOnEveryTransport() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")

        val result = harness.composite.startAll(40_123, identity)

        assertTrue(result is FlashResult.Success)
        harness.transports.forEach { transport ->
            assertTrue("advertise:40123" in transport.calls)
            assertTrue("browse" in transport.calls)
            assertEquals(identity, transport.lastIdentity)
        }
        val state = harness.composite.state.value
        assertTrue(state.isDiscovering)
        assertTrue(state.isAdvertising)
        assertEquals(40_123, state.advertisedPort)
    }

    @Test
    fun startAll_failure_listsWhichTransportsFailed() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")
        harness.wifiDirect.browseResult =
            FlashResult.Failure(FlashError.NetworkUnavailable("radio off"))

        val result = harness.composite.startAll(40_000, identity)

        assertTrue(result is FlashResult.Failure)
        val error = (result as FlashResult.Failure).error
        val message = (error as? FlashError.Unknown)?.message ?: error.toString()
        assertTrue(message.contains("WIFI_DIRECT"))
        assertFalse(message.contains("LAN:")) // LAN itself did not fail
        // Successful parts keep running so callers can stopAll cleanly.
        assertTrue(harness.composite.state.value.isDiscovering)
    }

    // ------------------------------------------------------------------
    // Interface methods
    // ------------------------------------------------------------------

    @Test
    fun startDiscovery_browsesEveryTransport_stopAllStopsEverything() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")

        assertTrue(harness.composite.startDiscovery() is FlashResult.Success)
        assertTrue(harness.composite.state.value.isDiscovering)
        assertFalse(harness.composite.state.value.isAdvertising)

        assertTrue(harness.composite.stopAll() is FlashResult.Success)
        harness.transports.forEach { transport -> assertTrue("stop" in transport.calls) }
        val state = harness.composite.state.value
        assertFalse(state.isDiscovering)
        assertFalse(state.isAdvertising)
    }

    @Test
    fun startAdvertising_withoutIdentity_fails() = runBlocking {
        harness = Harness("LAN")
        val result = harness.composite.startAdvertising(40_000)
        assertTrue(result is FlashResult.Failure)
    }

    // ------------------------------------------------------------------
    // P3.5-B3: mode fan-out + state reflection
    // ------------------------------------------------------------------

    @Test
    fun construction_defaultsToStandardPolicy() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")

        assertEquals(FlashDiscoveryMode.STANDARD, harness.composite.discoveryMode.value)
        // Transports were never told anything explicitly; their own constructor
        // default (STANDARD) matches the composite default.
        harness.transports.forEach { transport -> assertTrue(transport.policies.isEmpty()) }
    }

    @Test
    fun setMode_fansOutPolicyToEveryTransport() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")
        harness.composite.startAll(40_000, identity)

        harness.composite.setMode(FlashDiscoveryMode.ECO)

        assertEquals(FlashDiscoveryMode.ECO, harness.composite.discoveryMode.value)
        harness.transports.forEach { transport ->
            assertEquals(listOf(DiscoveryModePolicy.forMode(FlashDiscoveryMode.ECO)), transport.policies)
        }
    }

    @Test
    fun setMode_reflectedInStateMessagePrefix_withoutBreakingSuffix() = runBlocking {
        harness = Harness("LAN")
        harness.composite.startAll(40_000, identity)
        assertTrue(harness.composite.state.value.statusMessage.startsWith("[STANDARD] "))
        assertTrue(harness.composite.state.value.statusMessage.endsWith("Advertising and browsing"))

        harness.composite.setMode(FlashDiscoveryMode.ECO)
        assertTrue(harness.composite.state.value.statusMessage.startsWith("[ECO] "))
        assertTrue(harness.composite.state.value.isDiscovering)

        harness.composite.setMode(FlashDiscoveryMode.GHOST)
        val state = harness.composite.state.value
        assertTrue(state.statusMessage.startsWith("[GHOST] "))
        assertFalse(state.isAdvertising) // policy is authoritative, transports no-op Success
    }

    @Test
    fun ghostMode_startAll_advertiseSuppressedButBrowsingRuns() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")
        harness.composite.setMode(FlashDiscoveryMode.GHOST)

        val result = harness.composite.startAll(40_000, identity)

        assertTrue(result is FlashResult.Success)
        harness.transports.forEach { transport ->
            assertTrue("advertise:40000" in transport.calls) // call happened…
            assertFalse(transport.policies.isEmpty())         // …under GHOST policy
            assertTrue("browse" in transport.calls)
        }
        val state = harness.composite.state.value
        assertTrue(state.isDiscovering)
        assertFalse(state.isAdvertising) // composite never claims visibility in GHOST
        assertTrue(state.statusMessage.startsWith("[GHOST] "))
    }

    // ------------------------------------------------------------------
    // Presence liveness (the "peer vanishes ~30 s after it appears" fix)
    // ------------------------------------------------------------------

    @Test
    fun presence_keepsQuietPeerAliveAcrossManyGraceWindows() = runBlocking {
        // A stable NSD peer emits ONE Found and then only deduped sightings, which
        // the transport now reports as Presence. Sixty seconds of heartbeats — two
        // full grace windows — must not age it out, and must not churn the UI with
        // fake transitions either.
        harness = Harness("LAN")
        harness.composite.startAll(40_000, identity)
        harness.advance(100)
        harness.lan.found(endpointOf("d1"))

        repeat(6) {
            harness.advance(10_000)
            harness.lan.presence(endpointOf("d1"))
            harness.composite.sweep(nowMs = harness.now)
        }

        assertTrue(
            "presence heartbeats must feed the sweeper's TTL",
            harness.events.none { it is FlashTransportEvent.Lost },
        )
        assertEquals(1, harness.composite.discoveredEndpoints.value.size)
        assertEquals(1, harness.events.filterIsInstance<FlashTransportEvent.Found>().size)
        assertEquals(0, harness.events.filterIsInstance<FlashTransportEvent.Updated>().size)
    }

    @Test
    fun presence_forPeerNotYetKnown_selfHealsIntoFound() = runBlocking {
        harness = Harness("LAN")
        harness.composite.startDiscovery()
        harness.advance(100)

        // No prior Found for d1: a heartbeat for an unknown peer is promoted to a
        // real sighting rather than dropped, so a peer that was wrongly evicted
        // reappears on its own instead of staying invisible until it re-advertises.
        harness.lan.presence(endpointOf("d1"))

        assertEquals(1, harness.composite.discoveredEndpoints.value.size)
        assertEquals(1, harness.events.filterIsInstance<FlashTransportEvent.Found>().size)
    }

    // ------------------------------------------------------------------
    // Browse watchdog + forced restart
    // ------------------------------------------------------------------

    @Test
    fun browseWatchdog_reArmsTransportThatStoppedBrowsing_rateLimited() = runBlocking {
        val lan = FakeTransport("LAN")
        var now = 0L
        val gate = TickGate()
        val scope = CoroutineScope(SupervisorJob() + DirectDispatcher)
        val composite = CompositeDiscovery(
            transports = listOf(lan),
            scopeFactory = { scope },
            clock = { now },
            delayFn = gate.delayFn,
            sweepIntervalMs = 5_000,
            browseWatchdogMs = 10_000,
            maxSweepLoops = 5,
        )
        try {
            assertTrue(composite.startDiscovery() is FlashResult.Success)
            assertEquals(listOf("browse"), lan.calls)

            // The platform gave up (NSD onStopDiscoveryFailed / max retries). The
            // transport says so truthfully, which stamps the stall at now = 0.
            lan.stateChanged(browsing = false, message = "Browsing gave up after 5 attempts")
            assertFalse(composite.state.value.isDiscovering)

            // Half a watchdog window: too early, no restart yet.
            now = 5_000
            gate.tick()
            assertEquals(listOf("browse"), lan.calls)

            // At the window: the watchdog forces a restart (startBrowsing would be
            // a no-op inside the transport, hence restartBrowsing).
            now = 10_000
            gate.tick()
            assertEquals(listOf("browse", "restart"), lan.calls)
            assertTrue(composite.state.value.isDiscovering)

            // Still stalled per the transport, but rate-limited to one attempt per
            // window so a dead radio cannot spin the restart path.
            lan.stateChanged(browsing = false, message = "gave up again")
            now = 15_000
            gate.tick()
            assertEquals(listOf("browse", "restart"), lan.calls)

            now = 20_000
            gate.tick()
            assertEquals(listOf("browse", "restart", "restart"), lan.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restartDiscovery_forcesFreshBrowseOnEveryTransport() = runBlocking {
        harness = Harness("LAN", "WIFI_DIRECT")

        assertTrue(harness.composite.startDiscovery() is FlashResult.Success)
        assertTrue(harness.composite.restartDiscovery() is FlashResult.Success)

        harness.transports.forEach { transport ->
            assertEquals(listOf("browse", "restart"), transport.calls)
        }
        assertTrue(harness.composite.state.value.isDiscovering)
    }
}
