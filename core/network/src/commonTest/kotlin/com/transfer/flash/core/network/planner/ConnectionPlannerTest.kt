package com.transfer.flash.core.network.planner

import com.transfer.flash.core.network.planner.ConnectionPlanner.Sighting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ConnectionPlanner]'s rules (PC2). Rules 1–4 and 6 are the pre-PC2 `AutoConnectGate` and sweep
 * behaviour, so these cases also pin that nothing regressed in the move; rule 5 is new.
 */
class ConnectionPlannerTest {

    private class FakeLinks : ConnectionPlanner.Links {
        val live = HashSet<String>()
        val reconnecting = HashSet<String>()
        val hostsWithSession = HashSet<String>()
        override fun hasLiveSession(deviceId: String) = deviceId in live
        override fun isReconnectInFlight(deviceId: String) = deviceId in reconnecting
        override fun hasSessionAtHost(host: String) = host in hostsWithSession
    }

    private fun peer(id: String) = Sighting(deviceId = id, host = "10.0.0.${id.last().code % 200}", port = 45822, name = "P-$id")

    // "b" sorts between "a" and "c": as "b" the planner dials "c" at once and defers "a".
    private val links = FakeLinks()
    private fun planner(suppress: Long = 1_000L, defer: Long = 500L) =
        ConnectionPlanner(localDeviceId = "b", suppressMs = suppress, firstContactDeferMs = defer)

    private fun ConnectionPlanner.keys(now: Long, vararg ids: String, gateways: List<String> = emptyList()) =
        plan(now, ids.map(::peer), links, gateways).dials.map { it.key }

    @Test
    fun `never dials itself and dials a device once even when listed twice`() {
        val p = planner()
        assertEquals(listOf("c"), p.keys(0, "b", "c", "c"))
    }

    @Test
    fun `lower-id peer is dialed at once and the result carries the endpoint`() {
        val plan = planner().plan(0, listOf(peer("c")), links)
        val dial = plan.dials.single()
        assertEquals("c", dial.peerDeviceId)
        assertEquals(45822, dial.port)
        assertEquals("P-c", dial.name)
        assertNull(plan.recheckInMs)
    }

    @Test
    fun `live session skips the peer`() {
        links.live += "c"
        assertEquals(emptyList(), planner().keys(0, "c"))
    }

    @Test
    fun `reconnect engine in flight skips the peer`() {
        links.reconnecting += "c"
        assertEquals(emptyList(), planner().keys(0, "c"))
    }

    @Test
    fun `no second attempt while the first is in flight, even after the window`() {
        val p = planner(suppress = 1_000L)
        assertEquals(listOf("c"), p.keys(0, "c"))
        assertEquals(emptyList(), p.keys(5_000, "c"))
        p.dialFinished("c")
        assertEquals(listOf("c"), p.keys(5_001, "c"))
    }

    @Test
    fun `finished attempt is suppressed until the window passes`() {
        val p = planner(suppress = 1_000L)
        p.keys(0, "c")
        p.dialFinished("c")
        assertEquals(emptyList(), p.keys(999, "c"))
        assertEquals(listOf("c"), p.keys(1_000, "c"))
    }

    @Test
    fun `a live session clears suppression so a later drop re-arms at once`() {
        val p = planner(suppress = 10_000L)
        p.keys(0, "c")
        p.dialFinished("c")
        links.live += "c"
        p.keys(100, "c")
        links.live -= "c"
        assertEquals(listOf("c"), p.keys(200, "c"))
    }

    @Test
    fun `live session also clears an in-flight entry`() {
        val p = planner(suppress = 10_000L)
        p.keys(0, "c")
        links.live += "c"
        p.keys(100, "c")
        links.live -= "c"
        assertEquals(listOf("c"), p.keys(200, "c"), "a dial whose finish was never reported must not block forever")
    }

    @Test
    fun `higher-id side defers first contact and reports when to recheck`() {
        val p = planner(defer = 500L)
        val first = p.plan(0, listOf(peer("a")), links)
        assertEquals(emptyList(), first.dials)
        assertEquals(500L, first.recheckInMs)
        val mid = p.plan(300, listOf(peer("a")), links)
        assertEquals(emptyList(), mid.dials)
        assertEquals(200L, mid.recheckInMs)
        assertEquals(listOf("a"), p.keys(500, "a"))
    }

    @Test
    fun `higher-id side never dials when the lower id's session lands inside the wait`() {
        val p = planner(defer = 500L)
        p.keys(0, "a")
        links.live += "a"
        assertEquals(emptyList(), p.keys(600, "a"))
    }

    @Test
    fun `deferral applies once per episode, not to retries`() {
        val p = planner(suppress = 1_000L, defer = 500L)
        p.keys(0, "a")
        assertEquals(listOf("a"), p.keys(500, "a"))
        p.dialFinished("a")
        assertEquals(listOf("a"), p.keys(1_500, "a"), "a retry waits for suppression only")
    }

    @Test
    fun `a new episode after a live session defers again`() {
        val p = planner(defer = 500L)
        p.keys(0, "a"); p.keys(500, "a"); p.dialFinished("a")
        links.live += "a"; p.keys(600, "a"); links.live -= "a"
        assertEquals(emptyList(), p.keys(700, "a"))
        assertEquals(listOf("a"), p.keys(1_200, "a"))
    }

    @Test
    fun `mixed sightings dial the lower id now and the higher id later`() {
        val p = planner(defer = 500L)
        val plan = p.plan(0, listOf(peer("a"), peer("c")), links)
        assertEquals(listOf("c"), plan.dials.map { it.key })
        assertEquals(500L, plan.recheckInMs)
    }

    @Test
    fun `gateway probe is keyed by host, never deferred, and skipped while a session reaches the host`() {
        val p = planner()
        val plan = p.plan(0, emptyList(), links, gatewayHosts = listOf("192.168.43.1"))
        val dial = plan.dials.single()
        assertEquals("gateway:192.168.43.1", dial.key)
        assertTrue(dial.isGatewayProbe)
        assertEquals(0, dial.port)

        val q = planner()
        links.hostsWithSession += "192.168.43.1"
        assertEquals(emptyList(), q.keys(0, gateways = listOf("192.168.43.1")))
    }

    @Test
    fun `gateway probe is suppressed like a peer`() {
        val p = planner(suppress = 1_000L)
        p.keys(0, gateways = listOf("192.168.43.1"))
        p.dialFinished("gateway:192.168.43.1")
        assertEquals(emptyList(), p.keys(500, gateways = listOf("192.168.43.1")))
        assertEquals(listOf("gateway:192.168.43.1"), p.keys(1_000, gateways = listOf("192.168.43.1")))
    }

    @Test
    fun `a peer that flickers out of discovery stays suppressed`() {
        val p = planner(suppress = 1_000L)
        p.keys(0, "c"); p.dialFinished("c")
        p.keys(200)            // "c" missing from this sweep
        assertEquals(emptyList(), p.keys(400, "c"))
        assertEquals(listOf("c"), p.keys(1_000, "c"))
    }

    @Test
    fun `the two sides of a pair agree on who dials first`() {
        val low = ConnectionPlanner(localDeviceId = "a", firstContactDeferMs = 500L)
        val high = ConnectionPlanner(localDeviceId = "c", firstContactDeferMs = 500L)
        assertEquals(listOf("c"), low.plan(0, listOf(peer("c")), links).dials.map { it.key })
        assertEquals(emptyList(), high.plan(0, listOf(peer("a")), links).dials)
    }

    @Test
    fun `back-to-back sweeps never hand the same peer out twice`() {
        // The in-flight set is what stops the discovery edge and the periodic tick double-dialing.
        // (Real thread races are covered on the JVM in ConnectionPlannerConcurrencyTest.)
        val p = ConnectionPlanner(localDeviceId = "a", suppressMs = 60_000L)
        val sightings = (1..20).map { peer("p$it") }
        val results = (1..8).map { p.plan(0, sightings, links) }
        val all = results.flatMap { r -> r.dials.map { it.key } }
        assertEquals(all.toSet().size, all.size)
        assertEquals(20, all.size)
    }
}
