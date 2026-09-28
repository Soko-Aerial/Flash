package com.transfer.flash.core.network.mode

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** PC5: mode changes, ECO's dial filter and the park handshake between two hosts on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionModeControllerTest {

    private class Host(val id: String, var strategy: ConnectionStrategy) {
        var contacts: Set<String> = emptySet()
        var available: Set<String> = emptySet()
        var activity: Map<String, LinkActivity> = emptyMap()
        var busy: Set<String> = emptySet()
        var nearbyOpen = false
        /** Answers link frames; false models an old client that drops the prefix. */
        var understandsLink = true
        val released = ArrayList<String>()
        val closed = ArrayList<String>()
        val policyChanges = ArrayList<ConnectionStrategy>()
        val sent = ArrayList<String>()
        lateinit var controller: ConnectionModeController

        fun view() = LinkView(available, contacts, activity, busy, nearbyOpen)
    }

    private val idleMs = ConnectionModePolicy.ECO_IDLE_PARK_MS

    private fun TestScope.wire(vararg hosts: Host) {
        val byId = hosts.associateBy { it.id }
        for (h in hosts) {
            h.controller = ConnectionModeController(
                scope = backgroundScope,
                localDeviceId = h.id,
                policy = { ConnectionModePolicy.of(h.strategy, FlashPerformanceMode.HIGH) },
                snapshot = { h.view() },
                send = { peer, text ->
                    h.sent += text
                    val target = byId[peer]
                    if (target != null && target.understandsLink) target.controller.onInboundText(h.id, text)
                    target != null
                },
                release = { h.released += it },
                close = { h.closed += it; h.activity -= it },
                onPolicyChanged = { h.policyChanges += it.strategy },
                nowMs = { testScheduler.currentTime },
            )
        }
        hosts.forEach { it.controller.start() }
    }

    /**
     * R ("c") dialed P ("m"), and neither is among the other's ring neighbours: R's ring
     * a b [c] d e m x y gives b, d, e; P's ring c k l [m] n o gives l, n, o.
     */
    private fun pair(rStrategy: ConnectionStrategy, pStrategy: ConnectionStrategy): Pair<Host, Host> {
        val r = Host("c", rStrategy).apply {
            contacts = setOf("a", "b", "d", "e", "m", "x", "y")
            available = contacts
            activity = mapOf("m" to LinkActivity(outbound = true, userIdleMs = idleMs))
        }
        val p = Host("m", pStrategy).apply {
            contacts = setOf("c", "k", "l", "n", "o")
            available = contacts
            activity = mapOf("c" to LinkActivity(outbound = false, userIdleMs = idleMs))
        }
        return r to p
    }

    @Test
    fun `STANDARD and BOOST dial everyone, ECO only whom it wants`() = runTest {
        val h = Host("a", ConnectionStrategy.STANDARD).apply {
            contacts = setOf("b", "c", "d", "e", "f")
            available = contacts + "stranger"
        }
        wire(h)
        runCurrent()
        assertNull(h.controller.dialFilter.value)
        h.strategy = ConnectionStrategy.ECO
        h.controller.refresh()
        runCurrent()
        assertEquals(setOf("b", "f", "c"), h.controller.dialFilter.value)
        h.nearbyOpen = true
        h.controller.refresh()
        runCurrent()
        assertTrue("stranger" in h.controller.dialFilter.value!!)
        h.strategy = ConnectionStrategy.BOOST
        h.controller.refresh()
        runCurrent()
        assertNull(h.controller.dialFilter.value)
        assertEquals(listOf(ConnectionStrategy.ECO, ConnectionStrategy.BOOST), h.policyChanges, "once per change, not at start")
    }

    @Test
    fun `two ECO phones that both do not want a session park it, and neither redials`() = runTest {
        val (r, p) = pair(ConnectionStrategy.ECO, ConnectionStrategy.ECO)
        wire(r, p)
        runCurrent()
        assertEquals(listOf("m"), r.closed)
        assertEquals(listOf("c"), p.released, "the accepting side releases before it answers")
        assertTrue(p.closed.isEmpty(), "never closes a session the other side dialed")
        advanceTimeBy(3 * idleMs)
        runCurrent()
        assertEquals(1, r.sent.count { it.contains("t=park") }, "a parked session is not asked about again")
    }

    @Test
    fun `a close that does not take effect is not asked about again until the answer timeout`() = runTest {
        val (r, p) = pair(ConnectionStrategy.ECO, ConnectionStrategy.ECO)
        wire(r, p)
        // The session outlives the close (it stays in r's registry).
        runCurrent()
        r.activity = mapOf("m" to LinkActivity(outbound = true, userIdleMs = idleMs))
        advanceTimeBy(ConnectionModeController.PARK_ANSWER_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(1, r.sent.count { it.contains("t=park") })
    }

    @Test
    fun `a STANDARD or BOOST peer keeps the session, and ECO does not ask again for 10 minutes`() = runTest {
        for (other in listOf(ConnectionStrategy.STANDARD, ConnectionStrategy.BOOST)) {
            val (r, p) = pair(ConnectionStrategy.ECO, other)
            wire(r, p)
            runCurrent()
            assertTrue(r.closed.isEmpty())
            assertTrue(p.released.isEmpty())
            val asks = r.sent.count { it.contains("t=park") }
            assertEquals(1, asks)
            advanceTimeBy(idleMs - 1)
            runCurrent()
            assertEquals(1, r.sent.count { it.contains("t=park") }, "no re-ask inside the keep window")
            advanceTimeBy(ConnectionModeController.DEFAULT_CHECK_INTERVAL_MS + 1)
            runCurrent()
            assertEquals(2, r.sent.count { it.contains("t=park") })
        }
    }

    @Test
    fun `an ECO peer that wants the session keeps it`() = runTest {
        val (r, p) = pair(ConnectionStrategy.ECO, ConnectionStrategy.ECO)
        p.busy = setOf("c") // in a call with r
        wire(r, p)
        runCurrent()
        assertTrue(r.closed.isEmpty())
        assertTrue(p.released.isEmpty())
    }

    @Test
    fun `an old client never answers, so the session stays`() = runTest {
        val (r, p) = pair(ConnectionStrategy.ECO, ConnectionStrategy.ECO)
        p.understandsLink = false
        wire(r, p)
        runCurrent()
        advanceTimeBy(3 * idleMs)
        runCurrent()
        assertTrue(r.closed.isEmpty())
    }

    @Test
    fun `traffic that resumes before the answer arrives keeps the session`() = runTest {
        val (r, p) = pair(ConnectionStrategy.ECO, ConnectionStrategy.ECO)
        wire(r, p)
        // Stop the first step before it reaches p: p's answer is what we control here.
        p.understandsLink = false
        runCurrent()
        assertTrue(r.sent.any { it.contains("t=park") })
        r.activity = mapOf("m" to LinkActivity(outbound = true, userIdleMs = 1_000L))
        r.controller.onInboundText("m", LinkCodec.encode(LinkFrame.ParkOk))
        runCurrent()
        assertTrue(r.closed.isEmpty())
    }

    @Test
    fun `an unsolicited park-ok closes nothing`() = runTest {
        val (r, _) = pair(ConnectionStrategy.STANDARD, ConnectionStrategy.ECO)
        wire(r)
        runCurrent()
        r.controller.onInboundText("m", LinkCodec.encode(LinkFrame.ParkOk))
        runCurrent()
        assertTrue(r.closed.isEmpty())
    }

    @Test
    fun `link frames are consumed, anything else is not`() = runTest {
        val h = Host("a", ConnectionStrategy.ECO)
        wire(h)
        assertTrue(h.controller.onInboundText("b", "FLASH_LINK v=1 t=park"))
        assertTrue(h.controller.onInboundText("b", "FLASH_LINK v=7 t=whatever"))
        assertTrue(!h.controller.onInboundText("b", "FLASH_LINKS v=1"))
        assertTrue(!h.controller.onInboundText("b", "FLASH_PRES v=1 t=hello share=1"))
    }

    @Test
    fun `codec round trip and rejects`() {
        for (f in LinkFrame.entries) assertEquals(f, LinkCodec.decode(LinkCodec.encode(f)))
        assertNull(LinkCodec.decode("FLASH_LINK v=2 t=park"))
        assertNull(LinkCodec.decode("FLASH_LINK v=1 t=close"))
        assertNull(LinkCodec.decode("FLASH_MSG v=1 t=park"))
    }
}
