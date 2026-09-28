package com.transfer.flash.core.network.ws

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PC1's shared keepalive clock: one tick per interval for every connection, and none without any. */
@OptIn(ExperimentalCoroutinesApi::class)
class WsKeepaliveTickerTest {

    private class Counter : WsKeepaliveTicker.Target {
        var count = 0
        override fun onKeepaliveTick() {
            count++
        }
    }

    @Test
    fun `no connections, no timer`() = runTest {
        val ticker = WsKeepaliveTicker(intervalMs = { 10_000L }, scope = backgroundScope)

        advanceTimeBy(3_600_000L)
        runCurrent()

        assertEquals(0L, ticker.ticks)
    }

    @Test
    fun `every registered connection is serviced on the same tick`() = runTest {
        val ticker = WsKeepaliveTicker(intervalMs = { 10_000L }, scope = backgroundScope)
        val counters = List(3) { Counter() }
        counters.forEach(ticker::register)

        advanceTimeBy(30_001L)
        runCurrent()

        assertEquals(3L, ticker.ticks, "one wake-up per interval, not one per connection")
        counters.forEach { assertEquals(3, it.count) }
    }

    @Test
    fun `an unregistered connection gets no further ticks, and an empty clock stops`() = runTest {
        val ticker = WsKeepaliveTicker(intervalMs = { 10_000L }, scope = backgroundScope)
        val stays = Counter()
        val leaves = Counter()
        ticker.register(stays)
        ticker.register(leaves)

        advanceTimeBy(10_001L)
        runCurrent()
        ticker.unregister(leaves)
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(1, leaves.count)
        assertEquals(3, stays.count)

        ticker.unregister(stays)
        val ticksWhenEmptied = ticker.ticks
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(ticksWhenEmptied, ticker.ticks)
    }

    @Test
    fun `a failing connection does not stop the others being ticked`() = runTest {
        val ticker = WsKeepaliveTicker(intervalMs = { 10_000L }, scope = backgroundScope)
        val healthy = Counter()
        ticker.register { error("broken connection") }
        ticker.register(healthy)

        advanceTimeBy(20_001L)
        runCurrent()

        assertEquals(2, healthy.count)
    }

    @Test
    fun `the interval is read every tick`() = runTest {
        var interval = 10_000L
        val ticker = WsKeepaliveTicker(intervalMs = { interval }, scope = backgroundScope)
        val counter = Counter()
        ticker.register(counter)

        advanceTimeBy(10_001L)
        runCurrent()
        interval = 15_000L // the tier was pinned in Settings
        // The tick already armed keeps its 10 s; from then on it is 15 s.
        advanceTimeBy(10_000L + 15_000L)
        runCurrent()

        assertEquals(3, counter.count)
    }
}
