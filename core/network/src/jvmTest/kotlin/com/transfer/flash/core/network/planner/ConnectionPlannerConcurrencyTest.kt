package com.transfer.flash.core.network.planner

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The planner's lock-free state under real threads: sweeps from the discovery edge, the periodic
 * tick and a screen-on re-arm can overlap, and each peer must still be handed out exactly once.
 */
class ConnectionPlannerConcurrencyTest {

    private val links = object : ConnectionPlanner.Links {
        override fun hasLiveSession(deviceId: String) = false
        override fun isReconnectInFlight(deviceId: String) = false
    }

    @Test
    fun `racing sweeps admit each peer exactly once`() {
        repeat(50) {
            val planner = ConnectionPlanner(localDeviceId = "a", suppressMs = 60_000L)
            val sightings = (1..30).map { ConnectionPlanner.Sighting("p$it", "10.0.0.$it", 45822, "p$it") }
            val handedOut = ConcurrentLinkedQueue<String>()
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    planner.plan(0, sightings, links).dials.forEach { d -> handedOut += d.key }
                }.also { t -> t.start() }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(30, handedOut.size)
            assertEquals(30, handedOut.toSet().size)
        }
    }
}
