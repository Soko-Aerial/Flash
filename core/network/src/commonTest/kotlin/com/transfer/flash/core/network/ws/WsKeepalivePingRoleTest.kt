package com.transfer.flash.core.network.ws

import com.transfer.flash.core.network.ws.WsKeepalive.PingRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PC1's two ping-saving rules (`PRESENCE-CONNECTIONS-PLAN.md` §3.3): one pinger per pair, and no
 * ping when traffic already flowed both ways. The pair tests run both ends' [WsKeepalive] against
 * each other for an hour of simulated time, because the rules are only safe as a pair: what one
 * side stops sending is exactly what the other side's watchdog used to be fed with.
 */
class WsKeepalivePingRoleTest {

    // ---------------------------------------------------------------- role resolution

    @Test
    fun `a peer that announced no interval keeps both sides pinging`() {
        assertEquals(PingRole.BOTH, WsKeepalive.resolveRole(10_000L, null, "a", "b"))
        assertEquals(PingRole.BOTH, WsKeepalive.resolveRole(10_000L, 0L, "a", "b"))
    }

    @Test
    fun `the side with the shorter interval pings, whatever the ids`() {
        assertEquals(PingRole.PINGER, WsKeepalive.resolveRole(10_000L, 15_000L, "z", "a"))
        assertEquals(PingRole.ANSWERER, WsKeepalive.resolveRole(15_000L, 10_000L, "a", "z"))
    }

    @Test
    fun `equal intervals pick the smaller id, and both ends agree`() {
        val pairs = listOf("a" to "b", "5f0c" to "12ab", "same-prefix-1" to "same-prefix-2")
        pairs.forEach { (x, y) ->
            val fromX = WsKeepalive.resolveRole(10_000L, 10_000L, x, y)
            val fromY = WsKeepalive.resolveRole(10_000L, 10_000L, y, x)
            assertEquals(setOf(PingRole.PINGER, PingRole.ANSWERER), setOf(fromX, fromY), "$x/$y")
            assertEquals(if (x < y) PingRole.PINGER else PingRole.ANSWERER, fromX, "$x/$y")
        }
    }

    // ---------------------------------------------------------------- single-tick rules

    @Test
    fun `traffic both ways within half an interval makes the ping unnecessary`() {
        val k = WsKeepalive(10_000L, 25_000L, startedAtMs = 0L).apply { role = PingRole.PINGER }
        k.onInbound(8_000L)
        k.onOutbound(7_000L)
        assertIs<WsKeepalive.Verdict.Quiet>(k.onTick(10_000L))
    }

    @Test
    fun `a side that only receives keeps pinging, or the sender's watchdog starves`() {
        val k = WsKeepalive(10_000L, 25_000L, startedAtMs = 0L).apply { role = PingRole.PINGER }
        k.onInbound(9_900L)
        assertIs<WsKeepalive.Verdict.Ping>(k.onTick(10_000L))
    }

    @Test
    fun `a side that only sends still pings, to hear from the peer`() {
        val k = WsKeepalive(10_000L, 25_000L, startedAtMs = 0L).apply { role = PingRole.BOTH }
        k.onOutbound(9_900L)
        assertIs<WsKeepalive.Verdict.Ping>(k.onTick(10_000L))
    }

    @Test
    fun `an answerer stays quiet while the pinger is heard and probes once it is not`() {
        val k = WsKeepalive(10_000L, 25_000L, startedAtMs = 0L).apply { role = PingRole.ANSWERER }
        k.onInbound(9_000L)
        assertIs<WsKeepalive.Verdict.Quiet>(k.onTick(10_000L))
        // 13 s of silence: past half the liveness window, so the answerer checks for itself.
        assertIs<WsKeepalive.Verdict.Ping>(k.onTick(22_000L))
    }

    @Test
    fun `an answerer still sends the stall probe after a freeze`() {
        val k = WsKeepalive(10_000L, 25_000L, startedAtMs = 0L).apply { role = PingRole.ANSWERER }
        k.onInbound(9_000L)
        assertIs<WsKeepalive.Verdict.Quiet>(k.onTick(10_000L))
        // Next tick three minutes late: the process was frozen (ERROR-025).
        assertIs<WsKeepalive.Verdict.Ping>(k.onTick(190_000L))
    }

    // ---------------------------------------------------------------- the pair, simulated

    private class Side(val id: String, val pingMs: Long, val livenessMs: Long, val phaseMs: Long) {
        val keepalive = WsKeepalive(pingMs, livenessMs, startedAtMs = 0L)
        var pings = 0
        var closedAtMs: Long? = null
        var deadFromMs: Long = Long.MAX_VALUE
        fun alive(t: Long) = closedAtMs == null && t < deadFromMs
    }

    private class Event(val atMs: Long, val run: () -> Unit)

    /**
     * Both ends tick on their own schedule; a PING is delivered after [rttMs] / 2 and answered with
     * a PONG at once, exactly like [WsConnection]'s read loop. A dead side neither ticks nor answers.
     */
    private fun simulate(a: Side, b: Side, durationMs: Long, rttMs: Long = 40L) {
        val queue = mutableListOf<Event>()
        fun schedule(atMs: Long, run: () -> Unit) {
            queue += Event(atMs, run)
        }
        fun deliverPing(from: Side, to: Side, sentAtMs: Long) {
            val arrives = sentAtMs + rttMs / 2
            schedule(arrives) {
                if (!to.alive(arrives)) return@schedule
                to.keepalive.onInbound(arrives)
                to.keepalive.onOutbound(arrives) // the PONG
                val back = arrives + rttMs / 2
                schedule(back) { if (from.alive(back)) from.keepalive.onInbound(back) }
            }
        }
        fun scheduleTick(side: Side, other: Side, atMs: Long) {
            schedule(atMs) {
                if (!side.alive(atMs)) return@schedule
                when (val v = side.keepalive.onTick(atMs)) {
                    is WsKeepalive.Verdict.Close -> side.closedAtMs = atMs
                    WsKeepalive.Verdict.Ping -> {
                        side.pings++
                        side.keepalive.onOutbound(atMs)
                        deliverPing(side, other, atMs)
                    }
                    WsKeepalive.Verdict.Quiet -> Unit
                }
                if (side.alive(atMs)) scheduleTick(side, other, atMs + side.pingMs)
            }
        }
        scheduleTick(a, b, a.phaseMs + a.pingMs)
        scheduleTick(b, a, b.phaseMs + b.pingMs)
        while (queue.isNotEmpty()) {
            val next = queue.minBy { it.atMs }
            queue.remove(next)
            if (next.atMs > durationMs) break
            next.run()
        }
    }

    private fun resolve(a: Side, b: Side) {
        a.keepalive.role = WsKeepalive.resolveRole(a.pingMs, b.pingMs, a.id, b.id)
        b.keepalive.role = WsKeepalive.resolveRole(b.pingMs, a.pingMs, b.id, a.id)
    }

    private val hourMs = 3_600_000L

    @Test
    fun `one pinger per pair halves idle pings and never closes a healthy pair`() {
        val a = Side("a", 10_000L, 25_000L, phaseMs = 0L)
        val b = Side("b", 10_000L, 25_000L, phaseMs = 3_700L)
        resolve(a, b)

        simulate(a, b, hourMs)

        assertNull(a.closedAtMs)
        assertNull(b.closedAtMs)
        assertTrue(a.pings in 355..360, "pinger pings once per interval, got ${a.pings}")
        assertEquals(0, b.pings, "the answerer never needs to ping a healthy pinger")
    }

    @Test
    fun `mixed tiers - the faster side pings and the slower side's watchdog stays fed`() {
        // "a" would win an id tie-break, but the interval rule decides first.
        val low = Side("a", 15_000L, 40_000L, phaseMs = 0L)
        val high = Side("b", 10_000L, 25_000L, phaseMs = 6_100L)
        resolve(low, high)

        simulate(low, high, hourMs)

        assertNull(low.closedAtMs)
        assertNull(high.closedAtMs)
        assertEquals(0, low.pings)
        assertTrue(high.pings in 355..360, "got ${high.pings}")
    }

    @Test
    fun `a pinger that dies is still detected by the answerer`() {
        val a = Side("a", 10_000L, 25_000L, phaseMs = 0L)
        val b = Side("b", 10_000L, 25_000L, phaseMs = 2_000L)
        resolve(a, b)
        a.deadFromMs = 600_000L

        simulate(a, b, hourMs)

        val closedAt = assertNotNull(b.closedAtMs, "the answerer must reap a dead pinger")
        assertTrue(
            closedAt <= a.deadFromMs + b.livenessMs + 2 * b.pingMs,
            "closed ${closedAt - a.deadFromMs} ms after death",
        )
    }

    @Test
    fun `roles that disagree degrade to both pinging, never to neither`() {
        val a = Side("a", 10_000L, 25_000L, phaseMs = 0L)
        val b = Side("b", 10_000L, 25_000L, phaseMs = 5_000L)
        a.keepalive.role = PingRole.ANSWERER
        b.keepalive.role = PingRole.ANSWERER

        simulate(a, b, hourMs)

        assertNull(a.closedAtMs)
        assertNull(b.closedAtMs)
        assertTrue(a.pings + b.pings > 0)
    }

    @Test
    fun `an older peer that pings on its own is answered without our pings being needed`() {
        // The older client's side always pings (it predates PC1: role BOTH and no traffic rule is
        // modelled by giving it PINGER); ours has no interval from it, so ours is BOTH as well.
        val old = Side("z", 10_000L, 25_000L, phaseMs = 0L).apply { keepalive.role = PingRole.PINGER }
        val ours = Side("a", 10_000L, 25_000L, phaseMs = 2_500L).apply { keepalive.role = PingRole.BOTH }

        simulate(old, ours, hourMs)

        assertNull(old.closedAtMs)
        assertNull(ours.closedAtMs)
        // Our PONGs arrive within half an interval of each tick, so we rarely add pings of our own.
        assertTrue(ours.pings < old.pings, "ours=${ours.pings} old=${old.pings}")
    }
}
