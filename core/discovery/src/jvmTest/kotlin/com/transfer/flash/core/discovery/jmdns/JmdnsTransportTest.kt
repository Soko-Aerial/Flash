// EndpointDirectory and StandardEndpointDirectory are @FlashInternalApi — library-internal
// building blocks, opted into here for the same reason JmdnsTransport.kt does.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.jmdns

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.jmdns.ServiceInfo

/**
 * Behavioural suite for the desktop mDNS transport, driven entirely through a fake [JmdnsBridge]
 * so it runs on a CI box with no multicast.
 *
 * These assertions exist because PHASE-14 shipped a sample implementation that emitted no
 * `Presence`, no `StateChanged` and overrode no `restartBrowsing` — defects that compile
 * perfectly and only surface as "the peer disappears after 30 seconds" on real hardware. Each
 * test below pins one of the contract obligations named in [JmdnsTransport]'s KDoc.
 */
public class JmdnsTransportTest {

    // -- Fixtures --------------------------------------------------------------

    private class FakeJmdnsBridge(private val failOpen: Boolean = false) : JmdnsBridge {
        val calls = mutableListOf<String>()
        val resolveRequests = mutableListOf<String>()
        var registered: JmdnsAdvertiseRequest? = null
        var events: JmdnsBrowseEvents? = null

        override fun open() {
            calls += "open"
            if (failOpen) throw IOException("no usable mDNS interface on this host")
        }

        override fun register(request: JmdnsAdvertiseRequest) {
            calls += "register"
            registered = request
        }

        override fun unregisterAll() {
            calls += "unregisterAll"
            registered = null
        }

        override fun startBrowse(serviceType: String, events: JmdnsBrowseEvents) {
            calls += "startBrowse"
            this.events = events
        }

        override fun stopBrowse(serviceType: String) {
            calls += "stopBrowse"
        }

        override fun requestServiceInfo(serviceType: String, serviceName: String) {
            calls += "requestServiceInfo"
            resolveRequests += serviceName
        }

        override fun close() {
            calls += "close"
            events = null
            // Closing every responder destroys the records they were holding, exactly as
            // RealJmdnsBridge does. Modelling that is what lets a test prove restartBrowsing()
            // re-registers the advertisement instead of going silently invisible.
            registered = null
        }
    }

    private val identity = FlashAdvertisedIdentity(
        deviceId = FlashDeviceId("desktop-self"),
        friendlyName = "Flash Desktop",
        deviceModel = "linux-x64",
        protocolVersion = FlashProtocol.VERSION,
    )

    /**
     * `Dispatchers.Unconfined` rejects `limitedParallelism`, so the transport's lane falls back to
     * the raw dispatcher and every `launch` runs eagerly on the calling thread — the whole suite is
     * therefore synchronous, with no virtual time and no polling. All sleeps are no-ops and the
     * heartbeat loop is disabled (`maxPresenceTicks = 0`); tests that need a tick call
     * [JmdnsTransport.presenceTick] directly, which `jvmTest` can see because it is an associated
     * compilation of `jvmMain`.
     */
    private fun withTransport(
        bridge: FakeJmdnsBridge = FakeJmdnsBridge(),
        directory: EndpointDirectory = StandardEndpointDirectory(),
        sweep: (Long) -> List<EndpointDirectory.Diff.Lost> = { emptyList() },
        mode: FlashDiscoveryMode = FlashDiscoveryMode.STANDARD,
        lostDebounceMs: Long = 0L,
        // Injectable so a test can advance the clock across a cooldown without waiting for one.
        nowMs: () -> Long = { 1_000L },
        // Captures the transport's own narrative; the drop lines are its only rate measurement.
        logInfo: (String) -> Unit = { },
        block: suspend (JmdnsTransport, FakeJmdnsBridge, MutableList<FlashTransportEvent>) -> Unit,
    ) = runBlocking {
        val transport = JmdnsTransport(
            directory = directory,
            sweep = sweep,
            initialModePolicy = DiscoveryModePolicy.forMode(mode),
            timeSourceMs = nowMs,
            sleep = { },
            presenceSleep = { },
            maxPresenceTicks = 0,
            lostDebounceMs = lostDebounceMs,
            dispatcher = Dispatchers.Unconfined,
            bridgeOverride = bridge,
            logInfo = logInfo,
        )
        val seen = mutableListOf<FlashTransportEvent>()
        // Unconfined starts the collector eagerly, so the subscription is live before the first
        // emit — required, because the events flow has replay = 0.
        val collector = launch(Dispatchers.Unconfined) { transport.events.collect { seen += it } }
        try {
            block(transport, bridge, seen)
        } finally {
            collector.cancel()
        }
    }

    /**
     * The hollow resolution JmDNS 3.5.12 hands back when the TXT has not reached its cache:
     * correct address and port, `EMPTY_TXT` (one zero byte, `txtByteCount = 1`), no attributes.
     */
    private fun hollow(serviceName: String = "Flash Pixel") =
        JmdnsResolvedService(
            hostAddress = "192.168.1.20",
            port = 8080,
            serviceName = serviceName,
            attributes = emptyMap(),
            txtByteCount = 1,
        )

    private fun resolved(
        deviceId: String? = "peer-1",
        serviceName: String = "Flash Pixel",
        friendlyName: String? = "Pixel 8",
        proto: Int? = FlashProtocol.VERSION,
        hostAddress: String? = "192.168.1.20",
        port: Int = 8080,
        caps: String? = null,
    ): JmdnsResolvedService {
        // Literal TXT keys on purpose: they are the wire contract, so a test that reused
        // TxtCodec's constants could not catch a key being renamed.
        val attributes = LinkedHashMap<String, String>()
        deviceId?.let { attributes["device_id"] = it }
        friendlyName?.let { attributes["name"] = it }
        proto?.let { attributes["proto"] = it.toString() }
        caps?.let { attributes["caps"] = it }
        return JmdnsResolvedService(hostAddress, port, serviceName, attributes)
    }

    // -- Found / Updated / Presence -------------------------------------------

    @Test
    public fun firstResolveEmitsFoundAndRepeatResolveEmitsPresence(): Unit = withTransport {
        transport, bridge, seen ->
        transport.startBrowsing()
        bridge.events!!.onServiceResolved(resolved())
        bridge.events!!.onServiceResolved(resolved())

        val found = seen.filterIsInstance<FlashTransportEvent.Found>()
        val presence = seen.filterIsInstance<FlashTransportEvent.Presence>()
        assertEquals(1, found.size)
        assertEquals("peer-1", found.single().endpoint.deviceId.value)
        assertEquals("Pixel 8", found.single().endpoint.friendlyName)
        assertEquals("192.168.1.20", found.single().endpoint.hostAddress)
        assertEquals(8080, found.single().endpoint.port)
        // The identical second sighting dedups to Diff.Unchanged. Emitting nothing there is the
        // bug that makes a live peer vanish once the consumer's 30 s TTL expires.
        assertEquals(1, presence.size)
        assertTrue(seen.none { it is FlashTransportEvent.Updated })
    }

    @Test
    public fun changedPortEmitsUpdatedNotPresence(): Unit = withTransport { transport, bridge, seen ->
        transport.startBrowsing()
        bridge.events!!.onServiceResolved(resolved())
        bridge.events!!.onServiceResolved(resolved(port = 9090))

        val updated = seen.filterIsInstance<FlashTransportEvent.Updated>()
        assertEquals(1, updated.size)
        assertEquals(9090, updated.single().endpoint.port)
        assertTrue(seen.none { it is FlashTransportEvent.Presence })
    }

    @Test
    public fun presenceTickReAffirmsOnlyServicesTheRadioStillVouchesFor(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved())
            seen.clear()

            transport.presenceTick()
            assertEquals(1, seen.filterIsInstance<FlashTransportEvent.Presence>().size)

            // A goodbye withdraws the vouch, so later ticks must NOT keep the peer alive.
            bridge.events!!.onServiceRemoved("Flash Pixel")
            seen.clear()
            transport.presenceTick()
            assertTrue(seen.none { it is FlashTransportEvent.Presence })
        }

    // -- Identity and protocol gating (must match NsdTransport exactly) --------

    @Test
    public fun ourOwnAdvertisementIsFilteredByDeviceId(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startAdvertising(8080, identity)
            transport.startBrowsing()
            seen.clear()
            // Our own responder resolves our own service; filtering is by identity, never by a
            // service-name string match (plan C3.2).
            bridge.events!!.onServiceResolved(
                resolved(deviceId = "desktop-self", serviceName = "Flash Flash Desktop"),
            )
            assertTrue(seen.none { it is FlashTransportEvent.Found })
        }

    @Test
    public fun incompatibleProtocolVersionIsDroppedBeforeTheDirectory(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved(proto = FlashProtocol.VERSION + 7))
            assertTrue(seen.none { it is FlashTransportEvent.Found })
        }

    @Test
    public fun missingProtocolFallsBackToOurVersionAndIsAccepted(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            // Tolerant decode parity with androidMain's NsdTxtCodec: a MISSING proto degrades to
            // our version so a pre-P3.5 advertiser stays visible; the shared strict TxtCodec would
            // return null here and the desktop would hide a peer Android shows.
            bridge.events!!.onServiceResolved(resolved(proto = null))
            val found = seen.filterIsInstance<FlashTransportEvent.Found>().single()
            assertEquals(FlashProtocol.VERSION, found.endpoint.device.protocolVersion)
        }

    @Test
    public fun missingDeviceIdIsDropped(): Unit = withTransport { transport, bridge, seen ->
        transport.startBrowsing()
        bridge.events!!.onServiceResolved(resolved(deviceId = null))
        assertTrue(seen.none { it is FlashTransportEvent.Found })
    }

    @Test
    public fun resolveWithoutAnAddressIsIgnoredUntilTheNextUpdate(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved(hostAddress = null))
            assertTrue(seen.none { it is FlashTransportEvent.Found })
            bridge.events!!.onServiceResolved(resolved())
            assertEquals(1, seen.filterIsInstance<FlashTransportEvent.Found>().size)
        }

    @Test
    public fun missingFriendlyNameFallsBackToTheServiceName(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved(friendlyName = null))
            assertEquals(
                "Flash Pixel",
                seen.filterIsInstance<FlashTransportEvent.Found>().single().endpoint.friendlyName,
            )
        }

    // -- StateChanged ----------------------------------------------------------

    @Test
    public fun startBrowsingEmitsStateChangedWithBrowsingTrue(): Unit =
        withTransport { transport, _, seen ->
            transport.startBrowsing()
            // The ONLY input to CompositeDiscovery.applyBrowseState(), which sets isDiscovering and
            // the stall stamps its watchdog reads. A transport that never emits this is invisible
            // to recovery.
            val state = seen.filterIsInstance<FlashTransportEvent.StateChanged>().single()
            assertTrue(state.browsing)
        }

    @Test
    public fun advertisingOnlyNeverClaimsToBeBrowsing(): Unit =
        withTransport { transport, _, seen ->
            transport.startAdvertising(8080, identity)
            // browsing = false here, and StateChanged must say so: applyBrowseState treats
            // browsing = true as "this radio is healthy", so an advertising-only transport that
            // reported true could never be watchdog-recovered.
            assertFalse(seen.filterIsInstance<FlashTransportEvent.StateChanged>().last().browsing)
        }

    @Test
    public fun stopEmitsStateChangedWithBrowsingFalse(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            seen.clear()
            transport.stop()
            assertFalse(seen.filterIsInstance<FlashTransportEvent.StateChanged>().single().browsing)
            assertTrue(bridge.calls.contains("close"))
        }

    // -- Loss, debounce and sweep ----------------------------------------------

    @Test
    public fun serviceRemovedEmitsLostCarryingTheServiceName(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved())
            seen.clear()

            bridge.events!!.onServiceRemoved("Flash Pixel")
            val lost = seen.filterIsInstance<FlashTransportEvent.Lost>().single()
            assertEquals("peer-1", lost.deviceId.value)
            // The typed Lost carries the serviceName so CompositeDiscovery can drop its
            // name→endpoint mapping; a bare deviceId would leak the mapping forever.
            assertEquals("Flash Pixel", lost.serviceName)
        }

    @Test
    public fun aReResolveInsideTheDebounceWindowCancelsTheLoss(): Unit {
        // The only test that needs a real (non-zero) debounce, so it drives the window by hand:
        // `sleep` blocks on a signal the test releases after the re-resolve has landed.
        val gate = CompletableDeferred<Unit>()
        val bridge = FakeJmdnsBridge()
        runBlocking {
            val transport = JmdnsTransport(
                directory = StandardEndpointDirectory(),
                sweep = { emptyList() },
                timeSourceMs = { 1_000L },
                sleep = { gate.await() },
                presenceSleep = { gate.await() },
                maxPresenceTicks = 0,
                lostDebounceMs = 60_000L,
                dispatcher = Dispatchers.Unconfined,
                bridgeOverride = bridge,
            )
            val seen = mutableListOf<FlashTransportEvent>()
            val collector = launch(Dispatchers.Unconfined) { transport.events.collect { seen += it } }
            try {
                transport.startBrowsing()
                bridge.events!!.onServiceResolved(resolved())
                // Positive control: this harness is bespoke, so prove the pipeline is live before
                // asserting an absence — otherwise a dead transport would pass the test.
                assertEquals(1, seen.filterIsInstance<FlashTransportEvent.Found>().size)
                bridge.events!!.onServiceRemoved("Flash Pixel")
                // Still inside the window: nothing withdrawn yet.
                assertTrue(seen.none { it is FlashTransportEvent.Lost })

                bridge.events!!.onServiceResolved(resolved())
                gate.complete(Unit)
                // The pending job resumes, sees it was cancelled, and emits nothing.
                assertTrue(seen.none { it is FlashTransportEvent.Lost })
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    public fun sweepResultsAreEmittedAsLostOnEveryPresenceTick(): Unit {
        // One-shot: the sweep hands its batch over exactly once, so `.single()` below is a real
        // assertion about the transport rather than about how often the lambda was polled.
        var pending = listOf(EndpointDirectory.Diff.Lost(FlashDeviceId("peer-1")))
        withTransport(sweep = { pending.also { pending = emptyList() } }) { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved())
            seen.clear()

            transport.presenceTick()
            val lost = seen.filterIsInstance<FlashTransportEvent.Lost>().single()
            assertEquals("peer-1", lost.deviceId.value)
            // Resolved from the serviceName↔deviceId map, not invented.
            assertEquals("Flash Pixel", lost.serviceName)
        }
    }

    @Test
    public fun pollSweepDrainsWithoutWaitingForATick(): Unit {
        var pending = listOf(EndpointDirectory.Diff.Lost(FlashDeviceId("peer-9")))
        withTransport(sweep = { pending.also { pending = emptyList() } }) { transport, _, seen ->
            transport.startBrowsing()
            seen.clear()
            transport.pollSweep()
            val lost = seen.filterIsInstance<FlashTransportEvent.Lost>().single()
            assertEquals("peer-9", lost.deviceId.value)
            // Never sighted, so there is no serviceName to report — null, not a fabricated string.
            assertNull(lost.serviceName)
        }
    }

    // -- Recovery --------------------------------------------------------------

    @Test
    public fun restartBrowsingRebindsTheResponderInsteadOfReAddingAListener(): Unit =
        withTransport { transport, bridge, _ ->
            transport.startBrowsing()
            bridge.calls.clear()

            transport.restartBrowsing()
            // A responder bound to an address that has gone away still reports itself healthy, so
            // removing and re-adding a listener on it recovers nothing. The contract is a full
            // close/open cycle that enumerates interfaces again.
            assertEquals(listOf("stopBrowse", "close", "open", "startBrowse"), bridge.calls)
        }

    @Test
    public fun restartBrowsingIsNotSwallowedByTheStartBrowsingEarlyReturn(): Unit =
        withTransport { transport, bridge, _ ->
            transport.startBrowsing()
            bridge.calls.clear()
            // FlashRadioTransport's default restartBrowsing() delegates to startBrowsing(), which
            // returns Success immediately while `browsing` is true. Inheriting that default makes
            // CompositeDiscovery.watchdogBrowsing() a silent no-op, which is why this override
            // exists — the assertion is that work actually happened.
            transport.restartBrowsing()
            assertTrue(bridge.calls.isNotEmpty())
        }

    @Test
    public fun restartBrowsingReRegistersAnAdvertisementTheRebindDropped(): Unit =
        withTransport { transport, bridge, _ ->
            transport.startAdvertising(8080, identity)
            transport.startBrowsing()
            assertNotNull(bridge.registered)

            transport.restartBrowsing()
            // close() destroyed the responders that held the record; without re-registration the
            // desktop goes silently invisible to peers after every recovery.
            assertNotNull(bridge.registered)
            assertEquals(8080, bridge.registered!!.port)
        }

    @Test
    public fun aBridgeThatCannotBindReportsNetworkUnavailable(): Unit =
        withTransport(bridge = FakeJmdnsBridge(failOpen = true)) { transport, _, _ ->
            val result = transport.startBrowsing()
            val error = (result as FlashResult.Failure).error
            // NetworkUnavailable, not Unknown: "this host has no usable multicast interface" is a
            // environment condition the engine can surface, not an internal fault.
            assertTrue(error is FlashError.NetworkUnavailable)
        }

    @Test
    public fun serviceAddedAsksForResolutionAndNothingElse(): Unit =
        withTransport { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.calls.clear()
            bridge.events!!.onServiceAdded(JmdnsTransport.DEFAULT_SERVICE_TYPE, "Flash Pixel")
            // An announcement carries neither address nor TXT data, so it can only trigger a
            // resolve — never a Found built from a half-empty record.
            assertEquals(listOf("Flash Pixel"), bridge.resolveRequests)
            assertTrue(seen.none { it is FlashTransportEvent.Found })
        }

    @Test
    public fun emptyTxt_isRetriedOnABudget_andNeverSpins() {
        // An empty TXT means "the attributes have not arrived yet", not "this peer publishes none",
        // so the transport re-resolves instead of dropping. The trap that made this dangerous: our
        // own `requestServiceInfo(persistent = true)` makes JmDNS re-deliver the SAME cached
        // ServiceInfo, so `handleServiceResolved` is re-entered immediately. A budget that merely
        // counted attempts and reset itself on exhaustion therefore re-armed a fresh burst on every
        // re-delivery — an unbounded resolve→drop→resolve loop, one BLOCKING JmDNS query per
        // attempt on a fresh dispatcher task, which pegged a core and grew the heap for as long as
        // the hollow record stayed cached.
        //
        // The measurement is the point: the work must depend on the budget, not on how often JmDNS
        // re-delivers, and the drop line must be a per-cooldown report and not a per-delivery one.
        val bridge = FakeJmdnsBridge()
        var now = 1_000L
        val drops = mutableListOf<String>()
        withTransport(bridge = bridge, nowMs = { now }, logInfo = { drops += it }) { transport, _, _ ->
            transport.startBrowsing()
            bridge.calls.clear()

            // The cache hands back the same attribute-less record again and again.
            repeat(20) { bridge.events!!.onServiceResolved(hollow()) }
            val resolvesAfterTwenty = bridge.resolveRequests.size
            repeat(180) { bridge.events!!.onServiceResolved(hollow()) }

            assertTrue(
                "a hollow record must be re-resolved a bounded number of times " +
                    "(got $resolvesAfterTwenty)",
                resolvesAfterTwenty in 1..5,
            )
            assertEquals(
                "200 deliveries must cost exactly what 20 did — no progress, no extra work",
                resolvesAfterTwenty,
                bridge.resolveRequests.size,
            )
            assertTrue(
                "the drop is reported at the cooldown boundary, not once per delivery",
                drops.isEmpty(),
            )

            // Past the cooldown: one report, and the next cycle is armed so a late TXT still lands.
            now += 61_000L
            bridge.events!!.onServiceResolved(hollow())
            assertEquals(1, drops.size)
            assertTrue(drops.single().startsWith("Dropping mDNS endpoint without device_id"))
            val resolvesAtCooldown = bridge.resolveRequests.size
            bridge.events!!.onServiceResolved(hollow())
            assertEquals(
                "the cooldown boundary must start a fresh retry cycle",
                resolvesAtCooldown + 1,
                bridge.resolveRequests.size,
            )
        }
    }

    @Test
    public fun emptyTxt_thatLaterResolvesWithAttributes_isAcceptedAndClearsItsBudget() {
        // The retry exists so a slow TXT is not a permanent peer defect. Once the record arrives
        // with attributes the peer must be Found, and its budget must be forgotten so a later
        // re-announcement starts from a clean count.
        val bridge = FakeJmdnsBridge()
        var now = 1_000L
        val drops = mutableListOf<String>()
        withTransport(bridge = bridge, nowMs = { now }, logInfo = { drops += it }) { transport, _, seen ->
            transport.startBrowsing()
            repeat(4) { bridge.events!!.onServiceResolved(hollow()) }
            assertTrue(seen.none { it is FlashTransportEvent.Found })

            bridge.events!!.onServiceResolved(resolved(serviceName = "Flash Pixel"))
            val found = seen.filterIsInstance<FlashTransportEvent.Found>()
            assertEquals(1, found.size)
            assertEquals("peer-1", found.single().endpoint.deviceId.value)
            assertTrue(drops.isEmpty())
        }
    }

    // -- Mode policy -----------------------------------------------------------

    @Test
    public fun ghostModeRecordsTheIdentityButRegistersNothing(): Unit =
        withTransport(mode = FlashDiscoveryMode.GHOST) { transport, bridge, _ ->
            transport.startAdvertising(8080, identity)
            assertNull(bridge.registered)
            // Not even the responder is bound: GHOST must be radio-silent on the advertise path.
            assertFalse(bridge.calls.contains("open"))
        }

    @Test
    public fun ghostStillBrowses(): Unit =
        withTransport(mode = FlashDiscoveryMode.GHOST) { transport, bridge, seen ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved())
            // GHOST suppresses advertising only. A ghost device that could not see anyone would be
            // useless as a receiver.
            assertEquals(1, seen.filterIsInstance<FlashTransportEvent.Found>().size)
        }

    @Test
    public fun leavingGhostResumesTheRecordedAdvertisement(): Unit =
        withTransport(mode = FlashDiscoveryMode.GHOST) { transport, bridge, _ ->
            transport.startAdvertising(8080, identity)
            assertNull(bridge.registered)

            transport.setMode(DiscoveryModePolicy.forMode(FlashDiscoveryMode.STANDARD))
            // startAdvertising recorded identity + port precisely so this transition needs no
            // second call from the engine.
            assertNotNull(bridge.registered)
            assertEquals(8080, bridge.registered!!.port)
        }

    @Test
    public fun enteringGhostWithdrawsTheLiveAdvertisement(): Unit =
        withTransport { transport, bridge, _ ->
            transport.startAdvertising(8080, identity)
            assertNotNull(bridge.registered)

            transport.setMode(DiscoveryModePolicy.forMode(FlashDiscoveryMode.GHOST))
            assertTrue(bridge.calls.contains("unregisterAll"))
            assertNull(bridge.registered)
        }

    @Test
    public fun advertisedTxtCarriesTheWireKeysAndTruncatesTheInstanceName(): Unit =
        withTransport { transport, bridge, _ ->
            transport.startAdvertising(
                8080,
                identity.copy(friendlyName = "A friendly name far longer than the limit"),
            )
            val request = bridge.registered!!
            assertEquals("desktop-self", request.attributes["device_id"])
            assertEquals(FlashProtocol.VERSION.toString(), request.attributes["proto"])
            // "Flash " + 24 characters. DNS-SD instance names are length-bounded, and Android
            // truncates at exactly the same MAX_NAME_LENGTH.
            assertEquals("Flash A friendly name far long", request.serviceName)
        }

    // -- Cross-platform wire agreement -----------------------------------------

    @Test
    public fun transportNameMatchesTheAndroidSiblingsRankingBehaviour(): Unit =
        withTransport { transport, _, _ ->
            // "jmdns", deliberately not "LAN": CompositeDiscovery.priorityRank() looks the
            // uppercased name up in PRIORITY_ORDER and returns worst-rank on a miss. Android's
            // "nsd" misses too, so both LAN transports must miss for cross-platform dedup to
            // behave the same on both sides — the Phase 16 interop gate depends on it.
            assertEquals("jmdns", transport.transportName)
        }

    @Test
    public fun theServiceTypeConstantDenotesTheSameServiceAsTheAndroidForm() {
        // Runs against the real JmDNS parser and needs no multicast. Android's NsdTransport uses
        // "_flash-transfer._tcp." and NsdManager appends the domain; JmDNS wants it spelled out.
        // If a JmDNS upgrade ever stopped normalising the two to one type, the platforms would
        // silently stop seeing each other — hence an assertion rather than a comment.
        val desktopForm = ServiceInfo.create(
            JmdnsTransport.DEFAULT_SERVICE_TYPE,
            "Flash Desktop",
            8080,
            /* weight = */ 0,
            /* priority = */ 0,
            emptyMap<String, String>(),
        )
        val androidForm = ServiceInfo.create(
            "_flash-transfer._tcp.",
            "Flash Pixel",
            8080,
            /* weight = */ 0,
            /* priority = */ 0,
            emptyMap<String, String>(),
        )
        assertEquals("_flash-transfer._tcp.local.", desktopForm.type)
        assertEquals(desktopForm.type, androidForm.type)
    }

    // -- capability logging -----------------------------------------------------

    @Test
    public fun peerCapabilitiesAreLoggedOncePerServiceNotOnEveryResolve(): Unit {
        // Reported 2026-09-14 as "it's looping": the desktop console filled with
        // `Peer capabilities caps=mobile name=Flash V760` for a phone that was simply sitting
        // there. The line was emitted on every resolve, and the same resolve is re-delivered
        // constantly — `presenceTick` re-queries each vouched peer every 10 s with
        // `persistent = true`, so JmDNS hands back its cached record immediately.
        val lines = mutableListOf<String>()
        withTransport(logInfo = { lines += it }) { transport, bridge, _ ->
            transport.startBrowsing()
            val resolution = resolved(caps = "mobile")
            bridge.events!!.onServiceResolved(resolution)
            bridge.events!!.onServiceResolved(resolution)
            bridge.events!!.onServiceResolved(resolution)

            // JUnit 4 argument order: message FIRST. (`kotlin.test` puts it last; this file
            // imports `org.junit.Assert`.)
            assertEquals(
                "a static peer attribute must not be re-logged on every sighting",
                1,
                lines.count { it.contains("Peer capabilities") },
            )
        }
    }

    @Test
    public fun aChangedCapabilitySetIsLoggedAgain(): Unit {
        // The gate is "changed", not "once ever": a peer that starts advertising a different kind
        // must still say so, or the line would be a one-shot that hides the transition it exists to
        // make visible.
        val lines = mutableListOf<String>()
        withTransport(logInfo = { lines += it }) { transport, bridge, _ ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(resolved(caps = "mobile"))
            bridge.events!!.onServiceResolved(resolved(caps = "mobile"))
            bridge.events!!.onServiceResolved(resolved(caps = "desktop"))

            val capsLines = lines.filter { it.contains("Peer capabilities") }
            assertEquals("the transition must be reported once", 2, capsLines.size)
            assertTrue("the new set must be the one reported", capsLines.last().contains("caps=desktop"))
        }
    }

}
