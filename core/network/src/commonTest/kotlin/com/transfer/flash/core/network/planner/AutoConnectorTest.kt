package com.transfer.flash.core.network.planner

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [AutoConnector]: when sweeps run and what happens around each dial (PC2). */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectorTest {

    private class Rig(scope: TestScope, local: String = "b", quiet: Boolean = false) {
        val sightings = MutableStateFlow<List<ConnectionPlanner.Sighting>>(emptyList())
        val live = HashSet<String>()
        val dials = ArrayList<String>()
        val dialedHosts = ArrayList<String>()
        val logs = ArrayList<String>()
        var result: FlashResult<*> = FlashResult.Success(Unit)
        var quiet = quiet
        val links = object : ConnectionPlanner.Links {
            override fun hasLiveSession(deviceId: String) = deviceId in live
            override fun isReconnectInFlight(deviceId: String) = false
        }
        val connector = AutoConnector(
            scope = scope.backgroundScope,
            planner = ConnectionPlanner(localDeviceId = local, suppressMs = 15_000L, firstContactDeferMs = 1_500L),
            links = links,
            sightings = { sightings.value },
            dial = { d -> dials += d.key; dialedHosts += d.host; result },
            quiet = { this.quiet },
            log = { logs += it },
            nowMs = { scope.testScheduler.currentTime },
        )

        fun see(vararg ids: String) {
            sightings.value = ids.map { ConnectionPlanner.Sighting(it, "10.0.0.1", 45822, "N-$it") }
        }
    }

    @Test
    fun `a discovery edge dials at once, without waiting for the tick`() = runTest {
        val rig = Rig(this)
        rig.connector.start(rig.sightings)
        runCurrent()
        rig.see("c")
        runCurrent()
        assertEquals(listOf("c"), rig.dials)
    }

    @Test
    fun `a failed dial is retried on the tick after the suppression window`() = runTest {
        val rig = Rig(this)
        rig.result = FlashResult.Failure(FlashError.Unknown("refused"))
        rig.see("c")
        rig.connector.start(rig.sightings)
        runCurrent()
        assertEquals(1, rig.dials.size)
        advanceTimeBy(14_000); runCurrent()
        assertEquals(1, rig.dials.size)
        advanceTimeBy(6_000); runCurrent()
        assertEquals(2, rig.dials.size)
    }

    @Test
    fun `a deferred first contact is dialed when due, not at the next 5 s tick`() = runTest {
        val rig = Rig(this)
        rig.see("a")
        rig.connector.start(rig.sightings)
        runCurrent()
        assertEquals(emptyList(), rig.dials)
        advanceTimeBy(1_499); runCurrent()
        assertEquals(emptyList(), rig.dials)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("a"), rig.dials)
    }

    @Test
    fun `quiet skips sweeps and dialing resumes once it clears`() = runTest {
        val rig = Rig(this, quiet = true)
        rig.see("c")
        rig.connector.start(rig.sightings)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(emptyList(), rig.dials)
        rig.quiet = false
        rig.connector.sweepNow()
        runCurrent()
        assertEquals(listOf("c"), rig.dials)
    }

    @Test
    fun `log lines keep the text the PC0 script counts`() = runTest {
        val rig = Rig(this)
        rig.see("c")
        rig.connector.start(rig.sightings)
        runCurrent()
        assertTrue(rig.logs.any { it.startsWith("Auto-connect dialing peer=N-c id=c at 10.0.0.1:45822") }, "${rig.logs}")
        assertTrue(rig.logs.any { Regex("""Auto-connect result peer=\S+ success=true""").containsMatchIn(it) }, "${rig.logs}")
    }

    @Test
    fun `a dial that throws still releases the peer`() = runTest {
        val rig = Rig(this)
        val connector = AutoConnector(
            scope = backgroundScope,
            planner = ConnectionPlanner(localDeviceId = "b", suppressMs = 1_000L),
            links = rig.links,
            sightings = { rig.sightings.value },
            dial = { d -> rig.dials += d.key; error("boom") },
            log = { rig.logs += it },
            nowMs = { testScheduler.currentTime },
        )
        rig.see("c")
        connector.start()
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("c", "c"), rig.dials)
        assertTrue(rig.logs.any { it.contains("success=false detail=threw boom") }, "${rig.logs}")
    }

    // --- dial on demand (PC3) ---

    @Test
    fun `ensureSession dials at once and returns when the session lands`() = runTest {
        val rig = Rig(this)
        rig.see("a")                                   // higher-id side: the sweep would wait 1.5 s
        rig.connector.start()
        runCurrent()
        assertEquals(emptyList(), rig.dials)
        val job = async { rig.connector.ensureSession("a") }
        runCurrent()
        assertEquals(listOf("a"), rig.dials)
        advanceTimeBy(200); rig.live += "a"; advanceTimeBy(50)
        assertTrue(job.await())
        assertTrue(testScheduler.currentTime < 1_000)
    }

    @Test
    fun `ensureSession for a peer discovery does not see returns false at once`() = runTest {
        val rig = Rig(this)
        rig.connector.start()
        val start = testScheduler.currentTime
        assertFalse(rig.connector.ensureSession("zz"))
        assertEquals(start, testScheduler.currentTime)
        assertEquals(emptyList(), rig.dials)
    }

    @Test
    fun `queued messages to an unreachable peer share one dial and one budget`() = runTest {
        val rig = Rig(this)
        rig.result = FlashResult.Failure(FlashError.Unknown("refused"))
        rig.see("c")
        rig.quiet = true                                  // keep the sweep out of this test
        rig.connector.start()
        val start = testScheduler.currentTime
        repeat(16) { assertFalse(rig.connector.ensureSession("c")) }
        assertEquals(listOf("c"), rig.dials)
        assertTrue(testScheduler.currentTime - start <= 1_000, "one shared budget, not 16")
    }

    @Test
    fun `ensureSession tries a peer's second endpoint when the first one just failed`() = runTest {
        val rig = Rig(this)
        rig.result = FlashResult.Failure(FlashError.Unknown("refused"))
        rig.quiet = true                                  // keep the sweep out of this test
        rig.sightings.value = listOf(
            ConnectionPlanner.Sighting("c", "10.0.0.1", 45822, "N-c"),
            ConnectionPlanner.Sighting("c", "192.168.1.9", 45822, "N-c"),
        )
        rig.connector.start()

        // First send: the first endpoint is dialed (and refuses).
        assertFalse(rig.connector.ensureSession("c"))
        assertEquals(listOf("10.0.0.1"), rig.dialedHosts)

        // Second send, inside the first endpoint's suppression window: the other endpoint is tried,
        // not "no dial" for a peer that has a second way in.
        assertFalse(rig.connector.ensureSession("c"))
        assertEquals(listOf("10.0.0.1", "192.168.1.9"), rig.dialedHosts)
    }

    // --- sweep hits (DR3) ---

    @Test
    fun `a sweep hit is dialed once, logged as a sweep hit and reported dialed`() = runTest {
        val rig = Rig(this)
        val hits = MutableStateFlow(listOf("192.168.1.30"))
        val dialed = ArrayList<String>()
        val connector = AutoConnector(
            scope = backgroundScope,
            planner = ConnectionPlanner(localDeviceId = "b", suppressMs = 15_000L),
            links = rig.links,
            sightings = { emptyList() },
            dial = { d -> rig.dials += d.key; FlashResult.Success(Unit) },
            sweepHosts = { hits.value },
            sweepHitDialed = { host -> dialed += host; hits.value = hits.value - host },
            log = { rig.logs += it },
            nowMs = { testScheduler.currentTime },
        )
        connector.start(hits)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("sweep:192.168.1.30"), rig.dials)
        assertEquals(listOf("192.168.1.30"), dialed)
        assertTrue(rig.logs.any { it == "Auto-connect dialing sweep hit at 192.168.1.30:0 (subnet sweep)" }, "${rig.logs}")
        assertTrue(rig.logs.any { it == "Auto-connect result sweep hit 192.168.1.30 success=true" }, "${rig.logs}")
        assertTrue(rig.logs.none { it.contains("gateway") }, "a sweep hit must not be logged as a gateway probe")
    }

    @Test
    fun `a sweep hit is reported dialed even when its dial fails`() = runTest {
        val rig = Rig(this)
        val dialed = ArrayList<String>()
        val connector = AutoConnector(
            scope = backgroundScope,
            planner = ConnectionPlanner(localDeviceId = "b"),
            links = rig.links,
            sightings = { emptyList() },
            dial = { error("refused") },
            sweepHosts = { listOf("192.168.1.30") },
            sweepHitDialed = { dialed += it },
            nowMs = { testScheduler.currentTime },
        )
        connector.start()
        runCurrent()
        assertEquals(listOf("192.168.1.30"), dialed)
    }
}
