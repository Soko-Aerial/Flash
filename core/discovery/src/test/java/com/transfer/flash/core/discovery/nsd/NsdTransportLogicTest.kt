package com.transfer.flash.core.discovery.nsd

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [NsdTransport]'s extracted logic + bridge-driven event mapping
 * (plan C3.2–C3.4). No Robolectric / no kotlinx-coroutines-test: the module's test
 * classpath has neither, so virtual time is replaced by an injected no-op `sleep`
 * and a manual timestamp clock (documented deviation — gradle is read-only).
 */
class NsdTransportLogicTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeApiLevel(override val sdkInt: Int) : NsdApiLevel

    private fun identity(
        id: String = "peer-1",
        name: String = "Pixel A",
        model: String = "Pixel 7",
        proto: Int = 2,
    ) = FlashAdvertisedIdentity(FlashDeviceId(id), name, model, proto)

    private fun resolvedData(
        serviceName: String = "Flash Peer",
        deviceId: String? = "peer-1",
        host: String? = "192.168.1.50",
        port: Int = 45821,
        proto: String = "2",
        caps: String? = null,
        fp8: String? = null,
    ) = ResolvedServiceData(
        hostAddress = host,
        port = port,
        serviceName = serviceName,
        attributes = buildMap {
            if (deviceId != null) put(NsdTxtCodec.KEY_DEVICE_ID, deviceId)
            put(NsdTxtCodec.KEY_NAME, "Peer Name")
            put(NsdTxtCodec.KEY_MODEL, "Model X")
            put(NsdTxtCodec.KEY_PROTO, proto)
            if (caps != null) put(NsdTxtCodec.KEY_CAPS, caps)
            if (fp8 != null) put(NsdTxtCodec.KEY_FP8, fp8)
        },
    )

    /** EndpointDirectory fake driven by a queue of canned diffs (task-sanctioned inline impl). */
    private class FakeDirectory : EndpointDirectory {
        val seenCalls = mutableListOf<Pair<FlashDiscoveredEndpoint, Long>>()
        val lostCalls = mutableListOf<FlashDeviceId>()
        val seenResults = ArrayDeque<EndpointDirectory.Diff>()
        var lostResult: EndpointDirectory.Diff = EndpointDirectory.Diff.Unchanged

        override fun applySeen(endpoint: FlashDiscoveredEndpoint, nowMs: Long): EndpointDirectory.Diff {
            seenCalls += endpoint to nowMs
            return if (seenResults.isEmpty()) EndpointDirectory.Diff.Unchanged else seenResults.removeFirst()
        }

        override fun applyLost(deviceId: FlashDeviceId): EndpointDirectory.Diff {
            lostCalls += deviceId
            return lostResult
        }

        override fun sweepExpired(graceWindowMs: Long, nowMs: Long): List<EndpointDirectory.Diff.Lost> = emptyList()

        override fun snapshot(): List<EndpointDirectory.Entry> = emptyList()

        override fun get(deviceId: FlashDeviceId): EndpointDirectory.Entry? = null
    }

    @Suppress("FunctionName")
    private fun DiffFound(endpoint: FlashDiscoveredEndpoint) =
        EndpointDirectory.Diff.Found(
            EndpointDirectory.Entry(endpoint, firstSeenAtMs = 0L, lastSeenAtMs = 0L),
        )

    @Suppress("FunctionName")
    private fun DiffLost(id: String) = EndpointDirectory.Diff.Lost(FlashDeviceId(id))

    private class FakeBridge : NsdManagerBridge {
        val lockStates = mutableListOf<Boolean>()
        val advertiseRequests = mutableListOf<AdvertiseRequest>()
        val browseRequests = mutableListOf<BrowseRequest>()
        val monitorRequests = mutableListOf<MonitorRequest>()
        var startBrowseResult = true
        var advertiseResult = true
        var monitorResult = true
        var stopBrowseCalled = false
        var stopBrowseCount = 0
        var monitorsCancelled = false
        var unadvertiseCalled = false
        var unadvertiseCount = 0

        lateinit var browseEvents: BrowseEvents
            private set
        lateinit var monitorEvents: MonitorEvents
            private set

        override fun setMulticastLock(active: Boolean) {
            lockStates += active
        }

        override fun advertise(request: AdvertiseRequest, events: AdvertiseEvents): Boolean {
            advertiseRequests += request
            events.onRegistered(request.serviceName, request.port)
            return advertiseResult
        }

        override fun unadvertise(events: AdvertiseEvents) {
            unadvertiseCalled = true
            unadvertiseCount += 1
        }

        override fun startBrowse(request: BrowseRequest, events: BrowseEvents): Boolean {
            browseRequests += request
            browseEvents = events
            return startBrowseResult
        }

        override fun stopBrowse() {
            stopBrowseCalled = true
            stopBrowseCount += 1
        }

        override fun monitor(request: MonitorRequest, events: MonitorEvents): Boolean {
            monitorRequests += request
            monitorEvents = events
            return monitorResult
        }

        override fun cancelMonitors() {
            monitorsCancelled = true
        }

        fun fireBrowseStartFailed(errorCode: Int) = browseEvents.onStartFailed(errorCode)
        fun fireServiceFound(name: String) = browseEvents.onServiceFound(name)
        fun fireMonitorUpdated(data: ResolvedServiceData) = monitorEvents.onUpdated(data)
        fun fireMonitorLost(name: String) = monitorEvents.onMonitorLost(name)
    }

    /** Collects transport events synchronously; handlers run inline on Dispatchers.Unconfined. */
    private class EventRecorder(transport: NsdTransport) {
        val received = mutableListOf<FlashTransportEvent>()
        private val job: Job = CoroutineScope(Dispatchers.Unconfined + Job()).launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            transport.events.collect { received += it }
        }

        fun cancel() = job.cancel()
    }

    private fun newTransport(
        apiLevel: Int,
        directory: FakeDirectory,
        bridge: FakeBridge,
        maxRestarts: Int = 5,
        delays: MutableList<Long>? = null,
        modePolicy: DiscoveryModePolicy? = null,
        maxDutyCycles: Int = Int.MAX_VALUE,
        idleWaits: MutableList<Long>? = null,
        slept: MutableList<Long>? = null,
        lostDebounceMs: Long = 0L,
    ): NsdTransport {
        val recordedDelays = delays
        val recordedIdleWaits = idleWaits
        val recordedSlept = slept
        return NsdTransport(
            context = null,
            apiLevel = FakeApiLevel(apiLevel),
            directory = directory,
            sweep = { _ -> emptyList() },
            retryDelayMs = { attempt ->
                (1000L * attempt).also { recordedDelays?.add(it) }
            },
            maxBrowsingRestarts = maxRestarts,
            maxDutyCycles = maxDutyCycles,
            initialModePolicy = modePolicy
                ?: DiscoveryModePolicy.forMode(FlashDiscoveryMode.STANDARD),
            dispatcher = Dispatchers.Unconfined,
            timeSourceMs = { 1_000L },
            sleep = { ms -> recordedSlept?.add(ms) /* no-op: deterministic, no virtual time */ },
            idleWaitOverride = { ms ->
                recordedIdleWaits?.add(ms)
                false // full gap elapsed; deterministic, no virtual time needed
            },
            lostDebounceMs = lostDebounceMs, // synchronous loss in tests unless a case opts into debounce
            logInfo = {},
            logWarn = {},
            bridgeOverride = bridge,
        )
    }

    // ------------------------------------------------------------------
    // TXT codec
    // ------------------------------------------------------------------

    @Test
    fun txtCodec_encode_containsIdentityKeySet() {
        val txt = NsdTxtCodec.encode(identity())
        assertEquals("peer-1", txt[NsdTxtCodec.KEY_DEVICE_ID])
        assertEquals("Pixel A", txt[NsdTxtCodec.KEY_NAME])
        assertEquals("Pixel 7", txt[NsdTxtCodec.KEY_MODEL])
        assertEquals("2", txt[NsdTxtCodec.KEY_PROTO])
    }

    @Test
    fun txtCodec_decode_fallsBackGracefully() {
        val parsed = NsdTxtCodec.decode(mapOf(NsdTxtCodec.KEY_PROTO to "9"), fallbackName = "svc", fallbackProto = 2)
        assertNull(parsed.deviceId)
        assertEquals("svc", parsed.friendlyName)
        assertEquals(9, parsed.protocolVersion)

        val defaulted = NsdTxtCodec.decode(emptyMap(), fallbackName = "svc", fallbackProto = 2)
        assertEquals("svc", defaulted.friendlyName)
        assertEquals(2, defaulted.protocolVersion)
    }

    @Test
    fun restartPolicy_capsAndGivesUp() {
        assertEquals(1_000L, NsdRestartPolicy.computeRestart(1, 5) { 1_000L }.delayMs)
        assertNull(NsdRestartPolicy.computeRestart(6, 5) { 1_000L }.delayMs)
        assertTrue(NsdRestartPolicy.exponentialBackoff(10) <= 30_000L)
    }

    // ------------------------------------------------------------------
    // Advertising + self-filter (C3.2)
    // ------------------------------------------------------------------

    @Test
    fun startAdvertising_sendsIdentityTxtRecords() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 34, directory = FakeDirectory(), bridge = bridge)

        runBlocking {
            val result = transport.startAdvertising(45821, identity(id = "self-1", name = "My Phone"))
            assertTrue(result.isSuccess)
        }

        val request = bridge.advertiseRequests.single()
        assertEquals("_flash-transfer._tcp.", request.serviceType)
        assertEquals(45821, request.port)
        assertEquals("self-1", request.txtRecords[NsdTxtCodec.KEY_DEVICE_ID])
        assertEquals("2", request.txtRecords[NsdTxtCodec.KEY_PROTO])
        assertTrue(request.serviceName.startsWith("Flash"))
    }

    @Test
    fun resolvedEvent_matchingOwnDeviceId_isFilteredBeforeDirectory() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startAdvertising(45821, identity(id = "self-1")) }
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        bridge.fireServiceFound("Flash My Phone")
        bridge.fireMonitorUpdated(resolvedData(deviceId = "self-1"))

        assertTrue(directory.seenCalls.isEmpty())
        assertFalse(recorder.received.any { it is FlashTransportEvent.Found || it is FlashTransportEvent.Updated })
        recorder.cancel()
    }

    // ------------------------------------------------------------------
    // Found-once-then-updated mapping (C3.3/C3.4)
    // ------------------------------------------------------------------

    @Test
    fun serviceUpdate_mapsToFoundThenUpdatedThroughDirectory() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        val endpoint = FlashDiscoveredEndpoint(
            device = com.transfer.flash.core.common.model.FlashDevice(
                FlashDeviceId("peer-1"),
                "Peer Name",
                com.transfer.flash.core.common.model.FlashTransportType.LAN,
            ),
            hostAddress = "192.168.1.50",
            port = 45821,
            serviceName = "Flash Peer",
        )
        directory.seenResults.addLast(DiffFound(endpoint))
        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())

        // Second update → Updated diff. The entry must carry the NEW endpoint data
        // (production StandardEndpointDirectory stores the fresh sighting); the diff
        // kind only decides Found vs Updated emission.
        val updatedEndpoint = endpoint.copy(hostAddress = "192.168.1.51")
        directory.seenResults.addLast(
            EndpointDirectory.Diff.Updated(
                EndpointDirectory.Entry(updatedEndpoint, 0L, 5_000L),
                EndpointDirectory.Entry(endpoint, 0L, 1_000L),
            ),
        )
        bridge.fireMonitorUpdated(resolvedData(host = "192.168.1.51"))

        val found = recorder.received.filterIsInstance<FlashTransportEvent.Found>()
        val updated = recorder.received.filterIsInstance<FlashTransportEvent.Updated>()
        assertEquals(1, found.size)
        assertEquals("192.168.1.50", found.single().endpoint.hostAddress)
        assertEquals(1, updated.size)
        assertEquals("192.168.1.51", updated.single().endpoint.hostAddress)
        assertEquals(2, directory.seenCalls.size)
        assertEquals(1_000L, directory.seenCalls[0].second)
        recorder.cancel()
    }

    @Test
    fun serviceUpdate_withoutDeviceIdOrHost_isDropped() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("svc-a")
        bridge.fireMonitorUpdated(resolvedData(serviceName = "svc-a", deviceId = null))
        bridge.fireMonitorUpdated(resolvedData(serviceName = "svc-b", host = null))

        assertTrue(directory.seenCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // Lost mapping (C3.3) + sweeper hook (C3.5 groundwork)
    // ------------------------------------------------------------------

    @Test
    fun monitorLost_mapsToTypedLostViaReverseLookup() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        directory.seenResults.addLast(DiffFound(endpointOf("peer-1")))
        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())

        directory.lostResult = DiffLost("peer-1")
        bridge.fireMonitorLost("Flash Peer")

        val lost = recorder.received.filterIsInstance<FlashTransportEvent.Lost>()
        assertEquals(1, lost.size)
        assertEquals("peer-1", lost.single().deviceId.value)
        assertEquals("Flash Peer", lost.single().serviceName)
        assertEquals(listOf(FlashDeviceId("peer-1")), directory.lostCalls)
        recorder.cancel()
    }

    @Test
    fun monitorLost_debounced_reFindCancelsRemoval_noLostEmitted() {
        // With a long debounce, a transient radio goodbye must NOT evict the peer synchronously;
        // a re-find (onServiceFound → resolved update) before the window elapses cancels the pending
        // removal entirely. This is the hotspot/mDNS-flap fix: the peer stays in the directory.
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(
            apiLevel = 34,
            directory = directory,
            bridge = bridge,
            lostDebounceMs = 10_000L,
        )
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        directory.seenResults.addLast(DiffFound(endpointOf("peer-1")))
        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())

        // Transient loss: removal is deferred by the debounce, so nothing happens yet.
        directory.lostResult = DiffLost("peer-1")
        bridge.fireMonitorLost("Flash Peer")
        assertTrue("removal must be deferred, not immediate", directory.lostCalls.isEmpty())

        // Peer re-announced within the window → cancels the pending removal.
        directory.seenResults.addLast(DiffFound(endpointOf("peer-1")))
        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())

        assertTrue("re-find must cancel the removal", directory.lostCalls.isEmpty())
        assertTrue(recorder.received.filterIsInstance<FlashTransportEvent.Lost>().isEmpty())
        recorder.cancel()
    }

    // ------------------------------------------------------------------
    // Retry policy re-browse (C3.3) + API-level branch selection (C3.4)
    // ------------------------------------------------------------------

    @Test
    fun legacyApi_browsesWithoutNetworkRequest_andResolvesViaQueue() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 30, directory = FakeDirectory(), bridge = bridge)
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("legacy-peer")

        val request = bridge.browseRequests.single()
        assertFalse(request.useNetworkRequestDiscovery)
        assertEquals(ResolutionStrategy.LEGACY_RESOLVE_QUEUE, bridge.monitorRequests.single().strategy)
    }

    @Test
    fun api34_browsesWithNetworkRequest_andMonitorsContinuously() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 34, directory = FakeDirectory(), bridge = bridge)
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("modern-peer")

        val request = bridge.browseRequests.single()
        assertTrue(request.useNetworkRequestDiscovery)
        assertEquals(ResolutionStrategy.INFO_CALLBACK, bridge.monitorRequests.single().strategy)
    }

    @Test
    fun startFailures_reBrowseUntilAttemptBudgetExhausted_thenGiveUp() {
        val bridge = FakeBridge().apply { startBrowseResult = false }
        val delays = mutableListOf<Long>()
        val transport = newTransport(apiLevel = 34, directory = FakeDirectory(), bridge = bridge, maxRestarts = 2, delays = delays)
        val recorder = EventRecorder(transport)

        runBlocking { transport.startBrowsing() }

        // Attempt budget 2 → 3 total initiation attempts (initial + 2 retries), then give-up.
        assertEquals(3, bridge.browseRequests.size)
        assertEquals(listOf(1_000L, 2_000L), delays)
        val giveUp = recorder.received.filterIsInstance<FlashTransportEvent.StateChanged>().last()
        assertFalse(giveUp.browsing)
        recorder.cancel()

        // Runtime onStartFailed after a successful start also schedules a capped retry loop.
        val bridge2 = FakeBridge()
        val transport2 = newTransport(apiLevel = 34, directory = FakeDirectory(), bridge = bridge2, maxRestarts = 1)
        runBlocking { transport2.startBrowsing() }
        bridge2.fireBrowseStartFailed(errorCode = 3)
        assertEquals(2, bridge2.browseRequests.size)
    }

    @Test
    fun multicastLock_acquiredOnAllApiLevels() {
        // Background/screen-off resilience fix: the multicast lock is now taken on ALL API
        // levels while browsing. Framework-managed multicast (T-ext 7+) only covers FOREGROUND
        // apps, but Flash browses from a backgrounded FGS, so the explicit lock is always needed.
        val oldBridge = FakeBridge()
        val oldTransport = newTransport(apiLevel = 33, directory = FakeDirectory(), bridge = oldBridge)
        runBlocking { oldTransport.startBrowsing() }
        assertTrue(oldBridge.lockStates.contains(true))

        val newBridge = FakeBridge()
        val newTransport = newTransport(apiLevel = 35, directory = FakeDirectory(), bridge = newBridge)
        runBlocking { newTransport.startBrowsing() }
        assertTrue(newBridge.lockStates.contains(true))
    }

    @Test
    fun stop_releasesRadioResources() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 33, directory = FakeDirectory(), bridge = bridge)
        runBlocking {
            transport.startAdvertising(45821, identity())
            transport.startBrowsing()
            transport.stop()
        }
        assertTrue(bridge.stopBrowseCalled)
        assertTrue(bridge.unadvertiseCalled)
        assertTrue(bridge.monitorsCancelled)
        assertEquals(false, bridge.lockStates.last())
    }

    // ------------------------------------------------------------------
    // P3.5-A2/A4: TXT caps/fp8 on the wire + inbound hardening
    // ------------------------------------------------------------------

    @Test
    fun startAdvertising_capsAndFp8_includedInTxtRecords() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 34, directory = FakeDirectory(), bridge = bridge)
        val richIdentity = FlashAdvertisedIdentity(
            FlashDeviceId("self-1"), "My Phone", "Pixel 7", 2,
            capabilities = setOf("kiosk", "voice"),
            fingerprintPrefix = "deadbeef",
        )

        runBlocking { transport.startAdvertising(45821, richIdentity) }

        val request = bridge.advertiseRequests.single()
        assertEquals("kiosk,voice", request.txtRecords[NsdTxtCodec.KEY_CAPS])
        assertEquals("deadbeef", request.txtRecords[NsdTxtCodec.KEY_FP8])
    }

    @Test
    fun versionMismatch_droppedPreDirectory() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("svc-old")
        bridge.fireMonitorUpdated(resolvedData(serviceName = "svc-old", proto = "1"))

        assertTrue(directory.seenCalls.isEmpty())
    }

    @Test
    fun missingProto_fallsBackToOurVersion_accepted() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        directory.seenResults.addLast(DiffFound(endpointOf("peer-1")))
        // No proto attribute at all: tolerant fallback keeps legacy peers visible.
        bridge.fireServiceFound("svc-noproto")
        bridge.fireMonitorUpdated(
            ResolvedServiceData(
                hostAddress = "192.168.1.50",
                port = 45821,
                serviceName = "svc-noproto",
                attributes = mapOf(NsdTxtCodec.KEY_DEVICE_ID to "peer-1"),
            ),
        )

        assertEquals(1, directory.seenCalls.size)
        recorder.cancel()
    }

    @Test
    fun peerCaps_informationalOnly_peerStillAccepted() {
        val bridge = FakeBridge()
        val directory = FakeDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }

        // Unknown/foreign caps must NOT drop the endpoint: caps are informational,
        // enforcement is deferred to connect time (C3.10 seam).
        directory.seenResults.addLast(DiffFound(endpointOf("peer-1")))
        bridge.fireServiceFound("svc-caps")
        bridge.fireMonitorUpdated(resolvedData(caps = "totally-unknown-cap,kiosk", fp8 = "cafe1234"))

        assertEquals(1, directory.seenCalls.size)
    }

    // ------------------------------------------------------------------
    // P3.5-B2: mode wiring (GHOST / ECO / BOOST)
    // ------------------------------------------------------------------

    @Test
    fun ghostMode_startAdvertisingIsDocumentedNoOp_neverCallsBridgeAdvertise() {
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = FakeDirectory(),
            bridge = bridge,
            modePolicy = DiscoveryModePolicy.forMode(FlashDiscoveryMode.GHOST),
        )

        runBlocking {
            val result = transport.startAdvertising(45821, identity(id = "self-1"))
            assertTrue(result.isSuccess)
        }

        assertTrue(bridge.advertiseRequests.isEmpty())
    }

    @Test
    fun setMode_ghostWhileAdvertising_unadvertisesImmediately_thenResumeOnExit() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 34, directory = FakeDirectory(), bridge = bridge)
        runBlocking {
            transport.startAdvertising(45821, identity(id = "self-1"))
            assertEquals(1, bridge.advertiseRequests.size)

            transport.setMode(DiscoveryModePolicy.forMode(FlashDiscoveryMode.GHOST))
            assertTrue(bridge.unadvertiseCalled)

            // Leaving GHOST resumes advertising from the retained identity/port.
            transport.setMode(DiscoveryModePolicy.forMode(FlashDiscoveryMode.STANDARD))
            assertEquals(2, bridge.advertiseRequests.size)
            assertEquals(45821, bridge.advertiseRequests.last().port)
        }
    }

    @Test
    fun ecoMode_browseAndIdleAlternate_overDutyCycleBudget() {
        val bridge = FakeBridge()
        val idleWaits = mutableListOf<Long>()
        val eco = DiscoveryModePolicy.forMode(FlashDiscoveryMode.ECO)
        val transport = newTransport(
            apiLevel = 34,
            directory = FakeDirectory(),
            bridge = bridge,
            modePolicy = eco,
            maxDutyCycles = 2,
            idleWaits = idleWaits,
        )
        runBlocking { transport.startBrowsing() }

        // Two full bursts, each followed by stopBrowse + one full idle gap;
        // budget exhaustion ends the loop deterministically (test hook).
        assertEquals(2, bridge.browseRequests.size)
        assertEquals(2, bridge.stopBrowseCount)
        assertEquals(listOf(eco.idleDutyCycleMs, eco.idleDutyCycleMs), idleWaits)
    }

    @Test
    fun boostMode_backoffUsesLoweredBase_capAndAttemptsUnchanged() {
        val bridge = FakeBridge().apply { startBrowseResult = false }
        val delays = mutableListOf<Long>()
        val slept = mutableListOf<Long>()
        val transport = newTransport(
            apiLevel = 34,
            directory = FakeDirectory(),
            bridge = bridge,
            maxRestarts = 2,
            delays = delays,
            slept = slept,
            modePolicy = DiscoveryModePolicy.forMode(FlashDiscoveryMode.BOOST),
        )
        runBlocking { transport.startBrowsing() }

        // Attempt budget unchanged (3 initiation attempts); the ACTUAL backoff
        // sleeps are the provider outputs scaled by the BOOST base (250/1000).
        assertEquals(3, bridge.browseRequests.size)
        assertEquals(listOf(250L, 500L), slept)
        assertEquals(listOf(1_000L, 2_000L), delays) // raw provider outputs, unscaled
    }

    @Test
    fun standardMode_backoffUsesDefaultBase_unchanged() {
        val bridge = FakeBridge().apply { startBrowseResult = false }
        val delays = mutableListOf<Long>()
        val slept = mutableListOf<Long>()
        val transport = newTransport(
            apiLevel = 34,
            directory = FakeDirectory(),
            bridge = bridge,
            maxRestarts = 1,
            delays = delays,
            slept = slept,
        )
        runBlocking { transport.startBrowsing() }
        assertEquals(listOf(1_000L), delays)
        assertEquals(listOf(1_000L), slept)
    }

    private fun endpointOf(id: String) = FlashDiscoveredEndpoint(
        device = com.transfer.flash.core.common.model.FlashDevice(
            FlashDeviceId(id),
            "Peer Name",
            com.transfer.flash.core.common.model.FlashTransportType.LAN,
        ),
        hostAddress = "192.168.1.50",
        port = 45821,
        serviceName = "Flash Peer",
    )
}
