package com.transfer.flash.core.discovery.nsd

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
        var monitorsCancelled = false
        var unadvertiseCalled = false
        var unadvertiseCount = 0

        // Volatile: the connectivity re-arm path resumes on a scheduler thread
        // (it uses a REAL delay for its debounce), so these are read cross-thread.
        @Volatile var stopBrowseCount = 0

        @Volatile var browseStartCount = 0

        @Volatile var networkObserved = false
        private var networkListener: ((immediate: Boolean) -> Unit)? = null

        /**
         * What [linkFingerprint] returns next. A test moves this to stand for a hotspot coming up
         * or going down, the transition that produces no platform callback at all.
         */
        @Volatile var fingerprint: String = "wlan0=192.168.1.20"

        @Volatile var fingerprintReads = 0

        lateinit var browseEvents: BrowseEvents
            private set
        lateinit var monitorEvents: MonitorEvents
            private set

        /** Per-service monitor callbacks, so a test can fail exactly one registration. */
        val monitorEventsByName = mutableMapOf<String, MonitorEvents>()

        /** Non-null makes [advertise] report an asynchronous registration failure. */
        var advertiseFailureCode: Int? = null
        private var advertiseEventsRef: AdvertiseEvents? = null

        override fun setMulticastLock(active: Boolean) {
            lockStates += active
        }

        override fun advertise(request: AdvertiseRequest, events: AdvertiseEvents): Boolean {
            advertiseRequests += request
            advertiseEventsRef = events
            val failure = advertiseFailureCode
            if (failure != null) {
                events.onRegistrationFailed(failure)
            } else {
                events.onRegistered(request.serviceName, request.port)
            }
            return advertiseResult
        }

        override fun unadvertise(events: AdvertiseEvents) {
            unadvertiseCalled = true
            unadvertiseCount += 1
        }

        override fun startBrowse(request: BrowseRequest, events: BrowseEvents): Boolean {
            browseRequests += request
            browseEvents = events
            browseStartCount += 1 // volatile write publishes browseRequests too
            return startBrowseResult
        }

        override fun stopBrowse() {
            stopBrowseCalled = true
            stopBrowseCount += 1
        }

        override fun monitor(request: MonitorRequest, events: MonitorEvents): Boolean {
            monitorRequests += request
            monitorEvents = events
            monitorEventsByName[request.serviceName] = events
            return monitorResult
        }

        override fun cancelMonitors() {
            monitorsCancelled = true
        }

        /**
         * The primary overload — the one production's `observeNetworkChangesIfNeeded` actually
         * calls. The `() -> Unit` overload has an interface default that delegates here, so
         * overriding only that one (as this fake did until 414c570 added the immediate flag)
         * leaves registration silently answering the interface default `false`.
         */
        override fun observeNetworkChanges(onChanged: (immediate: Boolean) -> Unit): Boolean {
            networkListener = onChanged
            networkObserved = true
            return true
        }

        override fun stopObservingNetworkChanges() {
            networkListener = null
            networkObserved = false
        }

        override fun linkFingerprint(): String {
            fingerprintReads += 1
            return fingerprint
        }

        /** Fires the debounced change path — the `immediate` flag is for `onAvailable` edges. */
        fun fireNetworkChanged() = requireNotNull(networkListener) { "not observing" }.invoke(false)

        fun fireBrowseStartFailed(errorCode: Int) = browseEvents.onStartFailed(errorCode)
        fun fireServiceFound(name: String) = browseEvents.onServiceFound(name)
        fun fireMonitorUpdated(data: ResolvedServiceData) = monitorEvents.onUpdated(data)
        fun fireMonitorLost(name: String) = monitorEvents.onMonitorLost(name)

        /** Async `onServiceInfoCallbackRegistrationFailed` for one specific service. */
        fun fireMonitorRegistrationFailed(name: String, errorCode: Int) =
            requireNotNull(monitorEventsByName[name]) { "no monitor for $name" }
                .onRegistrationFailed(errorCode)

        /** The framework dropped our advertisement (mDNS daemon restart, OEM freeze). */
        fun fireAdvertiseUnregistered() =
            requireNotNull(advertiseEventsRef) { "not advertising" }.onUnregistered()
    }

    /**
     * Hand-cranked heartbeat pacing. The transport's `presenceSleep` parks on a
     * rendezvous channel, so [tick] runs EXACTLY one heartbeat iteration
     * synchronously (Unconfined resumes the parked coroutine inline) and the loop
     * can never spin — the determinism the no-op `sleep` hook gives the browse loop.
     */
    private class ManualTicker {
        private val gate = Channel<Unit>(Channel.RENDEZVOUS)
        val waits = mutableListOf<Long>()
        val sleep: suspend (Long) -> Unit = { ms ->
            waits += ms
            gate.receive()
        }

        fun tick() = runBlocking { gate.send(Unit) }
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
        directory: EndpointDirectory,
        bridge: FakeBridge,
        maxRestarts: Int = 5,
        delays: MutableList<Long>? = null,
        modePolicy: DiscoveryModePolicy? = null,
        maxDutyCycles: Int = Int.MAX_VALUE,
        idleWaits: MutableList<Long>? = null,
        slept: MutableList<Long>? = null,
        lostDebounceMs: Long = 0L,
        nowMs: () -> Long = { 1_000L },
        // 0 disables the heartbeat: tests that do not exercise it stay synchronous.
        presenceHeartbeatMs: Long = 0L,
        presenceSleep: (suspend (Long) -> Unit)? = null,
        maxPresenceTicks: Int = Int.MAX_VALUE,
        networkChangeDebounceMs: Long = 0L,
        // 0 disables the fast monitor retry / advertise watchdog: tests that do not exercise them
        // stay synchronous and spawn no loops.
        monitorRetryMs: Long = 0L,
        monitorRetrySleep: (suspend (Long) -> Unit)? = null,
        maxMonitorRetries: Int = NsdTransport.DEFAULT_MAX_MONITOR_RETRIES,
        advertiseWatchdogMs: Long = 0L,
        advertiseWatchdogSleep: (suspend (Long) -> Unit)? = null,
        maxAdvertiseWatchdogTicks: Int = Int.MAX_VALUE,
        sweepResults: ((Long) -> List<EndpointDirectory.Diff.Lost>)? = null,
    ): NsdTransport {
        val recordedDelays = delays
        val recordedIdleWaits = idleWaits
        val recordedSlept = slept
        return NsdTransport(
            context = null,
            apiLevel = FakeApiLevel(apiLevel),
            directory = directory,
            sweep = sweepResults ?: { _ -> emptyList() },
            retryDelayMs = { attempt ->
                (1000L * attempt).also { recordedDelays?.add(it) }
            },
            maxBrowsingRestarts = maxRestarts,
            maxDutyCycles = maxDutyCycles,
            initialModePolicy = modePolicy
                ?: DiscoveryModePolicy.forMode(FlashDiscoveryMode.STANDARD),
            dispatcher = Dispatchers.Unconfined,
            timeSourceMs = nowMs,
            sleep = { ms -> recordedSlept?.add(ms) /* no-op: deterministic, no virtual time */ },
            idleWaitOverride = { ms ->
                recordedIdleWaits?.add(ms)
                false // full gap elapsed; deterministic, no virtual time needed
            },
            lostDebounceMs = lostDebounceMs, // synchronous loss in tests unless a case opts into debounce
            presenceHeartbeatMs = presenceHeartbeatMs,
            presenceSleep = presenceSleep ?: { },
            maxPresenceTicks = maxPresenceTicks,
            monitorRetryMs = monitorRetryMs,
            maxMonitorRetries = maxMonitorRetries,
            monitorRetrySleep = monitorRetrySleep ?: { },
            advertiseWatchdogMs = advertiseWatchdogMs,
            advertiseWatchdogSleep = advertiseWatchdogSleep ?: { },
            maxAdvertiseWatchdogTicks = maxAdvertiseWatchdogTicks,
            networkChangeDebounceMs = networkChangeDebounceMs,
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

    // ------------------------------------------------------------------
    // Presence liveness: the "peer appears, then vanishes ~30s later and
    // never comes back" regression suite.
    // ------------------------------------------------------------------

    @Test
    fun unchangedSighting_emitsPresence_insteadOfNothing() {
        // A stable peer re-resolves to IDENTICAL data, which the directory dedups
        // to Diff.Unchanged. That used to emit NOTHING, so a consumer aging peers
        // out on a TTL saw one Found and then permanent silence for a peer sitting
        // right there. Unchanged must surface as a liveness signal.
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
        )
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())
        bridge.fireMonitorUpdated(resolvedData()) // byte-identical re-resolution

        assertEquals(1, recorder.received.filterIsInstance<FlashTransportEvent.Found>().size)
        assertEquals(0, recorder.received.filterIsInstance<FlashTransportEvent.Updated>().size)
        assertEquals(1, recorder.received.filterIsInstance<FlashTransportEvent.Presence>().size)
        recorder.cancel()
    }

    @Test
    fun restartBrowsing_forcesFreshBrowse_whereStartBrowsingIsANoOp() {
        val bridge = FakeBridge()
        val transport = newTransport(apiLevel = 34, directory = StandardEndpointDirectory(), bridge = bridge)

        runBlocking { transport.startBrowsing() }
        assertEquals(1, bridge.browseRequests.size)

        // Idempotent by contract: a second start cannot recover a dead browse.
        runBlocking { transport.startBrowsing() }
        assertEquals(1, bridge.browseRequests.size)
        assertEquals(0, bridge.stopBrowseCount)

        // Forced restart tears the radio down and re-arms unconditionally, so the
        // platform re-delivers onServiceFound for everything still present.
        runBlocking { transport.restartBrowsing() }
        assertEquals(2, bridge.browseRequests.size)
        assertEquals(1, bridge.stopBrowseCount)
        assertTrue(bridge.monitorsCancelled)
    }

    @Test
    fun heartbeat_reAffirmsQuietPeer_andNeverEvictsIt() {
        // THE field symptom: after the single Found, NSD delivers nothing at all
        // for a peer that never moved (API 34+ ServiceInfoCallback fires on change
        // only; pre-34 resolve is one-shot). Six ticks span 60s — twice the
        // consumer grace window — and must produce liveness, never a Lost.
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        var now = 1_000L
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            nowMs = { now },
            presenceHeartbeatMs = 1_000L,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 10,
        )
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())
        assertEquals(1, recorder.received.filterIsInstance<FlashTransportEvent.Found>().size)

        repeat(6) {
            now += 10_000L
            ticker.tick()
        }

        assertEquals(6, recorder.received.filterIsInstance<FlashTransportEvent.Presence>().size)
        assertTrue(
            "a monitored peer must never be evicted by the heartbeat",
            recorder.received.filterIsInstance<FlashTransportEvent.Lost>().isEmpty(),
        )
        recorder.cancel()
    }

    @Test
    fun heartbeat_retriesMonitorThatFailedToStart_thenStops() {
        // A found-but-never-resolved service was invisible forever: nothing retried
        // a failed monitor initiation, so the peer existed on the radio and nowhere
        // else. The heartbeat is that retry.
        val ticker = ManualTicker()
        val bridge = FakeBridge().apply { monitorResult = false }
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            presenceHeartbeatMs = 1_000L,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 5,
        )
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("Flash Peer")
        assertEquals(1, bridge.monitorRequests.size)

        bridge.monitorResult = true
        ticker.tick()
        assertEquals(2, bridge.monitorRequests.size)

        // Once it takes, the heartbeat stops re-initiating it.
        ticker.tick()
        assertEquals(2, bridge.monitorRequests.size)
    }

    @Test
    fun heartbeat_evictsPeerThePlatformStoppedVouchingFor_onlyAfterGraceTicks() {
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        var now = 1_000L
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            nowMs = { now },
            presenceHeartbeatMs = 1_000L,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 10,
        )
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)
        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())
        assertEquals(1, ticker.waits.size)

        // Forced restart drops every vouch; the radio is silent afterwards (this
        // peer really is gone), but the directory still holds it.
        runBlocking { transport.restartBrowsing() }
        assertEquals("restart must re-arm the heartbeat", 2, ticker.waits.size)

        // Inside the post-restart grace: eviction is suppressed so a still-present
        // peer that has not been re-announced YET is not falsely lost.
        now = 2_500L
        ticker.tick()
        assertTrue(recorder.received.filterIsInstance<FlashTransportEvent.Lost>().isEmpty())

        // Past the grace: now the silence is real.
        now = 5_000L
        ticker.tick()
        val lost = recorder.received.filterIsInstance<FlashTransportEvent.Lost>()
        assertEquals(1, lost.size)
        assertEquals("peer-1", lost.single().deviceId.value)
        assertEquals("Flash Peer", lost.single().serviceName)
        recorder.cancel()
    }

    @Test
    fun stop_drainsDirectory_soTheNextSessionRePublishesFound() {
        // stop() used to leave the directory populated. The next browse session
        // re-sighted the same peers, deduped them to Unchanged, and published
        // nothing — physically present devices stayed invisible until one of their
        // fields happened to change.
        val bridge = FakeBridge()
        val directory = StandardEndpointDirectory()
        val transport = newTransport(apiLevel = 34, directory = directory, bridge = bridge)
        runBlocking { transport.startBrowsing() }
        val recorder = EventRecorder(transport)

        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())
        assertEquals(1, recorder.received.filterIsInstance<FlashTransportEvent.Found>().size)

        runBlocking { transport.stop() }
        assertTrue(directory.snapshot().isEmpty())

        runBlocking { transport.startBrowsing() }
        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())

        assertEquals(2, recorder.received.filterIsInstance<FlashTransportEvent.Found>().size)
        recorder.cancel()
    }

    @Test
    fun connectivityChange_forcesBrowseRestart() {
        // The browse is deliberately UNBOUND (so a hotspot host can see its
        // clients), which also means it is network-blind: nothing else notices the
        // interface it started on disappearing.
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            networkChangeDebounceMs = 0L,
        )
        runBlocking { transport.startBrowsing() }
        assertTrue("browsing must register a connectivity observer", bridge.networkObserved)
        assertEquals(1, bridge.browseStartCount)

        bridge.fireNetworkChanged()

        // The debounce is a REAL delay (only the browse-loop sleep is stubbed), so
        // the restart lands on a scheduler thread shortly after.
        assertTrue(awaitTrue { bridge.browseStartCount >= 2 })
        assertTrue(bridge.stopBrowseCount >= 1)
        runBlocking { transport.stop() }
        assertFalse(bridge.networkObserved)
    }

    @Test
    fun hotspotComingUpWhileWifiStaysConnected_reArmsDiscovery() {
        // ERROR-035, the transition with NO platform signal whatsoever: turning this device's
        // hotspot on while it stays joined to Wi-Fi creates ap0 with a new subnet, but a SoftAP
        // interface is not a Network — no NetworkCallback fires, the default network is unchanged,
        // and TetheringManager's callback is API 30+ (this project ships to API 27). Without the
        // interface poll the unbound browse keeps running on wlan0 only and the tethered client is
        // never discovered.
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        var now = 1_000L
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            nowMs = { now },
            presenceHeartbeatMs = 1_000L,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 10,
            networkChangeDebounceMs = 0L,
        )
        runBlocking { transport.startBrowsing() }
        assertEquals(1, bridge.browseStartCount)

        // First tick only seeds the baseline — the interfaces we booted on are not a change.
        now += 10_000L
        ticker.tick()
        assertEquals("seeding must not restart the browse", 1, bridge.browseStartCount)

        bridge.fingerprint = "ap0=192.168.43.1;wlan0=192.168.1.20"
        now += 10_000L
        ticker.tick()

        assertTrue(awaitTrue { bridge.browseStartCount >= 2 })
        runBlocking { transport.stop() }
    }

    @Test
    fun unchangedInterfaces_neverRestartTheBrowse() {
        // The poll runs every heartbeat for the whole browse session, so a stable device must pay
        // nothing for it. A browse restart tears down the radio browse and costs a full re-discovery
        // grace window; doing that every 10s would be far worse than the bug it fixes.
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        var now = 1_000L
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            nowMs = { now },
            presenceHeartbeatMs = 1_000L,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 20,
            networkChangeDebounceMs = 0L,
        )
        runBlocking { transport.startBrowsing() }

        repeat(10) {
            now += 10_000L
            ticker.tick()
        }

        assertEquals(1, bridge.browseStartCount)
        assertTrue("the poll must actually be running", bridge.fingerprintReads >= 10)
        runBlocking { transport.stop() }
    }

    @Test
    fun flappingInterface_isRateLimitedToOneReArmPerTwoHeartbeats() {
        // An interface that goes up and down repeatedly (a hotspot being toggled, a USB tether
        // being reseated) would otherwise buy a browse teardown on every single tick for as long as
        // it lasted. The tracker's floor is two heartbeat periods, so alternating values every tick
        // can re-arm at most every other tick.
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        var now = 1_000L
        val heartbeatMs = 10_000L
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            nowMs = { now },
            presenceHeartbeatMs = heartbeatMs,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 20,
            networkChangeDebounceMs = 0L,
        )
        runBlocking { transport.startBrowsing() }

        now += heartbeatMs
        ticker.tick() // seed

        repeat(8) { index ->
            bridge.fingerprint = if (index % 2 == 0) "wlan0=192.168.1.20" else "ap0=192.168.43.1"
            now += heartbeatMs
            ticker.tick()
        }

        // 8 alternating ticks spanning 80s against a 20s floor: at most 5 verdicts, so at most 5
        // extra browse starts on top of the original.
        assertTrue(
            "flapping produced ${bridge.browseStartCount - 1} re-arms in 8 ticks",
            bridge.browseStartCount - 1 <= 5,
        )
        assertTrue("at least one flap must be acted on", bridge.browseStartCount >= 2)
        runBlocking { transport.stop() }
    }

    @Test
    fun interfaceBaselineIsForgottenOnStop() {
        // A restarted transport must re-seed: diffing a new session's first reading against the
        // previous session's would restart the browse immediately on every start().
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        var now = 1_000L
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            nowMs = { now },
            presenceHeartbeatMs = 1_000L,
            presenceSleep = ticker.sleep,
            maxPresenceTicks = 20,
            networkChangeDebounceMs = 0L,
        )
        runBlocking { transport.startBrowsing() }
        now += 10_000L
        ticker.tick()
        runBlocking { transport.stop() }

        bridge.fingerprint = "ap0=192.168.43.1"
        runBlocking { transport.startBrowsing() }
        val startsAfterRestart = bridge.browseStartCount
        now += 10_000L
        ticker.tick()

        assertEquals(
            "the first reading of a new session is a baseline, not a change",
            startsAfterRestart,
            bridge.browseStartCount,
        )
        runBlocking { transport.stop() }
    }

    // ------------------------------------------------------------------
    // Fast monitor retry + advertise watchdog (discovery-latency fixes)
    // ------------------------------------------------------------------

    @Test
    fun monitorStartFailure_isRetriedFast_notOnlyByTheHeartbeat() {
        // The presence heartbeat used to be the only retry path, so one failed resolve cost a
        // full 10s tick of discovery latency, two cost 20s, three cost 30s.
        val ticker = ManualTicker()
        val bridge = FakeBridge().apply { monitorResult = false }
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            presenceHeartbeatMs = 0L, // no heartbeat at all: the fast retry is the only path here
            monitorRetryMs = 600L,
            monitorRetrySleep = ticker.sleep,
        )
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("Flash Peer")
        assertEquals(1, bridge.monitorRequests.size)

        bridge.monitorResult = true
        ticker.tick()

        assertEquals(2, bridge.monitorRequests.size)
        assertEquals(listOf(600L), ticker.waits)
        runBlocking { transport.stop() }
    }

    @Test
    fun asyncMonitorRegistrationFailure_demotesTheService_andRetriesIt() {
        // registerServiceInfoCallback did not throw, so the service was optimistically marked
        // monitored; the platform then reported the failure asynchronously. That callback carried
        // no service name, so it was logged and dropped — leaving the peer permanently "monitored"
        // and therefore never retried and never evicted.
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            monitorRetryMs = 600L,
            monitorRetrySleep = ticker.sleep,
        )
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("Flash Peer")
        assertEquals(1, bridge.monitorRequests.size)

        bridge.fireMonitorRegistrationFailed("Flash Peer", errorCode = 3)
        ticker.tick()

        assertEquals(2, bridge.monitorRequests.size)
        assertEquals("Flash Peer", bridge.monitorRequests.last().serviceName)
        runBlocking { transport.stop() }
    }

    @Test
    fun resolvedService_isNotRetried() {
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            monitorRetryMs = 600L,
            monitorRetrySleep = ticker.sleep,
        )
        runBlocking { transport.startBrowsing() }

        bridge.fireServiceFound("Flash Peer")
        bridge.fireMonitorUpdated(resolvedData())

        // A healthy monitor schedules nothing, so the retry sleep was never even entered.
        assertEquals(1, bridge.monitorRequests.size)
        assertTrue(ticker.waits.isEmpty())
        runBlocking { transport.stop() }
    }

    @Test
    fun advertiseWatchdog_reRegistersAnAdvertisementTheFrameworkDropped() {
        // NSD offers no positive "still advertised" signal, and Android drops registrations for
        // reasons an app cannot prevent (mDNS daemon restart, interface change, OEM freeze).
        // Nothing used to put one back, so the device stayed invisible to every peer.
        val ticker = ManualTicker()
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            advertiseWatchdogMs = 10_000L,
            advertiseWatchdogSleep = ticker.sleep,
            maxAdvertiseWatchdogTicks = 2,
        )
        runBlocking { transport.startAdvertising(45821, identity()) }
        assertEquals(1, bridge.advertiseRequests.size)

        // A healthy registration is left alone.
        ticker.tick()
        assertEquals(1, bridge.advertiseRequests.size)

        bridge.fireAdvertiseUnregistered()
        ticker.tick()

        assertEquals(2, bridge.advertiseRequests.size)
        assertEquals(45821, bridge.advertiseRequests.last().port)
        runBlocking { transport.stop() }
    }

    @Test
    fun advertiseWatchdog_recoversFromARegistrationFailure_keepingTheMulticastLock() {
        val ticker = ManualTicker()
        val bridge = FakeBridge().apply { advertiseFailureCode = 3 } // NSD FAILURE_INTERNAL_ERROR
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            advertiseWatchdogMs = 10_000L,
            advertiseWatchdogSleep = ticker.sleep,
            maxAdvertiseWatchdogTicks = 1,
        )
        runBlocking { transport.startAdvertising(45821, identity()) }
        assertEquals(1, bridge.advertiseRequests.size)
        // Giving up the lock here would strand the retry below without multicast reception.
        assertFalse("multicast lock must survive a registration failure", bridge.lockStates.contains(false))

        bridge.advertiseFailureCode = null
        ticker.tick()

        assertEquals(2, bridge.advertiseRequests.size)
        runBlocking { transport.stop() }
    }

    @Test
    fun connectivityChange_reRegistersAdvertising_evenWhileItReportsHealthy() {
        // A registration pinned to a vanished interface keeps reporting itself as healthy — the
        // advertise-side twin of the dead-browse problem restartBrowsing() exists for. This is an
        // advertise-only transport, which also proves startAdvertising arms the observer itself.
        val bridge = FakeBridge()
        val transport = newTransport(
            apiLevel = 34,
            directory = StandardEndpointDirectory(),
            bridge = bridge,
            networkChangeDebounceMs = 0L,
        )
        runBlocking { transport.startAdvertising(45821, identity()) }
        assertTrue("advertising must register a connectivity observer", bridge.networkObserved)
        assertEquals(1, bridge.advertiseRequests.size)

        bridge.fireNetworkChanged()

        assertEquals(2, bridge.advertiseRequests.size)
        assertEquals(1, bridge.unadvertiseCount)
        assertEquals(0, bridge.browseStartCount) // never browsing: no browse restart
        runBlocking { transport.stop() }
    }

    /** Bounded spin for the one assertion that crosses a thread boundary. */
    private fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
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
