package com.transfer.flash.core.network.sweep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SubnetSweeperTest {

    private val hosts = (1..100).map { "10.0.0.$it" }

    @Test
    fun `never more than the concurrency limit in flight and every host is probed once`() = runTest {
        var inFlight = 0
        var peak = 0
        val seen = ArrayList<String>()
        val sweeper = SubnetSweeper({ host, _, _ ->
            seen += host
            inFlight++
            peak = maxOf(peak, inFlight)
            delay(300)
            inFlight--
            false
        }, concurrency = 32, timeoutMs = 300)
        assertEquals(emptyList(), sweeper.sweep(hosts, 45822))
        assertEquals(32, peak)
        assertEquals(hosts.sorted(), seen.sorted())
        // 100 hosts, 32 at a time, 300 ms each: four waves.
        assertEquals(1_200, currentTime)
    }

    @Test
    fun `returns the answering hosts in the order given and reports progress`() = runTest {
        val open = setOf("10.0.0.7", "10.0.0.50", "10.0.0.3")
        val progress = ArrayList<Int>()
        val sweeper = SubnetSweeper({ host, _, _ -> delay(host.substringAfterLast('.').toLong()); host in open })
        val hits = sweeper.sweep(hosts, 45822) { progress += it }
        assertEquals(listOf("10.0.0.3", "10.0.0.7", "10.0.0.50"), hits)
        assertEquals(100, progress.max())
        assertEquals(100, progress.size)
    }

    @Test
    fun `the port and timeout reach the probe unchanged`() = runTest {
        val args = ArrayList<Pair<Int, Int>>()
        SubnetSweeper({ _, port, timeout -> args += port to timeout; false }, timeoutMs = 300).sweep(listOf("10.0.0.1"), 45822)
        assertEquals(listOf(45822 to 300), args)
    }

    @Test
    fun `a probe that throws is a miss and does not stop the sweep`() = runTest {
        val sweeper = SubnetSweeper({ host, _, _ -> if (host.endsWith(".2")) error("boom") else host.endsWith(".3") })
        assertEquals(listOf("10.0.0.3"), sweeper.sweep(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), 45822))
    }

    @Test
    fun `an empty list probes nothing`() = runTest {
        var calls = 0
        assertEquals(emptyList(), SubnetSweeper({ _, _, _ -> calls++; true }).sweep(emptyList(), 1))
        assertEquals(0, calls)
    }

    @Test
    fun `cancelling stops new probes at once`() = runTest {
        val started = ArrayList<String>()
        val sweeper = SubnetSweeper({ host, _, _ -> started += host; delay(300); false }, concurrency = 4)
        val job = launch { sweeper.sweep(hosts, 45822) }
        runCurrent()
        assertEquals(4, started.size)
        advanceTimeBy(100)
        job.cancel(CancellationException("stop"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(4, started.size, "no probe may start after cancellation")
    }
}
