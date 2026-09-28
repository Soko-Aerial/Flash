package com.transfer.flash.core.network.presence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceExchangeTest {

    private class Host(val id: String) {
        var sessions: Set<String> = emptySet()
        var seen: Map<String, PresenceEndpoint?> = emptyMap()
        var trusted: Set<String> = emptySet()
        var ghost = false
        val tokens = HashMap<String, Any>()
        lateinit var exchange: PresenceExchange
        lateinit var job: Job
        val sent = ArrayList<Pair<String, String>>()

        fun view() = PresenceLocalView(
            ghost = ghost,
            sessions = sessions.associateWith { tokens.getOrPut(it) { Any() } },
            live = sessions,
            seen = seen,
            trusted = trusted,
            pinned = trusted,
            rosters = emptyList(),
        )
    }

    private fun TestScope.mesh(vararg hosts: Host, scope: CoroutineScope = backgroundScope) {
        val byId = hosts.associateBy { it.id }
        var salt = 0
        for (h in hosts) {
            h.exchange = PresenceExchange(
                scope = scope,
                localDeviceId = h.id,
                snapshot = { h.view() },
                send = { peer, text ->
                    h.sent += peer to text
                    val target = byId[peer]
                    target != null && h.id in target.sessions && target.exchange.onInboundText(h.id, text)
                },
                sha256 = TestHash::digest,
                randomSalt = { ByteArray(PresenceCodec.SALT_BYTES) { (salt++).toByte() } },
                nowMs = { testScheduler.currentTime },
            )
        }
        for (h in hosts) h.job = h.exchange.start()
    }

    private fun triangle(): Triple<Host, Host, Host> {
        val a = Host("A").apply { sessions = setOf("B", "C"); trusted = setOf("B", "C") }
        val b = Host("B").apply { sessions = setOf("A"); trusted = setOf("A", "C") }
        val c = Host("C").apply { sessions = setOf("A"); trusted = setOf("A", "B") }
        return Triple(a, b, c)
    }

    @Test
    fun onlyPresenceFramesAreConsumedAndMalformedOnesStillAre() = runTest {
        val a = Host("A")
        mesh(a)
        assertTrue(a.exchange.onInboundText("B", "FLASH_PRES v=1 t=hello share=1"))
        assertTrue(a.exchange.onInboundText("B", "FLASH_PRES v=9 t=anything"))
        assertTrue(a.exchange.onInboundText("B", "FLASH_PRES garbage"))
        assertFalse(a.exchange.onInboundText("B", "FLASH_PRESENCE v=1"))
        assertFalse(a.exchange.onInboundText("B", "FLASH_XFER action=pause transferId=1"))
    }

    @Test
    fun threeHostsShareAMutualContact() = runTest {
        val (a, b, c) = triangle()
        a.seen = mapOf("C" to PresenceEndpoint("192.168.1.30", 45822))
        mesh(a, b, c)
        runCurrent()
        assertEquals(setOf("C"), b.exchange.reachablePeerIds.value)
        // B is only told; it neither sees C nor has a session, so the report becomes a dial tip.
        val tip = assertNotNull(b.exchange.tips.value["C"])
        assertEquals(PresenceEndpoint("192.168.1.30", 45822), tip.endpoint)
        assertEquals(listOf("C"), b.exchange.tipSightings().map { it.deviceId })
        assertEquals(tip, b.exchange.tipFor("C", "192.168.1.30", 45822))
        assertNull(b.exchange.tipFor("C", "192.168.1.31", 45822), "a different address is not this tip")
        assertNull(b.exchange.tipFor(null, "192.168.1.30", 45822))
    }

    @Test
    fun aReportExpiresOnItsOwnWhenTheReporterFallsSilent() = runTest {
        val (a, b, c) = triangle()
        mesh(a, b, c)
        runCurrent()
        assertEquals(setOf("C"), b.exchange.reachablePeerIds.value)
        a.job.cancel()
        advanceTimeBy(PresenceConfig.STANDARD.maxAgeMs + 2)
        runCurrent()
        assertTrue(b.exchange.reachablePeerIds.value.isEmpty())
    }

    @Test
    fun quietMeshStaysQuietUntilTheRefresh() = runTest {
        val (a, b, c) = triangle()
        mesh(a, b, c)
        runCurrent()
        // Let any delta held back by the 1 s coalescing gap go out first.
        advanceTimeBy(2_001)
        runCurrent()
        val before = a.sent.size
        advanceTimeBy(PresenceConfig.STANDARD.refreshMs - 2_001 - 1)
        runCurrent()
        assertEquals(before, a.sent.size, "no traffic between refreshes when nothing changed")
        advanceTimeBy(2)
        runCurrent()
        assertTrue(a.sent.size > before, "refresh sends hello and digest")
    }

    @Test
    fun refreshAppliesAChangeTheEdgesMissed() = runTest {
        val (a, b, c) = triangle()
        mesh(a, b, c)
        runCurrent()
        c.ghost = true
        c.exchange.refresh()
        advanceTimeBy(PresenceConfig.STANDARD.minFrameGapMs + 1)
        runCurrent()
        assertTrue(b.exchange.reachablePeerIds.value.isEmpty())
    }
}
