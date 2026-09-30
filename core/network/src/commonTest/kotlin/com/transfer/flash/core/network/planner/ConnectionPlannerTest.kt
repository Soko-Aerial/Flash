package com.transfer.flash.core.network.planner

import com.transfer.flash.core.network.planner.ConnectionPlanner.Sighting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- rule 7: dial on demand (PC3) ---

    @Test
    fun `urgent dial skips the first-contact wait and the suppression window`() {
        val q = planner(suppress = 60_000L, defer = 500L)
        q.keys(0, "c"); q.dialFinished("c")
        assertEquals("c", q.planUrgent(200, peer("c"), links, floorMs = 100L)?.key)
        assertEquals("a", planner(defer = 500L).planUrgent(0, peer("a"), links)?.key, "no first-contact wait")
    }

    @Test
    fun `urgent dial respects in-flight, the reconnect engine and its floor`() {
        val p = planner()
        assertEquals("c", p.planUrgent(0, peer("c"), links, floorMs = 1_000L)?.key)
        assertNull(p.planUrgent(10, peer("c"), links, floorMs = 1_000L), "one in flight")
        p.dialFinished("c")
        assertNull(p.planUrgent(999, peer("c"), links, floorMs = 1_000L), "floor")
        assertEquals("c", p.planUrgent(1_000, peer("c"), links, floorMs = 1_000L)?.key)
        p.dialFinished("c")
        links.reconnecting += "c"
        assertNull(p.planUrgent(5_000, peer("c"), links, floorMs = 1_000L), "reconnect engine first")
    }

    @Test
    fun `urgent dial is also counted by the sweep`() {
        val p = planner(suppress = 1_000L)
        p.planUrgent(0, peer("c"), links)
        assertEquals(emptyList(), p.keys(10, "c"), "in flight")
        p.dialFinished("c")
        assertEquals(emptyList(), p.keys(500, "c"), "suppressed by the urgent attempt")
    }

    @Test
    fun `urgent dial with a live session clears and returns nothing`() {
        links.live += "c"
        assertNull(planner().planUrgent(0, peer("c"), links))
        assertNull(planner().planUrgent(0, peer("b"), links), "never itself")
    }

    @Test
    fun `rule 8 - the mode filter limits sweeps but not gateway probes or dial on demand`() {
        val p = planner()
        val plan = p.plan(0, listOf(peer("c"), peer("d")), links, listOf("192.168.43.1"), allowed = setOf("d"))
        assertEquals(listOf("d", "gateway:192.168.43.1"), plan.dials.map { it.key })
        // A send to "c" still dials it.
        assertEquals("c", p.planUrgent(0, peer("c"), links)?.key)
        // Null means STANDARD: everyone.
        assertEquals(listOf("e"), planner().keys(0, "e"))
    }

    // --- rule 9: sweep hits (DR3) ---

    @Test
    fun `rule 9 - a sweep hit is keyed by host, has no device, is never deferred and is not a gateway probe`() {
        val plan = planner().plan(0, emptyList(), links, sweepHosts = listOf("192.168.1.30"))
        val dial = plan.dials.single()
        assertEquals("sweep:192.168.1.30", dial.key)
        assertEquals("192.168.1.30", dial.host)
        assertEquals(0, dial.port)
        assertNull(dial.peerDeviceId)
        assertTrue(dial.isSweepHit)
        assertFalse(dial.isGatewayProbe)
        assertNull(plan.recheckInMs)
    }

    @Test
    fun `rule 9 - a sweep hit is skipped while a session reaches that host and is suppressed like a peer`() {
        links.hostsWithSession += "192.168.1.30"
        assertEquals(emptyList(), planner().plan(0, emptyList(), links, sweepHosts = listOf("192.168.1.30")).dials)

        links.hostsWithSession.clear()
        val p = planner(suppress = 1_000L)
        assertEquals(1, p.plan(0, emptyList(), links, sweepHosts = listOf("192.168.1.30")).dials.size)
        assertEquals(emptyList(), p.plan(10, emptyList(), links, sweepHosts = listOf("192.168.1.30")).dials, "in flight")
        p.dialFinished("sweep:192.168.1.30")
        assertEquals(emptyList(), p.plan(500, emptyList(), links, sweepHosts = listOf("192.168.1.30")).dials, "suppressed")
        assertEquals(1, p.plan(1_000, emptyList(), links, sweepHosts = listOf("192.168.1.30")).dials.size)
    }

    @Test
    fun `rule 9 - the mode filter does not apply to a sweep hit`() {
        val plan = planner().plan(0, listOf(peer("c")), links, sweepHosts = listOf("192.168.1.30"), allowed = emptySet())
        assertEquals(listOf("sweep:192.168.1.30"), plan.dials.map { it.key })
    }

    @Test
    fun `rule 9 - a duplicate host is dialed once and sweep hits and gateways do not collide`() {
        val plan = planner().plan(
            0, emptyList(), links,
            gatewayHosts = listOf("192.168.43.1"),
            sweepHosts = listOf("192.168.43.1", "192.168.43.1"),
        )
        assertEquals(listOf("gateway:192.168.43.1", "sweep:192.168.43.1"), plan.dials.map { it.key })
    }

    // --- endpoint-aware suppression ---

    @Test
    fun `suppression is endpoint-aware so a new IP or port is dialed without waiting for the old endpoint window`() {
        val p = planner(suppress = 1_000L)
        val oldSighting = Sighting("c", "10.13.65.211", 45822, "P-c")
        val newSighting = Sighting("c", "192.168.1.123", 45822, "P-c")

        val d1 = p.plan(0, listOf(oldSighting), links).dials.single()
        assertEquals("10.13.65.211", d1.host)
        p.dialFinished("c")

        // Old endpoint is suppressed within 1000ms.
        assertEquals(emptyList(), p.plan(200, listOf(oldSighting), links).dials)

        // New endpoint is dialed immediately even inside the 1000ms suppression of the old endpoint.
        val d2 = p.plan(200, listOf(newSighting), links).dials.single()
        assertEquals("192.168.1.123", d2.host)
        p.dialFinished("c")

        // Now the new endpoint is also suppressed within its own 1000ms window.
        assertEquals(emptyList(), p.plan(500, listOf(newSighting), links).dials)
    }

    @Test
    fun `urgent dial to a new endpoint bypasses the floor window`() {
        val p = planner()
        val oldSighting = Sighting("c", "10.13.65.211", 45822, "P-c")
        val newSighting = Sighting("c", "192.168.1.123", 45822, "P-c")

        assertEquals("c", p.planUrgent(0, oldSighting, links, floorMs = 1_000L)?.key)
        p.dialFinished("c")

        // Urgent dial to same endpoint within floor is floored.
        assertNull(p.planUrgent(100, oldSighting, links, floorMs = 1_000L))

        // Urgent dial to a different endpoint is allowed immediately.
        val urgent = p.planUrgent(100, newSighting, links, floorMs = 1_000L)
        assertEquals("c", urgent?.key)
        assertEquals("192.168.1.123", urgent?.host)
    }

    @Test
    fun `multiple failed endpoints for the same peer are each suppressed independently without ping-ponging`() {
        val p = planner(suppress = 1_000L)
        val ep1 = Sighting("c", "10.13.65.211", 45822, "P-c")
        val ep2 = Sighting("c", "192.168.1.123", 45822, "P-c")

        // Dial ep1 at t=0, fails.
        p.plan(0, listOf(ep1), links)
        p.dialFinished("c")

        // Dial ep2 at t=200, fails.
        p.plan(200, listOf(ep2), links)
        p.dialFinished("c")

        // At t=400, both ep1 (400 < 1000) and ep2 (400 - 200 = 200 < 1000) are suppressed: no ping-pong.
        assertEquals(emptyList(), p.plan(400, listOf(ep1), links).dials)
        assertEquals(emptyList(), p.plan(400, listOf(ep2), links).dials)

        // At t=1050, ep1 has passed its 1000ms window and can be retried.
        assertEquals(listOf("c"), p.plan(1_050, listOf(ep1), links).dials.map { it.key })
        p.dialFinished("c")

        // But ep2 at t=1050 is still within its window (1050 - 200 = 850 < 1000).
        assertEquals(emptyList(), p.plan(1_050, listOf(ep2), links).dials)
    }
}
