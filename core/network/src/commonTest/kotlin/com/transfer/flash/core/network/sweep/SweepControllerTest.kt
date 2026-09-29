package com.transfer.flash.core.network.sweep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SweepControllerTest {

    private class Rig(scope: TestScope) {
        var subnets = listOf(LocalSubnet("wlan0", "192.168.1.20", 24))
        var situation = SweepSituation(pairedPeers = 1, liveSessions = 0, discoveredPeers = 0, connectionAllowsAuto = true)
        var autoEnabled = true
        var open = setOf("192.168.1.30")
        val probed = ArrayList<String>()
        val logs = ArrayList<String>()
        val controller = SweepController(
            scope = scope.backgroundScope,
            subnets = { subnets },
            probe = { host, _, _ -> probed += host; delay(5); host in open },
            port = 45822,
            situation = { situation },
            autoEnabled = { autoEnabled },
            log = { logs += it },
            nowMs = { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun `scan now sweeps the local subnet and lists the answering host once`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.controller.scanNow())
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(253, rig.probed.size)
        assertEquals(listOf("192.168.1.30"), rig.controller.hostsToDial())
        val done = assertIs<SweepState.Finished>(rig.controller.state.value)
        assertEquals(253, done.probed)
        assertEquals(1, done.answered)
        assertFalse(done.automatic)
        assertFalse(done.narrowed)

        rig.controller.hitDialed("192.168.1.30")
        assertEquals(emptyList(), rig.controller.hostsToDial())
    }

    @Test
    fun `a hit expires after its time to live`() = runTest {
        val rig = Rig(this)
        rig.controller.scanNow()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, rig.controller.hostsToDial().size)
        advanceTimeBy(SweepController.HIT_TTL_MS)
        assertEquals(emptyList(), rig.controller.hostsToDial())
    }

    @Test
    fun `a second scan while one runs does not start another`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.controller.scanNow())
        assertFalse(rig.controller.scanNow())
        runCurrent()
        assertIs<SweepState.Scanning>(rig.controller.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(253, rig.probed.size)
    }

    @Test
    fun `a manual scan right after another is rate limited`() = runTest {
        val rig = Rig(this)
        rig.controller.scanNow()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(rig.controller.scanNow())
        assertEquals(SweepState.Refused(SweepRefusal.RATE_LIMITED), rig.controller.state.value)
        advanceTimeBy(SweepPolicy.DEFAULT_MANUAL_COOLDOWN_MS)
        assertTrue(rig.controller.scanNow())
    }

    @Test
    fun `with no LAN interface nothing is probed and the user is told`() = runTest {
        val rig = Rig(this)
        rig.subnets = emptyList()
        rig.controller.scanNow()
        runCurrent()
        assertEquals(emptyList(), rig.probed)
        assertEquals(SweepState.Refused(SweepRefusal.NO_LAN), rig.controller.state.value)
    }

    @Test
    fun `a non-private network is refused even when the user asks`() = runTest {
        val rig = Rig(this)
        rig.subnets = listOf(LocalSubnet("rmnet0", "100.72.5.9", 24))
        rig.controller.scanNow()
        runCurrent()
        assertEquals(emptyList(), rig.probed)
        assertEquals(SweepState.Refused(SweepRefusal.NOT_PRIVATE), rig.controller.state.value)
    }

    @Test
    fun `a wide network is narrowed to the own 24 when the user asks`() = runTest {
        val rig = Rig(this)
        rig.subnets = listOf(LocalSubnet("wlan0", "10.20.30.40", 16))
        rig.open = emptySet()
        rig.controller.scanNow()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(255, rig.probed.size)
        assertTrue(rig.probed.all { it.startsWith("10.20.30.") })
        assertTrue(assertIs<SweepState.Finished>(rig.controller.state.value).narrowed)
    }

    @Test
    fun `a hotspot host with two networks sweeps both`() = runTest {
        val rig = Rig(this)
        rig.subnets = listOf(LocalSubnet("wlan0", "192.168.1.20", 24), LocalSubnet("ap0", "192.168.43.1", 24))
        rig.controller.scanNow()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(506, rig.probed.size)
        assertTrue(rig.probed.any { it.startsWith("192.168.43.") })
    }

    @Test
    fun `auto sweeps after 60 quiet seconds and then not again for 10 minutes`() = runTest {
        val rig = Rig(this)
        rig.controller.start(checkIntervalMs = 10_000)
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(emptyList(), rig.probed, "still inside the quiet period")

        // The check at 70 s finds 60 s of quiet since the first one at 10 s; the sweep itself takes ~40 ms.
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(253, rig.probed.size)
        assertTrue(assertIs<SweepState.Finished>(rig.controller.state.value).automatic)

        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(253, rig.probed.size, "same network inside 10 minutes")

        advanceTimeBy(310_000)
        runCurrent()
        assertEquals(506, rig.probed.size, "swept again once the interval has passed")
    }

    @Test
    fun `auto never sweeps a wider than 24 network and does not repeat its refusal`() = runTest {
        val rig = Rig(this)
        rig.subnets = listOf(LocalSubnet("wlan0", "10.20.30.40", 16))
        rig.controller.start(checkIntervalMs = 10_000)
        advanceTimeBy(400_000)
        runCurrent()
        assertEquals(emptyList(), rig.probed)
        assertEquals(1, rig.logs.count { it.startsWith("Sweep skipped") }, "${rig.logs}")
    }

    @Test
    fun `the auto switch off keeps the sweep manual only`() = runTest {
        val rig = Rig(this)
        rig.autoEnabled = false
        rig.controller.start(checkIntervalMs = 10_000)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(emptyList(), rig.probed)
        assertTrue(rig.controller.scanNow())
    }

    @Test
    fun `any live session or discovered device keeps auto quiet`() = runTest {
        val rig = Rig(this)
        rig.situation = rig.situation.copy(discoveredPeers = 1)
        rig.controller.start(checkIntervalMs = 10_000)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(emptyList(), rig.probed)
    }

    @Test
    fun `cancelling the scope mid sweep stops probing and returns to idle`() = runTest {
        val probed = ArrayList<String>()
        val job = Job()
        val controller = SweepController(
            scope = CoroutineScope(coroutineContext + job),
            subnets = { listOf(LocalSubnet("wlan0", "192.168.1.20", 24)) },
            probe = { host, _, _ -> probed += host; delay(300); false },
            port = 45822,
            situation = { SweepSituation(1, 0, 0, true) },
            nowMs = { testScheduler.currentTime },
        )
        controller.scanNow()
        runCurrent()
        assertIs<SweepState.Scanning>(controller.state.value)
        val startedBeforeCancel = probed.size
        assertEquals(SweepController.DEFAULT_CONCURRENCY, startedBeforeCancel)

        job.cancel()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SweepState.Idle, controller.state.value)
        assertEquals(startedBeforeCancel, probed.size, "no probe may start after cancellation")
    }
}
