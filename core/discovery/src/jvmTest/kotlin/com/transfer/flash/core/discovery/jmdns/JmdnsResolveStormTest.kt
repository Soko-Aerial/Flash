// EndpointDirectory and StandardEndpointDirectory are @FlashInternalApi - library-internal
// building blocks, opted into here for the same reason JmdnsTransport.kt does.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.jmdns

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for BUG-001: the desktop mDNS resolve storm.
 *
 * **The defect.** Every call to [JmdnsBridge.requestServiceInfo] makes JmDNS build a fresh
 * `ServiceInfoResolver` and ADD A LISTENER to a synchronised collection - it does not replace the
 * one already there. Three code paths called it on a cadence the app could not bound:
 *
 * 1. `onServiceAdded` fired on every peer announcement (a phone announces every few seconds) and
 *    resolved unconditionally.
 * 2. `presenceTick` re-resolved every vouched peer every 10 s.
 * 3. The empty-TXT retry budget was per-cycle and `emptyTxtRetries.remove(name)` ran on EVERY
 *    successful resolve, so a peer whose real and hollow TXT records ALTERNATE re-armed the
 *    "bounded" retry forever - an unbounded resolve/drop/resolve loop.
 *
 * Measured 2026-09-14 against the running desktop app: 633,997 resolve requests in ~50 seconds
 * from two service names, 62 threads BLOCKED in `JmDNSImpl.addListener` (of 128 live), 669% of one
 * core, 950 MB of a 1 GB heap. Those blocked workers share `Dispatchers.Default` with the dial and
 * WS-handshake coroutines, so the starvation also surfaced as `WS handshake timed out` and peers
 * appearing then vanishing.
 *
 * **What this file pins.** Each test counts [JmdnsBridge.requestServiceInfo] calls on a fake
 * bridge, which is the resource unit: one call = one blocking JmDNS query + one listener JmDNS
 * never removes. Every assertion below fails on the pre-fix revision (ff31981).
 */
public class JmdnsResolveStormTest {

    private class CountingBridge : JmdnsBridge {
        val resolveRequests = mutableListOf<String>()
        val registered = mutableListOf<JmdnsAdvertiseRequest>()
        var events: JmdnsBrowseEvents? = null
        var opened = false

        /** Runs inside `close()`, standing for the seconds JmDNS spends closing while resolve requests keep arriving. */
        var duringClose: () -> Unit = { }

        override fun open() {
            opened = true
        }

        override fun register(request: JmdnsAdvertiseRequest) {
            registered += request
        }

        override fun unregisterAll() = Unit

        override fun startBrowse(serviceType: String, events: JmdnsBrowseEvents) {
            this.events = events
        }

        override fun stopBrowse(serviceType: String) {
            events = null
        }

        override fun requestServiceInfo(serviceType: String, serviceName: String) {
            resolveRequests += serviceName
        }

        override fun close() {
            duringClose()
            events = null
            opened = false
        }
    }

    private companion object {
        const val TYPE = "_flash-transfer._tcp.local."
        const val NAME = "Flash Storm"

        fun hollow(serviceName: String = NAME) = JmdnsResolvedService(
            hostAddress = "192.168.1.20",
            port = 8080,
            serviceName = serviceName,
            attributes = emptyMap(),
            txtByteCount = 1,
        )

        fun real(serviceName: String = NAME, deviceId: String = "peer-1") = JmdnsResolvedService(
            hostAddress = "192.168.1.20",
            port = 8080,
            serviceName = serviceName,
            attributes = linkedMapOf(
                "device_id" to deviceId,
                "name" to "Pixel 8",
                "proto" to FlashProtocol.VERSION.toString(),
            ),
        )
    }

    /**
     * `Dispatchers.Unconfined` rejects `limitedParallelism`, so the transport's lane falls back to
     * the raw dispatcher and every `launch` runs eagerly on the calling thread - the suite is
     * synchronous, so the counts are exact rather than approximate. `maxPresenceTicks = 0` disables
     * the heartbeat loop so only an explicit [JmdnsTransport.presenceTick] can produce a tick.
     */
    private fun withTransport(
        bridge: CountingBridge = CountingBridge(),
        nowMs: () -> Long = { 1_000L },
        directory: EndpointDirectory = StandardEndpointDirectory(),
        block: suspend (JmdnsTransport, CountingBridge) -> Unit,
    ) = runBlocking {
        val transport = JmdnsTransport(
            directory = directory,
            sweep = { emptyList() },
            initialModePolicy = DiscoveryModePolicy.forMode(FlashDiscoveryMode.STANDARD),
            timeSourceMs = nowMs,
            sleep = { },
            presenceSleep = { },
            maxPresenceTicks = 0,
            lostDebounceMs = 0L,
            dispatcher = Dispatchers.Unconfined,
            bridgeOverride = bridge,
            logInfo = { },
            logWarn = { },
        )
        val collector: Job = launch(Dispatchers.Unconfined) { transport.events.collect { } }
        try {
            block(transport, bridge)
        } finally {
            collector.cancel()
        }
    }

    @Test
    public fun repeatedAnnouncementsAskForResolutionOnlyOnce() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            bridge.resolveRequests.clear()

            repeat(200) { bridge.events!!.onServiceAdded(TYPE, NAME) }

            assertEquals(
                "200 announcements of one unchanged service must cost exactly one resolve",
                1,
                bridge.resolveRequests.size,
            )
        }
    }

    @Test
    public fun presenceTicksNeverReResolveAnAlreadyResolvedPeer() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            bridge.events!!.onServiceResolved(real())
            bridge.resolveRequests.clear()

            repeat(50) { transport.presenceTick() }

            assertEquals(
                "an idle peer must cost zero resolves per heartbeat, not one per tick",
                0,
                bridge.resolveRequests.size,
            )
        }
    }

    @Test
    public fun alternatingHollowAndRealTxtCannotReArmTheRetryForever() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            bridge.resolveRequests.clear()

            repeat(200) {
                bridge.events!!.onServiceResolved(hollow())
                bridge.events!!.onServiceResolved(real())
            }

            assertTrue(
                "alternating real/hollow TXT for one service must not keep re-resolving " +
                    "(got ${bridge.resolveRequests.size})",
                bridge.resolveRequests.size <= 4,
            )
        }
    }

    @Test
    public fun aHollowRecordAloneIsRetriedOnABudgetAndThenStops() {
        var now = 1_000L
        withTransport(nowMs = { now }) { transport, bridge ->
            transport.startBrowsing()
            bridge.resolveRequests.clear()

            repeat(100) { bridge.events!!.onServiceResolved(hollow()) }
            val afterFirstBurst = bridge.resolveRequests.size
            assertTrue(
                "a hollow record is retried, but a bounded number of times (got $afterFirstBurst)",
                afterFirstBurst in 1..5,
            )

            repeat(400) { bridge.events!!.onServiceResolved(hollow()) }
            assertEquals(
                "further deliveries inside the cooldown must add no work at all",
                afterFirstBurst,
                bridge.resolveRequests.size,
            )

            now += 61_000L
            bridge.events!!.onServiceResolved(hollow())
            assertEquals(
                "the cooldown boundary reports the drop and arms the next cycle without resolving",
                afterFirstBurst,
                bridge.resolveRequests.size,
            )
            bridge.events!!.onServiceResolved(hollow())
            assertEquals(
                "the next cycle's first delivery is retried once",
                afterFirstBurst + 1,
                bridge.resolveRequests.size,
            )
        }
    }

    @Test
    public fun aPeerThatDisappearsAndReturnsIsResolvedAgain() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            bridge.events!!.onServiceAdded(TYPE, NAME)
            assertEquals(1, bridge.resolveRequests.size)

            bridge.events!!.onServiceResolved(real())
            bridge.events!!.onServiceRemoved(NAME)
            bridge.resolveRequests.clear()

            bridge.events!!.onServiceAdded(TYPE, NAME)
            assertEquals(
                "a returning peer must be resolved again",
                1,
                bridge.resolveRequests.size,
            )
        }
    }

    @Test
    public fun distinctServicesEachCostExactlyOneResolve() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            bridge.resolveRequests.clear()

            repeat(100) {
                bridge.events!!.onServiceAdded(TYPE, "Flash Storm")
                bridge.events!!.onServiceAdded(TYPE, "Flash Storm (2)")
            }

            assertEquals(
                "one resolve per distinct service name, regardless of announcement count",
                2,
                bridge.resolveRequests.size,
            )
            assertEquals(setOf("Flash Storm", "Flash Storm (2)"), bridge.resolveRequests.toSet())
        }
    }

    @Test
    public fun resolveStormWorkloadReportsItsCost() {
        withTransport { transport, bridge ->
            val startedAtNs = System.nanoTime()
            transport.startBrowsing()
            bridge.resolveRequests.clear()
            val announcements = 20_000
            repeat(announcements) {
                bridge.events!!.onServiceAdded(TYPE, NAME)
                bridge.events!!.onServiceResolved(hollow())
            }
            repeat(announcements) { bridge.events!!.onServiceResolved(real()) }
            val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000

            println(
                "FLASH_RESOLVE_STORM_BENCH deliveries=${announcements * 3} " +
                    "resolveRequests=${bridge.resolveRequests.size} elapsedMs=$elapsedMs",
            )
            assertTrue(
                "the workload must stay bounded (got ${bridge.resolveRequests.size})",
                bridge.resolveRequests.size <= 4,
            )
        }
    }

    private val self = FlashAdvertisedIdentity(
        deviceId = FlashDeviceId("own-device"),
        friendlyName = "Flash Bunny",
        deviceModel = "linux-x64",
        protocolVersion = FlashProtocol.VERSION,
    )

    /**
     * ERROR-122, the Linux laptop of 2026-10-08. The app's OWN service name resolved with alternating real and hollow
     * TXT. A self-resolution returns before the name is vouched, so the vouched guard never covered it, and every real
     * delivery reset the retry budget: forced resolves for ever, thousands of stacked JmDNS listeners, OutOfMemoryError.
     */
    @Test
    public fun ownServiceNameAlternatingRealAndHollowNeverTriggersResolves() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            transport.startAdvertising(8080, self)
            val ownName = bridge.registered.single().serviceName
            bridge.resolveRequests.clear()

            repeat(500) {
                bridge.events!!.onServiceAdded(TYPE, ownName)
                bridge.events!!.onServiceResolved(hollow(ownName))
                bridge.events!!.onServiceResolved(real(ownName, deviceId = "own-device"))
            }

            assertEquals("our own advertisement must cost no resolve at all", 0, bridge.resolveRequests.size)
        }
    }

    /** A collision-renamed copy of our own service (`Name (2)`) is learned from its device id, then left alone. */
    @Test
    public fun renamedOwnServiceStopsBeingRetriedAfterItsFirstRealResolve() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            transport.startAdvertising(8080, self)
            bridge.resolveRequests.clear()
            val renamed = "Flash Flash Bunny (2)"

            bridge.events!!.onServiceResolved(real(renamed, deviceId = "own-device"))
            repeat(200) {
                bridge.events!!.onServiceResolved(hollow(renamed))
                bridge.events!!.onServiceResolved(real(renamed, deviceId = "own-device"))
            }

            assertEquals(0, bridge.resolveRequests.size)
        }
    }

    /** Whatever the cause, the cost of resolving is capped per minute and the window then re-opens. */
    @Test
    public fun resolveRequestsAreCappedPerWindowAndRecover() {
        var now = 1_000L
        withTransport(nowMs = { now }) { transport, bridge ->
            transport.startBrowsing()
            bridge.resolveRequests.clear()

            repeat(2_000) { bridge.events!!.onServiceAdded(TYPE, "Flash Peer $it") }
            assertEquals(JmdnsTransport.MAX_RESOLVES_PER_WINDOW, bridge.resolveRequests.size)

            now += JmdnsTransport.RESOLVE_WINDOW_MS + 1
            bridge.events!!.onServiceAdded(TYPE, "Flash Peer later")
            assertEquals(JmdnsTransport.MAX_RESOLVES_PER_WINDOW + 1, bridge.resolveRequests.size)
        }
    }

    /**
     * `restartBrowsing()` closes JmDNS, which takes seconds. A resolve request that arrives meanwhile must stand down
     * (the bridge is closing) instead of hitting an executor that is shutting down: 4,270 such rejections in 3 s.
     */
    @Test
    public fun resolveRequestsDuringARestartCloseStandDown() {
        withTransport { transport, bridge ->
            transport.startBrowsing()
            val events = bridge.events!!
            bridge.resolveRequests.clear()
            bridge.duringClose = { events.onServiceAdded(TYPE, "Flash Late") }

            transport.restartBrowsing()

            assertTrue(
                "no resolve may be issued while the bridge is closing (got ${bridge.resolveRequests})",
                "Flash Late" !in bridge.resolveRequests,
            )
        }
    }
}
