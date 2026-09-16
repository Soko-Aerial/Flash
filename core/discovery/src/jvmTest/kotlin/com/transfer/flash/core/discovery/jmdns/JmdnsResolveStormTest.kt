// EndpointDirectory and StandardEndpointDirectory are @FlashInternalApi - library-internal
// building blocks, opted into here for the same reason JmdnsTransport.kt does.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.jmdns

import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.EndpointDirectory
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
        var events: JmdnsBrowseEvents? = null
        var opened = false

        override fun open() {
            opened = true
        }

        override fun register(request: JmdnsAdvertiseRequest) = Unit

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
}